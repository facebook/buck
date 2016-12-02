/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.jvm.common.ResourceValidator;
import com.facebook.buck.jvm.java.CalculateAbi;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaLibraryRules;
import com.facebook.buck.jvm.java.JavaSourceJar;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsFactory;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.query.DepQueryUtils;
import com.facebook.buck.util.DependencyMode;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Collectors;

public class AndroidLibraryDescription
    implements Description<AndroidLibraryDescription.Arg>, Flavored,
    ImplicitDepsInferringDescription<AndroidLibraryDescription.Arg> {
  public static final BuildRuleType TYPE = BuildRuleType.of("android_library");

  private static final Flavor DUMMY_R_DOT_JAVA_FLAVOR =
      AndroidLibraryGraphEnhancer.DUMMY_R_DOT_JAVA_FLAVOR;

  public enum JvmLanguage {
    JAVA,
    KOTLIN,
    SCALA,
  }

  private final JavacOptions defaultOptions;
  private final AndroidLibraryCompilerFactory compilerFactory;

  public AndroidLibraryDescription(
      JavacOptions defaultOptions,
      AndroidLibraryCompilerFactory compilerFactory) {
    this.defaultOptions = defaultOptions;
    this.compilerFactory = compilerFactory;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    if (params.getBuildTarget().getFlavors().contains(JavaLibrary.SRC_JAR)) {
      return new JavaSourceJar(params, pathResolver, args.srcs, args.mavenCoords);
    }

    JavacOptions javacOptions = JavacOptionsFactory.create(
        defaultOptions,
        params,
        resolver,
        pathResolver,
        args
    );

    AndroidLibraryGraphEnhancer graphEnhancer = new AndroidLibraryGraphEnhancer(
        params.getBuildTarget(),
        params.copyWithExtraDeps(
            Suppliers.ofInstance(resolver.getAllRules(args.exportedDeps))),
        javacOptions,
        DependencyMode.FIRST_ORDER,
        /* forceFinalResourceIds */ false,
        args.resourceUnionPackage,
        args.finalRName);

    boolean hasDummyRDotJavaFlavor =
        params.getBuildTarget().getFlavors().contains(DUMMY_R_DOT_JAVA_FLAVOR);
    if (params.getBuildTarget().getFlavors().contains(CalculateAbi.FLAVOR)) {
      if (hasDummyRDotJavaFlavor) {
        return graphEnhancer.getBuildableForAndroidResourcesAbi(resolver, pathResolver);
      }
      BuildTarget libraryTarget = params.getBuildTarget().withoutFlavors(CalculateAbi.FLAVOR);
      resolver.requireRule(libraryTarget);
      return CalculateAbi.of(
          params.getBuildTarget(),
          pathResolver,
          params,
          new BuildTargetSourcePath(libraryTarget));
    }
    Optional<DummyRDotJava> dummyRDotJava = graphEnhancer.getBuildableForAndroidResources(
        resolver,
        /* createBuildableIfEmpty */ hasDummyRDotJavaFlavor);

    if (hasDummyRDotJavaFlavor) {
      return dummyRDotJava.get();
    } else {
      ImmutableSet<Path> additionalClasspathEntries = ImmutableSet.of();
      if (dummyRDotJava.isPresent()) {
        additionalClasspathEntries = ImmutableSet.of(dummyRDotJava.get().getPathToOutput());
        ImmutableSortedSet<BuildRule> newDeclaredDeps = ImmutableSortedSet.<BuildRule>naturalOrder()
            .addAll(params.getDeclaredDeps().get())
            .add(dummyRDotJava.get())
            .build();
        params = params.copyWithDeps(
            Suppliers.ofInstance(newDeclaredDeps),
            params.getExtraDeps());
      }

      BuildTarget abiJarTarget = params.getBuildTarget().withAppendedFlavors(CalculateAbi.FLAVOR);

      AndroidLibraryCompiler compiler =
          compilerFactory.getCompiler(args.language.orElse(JvmLanguage.JAVA));

      ImmutableSortedSet<BuildRule> exportedDeps = resolver.getAllRules(args.exportedDeps);

      ImmutableSortedSet.Builder<BuildRule> declaredDepsBuilder =
          ImmutableSortedSet.<BuildRule>naturalOrder()
              .addAll(params.getDeclaredDeps().get())
              .addAll(compiler.getDeclaredDeps(args, resolver));

      // Resolve the dependencies query, if present and add to declared deps
      if (args.depsQuery.isPresent()) {
        declaredDepsBuilder.addAll(
            DepQueryUtils.resolveDepQuery(
                params,
                args.depsQuery.get(),
                resolver,
                targetGraph)
                .collect(Collectors.toList()));
      }

      ImmutableSortedSet<BuildRule> declaredDeps = declaredDepsBuilder.build();

      ImmutableSortedSet<BuildRule> extraDeps =
          ImmutableSortedSet.<BuildRule>naturalOrder()
              .addAll(params.getExtraDeps().get())
              .addAll(BuildRules.getExportedRules(
                  Iterables.concat(
                      declaredDeps,
                      exportedDeps,
                      resolver.getAllRules(args.providedDeps))))
              .addAll(pathResolver.filterBuildRuleInputs(javacOptions.getInputs(pathResolver)))
              .addAll(compiler.getExtraDeps(args, resolver))
              .build();

      BuildRuleParams androidLibraryParams =
          params.copyWithDeps(
              Suppliers.ofInstance(declaredDeps),
              Suppliers.ofInstance(extraDeps));
      return new AndroidLibrary(
          androidLibraryParams,
          pathResolver,
          args.srcs,
          ResourceValidator.validateResources(
              pathResolver,
              params.getProjectFilesystem(), args.resources),
          args.proguardConfig.map(
              SourcePaths.toSourcePath(params.getProjectFilesystem())::apply),
          args.postprocessClassesCommands,
          exportedDeps,
          resolver.getAllRules(args.providedDeps),
          abiJarTarget,
          JavaLibraryRules.getAbiInputs(resolver, androidLibraryParams.getDeps()),
          additionalClasspathEntries,
          javacOptions,
          compiler.trackClassUsage(javacOptions),
          compiler.compileToJar(args, javacOptions, resolver),
          args.resourcesRoot,
          args.mavenCoords,
          args.manifest,
          args.tests);
    }
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return flavors.isEmpty() ||
        flavors.equals(ImmutableSet.of(JavaLibrary.SRC_JAR)) ||
        flavors.equals(ImmutableSet.of(DUMMY_R_DOT_JAVA_FLAVOR));
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      Arg constructorArg) {
    return compilerFactory.getCompiler(constructorArg.language.orElse(JvmLanguage.JAVA))
        .findDepsForTargetFromConstructorArgs(buildTarget, cellRoots, constructorArg);
  }

  @SuppressFieldNotInitialized
  public static class Arg extends JavaLibraryDescription.Arg {
    public Optional<SourcePath> manifest;
    public Optional<String> resourceUnionPackage;
    public Optional<String> finalRName;
    public Optional<JvmLanguage> language;
    public Optional<String> depsQuery;
  }
}

