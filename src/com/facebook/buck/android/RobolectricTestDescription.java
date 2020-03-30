/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.android.aapt.RDotTxtEntry;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.TargetConfiguration;
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
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
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
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.test.config.TestBuckConfig;
import com.facebook.buck.util.DependencyMode;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.immutables.value.Value;

public class RobolectricTestDescription
    implements DescriptionWithTargetGraph<RobolectricTestDescriptionArg>,
        ImplicitDepsInferringDescription<RobolectricTestDescriptionArg> {


  private final ToolchainProvider toolchainProvider;
  private final JavaBuckConfig javaBuckConfig;
  private final AndroidLibraryCompilerFactory compilerFactory;
  private final Function<TargetConfiguration, JavaOptions> javaOptionsForTests;
  private final LoadingCache<TargetConfiguration, JavacOptions> defaultJavacOptions;
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
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<TargetConfiguration, JavacOptions>() {
                  @Override
                  public JavacOptions load(TargetConfiguration toolchainTargetConfiguration)
                      throws Exception {
                    return toolchainProvider
                        .getByName(
                            JavacOptionsProvider.DEFAULT_NAME,
                            toolchainTargetConfiguration,
                            JavacOptionsProvider.class)
                        .getJavacOptions();
                  }
                });
    this.javacFactory = JavacFactory.getDefault(toolchainProvider);
  }

  @Override
  public Class<RobolectricTestDescriptionArg> getConstructorArgType() {
    return RobolectricTestDescriptionArg.class;
  }

  private UnresolvedCxxPlatform getCxxPlatform(
      RobolectricTestDescriptionArg args, TargetConfiguration toolchainTargetConfiguration) {
    return args.getDefaultCxxPlatform()
        .map(
            toolchainProvider
                    .getByName(
                        CxxPlatformsProvider.DEFAULT_NAME,
                        toolchainTargetConfiguration,
                        CxxPlatformsProvider.class)
                    .getUnresolvedCxxPlatforms()
                ::getValue)
        .orElse(
            toolchainProvider
                .getByName(
                    JavaCxxPlatformProvider.DEFAULT_NAME,
                    toolchainTargetConfiguration,
                    JavaCxxPlatformProvider.class)
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
        JavacOptionsFactory.create(
            defaultJavacOptions.getUnchecked(buildTarget.getTargetConfiguration()),
            buildTarget,
            graphBuilder,
            args);

    AndroidLibraryGraphEnhancer graphEnhancer =
        new AndroidLibraryGraphEnhancer(
            buildTarget,
            projectFilesystem,
            ImmutableSortedSet.copyOf(
                Iterables.concat(
                    params.getBuildDeps(), graphBuilder.getAllRules(args.getExportedDeps()))),
            javacFactory.create(graphBuilder, args, buildTarget.getTargetConfiguration()),
            javacOptions,
            DependencyMode.TRANSITIVE,
            args.isForceFinalResourceIds(),
            args.getResourceUnionPackage(),
            /* rName */ Optional.empty(),
            args.isUseOldStyleableFormat(),
            /* skipNonUnionRDotJava */ false);

    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.of(
            buildTarget,
            context.getCellPathResolver().getCellNameResolver(),
            graphBuilder,
            JavaTestDescription.MACRO_EXPANDERS);
    ImmutableList<Arg> vmArgs =
        ImmutableList.copyOf(Lists.transform(args.getVmArgs(), macrosConverter::convert));


    Optional<DummyRDotJava> dummyRDotJava =
        graphEnhancer.getBuildableForAndroidResources(
            graphBuilder, /* createBuildableIfEmpty */ true);
    RobolectricTestDescriptionArg testLibraryArgs = args;

    AndroidPlatformTarget androidPlatformTarget =
        toolchainProvider.getByName(
            AndroidPlatformTarget.DEFAULT_NAME,
            buildTarget.getTargetConfiguration(),
            AndroidPlatformTarget.class);

    Optional<UnitTestOptions> unitTestOptions = Optional.empty();
    if (dummyRDotJava.isPresent()) {
      DummyRDotJava actualDummyRDotJava = dummyRDotJava.get();
      ImmutableSortedSet<BuildRule> newDeclaredDeps =
          ImmutableSortedSet.<BuildRule>naturalOrder()
              .addAll(params.getDeclaredDeps().get())
              .add(actualDummyRDotJava)
              .build();
      if (!args.isUseBinaryResources()) {
        params = params.withDeclaredDeps(newDeclaredDeps);
        testLibraryArgs =
            testLibraryArgs.withDeps(
                Iterables.concat(
                    args.getDeps(),
                    Collections.singletonList(actualDummyRDotJava.getBuildTarget())));
      } else {
        Optional<SourcePath> maybeRoboManifest = args.getRobolectricManifest();
        Preconditions.checkArgument(
            maybeRoboManifest.isPresent(),
            "You must specify a manifest to use binary resources mode.");
        SourcePath robolectricManifest = maybeRoboManifest.get();

        FilteredResourcesProvider resourcesProvider =
            new IdentityResourcesProvider(
                actualDummyRDotJava.getAndroidResourceDeps().stream()
                    .map(HasAndroidResourceDeps::getRes)
                    .collect(ImmutableList.toImmutableList()));

        ToolProvider aapt2ToolProvider = androidPlatformTarget.getAapt2ToolProvider();

        ImmutableList<Aapt2Compile> compileables =
            AndroidBinaryResourcesGraphEnhancer.createAapt2CompileablesForResourceProvider(
                projectFilesystem,
                graphBuilder,
                aapt2ToolProvider,
                resourcesProvider,
                buildTarget,
                true,
                false);

        BuildTarget aapt2LinkBuildTarget =
            buildTarget.withAppendedFlavors(InternalFlavor.of("aapt2_link"));
        Aapt2Link aapt2Link =
            new Aapt2Link(
                aapt2LinkBuildTarget,
                projectFilesystem,
                graphBuilder,
                compileables,
                robolectricManifest,
                ManifestEntries.builder().build(),
                0,
                ImmutableList.of(),
                false,
                false,
                false,
                false,
                false,
                false,
                aapt2ToolProvider.resolve(
                    graphBuilder, aapt2LinkBuildTarget.getTargetConfiguration()),
                ImmutableList.of(),
                androidPlatformTarget.getAndroidJar(),
                false,
                ImmutableSet.of(),
                ImmutableSet.of());

        graphBuilder.addToIndex(aapt2Link);
        AaptOutputInfo aaptOutputInfo = aapt2Link.getAaptOutputInfo();

        unitTestOptions =
            Optional.of(
                new UnitTestOptions(
                    buildTarget.withAppendedFlavors(InternalFlavor.of("unit_test_options")),
                    projectFilesystem,
                    graphBuilder,
                    ImmutableMap.of(
                        "android_resource_apk",
                        graphBuilder
                            .getSourcePathResolver()
                            .getAbsolutePath(aaptOutputInfo.getPrimaryResourcesApkPath())
                            .toString())));

        graphBuilder.addToIndex(unitTestOptions.get());

        GenerateRDotJava generateRDotJava =
            new GenerateRDotJava(
                buildTarget.withAppendedFlavors(InternalFlavor.of("generate_rdot_java")),
                projectFilesystem,
                graphBuilder,
                EnumSet.noneOf(RDotTxtEntry.RType.class),
                Optional.empty(),
                ImmutableList.of(aaptOutputInfo.getPathToRDotTxt()),
                args.getResourceUnionPackage(),
                actualDummyRDotJava.getAndroidResourceDeps().stream()
                    .map(HasAndroidResourceDeps::getBuildTarget)
                    .map(graphBuilder::requireRule)
                    .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder())),
                ImmutableList.of(resourcesProvider));

        graphBuilder.addToIndex(generateRDotJava);

        params =
            params.copyAppendingExtraDeps(
                ImmutableSortedSet.of(generateRDotJava, unitTestOptions.get()));

        ImmutableSortedSet<SourcePath> updatedSrcs =
            ImmutableSortedSet.<SourcePath>naturalOrder()
                .addAll(testLibraryArgs.getSrcs())
                .add(generateRDotJava.getSourcePathToRZip())
                .build();

        testLibraryArgs = testLibraryArgs.withSrcs(updatedSrcs);
      }
    }

    JavaTestDescription.CxxLibraryEnhancement cxxLibraryEnhancement =
        new JavaTestDescription.CxxLibraryEnhancement(
            buildTarget,
            projectFilesystem,
            params,
            args.getUseCxxLibraries(),
            args.getCxxLibraryWhitelist(),
            graphBuilder,
            getCxxPlatform(args, buildTarget.getTargetConfiguration())
                .resolve(graphBuilder, buildTarget.getTargetConfiguration()));
    params = cxxLibraryEnhancement.updatedParams;

    BuildTarget testLibraryBuildTarget =
        buildTarget.withAppendedFlavors(JavaTest.COMPILED_TESTS_LIBRARY_FLAVOR);

    JavaLibrary testsLibrary =
        graphBuilder.addToIndex(
            DefaultJavaLibrary.rulesBuilder(
                    testLibraryBuildTarget,
                    projectFilesystem,
                    context.getToolchainProvider(),
                    params,
                    graphBuilder,
                    compilerFactory.getCompiler(
                        args.getLanguage().orElse(AndroidLibraryDescription.JvmLanguage.JAVA),
                        javacFactory,
                        buildTarget.getTargetConfiguration()),
                    javaBuckConfig,
                    testLibraryArgs)
                .setJavacOptions(javacOptions)
                .build()
                .buildLibrary());
    params = params.copyAppendingExtraDeps(ImmutableSortedSet.of(testsLibrary));

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
                  .apply(buildTarget.getTargetConfiguration())
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
        args.isUseBinaryResources() ? Optional.empty() : dummyRDotJava,
        unitTestOptions,
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
            .apply(buildTarget.getTargetConfiguration())
            .getJavaRuntimeLauncher(graphBuilder, buildTarget.getTargetConfiguration()));
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      RobolectricTestDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    if (constructorArg.getUseCxxLibraries().orElse(false)) {
      targetGraphOnlyDepsBuilder.addAll(
          getCxxPlatform(constructorArg, buildTarget.getTargetConfiguration())
              .getParseTimeDeps(buildTarget.getTargetConfiguration()));
    }
    javaOptionsForTests
        .apply(buildTarget.getTargetConfiguration())
        .addParseTimeDeps(targetGraphOnlyDepsBuilder, buildTarget.getTargetConfiguration());
    javacFactory.addParseTimeDeps(
        targetGraphOnlyDepsBuilder, constructorArg, buildTarget.getTargetConfiguration());
  }

  @RuleArg
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

    @Value.Default
    default boolean isUseBinaryResources() {
      return false;
    }

    default RobolectricTestDescriptionArg withDeps(Iterable<BuildTarget> deps) {
      if (getDeps().equals(deps)) {
        return (RobolectricTestDescriptionArg) this;
      }
      return RobolectricTestDescriptionArg.builder().from(this).setDeps(deps).build();
    }

    default RobolectricTestDescriptionArg withSrcs(Iterable<SourcePath> srcs) {
      if (getSrcs().equals(srcs)) {
        return (RobolectricTestDescriptionArg) this;
      }
      return RobolectricTestDescriptionArg.builder().from(this).setSrcs(srcs).build();
    }

  }
}
