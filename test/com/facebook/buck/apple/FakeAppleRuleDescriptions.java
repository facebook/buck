/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.apple.toolchain.AppleSdkPaths;
import com.facebook.buck.apple.toolchain.CodeSignIdentity;
import com.facebook.buck.apple.toolchain.CodeSignIdentityStore;
import com.facebook.buck.apple.toolchain.ProvisioningProfileStore;
import com.facebook.buck.apple.toolchain.impl.AppleCxxPlatforms;
import com.facebook.buck.apple.toolchain.impl.XcodeToolFinder;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.cxx.CxxBinaryFactory;
import com.facebook.buck.cxx.CxxBinaryFlavored;
import com.facebook.buck.cxx.CxxBinaryImplicitFlavors;
import com.facebook.buck.cxx.CxxBinaryMetadataFactory;
import com.facebook.buck.cxx.CxxLibraryFactory;
import com.facebook.buck.cxx.CxxLibraryFlavored;
import com.facebook.buck.cxx.CxxLibraryImplicitFlavors;
import com.facebook.buck.cxx.CxxLibraryMetadataFactory;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.DefaultCxxPlatforms;
import com.facebook.buck.cxx.toolchain.InferBuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.swift.SwiftLibraryDescription;
import com.facebook.buck.swift.toolchain.SwiftPlatform;
import com.facebook.buck.swift.toolchain.SwiftPlatformsProvider;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Utility class holding pre-made fake Apple rule descriptions for use in tests. */
public class FakeAppleRuleDescriptions {
  // Utility class, do not instantiate.
  private FakeAppleRuleDescriptions() {}

  private static final BuckConfig DEFAULT_BUCK_CONFIG =
      FakeBuckConfig.builder()
          .setSections(
              "[apple]",
              "default_debug_info_format_for_tests = NONE",
              "default_debug_info_format_for_binaries = NONE",
              "default_debug_info_format_for_libraries = NONE")
          .build();

  public static final AppleSdk DEFAULT_MACOSX_SDK =
      AppleSdk.builder()
          .setApplePlatform(ApplePlatform.MACOSX)
          .setName("macosx")
          .setArchitectures(ImmutableList.of("x86_64"))
          .setVersion("10.10")
          .setToolchains(ImmutableList.of())
          .build();

  public static final AppleSdk DEFAULT_IPHONEOS_SDK =
      AppleSdk.builder()
          .setApplePlatform(ApplePlatform.IPHONEOS)
          .setName("iphoneos")
          .setArchitectures(ImmutableList.of("i386", "x86_64"))
          .setVersion("8.0")
          .setToolchains(ImmutableList.of())
          .build();

  public static final AppleSdk DEFAULT_WATCHOS_SDK =
      AppleSdk.builder()
          .setApplePlatform(ApplePlatform.WATCHOS)
          .setName("watchos")
          .setArchitectures(ImmutableList.of("armv7k"))
          .setVersion("2.0")
          .setToolchains(ImmutableList.of())
          .build();

  public static final ProjectFilesystem FAKE_PROJECT_FILESYSTEM =
      ((Supplier<ProjectFilesystem>)
              () -> {
                ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
                Stream.of(
                        "Toolchains/XcodeDefault.xctoolchain/usr/bin/clang",
                        "Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++",
                        "Toolchains/XcodeDefault.xctoolchain/usr/bin/dsymutil",
                        "Toolchains/XcodeDefault.xctoolchain/usr/bin/lipo",
                        "Toolchains/XcodeDefault.xctoolchain/usr/bin/ranlib",
                        "Toolchains/XcodeDefault.xctoolchain/usr/bin/strip",
                        "Toolchains/XcodeDefault.xctoolchain/usr/bin/swiftc",
                        "Toolchains/XcodeDefault.xctoolchain/usr/bin/swift-stdlib-tool",
                        "Toolchains/XcodeDefault.xctoolchain/usr/bin/nm",
                        "Toolchains/XcodeDefault.xctoolchain/usr/bin/ar",
                        "Platforms/iPhoneOS.platform/Developer/usr/bin/libtool",
                        "usr/bin/actool",
                        "usr/bin/ibtool",
                        "usr/bin/momc",
                        "usr/bin/copySceneKitAssets",
                        "usr/bin/lldb",
                        "Tools/otest",
                        "usr/bin/xctest")
                    .forEach(
                        path -> {
                          Path actualPath = filesystem.getPath(path);
                          try {
                            Files.createDirectories(actualPath.getParent());
                            Files.createFile(actualPath);
                          } catch (IOException e) {
                            throw new RuntimeException(e);
                          }
                        });
                return filesystem;
              })
          .get();

