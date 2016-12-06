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

package com.facebook.buck.apple;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.MapMaker;

import org.immutables.value.Value;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AppleConfig {
  private static final String DEFAULT_TEST_LOG_DIRECTORY_ENVIRONMENT_VARIABLE = "FB_LOG_DIRECTORY";
  private static final String DEFAULT_TEST_LOG_LEVEL_ENVIRONMENT_VARIABLE = "FB_LOG_LEVEL";
  private static final String DEFAULT_TEST_LOG_LEVEL = "debug";

  private static final Logger LOG = Logger.get(AppleConfig.class);

  private static final Pattern XCODE_BUILD_NUMBER_PATTERN =
      Pattern.compile("Build version ([a-zA-Z0-9]+)");

  private final BuckConfig delegate;

  private final ConcurrentMap<Path, Supplier<Optional<String>>> xcodeVersionCache;

  public AppleConfig(BuckConfig delegate) {
    this.xcodeVersionCache = new MapMaker().weakKeys().makeMap();
    this.delegate = delegate;
  }

  /**
   * If specified, the value of {@code [apple] xcode_developer_dir} wrapped in a {@link Supplier}.
   * Otherwise, this returns a {@link Supplier} that lazily runs {@code xcode-select --print-path}
   * and caches the result.
   */
  public Supplier<Optional<Path>> getAppleDeveloperDirectorySupplier(
      ProcessExecutor processExecutor) {
    Optional<String> xcodeDeveloperDirectory = delegate.getValue("apple", "xcode_developer_dir");
    if (xcodeDeveloperDirectory.isPresent()) {
      Path developerDirectory = delegate.resolvePathThatMayBeOutsideTheProjectFilesystem(
          Paths.get(xcodeDeveloperDirectory.get()));
      return Suppliers.ofInstance(Optional.of(developerDirectory));
    } else {
      return createAppleDeveloperDirectorySupplier(processExecutor);
    }
  }

  /**
   * If specified, the value of {@code [apple] xcode_developer_dir_for_tests} wrapped in a
   * {@link Supplier}.
   * Otherwise, this falls back to {@code [apple] xcode_developer_dir} and finally
   * {@code xcode-select --print-path}.
   */
  public Supplier<Optional<Path>> getAppleDeveloperDirectorySupplierForTests(
      ProcessExecutor processExecutor) {
    Optional<String> xcodeDeveloperDirectory =
        delegate.getValue("apple", "xcode_developer_dir_for_tests");
    if (xcodeDeveloperDirectory.isPresent()) {
      Path developerDirectory = delegate.resolvePathThatMayBeOutsideTheProjectFilesystem(
          Paths.get(xcodeDeveloperDirectory.get()));
      return Suppliers.ofInstance(Optional.of(developerDirectory));
    } else {
      return getAppleDeveloperDirectorySupplier(processExecutor);
    }
  }

  public ImmutableList<Path> getExtraToolchainPaths() {
    ImmutableList<String> extraPathsStrings = delegate.getListWithoutComments(
        "apple",
        "extra_toolchain_paths");
    return ImmutableList.copyOf(Lists.transform(
        extraPathsStrings,
        string -> Paths.get(string)));
  }

  public ImmutableList<Path> getExtraPlatformPaths() {
    ImmutableList<String> extraPathsStrings = delegate.getListWithoutComments(
        "apple",
        "extra_platform_paths");
    return ImmutableList.copyOf(Lists.transform(
        extraPathsStrings,
        string -> Paths.get(string)));
  }

  public ImmutableMap<AppleSdk, AppleSdkPaths> getAppleSdkPaths(ProcessExecutor processExecutor) {
    Optional<Path> appleDeveloperDirectory =
        getAppleDeveloperDirectorySupplier(processExecutor).get();
    try {
      ImmutableMap<String, AppleToolchain> toolchains =
          AppleToolchainDiscovery.discoverAppleToolchains(
              appleDeveloperDirectory,
              getExtraToolchainPaths());
      return AppleSdkDiscovery.discoverAppleSdkPaths(
          appleDeveloperDirectory,
          getExtraPlatformPaths(),
          toolchains,
          this);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * @return a memoizing {@link Supplier} that caches the output of
   *     {@code xcode-select --print-path}.
   */
  private static Supplier<Optional<Path>> createAppleDeveloperDirectorySupplier(
      final ProcessExecutor processExecutor) {
    return Suppliers.memoize(new Supplier<Optional<Path>>() {
      @Override
      public Optional<Path> get() {
        ProcessExecutorParams processExecutorParams =
            ProcessExecutorParams.builder()
                .setCommand(ImmutableList.of("xcode-select", "--print-path"))
                .build();
        // Must specify that stdout is expected or else output may be wrapped in Ansi escape chars.
        Set<ProcessExecutor.Option> options = EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT);
        ProcessExecutor.Result result;
        try {
          result = processExecutor.launchAndExecute(
              processExecutorParams,
              options,
              /* stdin */ Optional.empty(),
              /* timeOutMs */ Optional.empty(),
              /* timeOutHandler */ Optional.empty());
        } catch (InterruptedException | IOException e) {
          LOG.warn("Could not execute xcode-select, continuing without developer dir.");
          return Optional.empty();
        }

        if (result.getExitCode() != 0) {
          throw new RuntimeException(
              result.getMessageForUnexpectedResult("xcode-select --print-path"));
        }

        return Optional.of(Paths.get(result.getStdout().get().trim()));
      }
    });
  }

  /**
   * For some inscrutable reason, the Xcode build number isn't in the toolchain's plist
   * (or in any .plist under Developer/)
   *
   * We have to run `Developer/usr/bin/xcodebuild -version` to get it.
   */
  public Supplier<Optional<String>> getXcodeBuildVersionSupplier(
      final Path developerPath,
      final ProcessExecutor processExecutor) {
    Supplier<Optional<String>> supplier = xcodeVersionCache.get(developerPath);
    if (supplier != null) {
      return supplier;
    }

    supplier = Suppliers.memoize(
        new Supplier<Optional<String>>() {
          @Override
          public Optional<String> get() {
            ProcessExecutorParams processExecutorParams =
                ProcessExecutorParams.builder()
                    .setCommand(
                        ImmutableList.of(
                            developerPath.resolve("usr/bin/xcodebuild").toString(), "-version"))
                    .build();
            // Specify that stdout is expected, or else output may be wrapped in Ansi escape chars.
            Set<ProcessExecutor.Option> options =
                EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT);
            ProcessExecutor.Result result;

            try {
              result = processExecutor.launchAndExecute(
                  processExecutorParams,
                  options,
                  /* stdin */ Optional.empty(),
                  /* timeOutMs */ Optional.empty(),
                  /* timeOutHandler */ Optional.empty());
            } catch (InterruptedException | IOException e) {
              LOG.warn("Could not execute xcodebuild to find Xcode build number.");
              return Optional.empty();
            }

            if (result.getExitCode() != 0) {
              throw new RuntimeException(
                  result.getMessageForUnexpectedResult("xcodebuild -version"));
            }

            Matcher matcher = XCODE_BUILD_NUMBER_PATTERN.matcher(result.getStdout().get());
            if (matcher.find()) {
              String xcodeBuildNumber = matcher.group(1);
              return Optional.of(xcodeBuildNumber);
            } else {
              LOG.warn("Xcode build number not found.");
              return Optional.empty();
            }
          }
        });
    xcodeVersionCache.put(developerPath, supplier);
    return supplier;
  }


  public Optional<String> getTargetSdkVersion(ApplePlatform platform) {
    return delegate.getValue("apple", platform.getName() + "_target_sdk_version");
  }

  public ImmutableList<String> getXctestPlatformNames() {
    return delegate.getListWithoutComments(
        "apple",
        "xctest_platforms");
  }

  public Optional<Path> getXctoolPath() {
    Path xctool = getOptionalPath("apple", "xctool_path").orElse(Paths.get("xctool"));
    return new ExecutableFinder().getOptionalExecutable(xctool, delegate.getEnvironment());
  }

  public Optional<BuildTarget> getXctoolZipTarget() {
    return delegate.getBuildTarget("apple", "xctool_zip_target");
  }

  public Optional<String> getXctoolDefaultDestinationSpecifier() {
    return delegate.getValue("apple", "xctool_default_destination_specifier");
  }

  public Optional<Long> getXctoolStutterTimeoutMs() {
    return delegate.getLong("apple", "xctool_stutter_timeout");
  }

  public boolean getXcodeDisableParallelizeBuild() {
    return delegate.getBooleanValue("apple", "xcode_disable_parallelize_build", false);
  }

  public boolean useDryRunCodeSigning() {
    return delegate.getBooleanValue("apple", "dry_run_code_signing", false);
  }

  public boolean cacheBundlesAndPackages() {
    return delegate.getBooleanValue("apple", "cache_bundles_and_packages", true);
  }


  public Optional<Path> getAppleDeviceHelperPath() {
    return getOptionalPath("apple", "device_helper_path");
  }

  public Optional<BuildTarget> getAppleDeviceHelperTarget() {
    return delegate.getBuildTarget("apple", "device_helper_target");
  }

  public Path getProvisioningProfileSearchPath() {
    return getOptionalPath(
        "apple",
        "provisioning_profile_search_path").orElse(Paths.get(System.getProperty("user.home") +
        "/Library/MobileDevice/Provisioning Profiles"));
  }

  private Optional<Path> getOptionalPath(String sectionName, String propertyName) {
    Optional<String> pathString = delegate.getValue(sectionName, propertyName);
    if (pathString.isPresent()) {
      return Optional.of(delegate.resolvePathThatMayBeOutsideTheProjectFilesystem(
              Paths.get(pathString.get())));
    } else {
      return Optional.empty();
    }
  }

  public boolean shouldUseHeaderMapsInXcodeProject() {
    return delegate.getBooleanValue(
        "apple",
        "use_header_maps_in_xcode",
        true);
  }

  public String getTestLogDirectoryEnvironmentVariable() {
    return delegate.getValue(
        "apple",
        "test_log_directory_environment_variable").orElse(
        DEFAULT_TEST_LOG_DIRECTORY_ENVIRONMENT_VARIABLE);
  }

  public String getTestLogLevelEnvironmentVariable() {
    return delegate.getValue(
        "apple",
        "test_log_level_environment_variable").orElse(DEFAULT_TEST_LOG_LEVEL_ENVIRONMENT_VARIABLE);
  }

  public String getTestLogLevel() {
    return delegate.getValue(
        "apple",
        "test_log_level").orElse(DEFAULT_TEST_LOG_LEVEL);
  }

  public AppleDebugFormat getDefaultDebugInfoFormatForBinaries() {
    return delegate.getEnum(
        "apple",
        "default_debug_info_format_for_binaries",
        AppleDebugFormat.class).orElse(AppleDebugFormat.DWARF_AND_DSYM);
  }

  public AppleDebugFormat getDefaultDebugInfoFormatForTests() {
    return delegate.getEnum(
        "apple",
        "default_debug_info_format_for_tests",
        AppleDebugFormat.class).orElse(AppleDebugFormat.DWARF);
  }

  public AppleDebugFormat getDefaultDebugInfoFormatForLibraries() {
    return delegate.getEnum(
        "apple",
        "default_debug_info_format_for_libraries",
        AppleDebugFormat.class).orElse(AppleDebugFormat.DWARF);
  }

  public boolean forceDsymModeInBuildWithBuck() {
    return delegate.getBooleanValue(
        "apple",
        "force_dsym_mode_in_build_with_buck",
        true);
  }

  /**
   * Returns the custom packager command specified in the config, if defined.
   *
   * This is translated into the config value of {@code apple.PLATFORMNAME_packager_command}.
   *
   * @param platform the platform to query.
   * @return the custom packager command specified in the config, if defined.
   */
  public Optional<ApplePackageConfig> getPackageConfigForPlatform(ApplePlatform platform) {
    String command =
        delegate.getValue("apple", platform.getName() + "_package_command").orElse("");
    String extension =
        delegate.getValue("apple", platform.getName() + "_package_extension").orElse("");
    if (command.isEmpty() ^ extension.isEmpty()) {
      throw new HumanReadableException(
          "Config option %s and %s should be both specified, or be both omitted.",
          "apple." + platform.getName() + "_package_command",
          "apple." + platform.getName() + "_package_extension");
    } else if (command.isEmpty() && extension.isEmpty()) {
      return Optional.empty();
    } else {
      return Optional.of(ApplePackageConfig.of(command, extension));
    }
  }

  public Optional<ImmutableList<String>> getToolchainsOverrideForSDKName(String name) {
    return delegate.getOptionalListWithoutComments(
        "apple",
        name + "_toolchains_override");
  }

  @Value.Immutable
  @BuckStyleTuple
  interface AbstractApplePackageConfig {
    String getCommand();
    String getExtension();
  }
}
