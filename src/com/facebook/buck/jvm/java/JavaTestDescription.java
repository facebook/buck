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

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.description.arg.HasContacts;
import com.facebook.buck.core.description.arg.HasTestTimeout;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.SymlinkTree;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.toolchain.JavaCxxPlatformProvider;
import com.facebook.buck.jvm.java.toolchain.JavaOptionsProvider;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.rules.macros.AbstractMacroExpander;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import org.immutables.value.Value;

public class JavaTestDescription
    implements DescriptionWithTargetGraph<JavaTestDescriptionArg>,
        ImplicitDepsInferringDescription<JavaTestDescription.AbstractJavaTestDescriptionArg>,
        VersionRoot<JavaTestDescriptionArg> {

  public static final ImmutableList<AbstractMacroExpander<? extends Macro, ?>> MACRO_EXPANDERS =
      ImmutableList.of(new LocationMacroExpander());

  private final ToolchainProvider toolchainProvider;
  private final JavaBuckConfig javaBuckConfig;

  public JavaTestDescription(ToolchainProvider toolchainProvider, JavaBuckConfig javaBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.javaBuckConfig = javaBuckConfig;
  }

  @Override
  public Class<JavaTestDescriptionArg> getConstructorArgType() {
    return JavaTestDescriptionArg.class;
  }

  private CxxPlatform getCxxPlatform(AbstractJavaTestDescriptionArg args) {
    return args.getDefaultCxxPlatform()
        .map(
            toolchainProvider
                    .getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class)
                    .getCxxPlatforms()
                ::getValue)
        .orElse(
            toolchainProvider
                .getByName(JavaCxxPlatformProvider.DEFAULT_NAME, JavaCxxPlatformProvider.class)
                .getDefaultJavaCxxPlatform());
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      JavaTestDescriptionArg args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    CellPathResolver cellRoots = context.getCellPathResolver();
    JavacOptions javacOptions =
        JavacOptionsFactory.create(
            toolchainProvider
                .getByName(JavacOptionsProvider.DEFAULT_NAME, JavacOptionsProvider.class)
                .getJavacOptions(),
            buildTarget,
            projectFilesystem,
            graphBuilder,
            args);

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    CxxLibraryEnhancement cxxLibraryEnhancement =
        new CxxLibraryEnhancement(
            buildTarget,
            projectFilesystem,
            params,
            args.getUseCxxLibraries(),
            args.getCxxLibraryWhitelist(),
            graphBuilder,
            ruleFinder,
            getCxxPlatform(args));
    params = cxxLibraryEnhancement.updatedParams;

    DefaultJavaLibraryRules defaultJavaLibraryRules =
        DefaultJavaLibrary.rulesBuilder(
                buildTarget.withAppendedFlavors(JavaTest.COMPILED_TESTS_LIBRARY_FLAVOR),
                projectFilesystem,
                context.getToolchainProvider(),
                params,
                graphBuilder,
                cellRoots,
                new JavaConfiguredCompilerFactory(
                    javaBuckConfig, JavacFactory.getDefault(toolchainProvider)),
                javaBuckConfig,
                args)
            .setJavacOptions(javacOptions)
            .setToolchainProvider(context.getToolchainProvider())
            .build();

    if (JavaAbis.isAbiTarget(buildTarget)) {
      return defaultJavaLibraryRules.buildAbi();
    }

    JavaLibrary testsLibrary = graphBuilder.addToIndex(defaultJavaLibraryRules.buildLibrary());

    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.builder()
            .setBuildTarget(buildTarget)
            .setCellPathResolver(cellRoots)
            .setExpanders(MACRO_EXPANDERS)
            .build();

    return new JavaTest(
        buildTarget,
        projectFilesystem,
        params.withDeclaredDeps(ImmutableSortedSet.of(testsLibrary)).withoutExtraDeps(),
        testsLibrary,
        /* additionalClasspathEntries */ ImmutableSet.of(),
        args.getLabels(),
        args.getContacts(),
        args.getTestType().orElse(TestType.JUNIT),
        toolchainProvider
            .getByName(JavaOptionsProvider.DEFAULT_NAME, JavaOptionsProvider.class)
            .getJavaOptionsForTests()
            .getJavaRuntimeLauncher(),
        args.getVmArgs(),
        cxxLibraryEnhancement.nativeLibsEnvironment,
        args.getTestRuleTimeoutMs()
            .map(Optional::of)
            .orElse(javaBuckConfig.getDelegate().getDefaultTestRuleTimeoutMs()),
        args.getTestCaseTimeoutMs(),
        ImmutableMap.copyOf(
            Maps.transformValues(args.getEnv(), x -> macrosConverter.convert(x, graphBuilder))),
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
      targetGraphOnlyDepsBuilder.addAll(
          CxxPlatforms.getParseTimeDeps(getCxxPlatform(constructorArg)));
    }
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

    ImmutableMap<String, StringWithMacros> getEnv();

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
        ImmutableSet<BuildTarget> cxxLibraryWhitelist,
        ActionGraphBuilder graphBuilder,
        SourcePathRuleFinder ruleFinder,
        CxxPlatform cxxPlatform) {
      if (useCxxLibraries.orElse(false)) {
        SymlinkTree nativeLibsSymlinkTree =
            buildNativeLibsSymlinkTreeRule(
                buildTarget, projectFilesystem, graphBuilder, ruleFinder, params, cxxPlatform);

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
                  "java_test_native_libs",
                  nativeLibsSymlinkTree.getBuildTarget(),
                  nativeLibsSymlinkTree.getProjectFilesystem(),
                  nativeLibsSymlinkTree
                      .getProjectFilesystem()
                      .relativize(nativeLibsSymlinkTree.getRoot()),
                  filteredLinks.build(),
                  ImmutableMultimap.of(),
                  ruleFinder);
        }

        graphBuilder.addToIndex(nativeLibsSymlinkTree);
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
                cxxPlatform.getLd().resolve(graphBuilder).searchPathEnvVar(),
                nativeLibsSymlinkTree.getRoot().toString());
      } else {
        updatedParams = params;
        nativeLibsEnvironment = ImmutableMap.of();
      }
    }

    public static SymlinkTree buildNativeLibsSymlinkTreeRule(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        ActionGraphBuilder graphBuilder,
        SourcePathRuleFinder ruleFinder,
        BuildRuleParams buildRuleParams,
        CxxPlatform cxxPlatform) {
      return CxxDescriptionEnhancer.createSharedLibrarySymlinkTree(
          buildTarget,
          projectFilesystem,
          graphBuilder,
          ruleFinder,
          cxxPlatform,
          buildRuleParams.getBuildDeps(),
          r -> r instanceof JavaLibrary ? Optional.of(r.getBuildDeps()) : Optional.empty());
    }
  }
}
