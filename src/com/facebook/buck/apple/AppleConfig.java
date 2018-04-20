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

import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.ConfigView;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BinaryBuildRuleToolProvider;
import com.facebook.buck.rules.ConstantToolProvider;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.ToolProvider;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutor.Option;
import com.facebook.buck.util.ProcessExecutor.Result;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.facebook.buck.util.zip.ZipCompressionLevel;
import com.google.common.base.Splitter;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.immutables.value.Value;

public class AppleConfig implements ConfigView<BuckConfig> {

  public static final ImmutableList<String> DEFAULT_IDENTITIES_COMMAND =
      ImmutableList.of("security", "find-identity", "-v", "-p", "codesigning");
  public static final ImmutableList<String> DEFAULT_READ_COMMAND =
      ImmutableList.of("openssl", "smime", "-inform", "der", "-verify", "-noverify", "-in");

  private static final String DEFAULT_TEST_LOG_DIRECTORY_ENVIRONMENT_VARIABLE = "FB_LOG_DIRECTORY";
  private static final String DEFAULT_TEST_LOG_LEVEL_ENVIRONMENT_VARIABLE = "FB_LOG_LEVEL";
  private static final String DEFAULT_TEST_LOG_LEVEL = "debug";

  private static final Logger LOG = Logger.get(AppleConfig.class);
  public static final String APPLE_SECTION = "apple";

  private static final String FORCE_LOAD_LINK_WHOLE_LIBRARY_ENABLED =
      "force_load_link_whole_library";
  private static final String FORCE_LOAD_LIBRARY_PATH = "force_load_library_path";

  private final BuckConfig delegate;

  // Reflection-based factory for ConfigView
  public static AppleConfig of(BuckConfig delegate) {
    return new AppleConfig(delegate);
  }

  @Override
  public BuckConfig getDelegate() {
    return delegate;
  }

  private AppleConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  /**
   * If specified, the value of {@code [apple] xcode_developer_dir} wrapped in a {@link Supplier}.
   * Otherwise, this returns a {@link Supplier} that lazily runs {@code xcode-select --print-path}
   * and caches the result.
   */
  public Supplier<Optional<Path>> getAppleDeveloperDirectorySupplier(
      ProcessExecutor processExecutor) {
    Optional<String> xcodeDeveloperDirectory =
        delegate.getValue(APPLE_SECTION, "xcode_developer_dir");
    if (xcodeDeveloperDirectory.isPresent()) {
      Path developerDirectory =
          delegate.resolvePathThatMayBeOutsideTheProjectFilesystem(
              Paths.get(xcodeDeveloperDirectory.get()));
      return Suppliers.ofInstance(Optional.of(normalizePath(developerDirectory)));
    } else {
      return createAppleDeveloperDirectorySupplier(processExecutor);
    }
  }

  public Optional<String> getXcodeDeveloperDirectoryForTests() {
    return delegate.getValue(APPLE_SECTION, "xcode_developer_dir_for_tests");
  }

  public ImmutableList<Path> getExtraToolchainPaths() {
    ImmutableList<String> extraPathsStrings =
        delegate.getListWithoutComments(APPLE_SECTION, "extra_toolchain_paths");
    return ImmutableList.copyOf(
        Lists.transform(
            extraPathsStrings,
            string ->
                normalizePath(
                    delegate.resolveNonNullPathOutsideTheProjectFilesystem(Paths.get(string)))));
  }

  public ImmutableList<Path> getExtraPlatformPaths() {
    ImmutableList<String> extraPathsStrings =
        delegate.getListWithoutComments(APPLE_SECTION, "extra_platform_paths");
    return ImmutableList.copyOf(
        Lists.transform(
            extraPathsStrings,
            string ->
                normalizePath(
                    delegate.resolveNonNullPathOutsideTheProjectFilesystem(Paths.get(string)))));
  }

