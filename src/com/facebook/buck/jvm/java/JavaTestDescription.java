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

package com.facebook.buck.jvm.java;

import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatforms;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Predicates;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import org.immutables.value.Value;

public class JavaTestDescription
    implements Description<JavaTestDescriptionArg>,
        ImplicitDepsInferringDescription<JavaTestDescription.AbstractJavaTestDescriptionArg> {

  private final JavaBuckConfig javaBuckConfig;
  private final JavaOptions javaOptions;
  private final JavacOptions templateJavacOptions;
  private final Optional<Long> defaultTestRuleTimeoutMs;
  private final CxxPlatform cxxPlatform;

  public JavaTestDescription(
      JavaBuckConfig javaBuckConfig,
      JavaOptions javaOptions,
      JavacOptions templateOptions,
      Optional<Long> defaultTestRuleTimeoutMs,
      CxxPlatform cxxPlatform) {
    this.javaBuckConfig = javaBuckConfig;
    this.javaOptions = javaOptions;
    this.templateJavacOptions = templateOptions;
    this.defaultTestRuleTimeoutMs = defaultTestRuleTimeoutMs;
    this.cxxPlatform = cxxPlatform;
  }

  @Override
  public Class<JavaTestDescriptionArg> getConstructorArgType() {
    return JavaTestDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      JavaTestDescriptionArg args)
      throws NoSuchBuildTargetException {
    JavacOptions javacOptions =
        JavacOptionsFactory.create(templateJavacOptions, params, resolver, args);

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    CxxLibraryEnhancement cxxLibraryEnhancement =
        new CxxLibraryEnhancement(
            params,
            args.getUseCxxLibraries(),
            args.getCxxLibraryWhitelist(),
            resolver,
            ruleFinder,
            cxxPlatform);
    params = cxxLibraryEnhancement.updatedParams;

    BuildRuleParams testsLibraryParams =
        params.withAppendedFlavor(JavaTest.COMPILED_TESTS_LIBRARY_FLAVOR);

    DefaultJavaLibraryBuilder defaultJavaLibraryBuilder =
        DefaultJavaLibrary.builder(
                targetGraph, testsLibraryParams, resolver, cellRoots, javaBuckConfig)
            .setArgs(args)
            .setJavacOptions(javacOptions)
            .setGeneratedSourceFolder(javacOptions.getGeneratedSourceFolderName())
            .setTrackClassUsage(javacOptions.trackClassUsage());

    if (HasJavaAbi.isAbiTarget(params.getBuildTarget())) {
      return defaultJavaLibraryBuilder.buildAbi();
    }

    JavaLibrary testsLibrary = resolver.addToIndex(defaultJavaLibraryBuilder.build());

    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    return new JavaTest(
        params.copyReplacingDeclaredAndExtraDeps(
            Suppliers.ofInstance(ImmutableSortedSet.of(testsLibrary)),
            Suppliers.ofInstance(ImmutableSortedSet.of())),
        pathResolver,
        testsLibrary,
        /* additionalClasspathEntries */ ImmutableSet.of(),
        args.getLabels(),
        args.getContacts(),
        args.getTestType().orElse(TestType.JUNIT),
        javaOptions.getJavaRuntimeLauncher(),
        args.getVmArgs(),
        cxxLibraryEnhancement.nativeLibsEnvironment,
        args.getTestRuleTimeoutMs().map(Optional::of).orElse(defaultTestRuleTimeoutMs),
        args.getTestCaseTimeoutMs(),
        args.getEnv(),
        args.getRunTestSeparately(),
        args.getForkMode(),
        args.getStdOutLogLevel(),
        args.getStdErrLogLevel());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractJavaTestDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    if (constructorArg.getUseCxxLibraries().orElse(false)) {
      extraDepsBuilder.addAll(CxxPlatforms.getParseTimeDeps(cxxPlatform));
    }
  }

  public interface CoreArg extends JavaLibraryDescription.CoreArg {
    @Value.NaturalOrder
    ImmutableSortedSet<String> getContacts();

    ImmutableList<String> getVmArgs();

    Optional<TestType> getTestType();

    @Value.Default
    default boolean getRunTestSeparately() {
      return false;
    }

    @Value.Default
    default ForkMode getForkMode() {
      return ForkMode.NONE;
    }

    Optional<Level> getStdErrLogLevel();

    Optional<Level> getStdOutLogLevel();

    Optional<Boolean> getUseCxxLibraries();

    ImmutableSet<BuildTarget> getCxxLibraryWhitelist();

    Optional<Long> getTestRuleTimeoutMs();

    Optional<Long> getTestCaseTimeoutMs();

    ImmutableMap<String, String> getEnv();
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractJavaTestDescriptionArg extends CoreArg {}

  public static class CxxLibraryEnhancement {
    public final BuildRuleParams updatedParams;
    public final ImmutableMap<String, String> nativeLibsEnvironment;

    public CxxLibraryEnhancement(
        BuildRuleParams params,
        Optional<Boolean> useCxxLibraries,
        final ImmutableSet<BuildTarget> cxxLibraryWhitelist,
        BuildRuleResolver resolver,
        SourcePathRuleFinder ruleFinder,
        CxxPlatform cxxPlatform)
        throws NoSuchBuildTargetException {
      if (useCxxLibraries.orElse(false)) {
        SymlinkTree nativeLibsSymlinkTree =
            buildNativeLibsSymlinkTreeRule(ruleFinder, params, cxxPlatform);

        // If the cxxLibraryWhitelist is present, remove symlinks that were not requested.
        // They could point to old, invalid versions of the library in question.
        if (!cxxLibraryWhitelist.isEmpty()) {
          ImmutableMap.Builder<Path, SourcePath> filteredLinks = ImmutableMap.builder();
          for (Map.Entry<Path, SourcePath> entry : nativeLibsSymlinkTree.getLinks().entrySet()) {
            if (!(entry.getValue() instanceof BuildTargetSourcePath)) {
              // Could consider including these, but I don't know of any examples.
              continue;
            }
            BuildTargetSourcePath sourcePath = (BuildTargetSourcePath) entry.getValue();
            if (cxxLibraryWhitelist.contains(sourcePath.getTarget().withFlavors())) {
              filteredLinks.put(entry.getKey(), entry.getValue());
            }
          }
          nativeLibsSymlinkTree =
              new SymlinkTree(
                  nativeLibsSymlinkTree.getBuildTarget(),
                  nativeLibsSymlinkTree.getProjectFilesystem(),
                  nativeLibsSymlinkTree
                      .getProjectFilesystem()
                      .relativize(nativeLibsSymlinkTree.getRoot()),
                  filteredLinks.build(),
                  ruleFinder);
        }

        resolver.addToIndex(nativeLibsSymlinkTree);
        updatedParams =
            params.copyAppendingExtraDeps(
                ImmutableList.<BuildRule>builder()
                    .add(nativeLibsSymlinkTree)
                    // Add all the native libraries as first-order dependencies.
                    // This has two effects:
                    // (1) They become runtime deps because JavaTest adds all first-order deps.
                    // (2) They affect the JavaTest's RuleKey, so changing them will invalidate
                    // the test results cache.
                    .addAll(
                        ruleFinder.filterBuildRuleInputs(nativeLibsSymlinkTree.getLinks().values()))
                    .build());
        nativeLibsEnvironment =
            ImmutableMap.of(
                cxxPlatform.getLd().resolve(resolver).searchPathEnvVar(),
                nativeLibsSymlinkTree.getRoot().toString());
      } else {
        updatedParams = params;
        nativeLibsEnvironment = ImmutableMap.of();
      }
    }

    public static SymlinkTree buildNativeLibsSymlinkTreeRule(
        SourcePathRuleFinder ruleFinder, BuildRuleParams buildRuleParams, CxxPlatform cxxPlatform)
        throws NoSuchBuildTargetException {
      return CxxDescriptionEnhancer.createSharedLibrarySymlinkTree(
          ruleFinder,
          buildRuleParams.getBuildTarget(),
          buildRuleParams.getProjectFilesystem(),
          cxxPlatform,
          buildRuleParams.getBuildDeps(),
          Predicates.or(NativeLinkable.class::isInstance, JavaLibrary.class::isInstance));
    }
  }
}
