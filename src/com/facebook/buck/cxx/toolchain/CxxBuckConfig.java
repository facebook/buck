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

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.UserFlavor;
import com.facebook.buck.core.rules.schedule.RuleScheduleInfo;
import com.facebook.buck.core.rules.type.BuildRuleType;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.toolchain.toolprovider.impl.BinaryBuildRuleToolProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.linker.DefaultLinkerProvider;
import com.facebook.buck.cxx.toolchain.linker.LinkerProvider;
import com.facebook.buck.rules.tool.config.ToolConfig;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
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
  private static final String MAX_TEST_OUTPUT_SIZE = "max_test_output_size";
  private static final String LINKER_PLATFORM = "linker_platform";
  private static final String UNTRACKED_HEADERS = "untracked_headers";
  private static final String UNTRACKED_HEADERS_WHITELIST = "untracked_headers_whitelist";
  private static final String EXPORTED_HEADERS_SYMLINKS_ENABLED =
      "exported_headers_symlinks_enabled";
  private static final String HEADERS_SYMLINKS_ENABLED = "headers_symlinks_enabled";
  private static final String LINK_WEIGHT = "link_weight";
  private static final String CACHE_LINKS = "cache_links";
  private static final String CACHE_BINARIES = "cache_binaries";
  private static final String PCH_ENABLED = "pch_enabled";
  private static final String SANDBOX_SOURCES = "sandbox_sources";
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
  private static final String SHARED_LIBRARY_EXT = "shared_library_extension";
  private static final String CONFLICTING_HEADER_BASENAME_WHITELIST =
      "conflicting_header_basename_whitelist";
  private static final String HEADER_MODE = "header_mode";

  private static final String ASFLAGS = "asflags";
  private static final String ASPPFLAGS = "asppflags";
  private static final String CFLAGS = "cflags";
  private static final String CXXFLAGS = "cxxflags";
  private static final String CPPFLAGS = "cppflags";
  private static final String CXXPPFLAGS = "cxxppflags";
  private static final String CUDAFLAGS = "cudaflags";
  private static final String CUDAPPFLAGS = "cudappflags";
  private static final String ASMFLAGS = "asmflags";
  private static final String ASMPPFLAGS = "asmppflags";
  private static final String LDFLAGS = "ldflags";
  private static final String ARFLAGS = "arflags";
  private static final String RANLIBFLAGS = "ranlibflags";

  private static final String AR = "ar";
  private static final String RANLIB = "ranlib";
  private static final String OBJCOPY = "objcopy";
  private static final String NM = "nm";
  private static final String STRIP = "strip";
  private static final String AS = "as";
  private static final String ASPP = "aspp";
  private static final String CC = "cc";
  private static final String CPP = "cpp";
  private static final String CXX = "cxx";
  private static final String CXXPP = "cxxpp";
  private static final String CUDA = "cuda";
  private static final String CUDAPP = "cudapp";
  private static final String ASM = "asm";
  private static final String ASMPP = "asmpp";
  private static final String LD = "ld";

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

  /** @return the environment in which {@link BuckConfig} was created. */
  public ImmutableMap<String, String> getEnvironment() {
    return delegate.getEnvironment();
  }

  /** @return the {@link BuildTarget} which represents the gtest library. */
  public Optional<BuildTarget> getGtestDep() {
    return delegate.getBuildTarget(cxxSection, GTEST_DEP);
  }

  /**
   * @return the {@link BuildTarget} which represents the main function that gtest tests should use
   *     by default (if no other main is given).
   */
  public Optional<BuildTarget> getGtestDefaultTestMainDep() {
    return delegate.getBuildTarget(cxxSection, GTEST_DEFAULT_TEST_MAIN_DEP);
  }

  /** @return the {@link BuildTarget} which represents the boost testing library. */
  public Optional<BuildTarget> getBoostTestDep() {
    return delegate.getBuildTarget(cxxSection, BOOST_TEST_DEP);
  }

  public Optional<Path> getPath(String name) {
    return delegate.getPath(cxxSection, name);
  }

  @Nullable
  public PathSourcePath getSourcePath(Path path) {
    return delegate.getPathSourcePath(path);
  }

  public Optional<SourcePath> getSourcePath(String name) {
    return delegate.getSourcePath(cxxSection, name);
  }

  public Optional<String> getDefaultPlatform() {
    return delegate.getValue(cxxSection, DEFAULT_PLATFORM);
  }

  public Optional<String> getHostPlatform() {
    return delegate.getValue(cxxSection, HOST_PLATFORM);
  }

  private Optional<ImmutableList<String>> getFlags(String field) {
    Optional<String> value = delegate.getValue(cxxSection, field);
    if (!value.isPresent()) {
      return Optional.empty();
    }
    return Optional.of(delegate.getListWithoutComments(cxxSection, field, ' '));
  }

  public Optional<ImmutableList<String>> getAsflags() {
    return getFlags(ASFLAGS);
  }

  public Optional<ImmutableList<String>> getAsppflags() {
    return getFlags(ASPPFLAGS);
  }

  public Optional<ImmutableList<String>> getCflags() {
    return getFlags(CFLAGS);
  }

  public Optional<ImmutableList<String>> getCxxflags() {
    return getFlags(CXXFLAGS);
  }

  public Optional<ImmutableList<String>> getCppflags() {
    return getFlags(CPPFLAGS);
  }

  public Optional<ImmutableList<String>> getCxxppflags() {
    return getFlags(CXXPPFLAGS);
  }

  public Optional<ImmutableList<String>> getCudaflags() {
    return getFlags(CUDAFLAGS);
  }

  public Optional<ImmutableList<String>> getCudappflags() {
    return getFlags(CUDAPPFLAGS);
  }

  public Optional<ImmutableList<String>> getAsmflags() {
    return getFlags(ASMFLAGS);
  }

  public Optional<ImmutableList<String>> getAsmppflags() {
    return getFlags(ASMPPFLAGS);
  }

  public Optional<ImmutableList<String>> getLdflags() {
    return getFlags(LDFLAGS);
  }

  public Optional<ImmutableList<String>> getArflags() {
    return getFlags(ARFLAGS);
  }

  public Optional<ImmutableList<String>> getRanlibflags() {
    return getFlags(RANLIBFLAGS);
  }

  /*
   * Constructs the appropriate Archiver for the specified platform.
   */
  public Optional<ArchiverProvider> getArchiverProvider(Platform defaultPlatform) {
    Optional<ToolProvider> toolProvider =
        delegate.getView(ToolConfig.class).getToolProvider(cxxSection, AR);
    return toolProvider.map(
        archiver -> {
          Optional<Platform> archiverPlatform =
              delegate.getEnum(cxxSection, ARCHIVER_PLATFORM, Platform.class);
          return ArchiverProvider.from(archiver, archiverPlatform.orElse(defaultPlatform));
        });
  }

  /** @return the maximum size in bytes of test output to report in test results. */
  public long getMaximumTestOutputSize() {
    return delegate.getLong(cxxSection, MAX_TEST_OUTPUT_SIZE).orElse(DEFAULT_MAX_TEST_OUTPUT_SIZE);
  }

  private Optional<CxxToolProviderParams> getCxxToolProviderParams(String field) {
    Optional<String> value = delegate.getValue(cxxSection, field);
    if (!value.isPresent()) {
      return Optional.empty();
    }
    String source = String.format("[%s] %s", cxxSection, field);
    Optional<BuildTarget> target = delegate.getMaybeBuildTarget(cxxSection, field);
    Optional<CxxToolProvider.Type> type =
        delegate.getEnum(cxxSection, field + "_type", CxxToolProvider.Type.class);
    if (target.isPresent()) {
      return Optional.of(
          CxxToolProviderParams.builder()
              .setSource(source)
              .setBuildTarget(target.get())
              .setType(type)
              .build());
    } else {
      return Optional.of(
          CxxToolProviderParams.builder()
              .setSource(source)
              .setPath(delegate.getPathSourcePath(delegate.getRequiredPath(cxxSection, field)))
              .setType(type)
              .build());
    }
  }

  private Optional<PreprocessorProvider> getPreprocessorProvider(String field) {
    return getCxxToolProviderParams(field)
        .map(AbstractCxxToolProviderParams::getPreprocessorProvider);
  }

  private Optional<CompilerProvider> getCompilerProvider(String field) {
    return getCxxToolProviderParams(field).map(AbstractCxxToolProviderParams::getCompilerProvider);
  }

  public Optional<CompilerProvider> getAs() {
    return getCompilerProvider(AS);
  }

  public Optional<PreprocessorProvider> getAspp() {
    return getPreprocessorProvider(ASPP);
  }

  public Optional<CompilerProvider> getCc() {
    return getCompilerProvider(CC);
  }

  public Optional<PreprocessorProvider> getCpp() {
    return getPreprocessorProvider(CPP);
  }

  public Optional<CompilerProvider> getCxx() {
    return getCompilerProvider(CXX);
  }

  public Optional<PreprocessorProvider> getCxxpp() {
    return getPreprocessorProvider(CXXPP);
  }

  public Optional<CompilerProvider> getCuda() {
    return getCompilerProvider(CUDA);
  }

  public Optional<PreprocessorProvider> getCudapp() {
    return getPreprocessorProvider(CUDAPP);
  }

  public Optional<CompilerProvider> getAsm() {
    return getCompilerProvider(ASM);
  }

  public Optional<PreprocessorProvider> getAsmpp() {
    return getPreprocessorProvider(ASMPP);
  }

  /**
   * Construct a linker based on `ld` and `linker_platform` sections in the config.
   *
   * @param defaultType the default type for a linker if `linker_platform` is not specified in the
   *     config.
   */
  public Optional<LinkerProvider> getLinkerProvider(LinkerProvider.Type defaultType) {
    Optional<ToolProvider> toolProvider =
        delegate.getView(ToolConfig.class).getToolProvider(cxxSection, LD);
    if (!toolProvider.isPresent()) {
      return Optional.empty();
    }
    Optional<LinkerProvider.Type> type =
        delegate.getEnum(cxxSection, LINKER_PLATFORM, LinkerProvider.Type.class);
    return Optional.of(new DefaultLinkerProvider(type.orElse(defaultType), toolProvider.get()));
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

  public boolean getPublicHeadersSymlinksEnabled() {
    return delegate.getBooleanValue(cxxSection, EXPORTED_HEADERS_SYMLINKS_ENABLED, true);
  }

  public boolean getPrivateHeadersSymlinksEnabled() {
    return delegate.getBooleanValue(cxxSection, HEADERS_SYMLINKS_ENABLED, true);
  }

  public Optional<RuleScheduleInfo> getLinkScheduleInfo() {
    Optional<Long> linkWeight = delegate.getLong(cxxSection, LINK_WEIGHT);
    if (!linkWeight.isPresent()) {
      return Optional.empty();
    }
    return Optional.of(
        RuleScheduleInfo.builder().setJobsMultiplier(linkWeight.get().intValue()).build());
  }

  public boolean shouldCacheLinks() {
    return delegate.getBooleanValue(cxxSection, CACHE_LINKS, true);
  }

  public boolean shouldCacheBinaries() {
    return delegate.getBooleanValue(cxxSection, CACHE_BINARIES, false);
  }

  public boolean isPCHEnabled() {
    return delegate.getBooleanValue(cxxSection, PCH_ENABLED, true);
  }

  public boolean sandboxSources() {
    return delegate.getBooleanValue(cxxSection, SANDBOX_SOURCES, false);
  }

  public ArchiveContents getArchiveContents() {
    return delegate
        .getEnum(cxxSection, ARCHIVE_CONTENTS, ArchiveContents.class)
        .orElse(ArchiveContents.NORMAL);
  }

  public ImmutableMap<String, Flavor> getDefaultFlavorsForRuleType(BuildRuleType type) {
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
    return getToolProvider(RANLIB);
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
    return delegate
        .getListWithoutComments(cxxSection, DECLARED_PLATFORMS)
        .stream()
        .map(s -> UserFlavor.of(s, String.format("Declared platform: %s", s)))
        .collect(ImmutableSet.toImmutableSet());
  }

  /** @return the extension to use for shared libraries (e.g. ".so"). */
  public Optional<String> getSharedLibraryExtension() {
    return delegate.getValue(cxxSection, SHARED_LIBRARY_EXT);
  }

  public ImmutableSortedSet<String> getConflictingHeaderBasenameWhitelist() {
    return ImmutableSortedSet.copyOf(
        delegate.getListWithoutComments(cxxSection, CONFLICTING_HEADER_BASENAME_WHITELIST));
  }

  /** @return the configured C/C++ header mode. */
  public Optional<HeaderMode> getHeaderMode() {
    return delegate.getEnum(cxxSection, HEADER_MODE, HeaderMode.class);
  }

  public BuckConfig getDelegate() {
    return delegate;
  }

  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractCxxToolProviderParams {

    public abstract String getSource();

    public abstract Optional<BuildTarget> getBuildTarget();

    public abstract Optional<PathSourcePath> getPath();

    public abstract Optional<CxxToolProvider.Type> getType();

    @Value.Check
    protected void check() {
      Preconditions.checkState(getBuildTarget().isPresent() || getPath().isPresent());
      Preconditions.checkState(!getBuildTarget().isPresent() || getType().isPresent());
    }

    public PreprocessorProvider getPreprocessorProvider() {
      if (getBuildTarget().isPresent()) {
        return new PreprocessorProvider(
            new BinaryBuildRuleToolProvider(getBuildTarget().get(), getSource()), getType().get());
      } else {
        return new PreprocessorProvider(getPath().get(), getType());
      }
    }

    public CompilerProvider getCompilerProvider() {
      if (getBuildTarget().isPresent()) {
        return new CompilerProvider(
            new BinaryBuildRuleToolProvider(getBuildTarget().get(), getSource()), getType().get());
      } else {
        return new CompilerProvider(getPath().get(), getType());
      }
    }
  }
}
