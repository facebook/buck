/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.android.AndroidAarDescription;
import com.facebook.buck.android.AndroidAppModularityDescription;
import com.facebook.buck.android.AndroidBinaryDescription;
import com.facebook.buck.android.AndroidBuildConfigDescription;
import com.facebook.buck.android.AndroidInstrumentationApkDescription;
import com.facebook.buck.android.AndroidInstrumentationTestDescription;
import com.facebook.buck.android.AndroidLibraryCompilerFactory;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.android.AndroidManifestDescription;
import com.facebook.buck.android.AndroidPrebuiltAarDescription;
import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.android.ApkGenruleDescription;
import com.facebook.buck.android.DefaultAndroidLibraryCompilerFactory;
import com.facebook.buck.android.DxConfig;
import com.facebook.buck.android.GenAidlDescription;
import com.facebook.buck.android.NdkLibraryDescription;
import com.facebook.buck.android.PrebuiltNativeLibraryDescription;
import com.facebook.buck.android.ProGuardConfig;
import com.facebook.buck.android.RobolectricTestDescription;
import com.facebook.buck.android.SmartDexingStep;
import com.facebook.buck.android.toolchain.NdkCxxPlatform;
import com.facebook.buck.android.toolchain.NdkCxxPlatformsProvider;
import com.facebook.buck.android.toolchain.impl.NdkCxxPlatformsProviderFactory;
import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.apple.AppleBinaryDescription;
import com.facebook.buck.apple.AppleBundleDescription;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.ApplePackageDescription;
import com.facebook.buck.apple.AppleTestDescription;
import com.facebook.buck.apple.CodeSignIdentityStore;
import com.facebook.buck.apple.PrebuiltAppleFrameworkDescription;
import com.facebook.buck.apple.ProvisioningProfileStore;
import com.facebook.buck.apple.SceneKitAssetsDescription;
import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.apple.toolchain.impl.AppleCxxPlatformsProviderFactory;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.DownloadConfig;
import com.facebook.buck.cxx.CxxBinaryDescription;
import com.facebook.buck.cxx.CxxGenruleDescription;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxPrecompiledHeaderDescription;
import com.facebook.buck.cxx.CxxTestDescription;
import com.facebook.buck.cxx.PrebuiltCxxLibraryDescription;
import com.facebook.buck.cxx.PrebuiltCxxLibraryGroupDescription;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.InferBuckConfig;
import com.facebook.buck.d.DBinaryDescription;
import com.facebook.buck.d.DBuckConfig;
import com.facebook.buck.d.DLibraryDescription;
import com.facebook.buck.d.DTestDescription;
import com.facebook.buck.dotnet.CsharpLibraryDescription;
import com.facebook.buck.dotnet.PrebuiltDotnetLibraryDescription;
import com.facebook.buck.file.Downloader;
import com.facebook.buck.file.ExplodingDownloader;
import com.facebook.buck.file.RemoteFileDescription;
import com.facebook.buck.file.StackedDownloader;
import com.facebook.buck.go.GoBinaryDescription;
import com.facebook.buck.go.GoBuckConfig;
import com.facebook.buck.go.GoLibraryDescription;
import com.facebook.buck.go.GoTestDescription;
import com.facebook.buck.graphql.GraphqlLibraryDescription;
import com.facebook.buck.gwt.GwtBinaryDescription;
import com.facebook.buck.halide.HalideBuckConfig;
import com.facebook.buck.halide.HalideLibraryDescription;
import com.facebook.buck.haskell.HaskellBinaryDescription;
import com.facebook.buck.haskell.HaskellBuckConfig;
import com.facebook.buck.haskell.HaskellGhciDescription;
import com.facebook.buck.haskell.HaskellHaddockDescription;
import com.facebook.buck.haskell.HaskellLibraryDescription;
import com.facebook.buck.haskell.HaskellPlatform;
import com.facebook.buck.haskell.HaskellPrebuiltLibraryDescription;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.js.AndroidReactNativeLibraryDescription;
import com.facebook.buck.js.IosReactNativeLibraryDescription;
import com.facebook.buck.js.JsBundleDescription;
import com.facebook.buck.js.JsBundleGenruleDescription;
import com.facebook.buck.js.JsLibraryDescription;
import com.facebook.buck.js.ReactNativeBuckConfig;
import com.facebook.buck.jvm.groovy.GroovyBuckConfig;
import com.facebook.buck.jvm.groovy.GroovyLibraryDescription;
import com.facebook.buck.jvm.groovy.GroovyTestDescription;
import com.facebook.buck.jvm.java.JavaAnnotationProcessorDescription;
import com.facebook.buck.jvm.java.JavaBinaryDescription;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaOptions;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.KeystoreDescription;
import com.facebook.buck.jvm.kotlin.KotlinBuckConfig;
import com.facebook.buck.jvm.kotlin.KotlinLibraryDescription;
import com.facebook.buck.jvm.kotlin.KotlinTestDescription;
import com.facebook.buck.jvm.scala.ScalaBuckConfig;
import com.facebook.buck.jvm.scala.ScalaLibraryDescription;
import com.facebook.buck.jvm.scala.ScalaTestDescription;
import com.facebook.buck.log.CommandThreadFactory;
import com.facebook.buck.log.Logger;
import com.facebook.buck.lua.CxxLuaExtensionDescription;
import com.facebook.buck.lua.LuaBinaryDescription;
import com.facebook.buck.lua.LuaBuckConfig;
import com.facebook.buck.lua.LuaLibraryDescription;
import com.facebook.buck.lua.LuaPlatform;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.ocaml.OcamlBinaryDescription;
import com.facebook.buck.ocaml.OcamlBuckConfig;
import com.facebook.buck.ocaml.OcamlLibraryDescription;
import com.facebook.buck.ocaml.PrebuiltOcamlLibraryDescription;
import com.facebook.buck.python.CxxPythonExtensionDescription;
import com.facebook.buck.python.PrebuiltPythonLibraryDescription;
import com.facebook.buck.python.PythonBinaryDescription;
import com.facebook.buck.python.PythonBuckConfig;
import com.facebook.buck.python.PythonLibraryDescription;
import com.facebook.buck.python.PythonPlatform;
import com.facebook.buck.python.PythonTestDescription;
import com.facebook.buck.rust.PrebuiltRustLibraryDescription;
import com.facebook.buck.rust.RustBinaryDescription;
import com.facebook.buck.rust.RustBuckConfig;
import com.facebook.buck.rust.RustLibraryDescription;
import com.facebook.buck.rust.RustTestDescription;
import com.facebook.buck.shell.CommandAliasDescription;
import com.facebook.buck.shell.ExportFileDescription;
import com.facebook.buck.shell.GenruleDescription;
import com.facebook.buck.shell.ShBinaryDescription;
import com.facebook.buck.shell.ShTestDescription;
import com.facebook.buck.shell.WorkerToolDescription;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.swift.SwiftLibraryDescription;
import com.facebook.buck.swift.toolchain.SwiftPlatformsProvider;
import com.facebook.buck.swift.toolchain.impl.SwiftPlatformsProviderFactory;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.versions.VersionedAliasDescription;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import javax.annotation.Nullable;
import org.pf4j.PluginManager;