  public static final AppleSdkPaths DEFAULT_MACOSX_SDK_PATHS =
      AppleSdkPaths.builder()
          .setDeveloperPath(FAKE_PROJECT_FILESYSTEM.getPath("."))
          .addToolchainPaths(FAKE_PROJECT_FILESYSTEM.getPath("Toolchains/XcodeDefault.xctoolchain"))
          .setPlatformPath(FAKE_PROJECT_FILESYSTEM.getPath("Platforms/MacOSX.platform"))
          .setSdkPath(
              FAKE_PROJECT_FILESYSTEM.getPath(
                  "Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk"))
          .build();

  public static final AppleSdkPaths DEFAULT_IPHONEOS_SDK_PATHS =
      AppleSdkPaths.builder()
          .setDeveloperPath(FAKE_PROJECT_FILESYSTEM.getPath("."))
          .addToolchainPaths(FAKE_PROJECT_FILESYSTEM.getPath("Toolchains/XcodeDefault.xctoolchain"))
          .setPlatformPath(FAKE_PROJECT_FILESYSTEM.getPath("Platforms/iPhoneOS.platform"))
          .setSdkPath(
              FAKE_PROJECT_FILESYSTEM.getPath(
                  "Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk"))
          .build();

  public static final AppleCxxPlatforms.XcodeBuildVersionCache FAKE_XCODE_BUILD_VERSION_CACHE =
      new AppleCxxPlatforms.XcodeBuildVersionCache() {
        @Override
        protected Optional<String> lookup(Path developerDir) {
          return Optional.of("0A0000");
        }
      };

  public static final AppleCxxPlatform DEFAULT_IPHONEOS_I386_PLATFORM =
      AppleCxxPlatforms.buildWithXcodeToolFinder(
          FAKE_PROJECT_FILESYSTEM,
          DEFAULT_IPHONEOS_SDK,
          "8.0",
          "i386",
          DEFAULT_IPHONEOS_SDK_PATHS,
          DEFAULT_BUCK_CONFIG,
          new XcodeToolFinder(),
          FAKE_XCODE_BUILD_VERSION_CACHE,
          Optional.empty());

  public static final AppleCxxPlatform DEFAULT_IPHONEOS_X86_64_PLATFORM =
      AppleCxxPlatforms.buildWithXcodeToolFinder(
          FAKE_PROJECT_FILESYSTEM,
          DEFAULT_IPHONEOS_SDK,
          "8.0",
          "x86_64",
          DEFAULT_IPHONEOS_SDK_PATHS,
          DEFAULT_BUCK_CONFIG,
          new XcodeToolFinder(),
          FAKE_XCODE_BUILD_VERSION_CACHE,
          Optional.empty());

  public static final AppleCxxPlatform DEFAULT_WATCHOS_ARMV7K_PLATFORM =
      AppleCxxPlatforms.buildWithXcodeToolFinder(
          FAKE_PROJECT_FILESYSTEM,
          DEFAULT_WATCHOS_SDK,
          "2.0",
          "armv7k",
          DEFAULT_IPHONEOS_SDK_PATHS,
          DEFAULT_BUCK_CONFIG,
          new XcodeToolFinder(),
          FAKE_XCODE_BUILD_VERSION_CACHE,
          Optional.empty());

  public static final AppleCxxPlatform DEFAULT_MACOSX_X86_64_PLATFORM =
      AppleCxxPlatforms.buildWithXcodeToolFinder(
          FAKE_PROJECT_FILESYSTEM,
          DEFAULT_MACOSX_SDK,
          "8.0",
          "x86_64",
          DEFAULT_MACOSX_SDK_PATHS,
          DEFAULT_BUCK_CONFIG,
          new XcodeToolFinder(),
          FAKE_XCODE_BUILD_VERSION_CACHE,
          Optional.empty());

  public static final CxxPlatform DEFAULT_PLATFORM =
      DefaultCxxPlatforms.build(Platform.MACOS, new CxxBuckConfig(DEFAULT_BUCK_CONFIG));

  public static final FlavorDomain<CxxPlatform> DEFAULT_APPLE_FLAVOR_DOMAIN =
      FlavorDomain.of(
          "Fake iPhone C/C++ Platform",
          DEFAULT_PLATFORM,
          DEFAULT_IPHONEOS_I386_PLATFORM.getCxxPlatform(),
          DEFAULT_IPHONEOS_X86_64_PLATFORM.getCxxPlatform(),
          DEFAULT_MACOSX_X86_64_PLATFORM.getCxxPlatform(),
          DEFAULT_WATCHOS_ARMV7K_PLATFORM.getCxxPlatform());

