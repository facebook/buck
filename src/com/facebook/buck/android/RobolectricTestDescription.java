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

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.test.rule.HasTestRunner;
import com.facebook.buck.core.test.rule.TestRunnerSpec;
import com.facebook.buck.core.test.rule.coercer.TestRunnerSpecCoercer;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.CalculateClassAbi;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.JavaBinary;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaLibraryClasspathProvider;
import com.facebook.buck.jvm.java.JavaOptions;
import com.facebook.buck.jvm.java.JavaTest;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.facebook.buck.jvm.java.JavaTestRunner;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsFactory;
import com.facebook.buck.jvm.java.TestType;
import com.facebook.buck.jvm.java.toolchain.JavaCxxPlatformProvider;
import com.facebook.buck.jvm.java.toolchain.JavaOptionsProvider;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.test.config.TestBuckConfig;
import com.facebook.buck.util.DependencyMode;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.immutables.value.Value;

public class RobolectricTestDescription
    implements DescriptionWithTargetGraph<RobolectricTestDescriptionArg>,
        ImplicitDepsInferringDescription<RobolectricTestDescriptionArg> {


  private final ToolchainProvider toolchainProvider;
  private final JavaBuckConfig javaBuckConfig;
  private final AndroidLibraryCompilerFactory compilerFactory;
  private final Supplier<JavaOptions> javaOptionsForTests;
  private final Supplier<JavacOptions> defaultJavacOptions;
  private final JavacFactory javacFactory;

  public RobolectricTestDescription(
      ToolchainProvider toolchainProvider,
      JavaBuckConfig javaBuckConfig,
      AndroidLibraryCompilerFactory compilerFactory) {
    this.toolchainProvider = toolchainProvider;
    this.javaBuckConfig = javaBuckConfig;
    this.compilerFactory = compilerFactory;
    this.javaOptionsForTests = JavaOptionsProvider.getDefaultJavaOptionsForTests(toolchainProvider);
    this.defaultJavacOptions =
        MoreSuppliers.memoize(
            () ->
                toolchainProvider
                    .getByName(JavacOptionsProvider.DEFAULT_NAME, JavacOptionsProvider.class)
                    .getJavacOptions());
    this.javacFactory = JavacFactory.getDefault(toolchainProvider);
  }

  @Override
  public Class<RobolectricTestDescriptionArg> getConstructorArgType() {
    return RobolectricTestDescriptionArg.class;
  }

  private UnresolvedCxxPlatform getCxxPlatform(RobolectricTestDescriptionArg args) {
    return args.getDefaultCxxPlatform()
        .map(
            toolchainProvider
                    .getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class)
                    .getUnresolvedCxxPlatforms()
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
      RobolectricTestDescriptionArg args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    if (JavaAbis.isClassAbiTarget(buildTarget)) {
      Preconditions.checkArgument(
          !buildTarget.getFlavors().contains(AndroidLibraryGraphEnhancer.DUMMY_R_DOT_JAVA_FLAVOR));
      BuildTarget testTarget = JavaAbis.getLibraryTarget(buildTarget);
      BuildRule testRule = graphBuilder.requireRule(testTarget);
      return CalculateClassAbi.of(
          buildTarget,
          graphBuilder,
          projectFilesystem,
          Objects.requireNonNull(testRule.getSourcePathToOutput()));
    }

    JavacOptions javacOptions =
        JavacOptionsFactory.create(defaultJavacOptions.get(), buildTarget, graphBuilder, args);

    AndroidLibraryGraphEnhancer graphEnhancer =
        new AndroidLibraryGraphEnhancer(
            buildTarget,
            projectFilesystem,
            ImmutableSortedSet.copyOf(
                Iterables.concat(
                    params.getBuildDeps(), graphBuilder.getAllRules(args.getExportedDeps()))),
            javacFactory.create(graphBuilder, args),
            javacOptions,
            DependencyMode.TRANSITIVE,
            args.isForceFinalResourceIds(),
            args.getResourceUnionPackage(),
            /* rName */ Optional.empty(),
            args.isUseOldStyleableFormat(),
            /* skipNonUnionRDotJava */ false);

    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.builder()
            .setBuildTarget(buildTarget)
            .setCellPathResolver(context.getCellPathResolver())
            .setActionGraphBuilder(graphBuilder)
            .setExpanders(JavaTestDescription.MACRO_EXPANDERS)
            .build();
    ImmutableList<Arg> vmArgs =
        ImmutableList.copyOf(Lists.transform(args.getVmArgs(), macrosConverter::convert));


    Optional<DummyRDotJava> dummyRDotJava =
        graphEnhancer.getBuildableForAndroidResources(
            graphBuilder, /* createBuildableIfEmpty */ true);
    RobolectricTestDescriptionArg testLibraryArgs = args;

    if (dummyRDotJava.isPresent()) {
      ImmutableSortedSet<BuildRule> newDeclaredDeps =
          ImmutableSortedSet.<BuildRule>naturalOrder()
              .addAll(params.getDeclaredDeps().get())
              .add(dummyRDotJava.get())
              .build();
      params = params.withDeclaredDeps(newDeclaredDeps);
      testLibraryArgs =
          testLibraryArgs.withDeps(
              Iterables.concat(
                  args.getDeps(), Collections.singletonList(dummyRDotJava.get().getBuildTarget())));
    }

    JavaTestDescription.CxxLibraryEnhancement cxxLibraryEnhancement =
        new JavaTestDescription.CxxLibraryEnhancement(
            buildTarget,
            projectFilesystem,
            params,
            args.getUseCxxLibraries(),
            args.getCxxLibraryWhitelist(),
            graphBuilder,
            getCxxPlatform(args).resolve(graphBuilder, buildTarget.getTargetConfiguration()));
    params = cxxLibraryEnhancement.updatedParams;

    BuildTarget testLibraryBuildTarget =
        buildTarget.withAppendedFlavors(JavaTest.COMPILED_TESTS_LIBRARY_FLAVOR);
    CellPathResolver cellRoots = context.getCellPathResolver();

    JavaLibrary testsLibrary =
        graphBuilder.addToIndex(
            DefaultJavaLibrary.rulesBuilder(
                    testLibraryBuildTarget,
                    projectFilesystem,
                    context.getToolchainProvider(),
                    params,
                    graphBuilder,
                    cellRoots,
                    compilerFactory.getCompiler(
                        args.getLanguage().orElse(AndroidLibraryDescription.JvmLanguage.JAVA),
                        javacFactory),
                    javaBuckConfig,
                    testLibraryArgs)
                .setJavacOptions(javacOptions)
                .build()
                .buildLibrary());
    params = params.copyAppendingExtraDeps(ImmutableSortedSet.of(testsLibrary));
    AndroidPlatformTarget androidPlatformTarget =
        toolchainProvider.getByName(
            AndroidPlatformTarget.DEFAULT_NAME, AndroidPlatformTarget.class);

    Optional<BuildTarget> runner = args.getRunner();
    Optional<TestRunnerSpec> runnerSpecs = args.getSpecs();
    if (runnerSpecs.isPresent()) {
      JavaTestRunner testRunner;
      if (runner.isPresent()) {
        BuildRule runnerRule = graphBuilder.requireRule(runner.get());
        if (!(runnerRule instanceof JavaTestRunner)) {
          throw new HumanReadableException(
              "Robolectric tests should have a java_test_runner as the runner for test protocol");
        }
        testRunner = (JavaTestRunner) runnerRule;

      } else {
        throw new HumanReadableException(
            "Robolectric test should have a java_test_runner as the runner for test protocol");
      }

      params = params.copyAppendingExtraDeps(testRunner.getCompiledTestsLibrary());

      // Construct the build rule to build the binary JAR.
      ImmutableSet<JavaLibrary> transitiveClasspathDeps =
          JavaLibraryClasspathProvider.getClasspathDeps(params.getBuildDeps());
      ImmutableSet<SourcePath> transitiveClasspaths =
          JavaLibraryClasspathProvider.getClasspathsFromLibraries(transitiveClasspathDeps);
      JavaBinary javaBinary =
          new JavaBinary(
              buildTarget.withFlavors(InternalFlavor.of("bin")),
              projectFilesystem,
              params.copyAppendingExtraDeps(transitiveClasspathDeps),
              javaOptionsForTests
                  .get()
                  .getJavaRuntimeLauncher(graphBuilder, buildTarget.getTargetConfiguration()),
              testRunner.getMainClass(),
              args.getManifestFile().orElse(null),
              true,
              false,
              null,
              ImmutableSet.of(),
              transitiveClasspathDeps,
              transitiveClasspaths,
              javaBuckConfig.shouldCacheBinaries(),
              javaBuckConfig.getDuplicatesLogLevel());

      graphBuilder.addToIndex(javaBinary);

      return new RobolectricTestX(
          buildTarget,
          projectFilesystem,
          params.copyAppendingExtraDeps(javaBinary),
          javaBinary,
          testsLibrary,
          args.getLabels(),
          args.getContacts(),
          TestRunnerSpecCoercer.coerce(args.getSpecs().get(), macrosConverter),
          vmArgs,
          dummyRDotJava,
          args.getUnbundledResourcesRoot(),
          args.getRobolectricRuntimeDependency(),
          javaBuckConfig
              .getDelegate()
              .getBooleanValue("test", "pass_robolectric_directories_in_file", false));
    } else if (runner.isPresent()) {
      throw new HumanReadableException("Should not have runner set when no specs are set");
    }

    return new RobolectricTest(
        buildTarget,
        projectFilesystem,
        params,
        androidPlatformTarget,
        testsLibrary,
        args.getLabels(),
        args.getContacts(),
        TestType.JUNIT,
        javacOptions.getLanguageLevelOptions().getTargetLevel(),
        vmArgs,
        cxxLibraryEnhancement.nativeLibsEnvironment,
        dummyRDotJava,
        args.getTestRuleTimeoutMs()
            .map(Optional::of)
            .orElse(
                javaBuckConfig
                    .getDelegate()
                    .getView(TestBuckConfig.class)
                    .getDefaultTestRuleTimeoutMs()),
        args.getTestCaseTimeoutMs(),
        ImmutableMap.copyOf(Maps.transformValues(args.getEnv(), macrosConverter::convert)),
        args.getRunTestSeparately(),
        args.getForkMode(),
        args.getStdOutLogLevel(),
        args.getStdErrLogLevel(),
        args.getUnbundledResourcesRoot(),
        args.getRobolectricRuntimeDependency(),
        args.getRobolectricManifest(),
        javaBuckConfig
            .getDelegate()
            .getBooleanValue("test", "pass_robolectric_directories_in_file", false),
        javaOptionsForTests
            .get()
            .getJavaRuntimeLauncher(graphBuilder, buildTarget.getTargetConfiguration()));
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      RobolectricTestDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    if (constructorArg.getUseCxxLibraries().orElse(false)) {
      targetGraphOnlyDepsBuilder.addAll(
          getCxxPlatform(constructorArg).getParseTimeDeps(buildTarget.getTargetConfiguration()));
    }
    javaOptionsForTests
        .get()
        .addParseTimeDeps(targetGraphOnlyDepsBuilder, buildTarget.getTargetConfiguration());
    javacFactory.addParseTimeDeps(targetGraphOnlyDepsBuilder, constructorArg);
  }

  @BuckStyleImmutable
  @Value.Immutable(copy = true)
  interface AbstractRobolectricTestDescriptionArg
      extends JavaTestDescription.CoreArg, AndroidKotlinCoreArg, HasTestRunner {

    Optional<SourcePath> getRobolectricRuntimeDependency();

    Optional<SourcePath> getRobolectricManifest();

    Optional<String> getResourceUnionPackage();

    @Value.Default
    default boolean isUseOldStyleableFormat() {
      return false;
    }

    @Value.Default
    default boolean isForceFinalResourceIds() {
      return true;
    }

  }
}
