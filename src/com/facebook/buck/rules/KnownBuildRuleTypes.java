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
import com.facebook.buck.android.AndroidBinaryDescription;
import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.AndroidBuildConfigDescription;
import com.facebook.buck.android.AndroidDirectoryResolver;
import com.facebook.buck.android.AndroidInstrumentationApkDescription;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.android.AndroidManifestDescription;
import com.facebook.buck.android.AndroidPrebuiltAarDescription;
import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.android.ApkGenruleDescription;
import com.facebook.buck.android.GenAidlDescription;
import com.facebook.buck.android.ImmutableNdkCxxPlatforms;
import com.facebook.buck.android.NdkCxxPlatform;
import com.facebook.buck.android.NdkCxxPlatforms;
import com.facebook.buck.android.NdkLibraryDescription;
import com.facebook.buck.android.PrebuiltNativeLibraryDescription;
import com.facebook.buck.android.ProGuardConfig;
import com.facebook.buck.android.RobolectricTestDescription;
import com.facebook.buck.android.SmartDexingStep;
import com.facebook.buck.apple.AppleAssetCatalogDescription;
import com.facebook.buck.apple.AppleBinaryDescription;
import com.facebook.buck.apple.AppleBundleDescription;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleCxxPlatform;
import com.facebook.buck.apple.AppleCxxPlatforms;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.AppleResourceDescription;
import com.facebook.buck.apple.AppleSdk;
import com.facebook.buck.apple.AppleSdkDiscovery;
import com.facebook.buck.apple.AppleSdkPaths;
import com.facebook.buck.apple.AppleTestDescription;
import com.facebook.buck.apple.AppleToolchain;
import com.facebook.buck.apple.AppleToolchainDiscovery;
import com.facebook.buck.apple.CoreDataModelDescription;
import com.facebook.buck.apple.XcodePrebuildScriptDescription;
import com.facebook.buck.apple.XcodePostbuildScriptDescription;
import com.facebook.buck.apple.XcodeWorkspaceConfigDescription;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cxx.CxxBinaryDescription;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatforms;
import com.facebook.buck.cxx.CxxPythonExtensionDescription;
import com.facebook.buck.cxx.CxxTestDescription;
import com.facebook.buck.cxx.DefaultCxxPlatforms;
import com.facebook.buck.cxx.PrebuiltCxxLibraryDescription;
import com.facebook.buck.d.DBinaryDescription;
import com.facebook.buck.d.DBuckConfig;
import com.facebook.buck.d.DLibraryDescription;
import com.facebook.buck.d.DTestDescription;
import com.facebook.buck.file.Downloader;
import com.facebook.buck.file.ExplodingDownloader;
import com.facebook.buck.file.HttpDownloader;
import com.facebook.buck.file.RemoteFileDescription;
import com.facebook.buck.gwt.GwtBinaryDescription;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.JavaBinaryDescription;
import com.facebook.buck.java.JavaBuckConfig;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.java.JavaTestDescription;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.KeystoreDescription;
import com.facebook.buck.java.PrebuiltJarDescription;
import com.facebook.buck.js.AndroidReactNativeLibraryDescription;
import com.facebook.buck.js.IosReactNativeLibraryDescription;
import com.facebook.buck.js.ReactNativeBuckConfig;
import com.facebook.buck.log.CommandThreadFactory;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.ocaml.OCamlBinaryDescription;
import com.facebook.buck.ocaml.OCamlBuckConfig;
import com.facebook.buck.ocaml.OCamlLibraryDescription;
import com.facebook.buck.ocaml.PrebuiltOCamlLibraryDescription;
import com.facebook.buck.python.PrebuiltPythonLibraryDescription;
import com.facebook.buck.python.PythonBinaryDescription;
import com.facebook.buck.python.PythonBuckConfig;
import com.facebook.buck.python.PythonEnvironment;
import com.facebook.buck.python.PythonLibraryDescription;
import com.facebook.buck.python.PythonTestDescription;
import com.facebook.buck.shell.ExportFileDescription;
import com.facebook.buck.shell.GenruleDescription;
import com.facebook.buck.shell.ShBinaryDescription;
import com.facebook.buck.shell.ShTestDescription;
import com.facebook.buck.thrift.ThriftBuckConfig;
import com.facebook.buck.thrift.ThriftCxxEnhancer;
import com.facebook.buck.thrift.ThriftJavaEnhancer;
import com.facebook.buck.thrift.ThriftLibraryDescription;
import com.facebook.buck.thrift.ThriftPythonEnhancer;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.io.IOException;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * A registry of all the build rules types understood by Buck.
 */