  private static Path normalizePath(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException e) {
      return path;
    }
  }

  /**
   * @return a memoizing {@link Supplier} that caches the output of {@code xcode-select
   *     --print-path}.
   */
  private static Supplier<Optional<Path>> createAppleDeveloperDirectorySupplier(
      ProcessExecutor processExecutor) {
    return MoreSuppliers.memoize(
        () -> {
          ProcessExecutorParams processExecutorParams =
              ProcessExecutorParams.builder()
                  .setCommand(ImmutableList.of("xcode-select", "--print-path"))
                  .build();
          // Must specify that stdout is expected or else output may be wrapped in Ansi escape
          // chars.
          Set<Option> options = EnumSet.of(Option.EXPECTING_STD_OUT);
          Result result;
          try {
            result =
                processExecutor.launchAndExecute(
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

          return Optional.of(normalizePath(Paths.get(result.getStdout().get().trim())));
        });
  }

  public Optional<String> getTargetSdkVersion(ApplePlatform platform) {
    return delegate.getValue(APPLE_SECTION, platform.getName() + "_target_sdk_version");
  }

  public ImmutableList<String> getXctestPlatformNames() {
    return delegate.getListWithoutComments(APPLE_SECTION, "xctest_platforms");
  }

  public Optional<Path> getXctoolPath() {
    Path xctool = getOptionalPath(APPLE_SECTION, "xctool_path").orElse(Paths.get("xctool"));
    return new ExecutableFinder().getOptionalExecutable(xctool, delegate.getEnvironment());
  }

  public Optional<BuildTarget> getXctoolZipTarget() {
    return delegate.getBuildTarget(APPLE_SECTION, "xctool_zip_target");
  }

  public ToolProvider getCodesignProvider() {
    String codesignField = "codesign";
    Optional<BuildTarget> target = delegate.getMaybeBuildTarget(APPLE_SECTION, codesignField);
    String source = String.format("[%s] %s", APPLE_SECTION, codesignField);
    if (target.isPresent()) {
      return new BinaryBuildRuleToolProvider(target.get(), source);
    } else {
      Optional<Path> codesignPath = delegate.getPath(APPLE_SECTION, codesignField);
      Path defaultCodesignPath = Paths.get("/usr/bin/codesign");
      HashedFileTool codesign =
          new HashedFileTool(delegate.getPathSourcePath(codesignPath.orElse(defaultCodesignPath)));
      return new ConstantToolProvider(codesign);
    }
  }

  public Optional<String> getXctoolDefaultDestinationSpecifier() {
    return delegate.getValue(APPLE_SECTION, "xctool_default_destination_specifier");
  }

  public Optional<Long> getXctoolStutterTimeoutMs() {
    return delegate.getLong(APPLE_SECTION, "xctool_stutter_timeout");
  }

  public boolean getXcodeDisableParallelizeBuild() {
    return delegate.getBooleanValue(APPLE_SECTION, "xcode_disable_parallelize_build", false);
  }

  public boolean useDryRunCodeSigning() {
    return delegate.getBooleanValue(APPLE_SECTION, "dry_run_code_signing", false);
  }

  public boolean cacheBundlesAndPackages() {
    return delegate.getBooleanValue(APPLE_SECTION, "cache_bundles_and_packages", true);
  }

  public boolean linkAllObjC() {
    return delegate.getBooleanValue(APPLE_SECTION, "always_link_with_objc_flag", true);
  }

  public ZipCompressionLevel getZipCompressionLevel() {
    return delegate
        .getEnum(AppleConfig.APPLE_SECTION, "ipa_compression_level", ZipCompressionLevel.class)
        .orElse(ZipCompressionLevel.DEFAULT);
  }

  public Optional<Path> getAppleDeviceHelperAbsolutePath() {
    return getOptionalPath(APPLE_SECTION, "device_helper_path");
  }

  public Optional<BuildTarget> getAppleDeviceHelperTarget() {
    return delegate.getBuildTarget(APPLE_SECTION, "device_helper_target");
  }

  public Path getProvisioningProfileSearchPath() {
    return getOptionalPath(APPLE_SECTION, "provisioning_profile_search_path")
        .orElse(
            Paths.get(
                System.getProperty("user.home") + "/Library/MobileDevice/Provisioning Profiles"));
  }

  private Optional<Path> getOptionalPath(String sectionName, String propertyName) {
    Optional<String> pathString = delegate.getValue(sectionName, propertyName);
    if (pathString.isPresent()) {
      return Optional.of(
          delegate.resolvePathThatMayBeOutsideTheProjectFilesystem(Paths.get(pathString.get())));
    } else {
      return Optional.empty();
    }
  }

  public boolean shouldUseHeaderMapsInXcodeProject() {
    return delegate.getBooleanValue(APPLE_SECTION, "use_header_maps_in_xcode", true);
  }

  public boolean shouldMergeHeaderMapsInXcodeProject() {
    return delegate.getBooleanValue(APPLE_SECTION, "merge_header_maps_in_xcode", false);
  }

  public boolean shouldGenerateHeaderSymlinkTreesOnly() {
    return delegate.getBooleanValue(APPLE_SECTION, "generate_header_symlink_tree_only", false);
  }

  public boolean shouldGenerateMissingUmbrellaHeaders() {
    return delegate.getBooleanValue(APPLE_SECTION, "generate_missing_umbrella_headers", false);
  }

  public boolean shouldUseSwiftDelegate() {
    // TODO(mgd): Remove Swift delegation from Apple rules
    return delegate.getBooleanValue(APPLE_SECTION, "use_swift_delegate", true);
  }

  public boolean shouldVerifyBundleResources() {
    return delegate.getBooleanValue(APPLE_SECTION, "verify_bundle_resources", false);
  }

  public boolean shouldAddLinkerFlagsForLinkWholeLibraries() {
    return delegate.getBooleanValue(APPLE_SECTION, FORCE_LOAD_LINK_WHOLE_LIBRARY_ENABLED, false);
  }

  public String getForceLoadLibraryPath(boolean isFocusedTarget) {
    Optional<String> path = delegate.getValue(APPLE_SECTION, FORCE_LOAD_LIBRARY_PATH);
    if (!isFocusedTarget && path.isPresent()) {
      return path.get();
    }
    return "$BUILT_PRODUCTS_DIR";
  }

  public AppleAssetCatalog.ValidationType assetCatalogValidation() {
    return delegate
        .getEnum(APPLE_SECTION, "asset_catalog_validation", AppleAssetCatalog.ValidationType.class)
        .orElse(AppleAssetCatalog.ValidationType.XCODE);
  }

  public String getTestLogDirectoryEnvironmentVariable() {
    return delegate
        .getValue(APPLE_SECTION, "test_log_directory_environment_variable")
        .orElse(DEFAULT_TEST_LOG_DIRECTORY_ENVIRONMENT_VARIABLE);
  }

  public String getTestLogLevelEnvironmentVariable() {
    return delegate
        .getValue(APPLE_SECTION, "test_log_level_environment_variable")
        .orElse(DEFAULT_TEST_LOG_LEVEL_ENVIRONMENT_VARIABLE);
  }

  public String getTestLogLevel() {
    return delegate.getValue(APPLE_SECTION, "test_log_level").orElse(DEFAULT_TEST_LOG_LEVEL);
  }

  public AppleDebugFormat getDefaultDebugInfoFormatForBinaries() {
    return delegate
        .getEnum(APPLE_SECTION, "default_debug_info_format_for_binaries", AppleDebugFormat.class)
        .orElse(AppleDebugFormat.DWARF_AND_DSYM);
  }

  public AppleDebugFormat getDefaultDebugInfoFormatForTests() {
    return delegate
        .getEnum(APPLE_SECTION, "default_debug_info_format_for_tests", AppleDebugFormat.class)
        .orElse(AppleDebugFormat.DWARF);
  }

  public AppleDebugFormat getDefaultDebugInfoFormatForLibraries() {
    return delegate
        .getEnum(APPLE_SECTION, "default_debug_info_format_for_libraries", AppleDebugFormat.class)
        .orElse(AppleDebugFormat.DWARF);
  }

  public ImmutableList<String> getProvisioningProfileReadCommand() {
    Optional<String> value = delegate.getValue(APPLE_SECTION, "provisioning_profile_read_command");
    if (!value.isPresent()) {
      return DEFAULT_READ_COMMAND;
    }
    return ImmutableList.copyOf(Splitter.on(' ').splitToList(value.get()));
  }

  public ImmutableList<String> getCodeSignIdentitiesCommand() {
    Optional<String> value = delegate.getValue(APPLE_SECTION, "code_sign_identities_command");
    if (!value.isPresent()) {
      return DEFAULT_IDENTITIES_COMMAND;
    }
    return ImmutableList.copyOf(Splitter.on(' ').splitToList(value.get()));
  }

  /**
   * Returns the custom packager command specified in the config, if defined.
   *
   * <p>This is translated into the config value of {@code apple.PLATFORMNAME_packager_command}.
   *
   * @param platform the platform to query.
   * @return the custom packager command specified in the config, if defined.
   */
  public Optional<ApplePackageConfig> getPackageConfigForPlatform(ApplePlatform platform) {
    String command =
        delegate.getValue(APPLE_SECTION, platform.getName() + "_package_command").orElse("");
    String extension =
        delegate.getValue(APPLE_SECTION, platform.getName() + "_package_extension").orElse("");
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
    return delegate.getOptionalListWithoutComments(APPLE_SECTION, name + "_toolchains_override");
  }

  public Optional<Path> getXcodeToolReplacement(String toolName) {
    return getOptionalPath(APPLE_SECTION, toolName + "_replacement");
  }

  public String getXcodeToolName(String toolName) {
    return delegate
        .getValue(APPLE_SECTION, toolName + "_xcode_tool_name_override")
        .orElse(toolName);
  }

  @Value.Immutable
  @BuckStyleTuple
  interface AbstractApplePackageConfig {
    String getCommand();

    String getExtension();
  }
}
