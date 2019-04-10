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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.UserFlavor;
import com.facebook.buck.core.rules.schedule.RuleScheduleInfo;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.toolchain.toolprovider.impl.BinaryBuildRuleToolProvider;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.ArchiverProvider.LegacyArchiverType;
import com.facebook.buck.cxx.toolchain.linker.DefaultLinkerProvider;
import com.facebook.buck.cxx.toolchain.linker.LinkerProvider;
import com.facebook.buck.rules.tool.config.ToolConfig;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/** Contains platform independent settings for C/C++ rules. */
public class CxxBuckConfig {

  private static final String FLAVORED_CXX_SECTION_PREFIX = "cxx#";
  private static final String UNFLAVORED_CXX_SECTION = "cxx";

  private static final long DEFAULT_MAX_TEST_OUTPUT_SIZE = 8096;

  private static final String DEFAULT_PLATFORM = "default_platform";
  private static final String GTEST_DEP = "gtest_dep";
  private static final String GTEST_DEFAULT_TEST_MAIN_DEP = "gtest_default_test_main_dep";
  private static final String BOOST_TEST_DEP = "boost_test_dep";
  private static final String HOST_PLATFORM = "host_platform";
  private static final String ARCHIVER_PLATFORM = "archiver_platform";
  private static final String ARCHIVER_TYPE = "archiver_type";
  private static final String MAX_TEST_OUTPUT_SIZE = "max_test_output_size";
  private static final String LINKER_PLATFORM = "linker_platform";
  private static final String UNTRACKED_HEADERS = "untracked_headers";
  private static final String UNTRACKED_HEADERS_WHITELIST = "untracked_headers_whitelist";
  private static final String EXPORTED_HEADERS_SYMLINKS_ENABLED =
      "exported_headers_symlinks_enabled";
  private static final String HEADERS_SYMLINKS_ENABLED = "headers_symlinks_enabled";
  private static final String LINK_WEIGHT = "link_weight";
  private static final String CACHE_LINKS = "cache_links";
  private static final String CACHE_STRIPS = "cache_strips";
  private static final String CACHE_BINARIES = "cache_binaries";
  private static final String PCH_ENABLED = "pch_enabled";
  private static final String ARCHIVE_CONTENTS = "archive_contents";
  private static final String DEBUG_PATH_SANITIZER_LIMIT = "debug_path_sanitizer_limit";
  private static final String SHOULD_REMAP_HOST_PLATFORM = "should_remap_host_platform";
  private static final String UNIQUE_LIBRARY_NAME_ENABLED = "unique_library_name_enabled";
  private static final String DEFAULT_REEXPORT_ALL_HEADER_DEPENDENCIES =
      "default_reexport_all_header_dependencies";
  private static final String SHLIB_INTERFACES = "shlib_interfaces";
  private static final String SHARED_LIBRARY_INTERFACES = "shared_library_interfaces";
  private static final String INDEPENDENT_SHLIB_INTERFACES = "independent_shlib_interfaces";
  private static final String INDEPENDENT_SHLIB_INTERFACE_LDFLAGS =
      "independent_shlib_interface_ldflags";
  private static final String DECLARED_PLATFORMS = "declared_platforms";
  private static final String BINARY_EXT = "binary_extension";
  private static final String SHARED_LIBRARY_EXT = "shared_library_extension";
  private static final String STATIC_LIBRARY_EXT = "static_library_extension";
  private static final String OBJECT_FILE_EXT = "object_file_extension";
  private static final String CONFLICTING_HEADER_BASENAME_WHITELIST =
      "conflicting_header_basename_whitelist";
  private static final String HEADER_MODE = "header_mode";
  private static final String DETAILED_UNTRACKED_HEADER_MESSAGES =
      "detailed_untracked_header_messages";
  private static final String USE_ARG_FILE = "use_arg_file";
  private static final String TOOLCHAIN_TARGET = "toolchain_target";
  private static final String FILEPATH_LENGTH_LIMITED = "filepath_length_limited";

  private static final String OBJCOPY = "objcopy";
  private static final String NM = "nm";
  private static final String STRIP = "strip";

  /** Enumerates possible external tools used in building C/C++ programs. */
  public enum ToolType {
    AR("ar", "arflags"),
    AS("as", "asflags"),
    ASM("asm", "asmflags"),
    ASMPP("asmpp", "asmppflags"),
    ASPP("aspp", "asppflags"),
    CC("cc", "cflags"),
    CPP("cpp", "cppflags"),
    CUDA("cuda", "cudaflags"),
    CUDAPP("cudapp", "cudappflags"),
    CXX("cxx", "cxxflags"),
    CXXPP("cxxpp", "cxxppflags"),
    HIP("hip", "hipflags"),
    HIPPP("hippp", "hipppflags"),
    LD("ld", "ldflags"),
    RANLIB("ranlib", "ranlibflags"),
    ;