  public static final FlavorDomain<AppleCxxPlatform> DEFAULT_APPLE_CXX_PLATFORM_FLAVOR_DOMAIN =
      FlavorDomain.of(
          "Fake Apple C++ Platforms",
          DEFAULT_IPHONEOS_I386_PLATFORM,
          DEFAULT_IPHONEOS_X86_64_PLATFORM,
          DEFAULT_MACOSX_X86_64_PLATFORM,
          DEFAULT_WATCHOS_ARMV7K_PLATFORM);

  public static final FlavorDomain<SwiftPlatform> DEFAULT_SWIFT_PLATFORM_FLAVOR_DOMAIN =
      new FlavorDomain<>(
          "Fake Swift Platform",
          ImmutableMap.of(
              DEFAULT_IPHONEOS_I386_PLATFORM.getFlavor(),
              DEFAULT_IPHONEOS_I386_PLATFORM.getSwiftPlatform().get(),
              DEFAULT_IPHONEOS_X86_64_PLATFORM.getFlavor(),
              DEFAULT_IPHONEOS_X86_64_PLATFORM.getSwiftPlatform().get(),
              DEFAULT_MACOSX_X86_64_PLATFORM.getFlavor(),
              DEFAULT_MACOSX_X86_64_PLATFORM.getSwiftPlatform().get(),
              DEFAULT_WATCHOS_ARMV7K_PLATFORM.getFlavor(),
              DEFAULT_WATCHOS_ARMV7K_PLATFORM.getSwiftPlatform().get()));

  public static final SwiftLibraryDescription SWIFT_LIBRARY_DESCRIPTION =
      new SwiftLibraryDescription(
          createTestToolchainProviderForSwiftPlatform(DEFAULT_SWIFT_PLATFORM_FLAVOR_DOMAIN),
          CxxPlatformUtils.DEFAULT_CONFIG,
          new SwiftBuckConfig(DEFAULT_BUCK_CONFIG));
  /** A fake apple_library description with an iOS platform for use in tests. */
  public static final AppleLibraryDescription LIBRARY_DESCRIPTION = createAppleLibraryDescription();

  private static AppleLibraryDescription createAppleLibraryDescription() {
    ToolchainProvider toolchainProvider =
        createTestToolchainProvider(
            DEFAULT_APPLE_CXX_PLATFORM_FLAVOR_DOMAIN, DEFAULT_SWIFT_PLATFORM_FLAVOR_DOMAIN);
    CxxLibraryImplicitFlavors cxxLibraryImplicitFlavors =
        new CxxLibraryImplicitFlavors(toolchainProvider, CxxPlatformUtils.DEFAULT_CONFIG);
    CxxLibraryFlavored cxxLibraryFlavored =
        new CxxLibraryFlavored(toolchainProvider, CxxPlatformUtils.DEFAULT_CONFIG);
    CxxLibraryFactory cxxLibraryFactory =
        new CxxLibraryFactory(
            toolchainProvider,
            CxxPlatformUtils.DEFAULT_CONFIG,
            new InferBuckConfig(DEFAULT_BUCK_CONFIG));
    CxxLibraryMetadataFactory cxxLibraryMetadataFactory =
        new CxxLibraryMetadataFactory(toolchainProvider);

    return new AppleLibraryDescription(
        toolchainProvider,
        SWIFT_LIBRARY_DESCRIPTION,
        DEFAULT_BUCK_CONFIG.getView(AppleConfig.class),
        new SwiftBuckConfig(DEFAULT_BUCK_CONFIG),
        cxxLibraryImplicitFlavors,
        cxxLibraryFlavored,
        cxxLibraryFactory,
        cxxLibraryMetadataFactory);
  }

  /** A fake apple_binary description with an iOS platform for use in tests. */
  public static final AppleBinaryDescription BINARY_DESCRIPTION = createAppleBinaryDescription();

  private static AppleBinaryDescription createAppleBinaryDescription() {
    ToolchainProvider toolchainProvider =
        new ToolchainProviderBuilder()
            .withToolchain(
                CxxPlatformsProvider.DEFAULT_NAME,
                CxxPlatformsProvider.of(
                    DEFAULT_IPHONEOS_I386_PLATFORM.getCxxPlatform(), DEFAULT_APPLE_FLAVOR_DOMAIN))
            .build();
    CxxBinaryImplicitFlavors cxxBinaryImplicitFlavors =
        new CxxBinaryImplicitFlavors(toolchainProvider, CxxPlatformUtils.DEFAULT_CONFIG);
    CxxBinaryFactory cxxBinaryFactory =
        new CxxBinaryFactory(
            toolchainProvider,
            CxxPlatformUtils.DEFAULT_CONFIG,
            new InferBuckConfig(DEFAULT_BUCK_CONFIG));
    CxxBinaryMetadataFactory cxxBinaryMetadataFactory =
        new CxxBinaryMetadataFactory(toolchainProvider);
    CxxBinaryFlavored cxxBinaryFlavored =
        new CxxBinaryFlavored(toolchainProvider, CxxPlatformUtils.DEFAULT_CONFIG);

    return new AppleBinaryDescription(
        createTestToolchainProviderForApplePlatform(DEFAULT_APPLE_CXX_PLATFORM_FLAVOR_DOMAIN),
        SWIFT_LIBRARY_DESCRIPTION,
        DEFAULT_BUCK_CONFIG.getView(AppleConfig.class),
        cxxBinaryImplicitFlavors,
        cxxBinaryFactory,
        cxxBinaryMetadataFactory,
        cxxBinaryFlavored);
  }

