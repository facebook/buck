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
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.macros.MacroException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasContacts;
import com.facebook.buck.rules.HasTestTimeout;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.MacroArg;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Level;
import org.immutables.value.Value;

public class JavaTestDescription
    implements Description<JavaTestDescriptionArg>,
        ImplicitDepsInferringDescription<JavaTestDescription.AbstractJavaTestDescriptionArg>,
        VersionRoot<JavaTestDescriptionArg> {

  private static final MacroHandler MACRO_HANDLER =
      new MacroHandler(ImmutableMap.of("location", new LocationMacroExpander()));

  private final JavaBuckConfig javaBuckConfig;
  private final JavaOptions javaOptions;
  private final JavacOptions templateJavacOptions;
  private final CxxPlatform defaultCxxPlatform;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;

  public JavaTestDescription(
      JavaBuckConfig javaBuckConfig,
      JavaOptions javaOptions,
      JavacOptions templateJavacOptions,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    this.javaBuckConfig = javaBuckConfig;
    this.javaOptions = javaOptions;
    this.templateJavacOptions = templateJavacOptions;
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.cxxPlatforms = cxxPlatforms;
  }

  @Override
  public Class<JavaTestDescriptionArg> getConstructorArgType() {
    return JavaTestDescriptionArg.class;
  }

  private CxxPlatform getCxxPlatform(AbstractJavaTestDescriptionArg args) {
    return args.getDefaultCxxPlatform().map(cxxPlatforms::getValue).orElse(defaultCxxPlatform);
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      JavaTestDescriptionArg args) {
    JavacOptions javacOptions =
        JavacOptionsFactory.create(
            templateJavacOptions, buildTarget, projectFilesystem, resolver, args);

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    CxxLibraryEnhancement cxxLibraryEnhancement =
        new CxxLibraryEnhancement(
            buildTarget,
            projectFilesystem,
            params,
            args.getUseCxxLibraries(),
            args.getCxxLibraryWhitelist(),
            resolver,
            ruleFinder,
            getCxxPlatform(args));
    params = cxxLibraryEnhancement.updatedParams;

    DefaultJavaLibraryRules defaultJavaLibraryRules =
        DefaultJavaLibrary.rulesBuilder(
                buildTarget.withAppendedFlavors(JavaTest.COMPILED_TESTS_LIBRARY_FLAVOR),
                projectFilesystem,
                params,
                resolver,
                new JavaConfiguredCompilerFactory(javaBuckConfig),
                javaBuckConfig,
                args)
            .setJavacOptions(javacOptions)
            .build();

    if (HasJavaAbi.isAbiTarget(buildTarget)) {
      return defaultJavaLibraryRules.buildAbi();
    }

    JavaLibrary testsLibrary = resolver.addToIndex(defaultJavaLibraryRules.buildLibrary());

    Function<String, Arg> toMacroArgFunction =
        MacroArg.toMacroArgFunction(MACRO_HANDLER, buildTarget, cellRoots, resolver);

    return new JavaTest(
        buildTarget,
        projectFilesystem,
        params.withDeclaredDeps(ImmutableSortedSet.of(testsLibrary)).withoutExtraDeps(),
        testsLibrary,
        /* additionalClasspathEntries */ ImmutableSet.of(),
        args.getLabels(),
        args.getContacts(),
        args.getTestType().orElse(TestType.JUNIT),
        javaOptions.getJavaRuntimeLauncher(),
        args.getVmArgs(),
        cxxLibraryEnhancement.nativeLibsEnvironment,
        args.getTestRuleTimeoutMs()
            .map(Optional::of)
            .orElse(javaBuckConfig.getDelegate().getDefaultTestRuleTimeoutMs()),
        args.getTestCaseTimeoutMs(),
        ImmutableMap.copyOf(Maps.transformValues(args.getEnv(), toMacroArgFunction::apply)),
        args.getRunTestSeparately(),
        args.getForkMode(),
        args.getStdOutLogLevel(),
        args.getStdErrLogLevel(),
        args.getUnbundledResourcesRoot());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractJavaTestDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    if (constructorArg.getUseCxxLibraries().orElse(false)) {
      extraDepsBuilder.addAll(CxxPlatforms.getParseTimeDeps(getCxxPlatform(constructorArg)));
    }
    for (String envValue : constructorArg.getEnv().values()) {
      try {
        MACRO_HANDLER.extractParseTimeDeps(
            buildTarget, cellRoots, envValue, extraDepsBuilder, targetGraphOnlyDepsBuilder);
      } catch (MacroException e) {
        throw new HumanReadableException(e, "%s: %s", buildTarget, e.getMessage());
      }
    }
  }

  @VisibleForTesting
  public CxxPlatform getDefaultCxxPlatform() {
    return defaultCxxPlatform;
  }

  public interface CoreArg extends HasContacts, HasTestTimeout, JavaLibraryDescription.CoreArg {
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

    Optional<Long> getTestCaseTimeoutMs();

    ImmutableMap<String, String> getEnv();

    Optional<Flavor> getDefaultCxxPlatform();
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractJavaTestDescriptionArg extends CoreArg {}

  public static class CxxLibraryEnhancement {
    public final BuildRuleParams updatedParams;
    public final ImmutableMap<String, String> nativeLibsEnvironment;

    public CxxLibraryEnhancement(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams params,
        Optional<Boolean> useCxxLibraries,
        final ImmutableSet<BuildTarget> cxxLibraryWhitelist,
        BuildRuleResolver resolver,
        SourcePathRuleFinder ruleFinder,
        CxxPlatform cxxPlatform) {
      if (useCxxLibraries.orElse(false)) {
        SymlinkTree nativeLibsSymlinkTree =
            buildNativeLibsSymlinkTreeRule(buildTarget, projectFilesystem, params, cxxPlatform);

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
                  filteredLinks.build());
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
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams buildRuleParams,
        CxxPlatform cxxPlatform) {
      return CxxDescriptionEnhancer.createSharedLibrarySymlinkTree(
          buildTarget,
          projectFilesystem,
          cxxPlatform,
          buildRuleParams.getBuildDeps(),
          r -> r instanceof JavaLibrary ? Optional.of(r.getBuildDeps()) : Optional.empty());
    }
  }
}
