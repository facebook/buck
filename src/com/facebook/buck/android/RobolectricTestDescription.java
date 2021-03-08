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
import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.CalculateClassAbi;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.DefaultJavaLibraryRules;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaCDBuckConfig;
import com.facebook.buck.jvm.java.JavaLibraryDeps;
import com.facebook.buck.jvm.java.JavaOptions;
import com.facebook.buck.jvm.java.JavaTest;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsFactory;
import com.facebook.buck.jvm.java.JavacPluginParams;
import com.facebook.buck.jvm.java.TestType;
import com.facebook.buck.jvm.java.toolchain.JavaCxxPlatformProvider;
import com.facebook.buck.jvm.java.toolchain.JavaOptionsProvider;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.test.config.TestBuckConfig;
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
import com.google.common.collect.Ordering;
import java.nio.file.Path;
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
  private final JavaCDBuckConfig javaCDBuckConfig;
  private final DownwardApiConfig downwardApiConfig;
  private final AndroidLibraryCompilerFactory compilerFactory;
  private final Function<TargetConfiguration, JavaOptions> javaOptionsForTests;
  private final Function<TargetConfiguration, JavaOptions> java11OptionsForTests;
  private final LoadingCache<TargetConfiguration, JavacOptions> defaultJavacOptions;
  private final JavacFactory javacFactory;

  public RobolectricTestDescription(
      ToolchainProvider toolchainProvider,
      JavaBuckConfig javaBuckConfig,
      JavaCDBuckConfig javaCDBuckConfig,
      DownwardApiConfig downwardApiConfig,
      AndroidLibraryCompilerFactory compilerFactory) {
    this.toolchainProvider = toolchainProvider;
    this.javaBuckConfig = javaBuckConfig;
    this.javaCDBuckConfig = javaCDBuckConfig;
    this.downwardApiConfig = downwardApiConfig;
    this.compilerFactory = compilerFactory;
    this.javaOptionsForTests = JavaOptionsProvider.getDefaultJavaOptionsForTests(toolchainProvider);
    this.java11OptionsForTests =
        JavaOptionsProvider.getDefaultJava11OptionsForTests(toolchainProvider);
    this.defaultJavacOptions =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<TargetConfiguration, JavacOptions>() {
                  @Override
                  public JavacOptions load(TargetConfiguration toolchainTargetConfiguration) {
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
            projectFilesystem.getRootPath(),
            args);

    CellPathResolver cellPathResolver = context.getCellPathResolver();
    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.of(
            buildTarget,
            cellPathResolver.getCellNameResolver(),
            graphBuilder,
            JavaTestDescription.MACRO_EXPANDERS);
    ImmutableList<Arg> vmArgs =
        ImmutableList.copyOf(Lists.transform(args.getVmArgs(), macrosConverter::convert));

    ImmutableSortedSet<BuildRule> originalDeps =
        ImmutableSortedSet.copyOf(
            Iterables.concat(
                params.getBuildDeps(), graphBuilder.getAllRules(args.getExportedDeps())));
    ImmutableList<HasAndroidResourceDeps> androidResourceDeps =
        UnsortedAndroidResourceDeps.createFrom(originalDeps).getResourceDeps().stream()
            .sorted(Comparator.comparing(HasAndroidResourceDeps::getBuildTarget))
            .collect(ImmutableList.toImmutableList());

    RobolectricTestDescriptionArg testLibraryArgs = args;

    AndroidPlatformTarget androidPlatformTarget =
        toolchainProvider.getByName(
            AndroidPlatformTarget.DEFAULT_NAME,
            buildTarget.getTargetConfiguration(),
            AndroidPlatformTarget.class);

    FilteredResourcesProvider resourcesProvider =
        new IdentityResourcesProvider(
            androidResourceDeps.stream()
                .map(HasAndroidResourceDeps::getRes)
                .filter(Objects::nonNull)
                .collect(ImmutableList.toImmutableList()));

    ToolProvider aapt2ToolProvider = androidPlatformTarget.getAapt2ToolProvider();

    ImmutableList<Aapt2Compile> compileables =
        androidResourceDeps.stream()
            .filter(resource -> resource.getRes() != null)
            .map(
                resource ->
                    (Aapt2Compile)
                        graphBuilder.requireRule(
                            resource
                                .getBuildTarget()
                                .withAppendedFlavors(
                                    AndroidResourceDescription.AAPT2_COMPILE_FLAVOR)))
            .collect(ImmutableList.toImmutableList());

    BuildTarget aapt2LinkBuildTarget =
        buildTarget.withAppendedFlavors(InternalFlavor.of("aapt2_link"));
    Aapt2Link aapt2Link =
        new Aapt2Link(
            aapt2LinkBuildTarget,
            projectFilesystem,
            graphBuilder,
            compileables,
            args.getRobolectricManifest(),
            args.getManifestEntries(),
            0,
            ImmutableList.of(),
            false,
            false,
            false,
            false,
            false,
            false,
            aapt2ToolProvider.resolve(graphBuilder, aapt2LinkBuildTarget.getTargetConfiguration()),
            ImmutableList.of(),
            androidPlatformTarget.getAndroidJar(),
            !args.getLocalesForBinaryResources().isEmpty(),
            args.getLocalesForBinaryResources(),
            ImmutableSet.of(),
            Optional.empty(),
            args.getPreferredDensityForBinaryResources(),
            args.getManifestEntries().getMinSdkVersion(),
            false,
            downwardApiConfig.isEnabledForAndroid());

    graphBuilder.addToIndex(aapt2Link);
    AaptOutputInfo aaptOutputInfo = aapt2Link.getAaptOutputInfo();

    MergeAssets binaryResources =
        new MergeAssets(
            buildTarget.withAppendedFlavors(
                AndroidBinaryResourcesGraphEnhancer.MERGE_ASSETS_FLAVOR),
            projectFilesystem,
            graphBuilder,
            Optional.of(aaptOutputInfo.getPrimaryResourcesApkPath()),
            androidResourceDeps.stream()
                .map(HasAndroidResourceDeps::getAssets)
                .filter(Objects::nonNull)
                .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())),
            javaBuckConfig.getDelegate().getView(BuildBuckConfig.class).areExternalActionsEnabled(),
            javaBuckConfig.getDefaultJavaOptions().getJavaRuntime(),
            DefaultJavaLibraryRules.getExternalActionsSourcePathSupplier(projectFilesystem));
    graphBuilder.addToIndex(binaryResources);

    SourcePathResolverAdapter sourcePathResolver = graphBuilder.getSourcePathResolver();
    RelPath outputPath =
        sourcePathResolver.getCellUnsafeRelPath(binaryResources.getSourcePathToOutput());
    RelPath manifestPath = sourcePathResolver.getCellUnsafeRelPath(args.getRobolectricManifest());
    UnitTestOptions unitTestOptions =
        new UnitTestOptions(
            buildTarget.withAppendedFlavors(InternalFlavor.of("unit_test_options")),
            projectFilesystem,
            graphBuilder,
            ImmutableMap.of(
                "android_resource_apk",
                // wrapped to unix path separators as otherwise Robolectic tests manifest get broken
                // on windows due to different path separators.
                // java works fine with '/' on all platforms
                PathFormatter.pathWithUnixSeparators(outputPath),
                "android_merged_manifest",
                PathFormatter.pathWithUnixSeparators(manifestPath)));
    graphBuilder.addToIndex(unitTestOptions);
    params = params.copyAppendingExtraDeps(ImmutableSortedSet.of(unitTestOptions));

    if (resourcesProvider.hasResources()) {
      GenerateRDotJava generateRDotJava =
          new GenerateRDotJava(
              buildTarget.withAppendedFlavors(InternalFlavor.of("generate_rdot_java")),
              projectFilesystem,
              graphBuilder,
              EnumSet.noneOf(RDotTxtEntry.RType.class),
              Optional.empty(),
              ImmutableList.of(aaptOutputInfo.getPathToRDotTxt()),
              args.getResourceUnionPackage(),
              androidResourceDeps.stream()
                  .filter(resources -> resources.getRes() != null)
                  .map(HasAndroidResourceDeps::getBuildTarget)
                  .map(graphBuilder::requireRule)
                  .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder())),
              ImmutableList.of(resourcesProvider));
      graphBuilder.addToIndex(generateRDotJava);
      params = params.copyAppendingExtraDeps(generateRDotJava);
      DefaultJavaLibrary rDotJavaLibrary =
          DefaultJavaLibrary.rulesBuilder(
                  buildTarget.withAppendedFlavors(InternalFlavor.of("rdotjavalibrary")),
                  projectFilesystem,
                  context.getToolchainProvider(),
                  params,
                  graphBuilder,
                  compilerFactory.getCompiler(
                      AndroidLibraryDescription.JvmLanguage.JAVA,
                      javacFactory,
                      buildTarget.getTargetConfiguration()),
                  javaBuckConfig,
                  javaCDBuckConfig,
                  downwardApiConfig,
                  null,
                  cellPathResolver)
              .setJavacOptions(
                  javacOptions.withJavaAnnotationProcessorParams(JavacPluginParams.EMPTY))
              .setSrcs(ImmutableList.of(generateRDotJava.getSourcePathToRZip()))
              .setDeps(new JavaLibraryDeps.Builder(graphBuilder).build())
              .build()
              .buildLibrary();
      graphBuilder.addToIndex(rDotJavaLibrary);
      params = params.copyAppendingExtraDeps(ImmutableSortedSet.of(rDotJavaLibrary));

      ImmutableSortedSet<BuildTarget> updatedDeps =
          ImmutableSortedSet.<BuildTarget>naturalOrder()
              .addAll(testLibraryArgs.getDeps())
              .add(rDotJavaLibrary.getBuildTarget())
              .build();

      testLibraryArgs = testLibraryArgs.withDeps(updatedDeps);
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
                .resolve(graphBuilder, buildTarget.getTargetConfiguration()),
            javaBuckConfig.shouldAddBuckLDSymlinkTree());
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
                    javaCDBuckConfig,
                    downwardApiConfig,
                    testLibraryArgs,
                    cellPathResolver)
                .setJavacOptions(javacOptions)
                .build()
                .buildLibrary());
    params = params.copyAppendingExtraDeps(ImmutableSortedSet.of(testsLibrary));

    Function<TargetConfiguration, JavaOptions> javaRuntimeConfig =
        javacOptions.getLanguageLevelOptions().getTargetLevel().equals("11")
            ? java11OptionsForTests
            : javaOptionsForTests;
    JavaOptions javaOptions = javaRuntimeConfig.apply(buildTarget.getTargetConfiguration());

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
        cxxLibraryEnhancement.requiredPaths,
        binaryResources,
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
        args.getResources(),
        args.getExternalResourcesPaths(),
        args.getRobolectricRuntimeDependency(),
        args.getRobolectricManifest(),
        javaOptions.getJavaRuntime(),
        javaOptions.getJavaRuntimeVersion(),
        javaBuckConfig
            .getDelegate()
            .getBooleanValue("test", "include_boot_classpath_in_required_paths", true),
        javaBuckConfig
            .getDelegate()
            .getView(TestBuckConfig.class)
            .useRelativePathsInClasspathFile(),
        downwardApiConfig.isEnabledForTests());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      RobolectricTestDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    targetGraphOnlyDepsBuilder.addAll(
        getCxxPlatform(constructorArg, buildTarget.getTargetConfiguration())
            .getParseTimeDeps(buildTarget.getTargetConfiguration()));
    javacFactory.addParseTimeDeps(
        targetGraphOnlyDepsBuilder, constructorArg, buildTarget.getTargetConfiguration());
  }

  @RuleArg
  interface AbstractRobolectricTestDescriptionArg
      extends JavaTestDescription.CoreArg, AndroidKotlinCoreArg {

    Optional<SourcePath> getRobolectricRuntimeDependency();

    SourcePath getRobolectricManifest();

    Optional<String> getResourceUnionPackage();

    ImmutableSet<String> getLocalesForBinaryResources();

    Optional<String> getPreferredDensityForBinaryResources();

    @Hint(isInput = false)
    @Value.NaturalOrder
    ImmutableSortedSet<Path> getExternalResourcesPaths();

    @Value.Default
    default ManifestEntries getManifestEntries() {
      return ManifestEntries.empty();
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