  /** A fake apple_bundle description with an iOS platform for use in tests. */
  public static final AppleBundleDescription BUNDLE_DESCRIPTION =
      new AppleBundleDescription(
          createTestToolchainProviderForApplePlatform(DEFAULT_APPLE_CXX_PLATFORM_FLAVOR_DOMAIN),
          BINARY_DESCRIPTION,
          LIBRARY_DESCRIPTION,
          DEFAULT_BUCK_CONFIG.getView(AppleConfig.class));

  /** A fake apple_test description with an iOS platform for use in tests. */
  public static final AppleTestDescription TEST_DESCRIPTION =
      new AppleTestDescription(
          createTestToolchainProviderForApplePlatform(DEFAULT_APPLE_CXX_PLATFORM_FLAVOR_DOMAIN),
          DEFAULT_BUCK_CONFIG.getView(AppleConfig.class),
          LIBRARY_DESCRIPTION);

  private static ToolchainProvider createTestToolchainProviderForSwiftPlatform(
      FlavorDomain<SwiftPlatform> swiftFlavorDomain) {
    return new ToolchainProviderBuilder()
        .withToolchain(
            SwiftPlatformsProvider.DEFAULT_NAME, SwiftPlatformsProvider.of(swiftFlavorDomain))
        .withToolchain(
            CxxPlatformsProvider.DEFAULT_NAME,
            CxxPlatformsProvider.of(DEFAULT_PLATFORM, DEFAULT_APPLE_FLAVOR_DOMAIN))
        .build();
  }

  private static ToolchainProvider createTestToolchainProviderForApplePlatform(
      FlavorDomain<AppleCxxPlatform> appleCxxPlatformFlavorDomain) {
    return new ToolchainProviderBuilder()
        .withToolchain(
            AppleCxxPlatformsProvider.DEFAULT_NAME,
            AppleCxxPlatformsProvider.of(appleCxxPlatformFlavorDomain))
        .withToolchain(
            CodeSignIdentityStore.DEFAULT_NAME,
            fromIdentities(ImmutableList.of(CodeSignIdentity.AD_HOC)))
        .withToolchain(ProvisioningProfileStore.DEFAULT_NAME, ProvisioningProfileStore.empty())
        .withToolchain(
            CxxPlatformsProvider.DEFAULT_NAME,
            CxxPlatformsProvider.of(DEFAULT_PLATFORM, DEFAULT_APPLE_FLAVOR_DOMAIN))
        .build();
  }

  private static ToolchainProvider createTestToolchainProvider(
      FlavorDomain<AppleCxxPlatform> appleCxxPlatformFlavorDomain,
      FlavorDomain<SwiftPlatform> swiftFlavorDomain) {
    return new ToolchainProviderBuilder()
        .withToolchain(
            AppleCxxPlatformsProvider.DEFAULT_NAME,
            AppleCxxPlatformsProvider.of(appleCxxPlatformFlavorDomain))
        .withToolchain(
            SwiftPlatformsProvider.DEFAULT_NAME, SwiftPlatformsProvider.of(swiftFlavorDomain))
        .withToolchain(
            CodeSignIdentityStore.DEFAULT_NAME,
            fromIdentities(ImmutableList.of(CodeSignIdentity.AD_HOC)))
        .withToolchain(ProvisioningProfileStore.DEFAULT_NAME, ProvisioningProfileStore.empty())
        .withToolchain(
            CxxPlatformsProvider.DEFAULT_NAME,
            CxxPlatformsProvider.of(DEFAULT_PLATFORM, DEFAULT_APPLE_FLAVOR_DOMAIN))
        .build();
  }

  private static CodeSignIdentityStore fromIdentities(Iterable<CodeSignIdentity> identities) {
    return CodeSignIdentityStore.of(Suppliers.ofInstance(ImmutableList.copyOf(identities)));
  }
}