public class KnownBuildRuleTypes {

  private static final Logger LOG = Logger.get(KnownBuildRuleTypes.class);
  private final ImmutableMap<BuildRuleType, Description<?>> descriptions;
  private final ImmutableMap<String, BuildRuleType> types;

  private KnownBuildRuleTypes(
      Map<BuildRuleType, Description<?>> descriptions,
      Map<String, BuildRuleType> types) {
    this.descriptions = ImmutableMap.copyOf(descriptions);
    this.types = ImmutableMap.copyOf(types);
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

  public static Builder builder() {
    return new Builder();
  }

  public static KnownBuildRuleTypes createInstance(
      BuckConfig config,
      ProjectFilesystem projectFilesystem,
      ProcessExecutor processExecutor,
      AndroidDirectoryResolver androidDirectoryResolver,
      PythonEnvironment pythonEnv) throws InterruptedException, IOException {
    return createBuilder(
        config,
        projectFilesystem,
        processExecutor,
        androidDirectoryResolver,
        pythonEnv).build();
  }

  private static void buildAppleCxxPlatforms(
      Supplier<Optional<Path>> appleDeveloperDirectorySupplier,
      ImmutableList<Path> extraToolchainPaths,
      ImmutableList<Path> extraPlatformPaths,
      BuckConfig buckConfig,
      AppleConfig appleConfig,
      ImmutableMap.Builder<Flavor, AppleCxxPlatform> platformFlavorsToAppleSdkPathsBuilder)
      throws IOException {
    Optional<Path> appleDeveloperDirectory = appleDeveloperDirectorySupplier.get();
    if (appleDeveloperDirectory.isPresent() &&
        !Files.isDirectory(appleDeveloperDirectory.get())) {
      LOG.error(
        "Developer directory is set to %s, but is not a directory",
        appleDeveloperDirectory.get());
      return;
    }

    ImmutableMap<String, AppleToolchain> toolchains =
        AppleToolchainDiscovery.discoverAppleToolchains(
            appleDeveloperDirectory,
            extraToolchainPaths);

    ImmutableMap<AppleSdk, AppleSdkPaths> sdkPaths = AppleSdkDiscovery.discoverAppleSdkPaths(
        appleDeveloperDirectory,
        extraPlatformPaths,
        toolchains);

    for (Map.Entry<AppleSdk, AppleSdkPaths> entry : sdkPaths.entrySet()) {
      AppleSdk sdk = entry.getKey();
      AppleSdkPaths appleSdkPaths = entry.getValue();
      String targetSdkVersion = appleConfig.getTargetSdkVersion(
          sdk.getApplePlatform()).or(sdk.getVersion());
      LOG.debug("SDK %s using default version %s", sdk, targetSdkVersion);
      for (String architecture : sdk.getArchitectures()) {
        AppleCxxPlatform appleCxxPlatform = AppleCxxPlatforms.build(
            sdk,
            targetSdkVersion,
            architecture,
            appleSdkPaths,
            buckConfig);
        platformFlavorsToAppleSdkPathsBuilder.put(
            appleCxxPlatform.getCxxPlatform().getFlavor(),
            appleCxxPlatform);
      }
    }
  }

  @VisibleForTesting
  static Builder createBuilder(
      BuckConfig config,
      ProjectFilesystem projectFilesystem,
      ProcessExecutor processExecutor,
      AndroidDirectoryResolver androidDirectoryResolver,
      PythonEnvironment pythonEnv) throws InterruptedException, IOException {

    Platform platform = Platform.detect();

    AndroidBuckConfig androidConfig = new AndroidBuckConfig(config, platform);
    Optional<String> ndkVersion = androidConfig.getNdkVersion();
    // If a NDK version isn't specified, we've got to reach into the runtime environment to find
    // out which one we will end up using.
    if (!ndkVersion.isPresent()) {
      ndkVersion = androidDirectoryResolver.getNdkVersion();
    }

    AppleConfig appleConfig = new AppleConfig(config);
    ImmutableMap.Builder<Flavor, AppleCxxPlatform> platformFlavorsToAppleCxxPlatformsBuilder =
        ImmutableMap.builder();
    buildAppleCxxPlatforms(
        appleConfig.getAppleDeveloperDirectorySupplier(processExecutor),
        appleConfig.getExtraToolchainPaths(),
        appleConfig.getExtraPlatformPaths(),
        config,
        appleConfig,
        platformFlavorsToAppleCxxPlatformsBuilder);
    ImmutableMap<Flavor, AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms =
        platformFlavorsToAppleCxxPlatformsBuilder.build();

    // Setup the NDK C/C++ platforms.
    Optional<Path> ndkRoot = androidDirectoryResolver.findAndroidNdkDir();
    ImmutableMap.Builder<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> ndkCxxPlatformsBuilder =
        ImmutableMap.builder();
    if (ndkRoot.isPresent()) {
      NdkCxxPlatforms.Compiler.Type compilerType =
          androidConfig.getNdkCompiler().or(NdkCxxPlatforms.DEFAULT_COMPILER_TYPE);
      String gccVersion = androidConfig.getNdkGccVersion().or(NdkCxxPlatforms.DEFAULT_GCC_VERSION);
      NdkCxxPlatforms.Compiler compiler =
          ImmutableNdkCxxPlatforms.Compiler.builder()
              .setType(compilerType)
              .setVersion(
                  compilerType == NdkCxxPlatforms.Compiler.Type.GCC ?
                      gccVersion :
                      androidConfig.getNdkClangVersion().or(NdkCxxPlatforms.DEFAULT_CLANG_VERSION))
              .setGccVersion(gccVersion)
              .build();
      ndkCxxPlatformsBuilder.putAll(
          NdkCxxPlatforms.getPlatforms(
              new ProjectFilesystem(ndkRoot.get()),
              compiler,
              androidConfig.getNdkCxxRuntime().or(NdkCxxPlatforms.DEFAULT_CXX_RUNTIME),
              androidConfig.getNdkAppPlatform().or(NdkCxxPlatforms.DEFAULT_TARGET_APP_PLATFORM),
              platform));
    }
    ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> ndkCxxPlatforms =
        ndkCxxPlatformsBuilder.build();

    // Construct the C/C++ config wrapping the buck config.
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(config);
    ImmutableMap.Builder<Flavor, CxxPlatform> cxxPlatformsBuilder = ImmutableMap.builder();

    // If an Android NDK is present, add platforms for that.  This is mostly useful for
    // testing our Android NDK support for right now.
    for (NdkCxxPlatform ndkCxxPlatform : ndkCxxPlatforms.values()) {
      cxxPlatformsBuilder.put(
          ndkCxxPlatform.getCxxPlatform().getFlavor(),
          ndkCxxPlatform.getCxxPlatform());
    }

    for (Map.Entry<Flavor, AppleCxxPlatform> entry :
        platformFlavorsToAppleCxxPlatforms.entrySet()) {
      cxxPlatformsBuilder.put(entry.getKey(), entry.getValue().getCxxPlatform());
    }

    // Add the default, config-defined C/C++ platform.
    CxxPlatform systemDefaultCxxPlatform = DefaultCxxPlatforms.build(platform, cxxBuckConfig);
    cxxPlatformsBuilder.put(systemDefaultCxxPlatform.getFlavor(), systemDefaultCxxPlatform);
    ImmutableMap<Flavor, CxxPlatform> cxxPlatformsMap = cxxPlatformsBuilder.build();

    // Get the default platform from config.
    CxxPlatform defaultCxxPlatform = CxxPlatforms.getConfigDefaultCxxPlatform(
        cxxBuckConfig,
        cxxPlatformsMap,
        systemDefaultCxxPlatform);

    // Add platforms for each cxx flavor obtained from the buck config files
    // from sections of the form cxx#{flavor name}
    ImmutableSet<Flavor> cxxFlavors = CxxBuckConfig.getCxxFlavors(config);
    for (Flavor flavor: cxxFlavors) {
      CxxBuckConfig flavoredCxxBuckConfig =  new CxxBuckConfig(config, flavor);
      CxxPlatform defaultPlatformForFlavor = CxxPlatforms.getConfigDefaultCxxPlatform(
          flavoredCxxBuckConfig,
          cxxPlatformsMap,
          systemDefaultCxxPlatform);
      cxxPlatformsBuilder.put(flavor, CxxPlatforms.copyPlatformWithFlavorAndConfig(
          defaultPlatformForFlavor,
          flavoredCxxBuckConfig,
          flavor));
    }

    cxxPlatformsMap = cxxPlatformsBuilder.build();


    // Build up the final list of C/C++ platforms.
    FlavorDomain<CxxPlatform> cxxPlatforms = new FlavorDomain<>(
        "C/C++ platform",
        cxxPlatformsMap);

    DBuckConfig dBuckConfig = new DBuckConfig(config);

    ReactNativeBuckConfig reactNativeBuckConfig = new ReactNativeBuckConfig(config);

    ProGuardConfig proGuardConfig = new ProGuardConfig(config);

    PythonBuckConfig pyConfig = new PythonBuckConfig(config, new ExecutableFinder());

    // Look up the path to the main module we use for python tests.
    Path pythonPathToPythonTestMain = pyConfig.getPathToTestMain();

    // Look up the timeout to apply to entire test rules.
    Optional<Long> testRuleTimeoutMs = config.getLong("test", "rule_timeout");

    // Default maven repo, if set
    Optional<String> defaultMavenRepo = config.getValue("download", "maven_repo");
    boolean downloadAtRuntimeOk = config.getBooleanValue("download", "in_build", false);
    Downloader downloader;
    if (downloadAtRuntimeOk) {
      downloader = new HttpDownloader(Optional.<Proxy>absent(), defaultMavenRepo);
    } else {
      downloader = new ExplodingDownloader();
    }

    Builder builder = builder();

    JavaBuckConfig javaConfig = new JavaBuckConfig(config);
    JavacOptions defaultJavacOptions = javaConfig.getDefaultJavacOptions(processExecutor);
    JavacOptions androidBinaryOptions = JavacOptions.builder(defaultJavacOptions)
        .build();

    CxxBinaryDescription cxxBinaryDescription =
        new CxxBinaryDescription(
            cxxBuckConfig,
            defaultCxxPlatform,
            cxxPlatforms,
            cxxBuckConfig.getPreprocessMode());

    CxxLibraryDescription cxxLibraryDescription = new CxxLibraryDescription(
        cxxBuckConfig,
        cxxPlatforms,
        cxxBuckConfig.getPreprocessMode());

    AppleLibraryDescription appleLibraryDescription =
        new AppleLibraryDescription(
            cxxLibraryDescription,
            cxxPlatforms);
    builder.register(appleLibraryDescription);

    AppleBinaryDescription appleBinaryDescription =
        new AppleBinaryDescription(cxxBinaryDescription);
    builder.register(appleBinaryDescription);

    // Create an executor service exclusively for the smart dexing step.
    ListeningExecutorService dxExecutorService =
        MoreExecutors.listeningDecorator(
            Executors.newFixedThreadPool(
                SmartDexingStep.determineOptimalThreadCount(),
                new CommandThreadFactory("SmartDexing")));

    builder.register(new AndroidAarDescription(new AndroidManifestDescription()));
    builder.register(
        new AndroidBinaryDescription(
            androidBinaryOptions,
            proGuardConfig,
            ndkCxxPlatforms,
            dxExecutorService));
    builder.register(new AndroidBuildConfigDescription(androidBinaryOptions));
    builder.register(new AndroidInstrumentationApkDescription(
            proGuardConfig,
            androidBinaryOptions,
            ndkCxxPlatforms,
            dxExecutorService));
    builder.register(new AndroidLibraryDescription(androidBinaryOptions));
    builder.register(new AndroidManifestDescription());
    builder.register(new AndroidPrebuiltAarDescription(androidBinaryOptions));
    builder.register(new AndroidReactNativeLibraryDescription(reactNativeBuckConfig));
    builder.register(new AndroidResourceDescription());
    builder.register(new ApkGenruleDescription());
    builder.register(new AppleAssetCatalogDescription());
    AppleBundleDescription appleBundleDescription =
        new AppleBundleDescription(
            appleBinaryDescription,
            appleLibraryDescription,
            cxxPlatforms,
            platformFlavorsToAppleCxxPlatforms,
            defaultCxxPlatform);
    builder.register(appleBundleDescription);
    builder.register(new AppleResourceDescription());
    builder.register(
        new AppleTestDescription(
            appleConfig,
            appleBundleDescription,
            appleLibraryDescription,
            cxxPlatforms,
            platformFlavorsToAppleCxxPlatforms,
            defaultCxxPlatform));
    builder.register(new CoreDataModelDescription());
    builder.register(cxxBinaryDescription);
    builder.register(cxxLibraryDescription);
    builder.register(new CxxPythonExtensionDescription(cxxBuckConfig, cxxPlatforms));
    builder.register(new CxxTestDescription(cxxBuckConfig, defaultCxxPlatform, cxxPlatforms));
    builder.register(new DBinaryDescription(dBuckConfig));
    builder.register(new DLibraryDescription(dBuckConfig));
    builder.register(new DTestDescription(dBuckConfig));
    builder.register(new ExportFileDescription());
    builder.register(new GenruleDescription());
    builder.register(new GenAidlDescription());
    builder.register(new GwtBinaryDescription());
    builder.register(new IosReactNativeLibraryDescription(reactNativeBuckConfig));
    builder.register(new JavaBinaryDescription(defaultJavacOptions, defaultCxxPlatform));
    builder.register(new JavaLibraryDescription(defaultJavacOptions));
    builder.register(
        new JavaTestDescription(
            defaultJavacOptions,
            testRuleTimeoutMs,
            defaultCxxPlatform));
    builder.register(new KeystoreDescription());
    builder.register(new NdkLibraryDescription(ndkVersion, ndkCxxPlatforms));
    OCamlBuckConfig ocamlBuckConfig = new OCamlBuckConfig(platform, config);
    builder.register(new OCamlBinaryDescription(ocamlBuckConfig));
    builder.register(new OCamlLibraryDescription(ocamlBuckConfig));
    builder.register(new PrebuiltCxxLibraryDescription(cxxPlatforms));
    builder.register(new PrebuiltJarDescription());
    builder.register(new PrebuiltNativeLibraryDescription());
    builder.register(new PrebuiltOCamlLibraryDescription());
    builder.register(new PrebuiltPythonLibraryDescription());
    builder.register(new ProjectConfigDescription());
    builder.register(
        new PythonBinaryDescription(
            pyConfig.getPathToPex(),
            pyConfig.getPathToPexExecuter(),
            pyConfig.getPexExtension(),
            pythonEnv,
            defaultCxxPlatform,
            cxxPlatforms));
    builder.register(new PythonLibraryDescription());
    builder.register(
        new PythonTestDescription(
            projectFilesystem,
            pyConfig.getPathToPex(),
            pyConfig.getPathToPexExecuter(),
            pyConfig.getPexExtension(),
            pythonPathToPythonTestMain,
            pythonEnv,
            defaultCxxPlatform,
            cxxPlatforms));
    builder.register(new RemoteFileDescription(downloader));
    builder.register(new RobolectricTestDescription(
            androidBinaryOptions,
            testRuleTimeoutMs,
            defaultCxxPlatform));
    builder.register(new ShBinaryDescription());
    builder.register(new ShTestDescription());
    ThriftBuckConfig thriftBuckConfig = new ThriftBuckConfig(config);
    builder.register(
        new ThriftLibraryDescription(
            thriftBuckConfig,
            ImmutableList.of(
                new ThriftJavaEnhancer(thriftBuckConfig, defaultJavacOptions),
                new ThriftCxxEnhancer(
                    thriftBuckConfig,
                    cxxLibraryDescription,
                    /* cpp2 */ false),
                new ThriftCxxEnhancer(
                    thriftBuckConfig,
                    cxxLibraryDescription,
                    /* cpp2 */ true),
                new ThriftPythonEnhancer(thriftBuckConfig, ThriftPythonEnhancer.Type.NORMAL),
                new ThriftPythonEnhancer(thriftBuckConfig, ThriftPythonEnhancer.Type.TWISTED))));
    builder.register(new XcodePostbuildScriptDescription());
    builder.register(new XcodePrebuildScriptDescription());
    builder.register(new XcodeWorkspaceConfigDescription());

    return builder;
  }

  public static class Builder {
    private final Map<BuildRuleType, Description<?>> descriptions;
    private final Map<String, BuildRuleType> types;

    protected Builder() {
      this.descriptions = Maps.newConcurrentMap();
      this.types = Maps.newConcurrentMap();
    }

    public void register(Description<?> description) {
      BuildRuleType type = description.getBuildRuleType();
      types.put(type.getName(), type);
      descriptions.put(type, description);
    }

    public KnownBuildRuleTypes build() {
      return new KnownBuildRuleTypes(descriptions, types);
    }
  }
}
