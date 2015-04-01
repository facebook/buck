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
import com.facebook.buck.android.AndroidBinary;
import com.facebook.buck.android.AndroidBinaryDescription;
import com.facebook.buck.android.AndroidBuildConfigDescription;
import com.facebook.buck.android.AndroidDirectoryResolver;
import com.facebook.buck.android.AndroidInstrumentationApkDescription;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.android.AndroidManifestDescription;
import com.facebook.buck.android.AndroidPrebuiltAarDescription;
import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.android.ApkGenruleDescription;
import com.facebook.buck.android.GenAidlDescription;
import com.facebook.buck.android.NdkCxxPlatform;
import com.facebook.buck.android.NdkCxxPlatforms;
import com.facebook.buck.android.NdkLibraryDescription;
import com.facebook.buck.android.PrebuiltNativeLibraryDescription;
import com.facebook.buck.android.ProGuardConfig;
import com.facebook.buck.android.RobolectricTestDescription;
import com.facebook.buck.apple.AppleAssetCatalogDescription;
import com.facebook.buck.apple.AppleBinaryDescription;
import com.facebook.buck.apple.AppleBundleDescription;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleCxxPlatforms;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.AppleResourceDescription;
import com.facebook.buck.apple.AppleSdk;
import com.facebook.buck.apple.AppleSdkDiscovery;
import com.facebook.buck.apple.AppleSdkPaths;
import com.facebook.buck.apple.AppleTestDescription;
import com.facebook.buck.apple.AppleToolchainDiscovery;
import com.facebook.buck.apple.CoreDataModelDescription;
import com.facebook.buck.apple.IosPostprocessResourcesDescription;
import com.facebook.buck.apple.XcodeWorkspaceConfigDescription;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cxx.CxxBinaryDescription;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPythonExtensionDescription;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.CxxTestDescription;
import com.facebook.buck.cxx.DefaultCxxPlatforms;
import com.facebook.buck.cxx.PrebuiltCxxLibraryDescription;
import com.facebook.buck.d.DBinaryDescription;
import com.facebook.buck.d.DBuckConfig;
import com.facebook.buck.d.DLibraryDescription;
import com.facebook.buck.extension.BuckExtensionDescription;
import com.facebook.buck.file.Downloader;
import com.facebook.buck.file.ExplodingDownloader;
import com.facebook.buck.file.HttpDownloader;
import com.facebook.buck.file.RemoteFileDescription;
import com.facebook.buck.gwt.GwtBinaryDescription;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.JavaBinaryDescription;
import com.facebook.buck.java.JavaBuckConfig;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.java.JavaTestDescription;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.KeystoreDescription;
import com.facebook.buck.java.PrebuiltJarDescription;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.ocaml.OCamlBinaryDescription;
import com.facebook.buck.ocaml.OCamlBuckConfig;
import com.facebook.buck.ocaml.OCamlLibraryDescription;
import com.facebook.buck.ocaml.PrebuiltOCamlLibraryDescription;
import com.facebook.buck.parcelable.GenParcelableDescription;
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