    /** Buck config key used to specify tool path. */
    private final String key;
    /** Buck config key used to specify tool flags. */
    private final String flagsKey;

    ToolType(String key, String flagsKey) {
      this.key = key;
      this.flagsKey = flagsKey;
    }
  }

  private final BuckConfig delegate;
  private final String cxxSection;

  public static final String DEFAULT_FLAVOR_LIBRARY_TYPE = "type";
  public static final String DEFAULT_FLAVOR_PLATFORM = "platform";

  /**
   * Constructs set of flavors given in a .buckconfig file, as is specified by section names of the
   * form cxx#{flavor name}.
   */
  public static ImmutableSet<Flavor> getCxxFlavors(BuckConfig config) {
    ImmutableSet.Builder<Flavor> builder = ImmutableSet.builder();
    ImmutableSet<String> sections = config.getSections();
    for (String section : sections) {
      if (section.startsWith(FLAVORED_CXX_SECTION_PREFIX)) {
        builder.add(InternalFlavor.of(section.substring(FLAVORED_CXX_SECTION_PREFIX.length())));
      }
    }
    return builder.build();
  }

  public CxxBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
    this.cxxSection = UNFLAVORED_CXX_SECTION;
  }

  /*
   * A special constructor for a section of the form cxx#{flavor name}
   * which represents a generated flavor that uses the cxx options defined
   * in that section.
   */
  public CxxBuckConfig(BuckConfig delegate, Flavor flavor) {
    this.delegate = delegate;
    this.cxxSection = FLAVORED_CXX_SECTION_PREFIX + flavor.getName();
  }

  public ImmutableMap<Flavor, CxxBuckConfig> getFlavoredConfigs() {
    ImmutableMap.Builder<Flavor, CxxBuckConfig> builder = ImmutableMap.builder();
    for (Flavor flav : CxxBuckConfig.getCxxFlavors(this.delegate)) {
      builder.put(flav, new CxxBuckConfig(this.delegate, flav));
    }
    return builder.build();
  }

  /** @return the environment in which {@link BuckConfig} was created. */
  public ImmutableMap<String, String> getEnvironment() {
    return delegate.getEnvironment();
  }

  /** @return the {@link BuildTarget} which represents the gtest library. */
  public Optional<BuildTarget> getGtestDep(TargetConfiguration targetConfiguration) {
    return delegate.getBuildTarget(cxxSection, GTEST_DEP, targetConfiguration);
  }

  /**
   * @return the {@link BuildTarget} which represents the main function that gtest tests should use
   *     by default (if no other main is given).
   */
  public Optional<BuildTarget> getGtestDefaultTestMainDep(TargetConfiguration targetConfiguration) {
    return delegate.getBuildTarget(cxxSection, GTEST_DEFAULT_TEST_MAIN_DEP, targetConfiguration);
  }

  /** @return the {@link BuildTarget} which represents the boost testing library. */
  public Optional<BuildTarget> getBoostTestDep(TargetConfiguration targetConfiguration) {
    return delegate.getBuildTarget(cxxSection, BOOST_TEST_DEP, targetConfiguration);
  }

  public Optional<Path> getPath(String name) {
    return delegate.getPath(cxxSection, name);
  }

  @Nullable
  public PathSourcePath getSourcePath(Path path) {
    return delegate.getPathSourcePath(path);
  }

  public Optional<SourcePath> getSourcePath(String name, TargetConfiguration targetConfiguration) {
    return delegate.getSourcePath(cxxSection, name, targetConfiguration);
  }

  public Optional<String> getDefaultPlatform() {
    return delegate.getValue(cxxSection, DEFAULT_PLATFORM);
  }

  public Optional<String> getHostPlatform() {
    return delegate.getValue(cxxSection, HOST_PLATFORM);
  }

  private Optional<ImmutableList<String>> getFlags(ToolType toolType) {
    return getFlags(toolType.flagsKey);
  }

  private Optional<ImmutableList<String>> getFlags(String field) {
    Optional<String> value = delegate.getValue(cxxSection, field);
    if (!value.isPresent()) {
      return Optional.empty();
    }
    return Optional.of(delegate.getListWithoutComments(cxxSection, field, ' '));
  }

  public Optional<ImmutableList<String>> getAsflags() {
    return getFlags(ToolType.AS);
  }

  public Optional<ImmutableList<String>> getAsppflags() {
    return getFlags(ToolType.ASPP);
  }

  public Optional<ImmutableList<String>> getCflags() {
    return getFlags(ToolType.CC);
  }

  public Optional<ImmutableList<String>> getCxxflags() {
    return getFlags(ToolType.CXX);
  }

  public Optional<ImmutableList<String>> getCppflags() {
    return getFlags(ToolType.CPP);
  }

  public Optional<ImmutableList<String>> getCxxppflags() {
    return getFlags(ToolType.CXXPP);
  }

  public Optional<ImmutableList<String>> getCudaflags() {
    return getFlags(ToolType.CUDA);
  }

  public Optional<ImmutableList<String>> getCudappflags() {
    return getFlags(ToolType.CUDAPP);
  }

  public Optional<ImmutableList<String>> getHipflags() {
    return getFlags(ToolType.HIP);
  }

  public Optional<ImmutableList<String>> getHipppflags() {
    return getFlags(ToolType.HIPPP);
  }

  public Optional<ImmutableList<String>> getAsmflags() {
    return getFlags(ToolType.ASM);
  }

  public Optional<ImmutableList<String>> getAsmppflags() {
    return getFlags(ToolType.ASMPP);
  }

  public Optional<ImmutableList<String>> getLdflags() {
    return getFlags(ToolType.LD);
  }

  public Optional<ImmutableList<String>> getArflags() {
    return getFlags(ToolType.AR);
  }

  public Optional<ImmutableList<String>> getRanlibflags() {
    return getFlags(ToolType.RANLIB);
  }

  /*
   * Constructs the appropriate Archiver for the specified platform.
   */
  public Optional<ArchiverProvider> getArchiverProvider(Platform defaultPlatform) {
    Optional<ToolProvider> toolProvider =
        delegate.getView(ToolConfig.class).getToolProvider(cxxSection, ToolType.AR.key);
    // TODO(cjhopman): This should probably accept ArchiverProvider.Type, not LegacyArchiverType.
    Optional<LegacyArchiverType> type =
        delegate.getEnum(cxxSection, ARCHIVER_TYPE, LegacyArchiverType.class);
    return toolProvider.map(
        archiver -> {
          Optional<Platform> archiverPlatform =
              delegate.getEnum(cxxSection, ARCHIVER_PLATFORM, Platform.class);

          Platform platform = archiverPlatform.orElse(defaultPlatform);
          return ArchiverProvider.from(archiver, platform, type);
        });
  }

  /** @return the maximum size in bytes of test output to report in test results. */
  public long getMaximumTestOutputSize() {
    return delegate.getLong(cxxSection, MAX_TEST_OUTPUT_SIZE).orElse(DEFAULT_MAX_TEST_OUTPUT_SIZE);
  }

  private Optional<CxxToolProviderParams> getCxxToolProviderParams(ToolType toolType) {
    Optional<String> value = delegate.getValue(cxxSection, toolType.key);
    if (!value.isPresent()) {
      return Optional.empty();
    }
    String source = String.format("[%s] %s", cxxSection, toolType.key);
    Optional<UnconfiguredBuildTargetView> target =
        delegate.getMaybeUnconfiguredBuildTarget(cxxSection, toolType.key);
    Optional<CxxToolProvider.Type> type =
        delegate.getEnum(cxxSection, toolType.key + "_type", CxxToolProvider.Type.class);
    if (target.isPresent()) {
      return Optional.of(
          CxxToolProviderParams.builder()
              .setSource(source)
              .setBuildTarget(target.get())
              .setType(type)
              .setPreferDependencyTree(getUseDetailedUntrackedHeaderMessages())
              .setToolType(toolType)
              .build());
    } else {
      return Optional.of(
          CxxToolProviderParams.builder()
              .setSource(source)
              .setPath(
                  delegate.getPathSourcePath(delegate.getRequiredPath(cxxSection, toolType.key)))
              .setType(type)
              .setPreferDependencyTree(getUseDetailedUntrackedHeaderMessages())
              .setToolType(toolType)
              .build());
    }
  }

  private Optional<PreprocessorProvider> getPreprocessorProvider(ToolType toolType) {
    return getCxxToolProviderParams(toolType)
        .map(AbstractCxxToolProviderParams::getPreprocessorProvider);
  }

  private Optional<CompilerProvider> getCompilerProvider(ToolType toolType) {
    return getCxxToolProviderParams(toolType)
        .map(AbstractCxxToolProviderParams::getCompilerProvider);
  }

  public Optional<CompilerProvider> getAs() {
    return getCompilerProvider(ToolType.AS);
  }

  public Optional<PreprocessorProvider> getAspp() {
    return getPreprocessorProvider(ToolType.ASPP);
  }

  public Optional<CompilerProvider> getCc() {
    return getCompilerProvider(ToolType.CC);
  }

  public Optional<PreprocessorProvider> getCpp() {
    return getPreprocessorProvider(ToolType.CPP);
  }

  public Optional<CompilerProvider> getCxx() {
    return getCompilerProvider(ToolType.CXX);
  }

  public Optional<PreprocessorProvider> getCxxpp() {
    return getPreprocessorProvider(ToolType.CXXPP);
  }

  public Optional<CompilerProvider> getCuda() {
    return getCompilerProvider(ToolType.CUDA);
  }

  public Optional<PreprocessorProvider> getCudapp() {
    return getPreprocessorProvider(ToolType.CUDAPP);
  }

  public Optional<CompilerProvider> getHip() {
    return getCompilerProvider(ToolType.HIP);
  }

  public Optional<PreprocessorProvider> getHippp() {
    return getPreprocessorProvider(ToolType.HIPPP);
  }

  public Optional<CompilerProvider> getAsm() {
    return getCompilerProvider(ToolType.ASM);
  }

  public Optional<PreprocessorProvider> getAsmpp() {
    return getPreprocessorProvider(ToolType.ASMPP);
  }

  public Optional<Boolean> getUseArgFile() {
    return delegate.getBoolean(cxxSection, USE_ARG_FILE);
  }

  /**
   * Construct a linker based on `ld` and `linker_platform` sections in the config.
   *
   * @param defaultType the default type for a linker if `linker_platform` is not specified in the
   *     config.
   */
  public Optional<LinkerProvider> getLinkerProvider(LinkerProvider.Type defaultType) {
    Optional<ToolProvider> toolProvider =
        delegate.getView(ToolConfig.class).getToolProvider(cxxSection, ToolType.LD.key);
    if (!toolProvider.isPresent()) {
      return Optional.empty();
    }
    Optional<LinkerProvider.Type> type =
        delegate.getEnum(cxxSection, LINKER_PLATFORM, LinkerProvider.Type.class);
    return Optional.of(
        new DefaultLinkerProvider(
            type.orElse(defaultType), toolProvider.get(), shouldCacheLinks()));
  }

  public HeaderVerification getHeaderVerificationOrIgnore() {
    return HeaderVerification.builder()
        .setMode(
            delegate
                .getEnum(cxxSection, UNTRACKED_HEADERS, HeaderVerification.Mode.class)
                .orElse(HeaderVerification.Mode.IGNORE))
        .addAllWhitelist(delegate.getListWithoutComments(cxxSection, UNTRACKED_HEADERS_WHITELIST))
        .build();
  }

  public Optional<Boolean> getPublicHeadersSymlinksSetting() {
    return delegate.getBoolean(cxxSection, EXPORTED_HEADERS_SYMLINKS_ENABLED);
  }

  public boolean getPublicHeadersSymlinksEnabled() {
    return getPublicHeadersSymlinksSetting().orElse(true);
  }

  public Optional<Boolean> getPrivateHeadersSymlinksSetting() {
    return delegate.getBoolean(cxxSection, HEADERS_SYMLINKS_ENABLED);
  }

  public boolean getPrivateHeadersSymlinksEnabled() {
    return getPrivateHeadersSymlinksSetting().orElse(true);
  }

  public Optional<RuleScheduleInfo> getLinkScheduleInfo() {
    Optional<Long> linkWeight = delegate.getLong(cxxSection, LINK_WEIGHT);
    return linkWeight.map(
        weight -> RuleScheduleInfo.builder().setJobsMultiplier(weight.intValue()).build());
  }

  public boolean shouldCacheLinks() {
    return delegate.getBooleanValue(cxxSection, CACHE_LINKS, true);
  }

  public boolean shouldCacheStrip() {
    return delegate.getBooleanValue(cxxSection, CACHE_STRIPS, true);
  }

  public boolean shouldCacheBinaries() {
    return delegate.getBooleanValue(cxxSection, CACHE_BINARIES, false);
  }

  public boolean isPCHEnabled() {
    return delegate.getBooleanValue(cxxSection, PCH_ENABLED, true);
  }

  public Optional<ArchiveContents> getArchiveContents() {
    return delegate.getEnum(cxxSection, ARCHIVE_CONTENTS, ArchiveContents.class);
  }

  public ImmutableMap<String, Flavor> getDefaultFlavorsForRuleType(RuleType type) {
    return ImmutableMap.copyOf(
        Maps.transformValues(
            delegate.getEntriesForSection("defaults." + type.getName()), InternalFlavor::of));
  }

  public int getDebugPathSanitizerLimit() {
    return delegate.getInteger(cxxSection, DEBUG_PATH_SANITIZER_LIMIT).orElse(250);
  }

  /** @return whether to remap to the underlying host platform or to use #default */
  public boolean getShouldRemapHostPlatform() {
    return delegate.getBooleanValue(cxxSection, SHOULD_REMAP_HOST_PLATFORM, false);
  }

  private Optional<ToolProvider> getToolProvider(String name) {
    return delegate.getView(ToolConfig.class).getToolProvider(cxxSection, name);
  }

  public Optional<ToolProvider> getRanlib() {
    return getToolProvider(ToolType.RANLIB.key);
  }

  public Optional<ToolProvider> getObjcopy() {
    return getToolProvider(OBJCOPY);
  }

  private Optional<Tool> getTool(String name) {
    return getPath(name).map(this::getSourcePath).map(HashedFileTool::new);
  }

  public Optional<Tool> getNm() {
    return getTool(NM);
  }

  public Optional<Tool> getStrip() {
    return getTool(STRIP);
  }

  public boolean isUniqueLibraryNameEnabled() {
    return delegate.getBooleanValue(cxxSection, UNIQUE_LIBRARY_NAME_ENABLED, false);
  }

  public boolean getDefaultReexportAllHeaderDependencies() {
    return delegate.getBooleanValue(cxxSection, DEFAULT_REEXPORT_ALL_HEADER_DEPENDENCIES, true);
  }

  /** @return whether to enable shared library interfaces. */
  public SharedLibraryInterfaceParams.Type getSharedLibraryInterfaces() {

    // Check for an explicit setting.
    Optional<SharedLibraryInterfaceParams.Type> setting =
        delegate.getEnum(cxxSection, SHLIB_INTERFACES, SharedLibraryInterfaceParams.Type.class);
    if (setting.isPresent()) {
      return setting.get();
    }

    // For backwards compatibility, check the older boolean setting.
    Optional<Boolean> oldSetting = delegate.getBoolean(cxxSection, SHARED_LIBRARY_INTERFACES);
    if (oldSetting.isPresent()) {
      return oldSetting.get()
          ? SharedLibraryInterfaceParams.Type.ENABLED
          : SharedLibraryInterfaceParams.Type.DISABLED;
    }

    // Default.
    return SharedLibraryInterfaceParams.Type.DISABLED;
  }

  /**
   * @return additional flags to pass to the linker when linking independent shared library
   *     interfaces.
   */
  public Optional<ImmutableList<String>> getIndependentShlibInterfacesLdflags() {
    return getFlags(INDEPENDENT_SHLIB_INTERFACE_LDFLAGS);
  }

  /**
   * @return whether to generate a rule's shared library interface directly from it's object files,
   *     to avoid having to wait for it's shared library to build.
   */
  public boolean isIndependentSharedLibraryInterfaces() {
    return delegate.getBooleanValue(cxxSection, INDEPENDENT_SHLIB_INTERFACES, false);
  }

  /** @return the list of flavors that buck will consider valid when building the target graph. */
  public ImmutableSet<Flavor> getDeclaredPlatforms() {
    return delegate.getListWithoutComments(cxxSection, DECLARED_PLATFORMS).stream()
        .map(s -> UserFlavor.of(s, String.format("Declared platform: %s", s)))
        .collect(ImmutableSet.toImmutableSet());
  }

  /** @return the extension to use for binaries (e.g. ".exe"). */
  public Optional<String> getBinaryExtension() {
    return delegate.getValue(cxxSection, BINARY_EXT);
  }

  /** @return the extension to use for shared libraries (e.g. ".so"). */
  public Optional<String> getSharedLibraryExtension() {
    return delegate.getValue(cxxSection, SHARED_LIBRARY_EXT);
  }

  /** @return the extension to use for static libraries (e.g. ".a"). */
  public Optional<String> getStaticLibraryExtension() {
    return delegate.getValue(cxxSection, STATIC_LIBRARY_EXT);
  }

  /** @return the extension to use for object files (e.g. ".o"). */
  public Optional<String> getObjectFileExtension() {
    return delegate.getValue(cxxSection, OBJECT_FILE_EXT);
  }

  public ImmutableSortedSet<String> getConflictingHeaderBasenameWhitelist() {
    return ImmutableSortedSet.copyOf(
        delegate.getListWithoutComments(cxxSection, CONFLICTING_HEADER_BASENAME_WHITELIST));
  }

  /** @return the configured C/C++ header mode. */
  public Optional<HeaderMode> getHeaderMode() {
    return delegate.getEnum(cxxSection, HEADER_MODE, HeaderMode.class);
  }

  /** @return whether to generate more detailed untracked header messages. */
  public Boolean getUseDetailedUntrackedHeaderMessages() {
    return delegate.getBooleanValue(cxxSection, DETAILED_UNTRACKED_HEADER_MESSAGES, false);
  }

  /** @return whether short names for intermediate files should be used */
  public Boolean getFilepathLengthLimited() {
    return delegate.getBooleanValue(cxxSection, FILEPATH_LENGTH_LIMITED, false);
  }

  public BuckConfig getDelegate() {
    return delegate;
  }

  /**
   * If the config specifies a value for "toolchain_target", returns a {@link UnresolvedCxxPlatform}
   * backed by the specified target.
   */
  public static Optional<UnresolvedCxxPlatform> getProviderBasedPlatform(
      BuckConfig config, Flavor flavor, TargetConfiguration targetConfiguration) {
    String cxxSection = new CxxBuckConfig(config, flavor).cxxSection;

    Optional<BuildTarget> toolchainTarget =
        config.getBuildTarget(cxxSection, TOOLCHAIN_TARGET, targetConfiguration);
    if (!toolchainTarget.isPresent()) {
      return Optional.empty();
    }

    if (!cxxSection.equals(UNFLAVORED_CXX_SECTION)) {
      // In a flavored cxx section, we don't allow any configuration except for configuration of the
      // platform.
      ImmutableMap<String, String> allEntries = config.getEntriesForSection(cxxSection);
      if (allEntries.size() != 1) {
        throw new HumanReadableException(
            "When configuring a cxx %s, no other configuration is allowed in that section. Got unexpected keys [%s]",
            TOOLCHAIN_TARGET,
            Joiner.on(", ")
                .join(Sets.difference(allEntries.keySet(), ImmutableSet.of(TOOLCHAIN_TARGET))));
      }
    }

    return Optional.of(new ProviderBasedUnresolvedCxxPlatform(toolchainTarget.get(), flavor));
  }

  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractCxxToolProviderParams {

    public abstract String getSource();

    public abstract Optional<UnconfiguredBuildTargetView> getBuildTarget();

    public abstract Optional<PathSourcePath> getPath();

    public abstract Optional<CxxToolProvider.Type> getType();

    public abstract ToolType getToolType();

    public abstract Optional<Boolean> getPreferDependencyTree();

    @Value.Check
    protected void check() {
      Preconditions.checkState(getBuildTarget().isPresent() || getPath().isPresent());
      Preconditions.checkState(!getBuildTarget().isPresent() || getType().isPresent());
    }

    public PreprocessorProvider getPreprocessorProvider() {
      if (getBuildTarget().isPresent()) {
        return new PreprocessorProvider(
            new BinaryBuildRuleToolProvider(getBuildTarget().get(), getSource()),
            getType().get(),
            getToolType());
      } else {
        PathSourcePath path = getPath().get();
        return new PreprocessorProvider(
            new ConstantToolProvider(new HashedFileTool(path)),
            () -> getType().orElseGet(() -> CxxToolTypeInferer.getTypeFromPath(path)),
            getToolType());
      }
    }

    public CompilerProvider getCompilerProvider() {
      boolean preferDependencyTree = getPreferDependencyTree().orElse(false);
      if (getBuildTarget().isPresent()) {
        return new CompilerProvider(
            new BinaryBuildRuleToolProvider(getBuildTarget().get(), getSource()),
            getType().get(),
            getToolType(),
            preferDependencyTree);
      } else {
        PathSourcePath path = getPath().get();
        return new CompilerProvider(
            new ConstantToolProvider(new HashedFileTool(path)),
            () -> getType().orElseGet(() -> CxxToolTypeInferer.getTypeFromPath(path)),
            getToolType(),
            preferDependencyTree);
      }
    }
  }
}
