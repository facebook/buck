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

package com.facebook.buck.thrift;

import com.facebook.buck.jvm.java.CalculateAbi;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsAmender;
import com.facebook.buck.jvm.java.JavacStepFactory;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.TargetGraph;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;

import java.nio.file.Path;

public class ThriftJavaEnhancer implements ThriftLanguageSpecificEnhancer {

  private static final Flavor JAVA_FLAVOR = ImmutableFlavor.of("java");

  private final ThriftBuckConfig thriftBuckConfig;
  private final JavacOptions templateOptions;

  public ThriftJavaEnhancer(
      ThriftBuckConfig thriftBuckConfig,
      JavacOptions templateOptions) {
    this.thriftBuckConfig = thriftBuckConfig;
    this.templateOptions = templateOptions;
  }

  @Override
  public String getLanguage() {
    return "java";
  }

  @Override
  public Flavor getFlavor() {
    return JAVA_FLAVOR;
  }

  @Override
  public ImmutableSortedSet<String> getGeneratedSources(
      BuildTarget target,
      ThriftConstructorArg args,
      String thriftName,
      ImmutableList<String> services) {
    return ImmutableSortedSet.of("");
  }

  @VisibleForTesting
  protected BuildTarget getSourceZipBuildTarget(UnflavoredBuildTarget target, String name) {
    return BuildTargets.createFlavoredBuildTarget(
        target,
        ImmutableFlavor.of(
            String.format(
                "thrift-java-source-zip-%s",
                name.replace('/', '-').replace('.', '-').replace('+', '-').replace(' ', '-'))));
  }

  private Path getSourceZipOutputPath(UnflavoredBuildTarget target, String name) {
    BuildTarget flavoredTarget = getSourceZipBuildTarget(target, name);
    return BuildTargets.getScratchPath(flavoredTarget, "%s" + Javac.SRC_ZIP);
  }

  @Override
  public DefaultJavaLibrary createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      ThriftConstructorArg args,
      ImmutableMap<String, ThriftSource> sources,
      ImmutableSortedSet<BuildRule> deps) {
    final SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    // Pack all the generated sources into a single source zip that we'll pass to the
    // java rule below.
    ImmutableSortedSet.Builder<BuildRule> sourceZipsBuilder = ImmutableSortedSet.naturalOrder();
    UnflavoredBuildTarget unflavoredBuildTarget =
        params.getBuildTarget().getUnflavoredBuildTarget();
    for (ImmutableMap.Entry<String, ThriftSource> ent : sources.entrySet()) {
      String name = ent.getKey();
      BuildRule compilerRule = ent.getValue().getCompileRule();
      Path sourceDirectory = ent.getValue().getOutputDir().resolve("gen-java");

      BuildTarget sourceZipTarget = getSourceZipBuildTarget(unflavoredBuildTarget, name);
      Path sourceZip = getSourceZipOutputPath(unflavoredBuildTarget, name);

      sourceZipsBuilder.add(
          new SrcZip(
              params.copyWithChanges(
                  sourceZipTarget,
                  Suppliers.ofInstance(ImmutableSortedSet.of(compilerRule)),
                  Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
              pathResolver,
              sourceZip,
              sourceDirectory));
    }
    ImmutableSortedSet<BuildRule> sourceZips = sourceZipsBuilder.build();
    resolver.addAllToIndex(sourceZips);

    // Create to main compile rule.
    BuildRuleParams javaParams = params.copyWithChanges(
        BuildTargets.createFlavoredBuildTarget(
            unflavoredBuildTarget,
            getFlavor()),
        Suppliers.ofInstance(
            ImmutableSortedSet.<BuildRule>naturalOrder()
                .addAll(sourceZips)
                .addAll(deps)
                .addAll(BuildRules.getExportedRules(deps))
                .addAll(pathResolver.filterBuildRuleInputs(templateOptions.getInputs(pathResolver)))
                .build()),
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));

    BuildTarget abiJarTarget =
        BuildTarget.builder(params.getBuildTarget())
            .addFlavors(CalculateAbi.FLAVOR)
            .build();

    DefaultJavaLibrary library =
        resolver.addToIndex(
            new DefaultJavaLibrary(
                javaParams,
                pathResolver,
                FluentIterable.from(sourceZips)
                    .transform(SourcePaths.getToBuildTargetSourcePath())
                    .toSortedSet(Ordering.natural()),
                /* resources */ ImmutableSet.<SourcePath>of(),
                templateOptions.getGeneratedSourceFolderName(),
                /* proguardConfig */ Optional.<SourcePath>absent(),
                /* postprocessClassesCommands */ ImmutableList.<String>of(),
                /* exportedDeps */ ImmutableSortedSet.<BuildRule>of(),
                /* providedDeps */ ImmutableSortedSet.<BuildRule>of(),
                /* abiJar */ new BuildTargetSourcePath(abiJarTarget),
                /* additionalClasspathEntries */ ImmutableSet.<Path>of(),
                new JavacStepFactory(templateOptions, JavacOptionsAmender.IDENTITY),
                /* resourcesRoot */ Optional.<Path>absent(),
                /* mavenCoords */ Optional.<String>absent(),
                /* tests */ ImmutableSortedSet.<BuildTarget>of()));

    resolver.addToIndex(
        CalculateAbi.of(
            abiJarTarget,
            pathResolver,
            params,
            new BuildTargetSourcePath(library.getBuildTarget())));

    return library;
  }

  private ImmutableSet<BuildTarget> getImplicitDeps() {
    return ImmutableSet.of(thriftBuckConfig.getJavaDep());
  }

  @Override
  public ImmutableSet<BuildTarget> getImplicitDepsForTargetFromConstructorArg(
      BuildTarget target,
      ThriftConstructorArg args) {
    return getImplicitDeps();
  }

  @Override
  public ImmutableSet<String> getOptions(BuildTarget target, ThriftConstructorArg arg) {
    return arg.javaOptions.or(ImmutableSet.<String>of());
  }

  @Override
  public ThriftLibraryDescription.CompilerType getCompilerType() {
    return ThriftLibraryDescription.CompilerType.THRIFT;
  }

}