import java.io.IOException;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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

  /**
   * @return the map holding the available {@link NdkCxxPlatform}s.
   */
  private static ImmutableMap<AndroidBinary.TargetCpuType, NdkCxxPlatform> getNdkCxxPlatforms(
      Path ndkRoot,
      Platform platform) {

    ImmutableMap.Builder<AndroidBinary.TargetCpuType, NdkCxxPlatform> ndkCxxPlatformBuilder =
        ImmutableMap.builder();

    NdkCxxPlatform armeabi =
        NdkCxxPlatforms.build(
            ImmutableFlavor.of("android-arm"),
            platform,
            ndkRoot,
            new NdkCxxPlatforms.TargetConfiguration(
                NdkCxxPlatforms.Toolchain.ARM_LINUX_ADNROIDEABI_4_8,
                NdkCxxPlatforms.ToolchainPrefix.ARM_LINUX_ANDROIDEABI,
                NdkCxxPlatforms.TargetArch.ARM,
                NdkCxxPlatforms.TargetArchAbi.ARMEABI,
                /* androidPlatform */ "android-9",
                /* compilerVersion */ "4.8",
                /* compilerFlags */ ImmutableList.of(
                    "-march=armv5te",
                    "-mtune=xscale",
                    "-msoft-float",
                    "-mthumb",
                    "-Os"),
                /* linkerFlags */ ImmutableList.of(
                    "-march=armv5te",
                    "-Wl,--fix-cortex-a8")),
            NdkCxxPlatforms.CxxRuntime.GNUSTL);
    ndkCxxPlatformBuilder.put(AndroidBinary.TargetCpuType.ARM, armeabi);
    NdkCxxPlatform armeabiv7 =
        NdkCxxPlatforms.build(
            ImmutableFlavor.of("android-armv7"),
            platform,
            ndkRoot,
            new NdkCxxPlatforms.TargetConfiguration(
                NdkCxxPlatforms.Toolchain.ARM_LINUX_ADNROIDEABI_4_8,
                NdkCxxPlatforms.ToolchainPrefix.ARM_LINUX_ANDROIDEABI,
                NdkCxxPlatforms.TargetArch.ARM,
                NdkCxxPlatforms.TargetArchAbi.ARMEABI_V7A,
                /* androidPlatform */ "android-9",
                /* compilerVersion */ "4.8",
                /* compilerFlags */ ImmutableList.of(
                    "-finline-limit=64",
                    "-march=armv7-a",
                    "-mfpu=vfpv3-d16",
                    "-mfloat-abi=softfp",
                    "-mthumb",
                    "-Os"),
                /* linkerFlags */ ImmutableList.<String>of()),
            NdkCxxPlatforms.CxxRuntime.GNUSTL);
    ndkCxxPlatformBuilder.put(AndroidBinary.TargetCpuType.ARMV7, armeabiv7);
    NdkCxxPlatform x86 =
        NdkCxxPlatforms.build(
            ImmutableFlavor.of("android-x86"),
            platform,
            ndkRoot,
            new NdkCxxPlatforms.TargetConfiguration(
                NdkCxxPlatforms.Toolchain.X86_4_8,
                NdkCxxPlatforms.ToolchainPrefix.I686_LINUX_ANDROID,
                NdkCxxPlatforms.TargetArch.X86,
                NdkCxxPlatforms.TargetArchAbi.X86,
                /* androidPlatform */ "android-9",
                /* compilerVersion */ "4.8",
                /* compilerFlags */ ImmutableList.of(
                    "-funswitch-loops",
                    "-finline-limit=300",
                    "-O2"),
                /* linkerFlags */ ImmutableList.<String>of()),
            NdkCxxPlatforms.CxxRuntime.GNUSTL);
    ndkCxxPlatformBuilder.put(AndroidBinary.TargetCpuType.X86, x86);

    return ndkCxxPlatformBuilder.build();
  }

  private static void buildAppleCxxPlatforms(
      Supplier<Path> appleDeveloperDirectorySupplier,
      Platform buildPlatform,
      BuckConfig buckConfig,
      AppleConfig appleConfig,
      ImmutableMap.Builder<CxxPlatform, AppleSdkPaths> appleCxxPlatformsToAppleSdkPathsBuilder)
      throws IOException {
    if (!buildPlatform.equals(Platform.MACOS)) {
      return;
    }

    Path appleDeveloperDirectory = appleDeveloperDirectorySupplier.get();
    if (!Files.isDirectory(appleDeveloperDirectory)) {
      // TODO(user): This should be fatal, but a ton of integration tests enter this code on
      // Apple platforms.
      return;
    }

    Path xcodeDir = appleDeveloperDirectory.getParent();
    if (xcodeDir == null) {
      LOG.warn(
          "Couldn't find parent of developer directory %s (Apple platforms will be unavailable)",
          appleDeveloperDirectory);
      return;
    }

    Path versionPlistPath = xcodeDir.resolve("version.plist");
    if (!Files.exists(versionPlistPath)) {
      LOG.warn(
          "Couldn't find version file at %s (Apple platforms will be unavailable)",
          versionPlistPath);
      return;
    }

    ImmutableMap<String, Path> toolchainPaths = AppleToolchainDiscovery.discoverAppleToolchainPaths(
        appleDeveloperDirectory);

    ImmutableMap<AppleSdk, AppleSdkPaths> sdkPaths = AppleSdkDiscovery.discoverAppleSdkPaths(
        appleDeveloperDirectory,
        versionPlistPath,
        toolchainPaths);

    for (Map.Entry<AppleSdk, AppleSdkPaths> entry : sdkPaths.entrySet()) {
      AppleSdk sdk = entry.getKey();
      AppleSdkPaths appleSdkPaths = entry.getValue();
      String targetSdkVersion = appleConfig.getTargetSdkVersion(
          sdk.getApplePlatform()).or(sdk.getVersion());
      LOG.debug("SDK %s using default version %s", sdk, targetSdkVersion);
      for (String architecture : sdk.getArchitectures()) {
        CxxPlatform appleCxxPlatform = AppleCxxPlatforms.build(
            sdk.getApplePlatform(),
            sdk.getName(),
            sdk.getXcodeVersion(),
            targetSdkVersion,
            architecture,
            appleSdkPaths,
            buckConfig);
        appleCxxPlatformsToAppleSdkPathsBuilder.put(appleCxxPlatform, appleSdkPaths);
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

    Optional<String> ndkVersion = config.getNdkVersion();
    // If a NDK version isn't specified, we've got to reach into the runtime environment to find
    // out which one we will end up using.
    if (!ndkVersion.isPresent()) {
      ndkVersion = androidDirectoryResolver.getNdkVersion();
    }

    AppleConfig appleConfig = new AppleConfig(config);
    ImmutableMap.Builder<CxxPlatform, AppleSdkPaths> appleCxxPlatformsToAppleSdkPathsBuilder =
        ImmutableMap.builder();
    buildAppleCxxPlatforms(
        appleConfig.getAppleDeveloperDirectorySupplier(processExecutor),
        platform,
        config,
        appleConfig,
        appleCxxPlatformsToAppleSdkPathsBuilder);
    ImmutableMap<CxxPlatform, AppleSdkPaths> appleCxxPlatformsToAppleSdkPaths =
        appleCxxPlatformsToAppleSdkPathsBuilder.build();

    // Construct the thrift config wrapping the buck config.
    ThriftBuckConfig thriftBuckConfig = new ThriftBuckConfig(config);

    // Construct the OCaml config wrapping the buck config.
    OCamlBuckConfig ocamlBuckConfig = new OCamlBuckConfig(platform, config);

    // Setup the NDK C/C++ platforms.
    ImmutableMap.Builder<AndroidBinary.TargetCpuType, NdkCxxPlatform> ndkCxxPlatformsBuilder =
        ImmutableMap.builder();
    Optional<Path> ndkRoot = androidDirectoryResolver.findAndroidNdkDir();
    if (ndkRoot.isPresent()) {
      ndkCxxPlatformsBuilder.putAll(getNdkCxxPlatforms(ndkRoot.get(), platform));
    }
    ImmutableMap<AndroidBinary.TargetCpuType, NdkCxxPlatform> ndkCxxPlatforms =
        ndkCxxPlatformsBuilder.build();

    // Construct the C/C++ config wrapping the buck config.
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(config);
    ImmutableMap.Builder<Flavor, CxxPlatform> cxxPlatformsBuilder = ImmutableMap.builder();

    // Add the default, config-defined C/C++ platform.
    CxxPlatform defaultCxxPlatform = DefaultCxxPlatforms.build(platform, cxxBuckConfig);
    cxxPlatformsBuilder.put(defaultCxxPlatform.getFlavor(), defaultCxxPlatform);

    // If an Android NDK is present, add platforms for that.  This is mostly useful for
    // testing our Android NDK support for right now.
    for (NdkCxxPlatform ndkCxxPlatform : ndkCxxPlatforms.values()) {
      cxxPlatformsBuilder.put(
          ndkCxxPlatform.getCxxPlatform().getFlavor(),
          ndkCxxPlatform.getCxxPlatform());
    }

    for (CxxPlatform appleCxxPlatform : appleCxxPlatformsToAppleSdkPaths.keySet()) {
      cxxPlatformsBuilder.put(appleCxxPlatform.getFlavor(), appleCxxPlatform);
    }

    // Build up the final list of C/C++ platforms.
    FlavorDomain<CxxPlatform> cxxPlatforms = new FlavorDomain<>(
        "C/C++ platform",
        cxxPlatformsBuilder.build());

    DBuckConfig dBuckConfig = new DBuckConfig(config);

    ProGuardConfig proGuardConfig = new ProGuardConfig(config);

    PythonBuckConfig pyConfig = new PythonBuckConfig(config);

    // Look up the path to the main module we use for python tests.
    Optional<Path> pythonPathToPythonTestMain = pyConfig.getPathToTestMain();

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

    CxxBinaryDescription cxxBinaryDescription = new CxxBinaryDescription(
        cxxBuckConfig,
        defaultCxxPlatform,
        cxxPlatforms,
        CxxSourceRuleFactory.Strategy.SEPARATE_PREPROCESS_AND_COMPILE);

    CxxLibraryDescription cxxLibraryDescription = new CxxLibraryDescription(
        cxxBuckConfig,
        cxxPlatforms,
        CxxSourceRuleFactory.Strategy.SEPARATE_PREPROCESS_AND_COMPILE);

    AppleLibraryDescription appleLibraryDescription =
        new AppleLibraryDescription(
            appleConfig,
            new CxxLibraryDescription(
                cxxBuckConfig,
                cxxPlatforms,
                CxxSourceRuleFactory.Strategy.COMBINED_PREPROCESS_AND_COMPILE),
            cxxPlatforms,
            appleCxxPlatformsToAppleSdkPaths);
    builder.register(appleLibraryDescription);

    builder.register(new AndroidAarDescription(
            new AndroidManifestDescription(),
            new JavaBinaryDescription(defaultJavacOptions, defaultCxxPlatform)));
    builder.register(
        new AndroidBinaryDescription(
            androidBinaryOptions,
            proGuardConfig,
            ndkCxxPlatforms));
    builder.register(new AndroidBuildConfigDescription(androidBinaryOptions));
    builder.register(new AndroidInstrumentationApkDescription(
            proGuardConfig,
            androidBinaryOptions,
            ndkCxxPlatforms));
    builder.register(new AndroidLibraryDescription(androidBinaryOptions));
    builder.register(new AndroidManifestDescription());
    builder.register(new AndroidPrebuiltAarDescription(androidBinaryOptions));
    builder.register(new AndroidResourceDescription());
    builder.register(new ApkGenruleDescription());
    builder.register(new AppleAssetCatalogDescription());
    builder.register(
        new AppleBinaryDescription(
            appleConfig,
            new CxxBinaryDescription(
                cxxBuckConfig,
                defaultCxxPlatform,
                cxxPlatforms,
                CxxSourceRuleFactory.Strategy.COMBINED_PREPROCESS_AND_COMPILE),
            cxxPlatforms,
            appleCxxPlatformsToAppleSdkPaths));
    builder.register(new AppleBundleDescription());
    builder.register(new AppleResourceDescription());
    builder.register(new AppleTestDescription(appleLibraryDescription));
    builder.register(new BuckExtensionDescription(defaultJavacOptions));
    builder.register(new CoreDataModelDescription());
    builder.register(cxxBinaryDescription);
    builder.register(cxxLibraryDescription);
    builder.register(new CxxPythonExtensionDescription(cxxBuckConfig, cxxPlatforms));
    builder.register(new CxxTestDescription(cxxBuckConfig, defaultCxxPlatform, cxxPlatforms));
    builder.register(new DBinaryDescription(dBuckConfig));
    builder.register(new DLibraryDescription(dBuckConfig));
    builder.register(new ExportFileDescription());
    builder.register(new GenruleDescription());
    builder.register(new GenAidlDescription());
    builder.register(new GenParcelableDescription());
    builder.register(new GwtBinaryDescription());
    builder.register(new IosPostprocessResourcesDescription());
    builder.register(new JavaBinaryDescription(defaultJavacOptions, defaultCxxPlatform));
    builder.register(new JavaLibraryDescription(defaultJavacOptions));
    builder.register(new JavaTestDescription(defaultJavacOptions, testRuleTimeoutMs));
    builder.register(new KeystoreDescription());
    builder.register(new NdkLibraryDescription(ndkVersion, ndkCxxPlatforms));
    builder.register(new OCamlBinaryDescription(ocamlBuckConfig));
    builder.register(new OCamlLibraryDescription(ocamlBuckConfig));
    builder.register(new PrebuiltCxxLibraryDescription(cxxPlatforms));
    builder.register(new PrebuiltJarDescription());
    builder.register(new PrebuiltNativeLibraryDescription());
    builder.register(new PrebuiltOCamlLibraryDescription());
    builder.register(new ProjectConfigDescription());
    builder.register(
        new PythonBinaryDescription(
            pyConfig.getPathToPex(),
            pyConfig.getPathToPexExecuter(),
            pythonEnv,
            defaultCxxPlatform,
            cxxPlatforms));
    builder.register(new PythonLibraryDescription());
    builder.register(
        new PythonTestDescription(
            projectFilesystem,
            pyConfig.getPathToPex(),
            pyConfig.getPathToPexExecuter(),
            pythonPathToPythonTestMain,
            pythonEnv,
            defaultCxxPlatform,
            cxxPlatforms));
    builder.register(new RemoteFileDescription(downloader));
    builder.register(new RobolectricTestDescription(
            androidBinaryOptions,
            testRuleTimeoutMs));
    builder.register(new ShBinaryDescription());
    builder.register(new ShTestDescription());
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