/** A registry of all the build rules types understood by Buck. */
public class KnownBuildRuleTypes {

  private static final Logger LOG = Logger.get(KnownBuildRuleTypes.class);
  private final ImmutableMap<BuildRuleType, Description<?>> descriptions;
  private final ImmutableMap<String, BuildRuleType> types;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;
  private final CxxPlatform defaultCxxPlatforms;

  private KnownBuildRuleTypes(
      Map<BuildRuleType, Description<?>> descriptions,
      Map<String, BuildRuleType> types,
      FlavorDomain<CxxPlatform> cxxPlatforms,
      CxxPlatform defaultCxxPlatforms) {
    this.descriptions = ImmutableMap.copyOf(descriptions);
    this.types = ImmutableMap.copyOf(types);
    this.cxxPlatforms = cxxPlatforms;
    this.defaultCxxPlatforms = defaultCxxPlatforms;
  }

  public BuildRuleType getBuildRuleType(String named) {
    BuildRuleType type = types.get(named);
    if (type == null) {
      throw new HumanReadableException("Unable to find build rule type: " + named);
    }
    return type;
  }

  public Description<?> getDescription(BuildRuleType buildRuleType) {
    Description<?> description = descriptions.get(buildRuleType);
    if (description == null) {
      throw new HumanReadableException(
          "Unable to find description for build rule type: " + buildRuleType);
    }
    return description;
  }

  public ImmutableSet<Description<?>> getAllDescriptions() {
    return ImmutableSet.copyOf(descriptions.values());
  }

  public FlavorDomain<CxxPlatform> getCxxPlatforms() {
    return cxxPlatforms;
  }

  public CxxPlatform getDefaultCxxPlatforms() {
    return defaultCxxPlatforms;
  }

  public static Builder builder() {
    return new Builder();
  }

  static KnownBuildRuleTypes createInstance(
      BuckConfig config,
      ProjectFilesystem filesystem,
      ProcessExecutor processExecutor,
      ToolchainProvider toolchainProvider,
      SdkEnvironment sdkEnvironment,
      PluginManager pluginManager)
      throws InterruptedException, IOException {

    SwiftBuckConfig swiftBuckConfig = new SwiftBuckConfig(config);

    AppleCxxPlatformsProvider appleCxxPlatformsProvider =
        AppleCxxPlatformsProviderFactory.create(
            config,
            filesystem,
            sdkEnvironment.getAppleSdkPaths(),
            sdkEnvironment.getAppleToolchains());

    FlavorDomain<AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms =
        appleCxxPlatformsProvider.getAppleCxxPlatforms();

    SwiftPlatformsProvider swiftPlatformsProvider =
        SwiftPlatformsProviderFactory.create(appleCxxPlatformsProvider);

    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(config);

    // Setup the NDK C/C++ platforms.
    NdkCxxPlatformsProvider ndkCxxPlatformsProvider =
        NdkCxxPlatformsProviderFactory.create(config, filesystem, toolchainProvider);

    ImmutableMap<TargetCpuType, NdkCxxPlatform> ndkCxxPlatforms =
        ndkCxxPlatformsProvider.getNdkCxxPlatforms();

    // Create a map of system platforms.
    ImmutableMap.Builder<Flavor, CxxPlatform> cxxSystemPlatformsBuilder = ImmutableMap.builder();

    // If an Android NDK is present, add platforms for that.  This is mostly useful for
    // testing our Android NDK support for right now.
    for (NdkCxxPlatform ndkCxxPlatform : ndkCxxPlatforms.values()) {
      cxxSystemPlatformsBuilder.put(
          ndkCxxPlatform.getCxxPlatform().getFlavor(), ndkCxxPlatform.getCxxPlatform());
    }

    for (AppleCxxPlatform appleCxxPlatform : platformFlavorsToAppleCxxPlatforms.getValues()) {
      cxxSystemPlatformsBuilder.put(
          appleCxxPlatform.getCxxPlatform().getFlavor(), appleCxxPlatform.getCxxPlatform());
    }

    CxxPlatformsProvider cxxPlatformsProvider =
        CxxPlatformsProvider.create(config, cxxSystemPlatformsBuilder.build());

    // Build up the final list of C/C++ platforms.
    FlavorDomain<CxxPlatform> cxxPlatforms = cxxPlatformsProvider.getCxxPlatforms();

    // Get the default target platform from config.
    CxxPlatform defaultCxxPlatform = cxxPlatformsProvider.getDefaultCxxPlatform();

    DBuckConfig dBuckConfig = new DBuckConfig(config);

    ReactNativeBuckConfig reactNativeBuckConfig = new ReactNativeBuckConfig(config);

    RustBuckConfig rustBuckConfig = new RustBuckConfig(config);

    GoBuckConfig goBuckConfig = new GoBuckConfig(config, processExecutor, cxxPlatforms);

    HalideBuckConfig halideBuckConfig = new HalideBuckConfig(config);

    ProGuardConfig proGuardConfig = new ProGuardConfig(config);

    DxConfig dxConfig = new DxConfig(config);

    ExecutableFinder executableFinder = new ExecutableFinder();

    PythonBuckConfig pyConfig = new PythonBuckConfig(config, executableFinder);
    ImmutableList<PythonPlatform> pythonPlatformsList =
        pyConfig.getPythonPlatforms(processExecutor);
    FlavorDomain<PythonPlatform> pythonPlatforms =
        FlavorDomain.from("Python Platform", pythonPlatformsList);
    PythonBinaryDescription pythonBinaryDescription =
        new PythonBinaryDescription(
            pyConfig, pythonPlatforms, cxxBuckConfig, defaultCxxPlatform, cxxPlatforms);

    // Look up the timeout to apply to entire test rules.
    Optional<Long> defaultTestRuleTimeoutMs = config.getLong("test", "rule_timeout");

    // Prepare the downloader if we're allowing mid-build downloads
    Downloader downloader;
    DownloadConfig downloadConfig = new DownloadConfig(config);
    if (downloadConfig.isDownloadAtRuntimeOk()) {
      downloader = StackedDownloader.createFromConfig(config, toolchainProvider);
    } else {
      // Or just set one that blows up
      downloader = new ExplodingDownloader();
    }

    Builder builder = builder();

    JavaBuckConfig javaConfig = config.getView(JavaBuckConfig.class);
    JavacOptions defaultJavacOptions = javaConfig.getDefaultJavacOptions();
    JavaOptions defaultJavaOptions = javaConfig.getDefaultJavaOptions();
    JavaOptions defaultJavaOptionsForTests = javaConfig.getDefaultJavaOptionsForTests();
    CxxPlatform defaultJavaCxxPlatform =
        javaConfig
            .getDefaultCxxPlatform()
            .map(InternalFlavor::of)
            .map(cxxPlatforms::getValue)
            .orElse(defaultCxxPlatform);

    KotlinBuckConfig kotlinBuckConfig = new KotlinBuckConfig(config);

    ScalaBuckConfig scalaConfig = new ScalaBuckConfig(config);

    InferBuckConfig inferBuckConfig = new InferBuckConfig(config);

    LuaBuckConfig luaBuckConfig = new LuaBuckConfig(config, executableFinder);
    FlavorDomain<LuaPlatform> luaPlatforms =
        FlavorDomain.from(
            LuaPlatform.FLAVOR_DOMAIN_NAME, luaBuckConfig.getPlatforms(cxxPlatforms.getValues()));
    LuaPlatform defaultLuaPlatform = luaPlatforms.getValue(defaultCxxPlatform.getFlavor());

    CxxBinaryDescription cxxBinaryDescription =
        new CxxBinaryDescription(
            cxxBuckConfig, inferBuckConfig, defaultCxxPlatform.getFlavor(), cxxPlatforms);

    CxxLibraryDescription cxxLibraryDescription =
        new CxxLibraryDescription(
            cxxBuckConfig, defaultCxxPlatform.getFlavor(), inferBuckConfig, cxxPlatforms);

    SwiftLibraryDescription swiftLibraryDescription =
        new SwiftLibraryDescription(
            cxxBuckConfig,
            swiftBuckConfig,
            cxxPlatforms,
            swiftPlatformsProvider.getSwiftCxxPlatforms());
    builder.register(swiftLibraryDescription);

    AppleConfig appleConfig = config.getView(AppleConfig.class);
    CodeSignIdentityStore codeSignIdentityStore =
        CodeSignIdentityStore.fromSystem(
            processExecutor, appleConfig.getCodeSignIdentitiesCommand());
    ProvisioningProfileStore provisioningProfileStore =
        ProvisioningProfileStore.fromSearchPath(
            processExecutor,
            appleConfig.getProvisioningProfileReadCommand(),
            appleConfig.getProvisioningProfileSearchPath());

    AppleLibraryDescription appleLibraryDescription =
        new AppleLibraryDescription(
            cxxLibraryDescription,
            swiftLibraryDescription,
            platformFlavorsToAppleCxxPlatforms,
            defaultCxxPlatform.getFlavor(),
            codeSignIdentityStore,
            provisioningProfileStore,
            appleConfig,
            swiftBuckConfig,
            swiftPlatformsProvider.getSwiftCxxPlatforms());
    builder.register(appleLibraryDescription);
    PrebuiltAppleFrameworkDescription appleFrameworkDescription =
        new PrebuiltAppleFrameworkDescription(platformFlavorsToAppleCxxPlatforms);
    builder.register(appleFrameworkDescription);

    AppleBinaryDescription appleBinaryDescription =
        new AppleBinaryDescription(
            cxxBinaryDescription,
            swiftLibraryDescription,
            platformFlavorsToAppleCxxPlatforms,
            codeSignIdentityStore,
            provisioningProfileStore,
            appleConfig);
    builder.register(appleBinaryDescription);

    HaskellBuckConfig haskellBuckConfig = new HaskellBuckConfig(config, executableFinder);
    FlavorDomain<HaskellPlatform> haskellPlatforms =
        FlavorDomain.from(
            "Haskell platform", haskellBuckConfig.getPlatforms(cxxPlatforms.getValues()));
    HaskellPlatform defaultHaskellPlatform =
        haskellPlatforms.getValue(defaultCxxPlatform.getFlavor());
    builder.register(new HaskellHaddockDescription(defaultHaskellPlatform, haskellPlatforms));
    builder.register(new HaskellLibraryDescription(haskellPlatforms, cxxBuckConfig));
    builder.register(new HaskellBinaryDescription(defaultHaskellPlatform, haskellPlatforms));
    builder.register(new HaskellPrebuiltLibraryDescription());
    builder.register(
        new HaskellGhciDescription(defaultHaskellPlatform, haskellPlatforms, cxxBuckConfig));

    if (javaConfig.getDxThreadCount().isPresent()) {
      LOG.warn("java.dx_threads has been deprecated. Use dx.max_threads instead");
    }

    // Create an executor service exclusively for the smart dexing step.
    ListeningExecutorService dxExecutorService =
        MoreExecutors.listeningDecorator(
            Executors.newFixedThreadPool(
                dxConfig
                    .getDxMaxThreadCount()
                    .orElse(
                        javaConfig
                            .getDxThreadCount()
                            .orElse(SmartDexingStep.determineOptimalThreadCount())),
                new CommandThreadFactory("SmartDexing")));

    AndroidLibraryCompilerFactory defaultAndroidCompilerFactory =
        new DefaultAndroidLibraryCompilerFactory(javaConfig, scalaConfig, kotlinBuckConfig);

    builder.register(
        new AndroidAarDescription(
            new AndroidManifestDescription(),
            cxxBuckConfig,
            javaConfig,
            defaultJavacOptions,
            ndkCxxPlatforms));
    builder.register(new AndroidAppModularityDescription());
    builder.register(
        new AndroidBinaryDescription(
            toolchainProvider,
            javaConfig,
            defaultJavaOptions,
            defaultJavacOptions,
            proGuardConfig,
            ndkCxxPlatforms,
            dxExecutorService,
            config,
            cxxBuckConfig,
            dxConfig));
    builder.register(new AndroidBuildConfigDescription(javaConfig, defaultJavacOptions));
    builder.register(
        new AndroidInstrumentationApkDescription(
            toolchainProvider,
            javaConfig,
            proGuardConfig,
            defaultJavacOptions,
            ndkCxxPlatforms,
            dxExecutorService,
            cxxBuckConfig,
            dxConfig));
    builder.register(
        new AndroidInstrumentationTestDescription(defaultJavaOptions, defaultTestRuleTimeoutMs));
    builder.register(
        new AndroidLibraryDescription(
            javaConfig, defaultJavacOptions, defaultAndroidCompilerFactory));
    builder.register(new AndroidManifestDescription());
    builder.register(
        new AndroidPrebuiltAarDescription(toolchainProvider, javaConfig, defaultJavacOptions));
    builder.register(
        new AndroidReactNativeLibraryDescription(toolchainProvider, reactNativeBuckConfig));
    builder.register(
        new AndroidResourceDescription(
            toolchainProvider, config.isGrayscaleImageProcessingEnabled()));
    builder.register(new ApkGenruleDescription());
    builder.register(
        new ApplePackageDescription(
            appleConfig, defaultCxxPlatform.getFlavor(), platformFlavorsToAppleCxxPlatforms));
    AppleBundleDescription appleBundleDescription =
        new AppleBundleDescription(
            appleBinaryDescription,
            appleLibraryDescription,
            cxxPlatforms,
            platformFlavorsToAppleCxxPlatforms,
            defaultCxxPlatform.getFlavor(),
            codeSignIdentityStore,
            provisioningProfileStore,
            appleConfig);
    builder.register(appleBundleDescription);
    builder.register(
        new AppleTestDescription(
            appleConfig,
            appleLibraryDescription,
            cxxPlatforms,
            platformFlavorsToAppleCxxPlatforms,
            defaultCxxPlatform.getFlavor(),
            codeSignIdentityStore,
            provisioningProfileStore,
            appleConfig.getAppleDeveloperDirectorySupplierForTests(processExecutor),
            defaultTestRuleTimeoutMs));
    builder.register(new CommandAliasDescription(Platform.detect()));
    builder.register(new CsharpLibraryDescription());
    builder.register(cxxBinaryDescription);
    builder.register(cxxLibraryDescription);
    builder.register(new CxxGenruleDescription(cxxPlatforms));
    builder.register(new CxxLuaExtensionDescription(luaPlatforms, cxxBuckConfig));
    builder.register(
        new CxxPythonExtensionDescription(pythonPlatforms, cxxBuckConfig, cxxPlatforms));
    builder.register(
        new CxxTestDescription(
            cxxBuckConfig, defaultCxxPlatform.getFlavor(), cxxPlatforms, defaultTestRuleTimeoutMs));
    builder.register(new DBinaryDescription(dBuckConfig, cxxBuckConfig, defaultCxxPlatform));
    builder.register(new DLibraryDescription(dBuckConfig, cxxBuckConfig, defaultCxxPlatform));
    builder.register(
        new DTestDescription(
            dBuckConfig, cxxBuckConfig, defaultCxxPlatform, defaultTestRuleTimeoutMs));
    builder.register(new ExportFileDescription());
    builder.register(new GenruleDescription());
    builder.register(new GenAidlDescription(toolchainProvider));
    builder.register(new GoBinaryDescription(goBuckConfig));
    builder.register(new GoLibraryDescription(goBuckConfig));
    builder.register(new GoTestDescription(goBuckConfig, defaultTestRuleTimeoutMs));
    builder.register(new GraphqlLibraryDescription());
    GroovyBuckConfig groovyBuckConfig = new GroovyBuckConfig(config);
    builder.register(
        new GroovyLibraryDescription(groovyBuckConfig, javaConfig, defaultJavacOptions));
    builder.register(
        new GroovyTestDescription(
            groovyBuckConfig,
            javaConfig,
            defaultJavaOptionsForTests,
            defaultJavacOptions,
            defaultTestRuleTimeoutMs));
    builder.register(new GwtBinaryDescription(defaultJavaOptions));
    builder.register(
        new HalideLibraryDescription(
            cxxBuckConfig, defaultCxxPlatform, cxxPlatforms, halideBuckConfig));
    builder.register(new IosReactNativeLibraryDescription(reactNativeBuckConfig));
    builder.register(
        new JavaBinaryDescription(
            defaultJavaOptions,
            defaultJavacOptions,
            javaConfig,
            defaultJavaCxxPlatform,
            cxxPlatforms));
    builder.register(new JavaAnnotationProcessorDescription());
    builder.register(new JavaLibraryDescription(javaConfig, defaultJavacOptions));
    builder.register(
        new JavaTestDescription(
            javaConfig,
            defaultJavaOptionsForTests,
            defaultJavacOptions,
            defaultTestRuleTimeoutMs,
            defaultJavaCxxPlatform,
            cxxPlatforms));
    builder.register(new JsBundleDescription(toolchainProvider));
    builder.register(new JsBundleGenruleDescription());
    builder.register(new JsLibraryDescription());
    builder.register(new KeystoreDescription());
    builder.register(
        new KotlinLibraryDescription(kotlinBuckConfig, javaConfig, defaultJavacOptions));
    builder.register(
        new KotlinTestDescription(
            kotlinBuckConfig,
            javaConfig,
            defaultJavaOptionsForTests,
            defaultJavacOptions,
            defaultTestRuleTimeoutMs));
    builder.register(
        new LuaBinaryDescription(defaultLuaPlatform, luaPlatforms, cxxBuckConfig, pythonPlatforms));
    builder.register(new LuaLibraryDescription());
    builder.register(new NdkLibraryDescription(toolchainProvider, ndkCxxPlatforms));
    OcamlBuckConfig ocamlBuckConfig = new OcamlBuckConfig(config, defaultCxxPlatform);
    builder.register(new OcamlBinaryDescription(ocamlBuckConfig));
    builder.register(new OcamlLibraryDescription(ocamlBuckConfig));
    builder.register(new PrebuiltCxxLibraryDescription(cxxBuckConfig, cxxPlatforms));
    builder.register(PrebuiltCxxLibraryGroupDescription.of());
    builder.register(new CxxPrecompiledHeaderDescription());
    builder.register(new PrebuiltDotnetLibraryDescription());
    builder.register(new PrebuiltNativeLibraryDescription());
    builder.register(new PrebuiltOcamlLibraryDescription());
    builder.register(new PrebuiltPythonLibraryDescription());
    builder.register(pythonBinaryDescription);
    PythonLibraryDescription pythonLibraryDescription =
        new PythonLibraryDescription(pythonPlatforms, cxxPlatforms);
    builder.register(pythonLibraryDescription);
    builder.register(
        new PythonTestDescription(
            pythonBinaryDescription,
            pyConfig,
            pythonPlatforms,
            cxxBuckConfig,
            defaultCxxPlatform,
            defaultTestRuleTimeoutMs,
            cxxPlatforms));
    builder.register(new RemoteFileDescription(downloader));
    builder.register(
        new RobolectricTestDescription(
            javaConfig,
            defaultJavaOptionsForTests,
            defaultJavacOptions,
            defaultTestRuleTimeoutMs,
            defaultCxxPlatform,
            defaultAndroidCompilerFactory));
    builder.register(new RustBinaryDescription(rustBuckConfig, cxxPlatforms, defaultCxxPlatform));
    builder.register(new RustLibraryDescription(rustBuckConfig, cxxPlatforms, defaultCxxPlatform));
    builder.register(new RustTestDescription(rustBuckConfig, cxxPlatforms, defaultCxxPlatform));
    builder.register(new PrebuiltRustLibraryDescription());
    builder.register(new ScalaLibraryDescription(scalaConfig, javaConfig, defaultJavacOptions));
    builder.register(
        new ScalaTestDescription(
            scalaConfig,
            javaConfig,
            defaultJavacOptions,
            defaultJavaOptionsForTests,
            defaultTestRuleTimeoutMs,
            defaultCxxPlatform));
    builder.register(new SceneKitAssetsDescription());
    builder.register(new ShBinaryDescription());
    builder.register(new ShTestDescription(defaultTestRuleTimeoutMs));
    builder.register(new WorkerToolDescription(config));

    List<DescriptionProvider> descriptionProviders =
        pluginManager.getExtensions(DescriptionProvider.class);
    for (DescriptionProvider provider : descriptionProviders) {
      for (Description<?> description : provider.getDescriptions()) {
        builder.register(description);
      }
    }

    builder.setCxxPlatforms(cxxPlatforms);
    builder.setDefaultCxxPlatform(defaultCxxPlatform);

    builder.register(VersionedAliasDescription.of());

    return builder.build();
  }

  public static class Builder {
    private final Map<BuildRuleType, Description<?>> descriptions;
    private final Map<String, BuildRuleType> types;

    @Nullable private FlavorDomain<CxxPlatform> cxxPlatforms;
    @Nullable private CxxPlatform defaultCxxPlatform;

    protected Builder() {
      this.descriptions = Maps.newConcurrentMap();
      this.types = Maps.newConcurrentMap();
    }

    public Builder register(Description<?> description) {
      BuildRuleType type = Description.getBuildRuleType(description);
      types.put(type.getName(), type);
      descriptions.put(type, description);
      return this;
    }

    public Builder setCxxPlatforms(FlavorDomain<CxxPlatform> cxxPlatforms) {
      this.cxxPlatforms = cxxPlatforms;
      return this;
    }

    public Builder setDefaultCxxPlatform(CxxPlatform defaultCxxPlatform) {
      this.defaultCxxPlatform = defaultCxxPlatform;
      return this;
    }

    public KnownBuildRuleTypes build() {
      return new KnownBuildRuleTypes(
          descriptions,
          types,
          Preconditions.checkNotNull(cxxPlatforms),
          Preconditions.checkNotNull(defaultCxxPlatform));
    }
  }
}
