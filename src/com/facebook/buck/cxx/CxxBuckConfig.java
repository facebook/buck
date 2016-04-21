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

package com.facebook.buck.cxx;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BinaryBuildRuleToolProvider;
import com.facebook.buck.rules.RuleScheduleInfo;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.ToolProvider;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Contains platform independent settings for C/C++ rules.
 */
public class CxxBuckConfig {

  private static final String FLAVORED_CXX_SECTION_PREFIX = "cxx#";
  private static final String UNFLAVORED_CXX_SECTION_PREFIX = "cxx";

  private static final long DEFAULT_MAX_TEST_OUTPUT_SIZE = 8096;

  private final BuckConfig delegate;
  private final String cxxSection;

  /**
   * Constructs set of flavors given in a .buckconfig file, as is specified by section names
   * of the form cxx#{flavor name}.
   */
  public static ImmutableSet<Flavor> getCxxFlavors(BuckConfig config) {
    ImmutableSet.Builder<Flavor> builder = ImmutableSet.builder();
    ImmutableSet<String> sections = config.getSections();
    for (String section: sections) {
      if (section.startsWith(FLAVORED_CXX_SECTION_PREFIX)) {
        builder.add(ImmutableFlavor.of(section.substring(FLAVORED_CXX_SECTION_PREFIX.length())));
      }
    }
    return builder.build();
  }

  public CxxBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
    this.cxxSection = UNFLAVORED_CXX_SECTION_PREFIX;
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

  /**
   * @return the {@link BuildTarget} which represents the gtest library.
   */
  public BuildTarget getGtestDep() {
    return delegate.getRequiredBuildTarget(cxxSection, "gtest_dep");
  }

  /**
   * @return the {@link BuildTarget} which represents the main function that
   * gtest tests should use by default (if no other main is given).
   */
  public BuildTarget getGtestDefaultTestMainDep() {
    return delegate.getBuildTarget(cxxSection, "gtest_default_test_main_dep")
        .or(getGtestDep());
  }

  /**
   * @return the {@link BuildTarget} which represents the boost testing library.
   */
  public BuildTarget getBoostTestDep() {
    return delegate.getRequiredBuildTarget(cxxSection, "boost_test_dep");
  }

  public Optional<Path> getPath(String flavor, String name) {
    Optional<String> rawPath = delegate
        .getValue(cxxSection, flavor + "_" + name)
        .or(delegate.getValue(cxxSection, name));

    if (!rawPath.isPresent()) {
      return Optional.absent();
    }

    return Optional.fromNullable(
        delegate.resolvePathThatMayBeOutsideTheProjectFilesystem(Paths.get(rawPath.get())));
  }

  public Optional<String> getDefaultPlatform() {
    return delegate.getValue(cxxSection, "default_platform");
  }

  public Optional<String> getHostPlatform() {
    return delegate.getValue(cxxSection, "host_platform");
  }

  public Optional<ImmutableList<String>> getFlags(
      String field) {
    Optional<String> value = delegate.getValue(cxxSection, field);
    if (!value.isPresent()) {
      return Optional.absent();
    }
    ImmutableList.Builder<String> split = ImmutableList.builder();
    if (!value.get().trim().isEmpty()) {
      split.addAll(Splitter.on(" ").split(value.get().trim()));
    }
    return Optional.of(split.build());
  }

  public CxxPreprocessMode getPreprocessMode() {
    Optional<CxxPreprocessMode> setting = delegate.getEnum(
      cxxSection, "preprocess_mode", CxxPreprocessMode.class);

    if (setting.isPresent()) {
      return setting.get();
    }

    // Support legacy configuration option
    return delegate.getBooleanValue(
            cxxSection,
            "combined_preprocess_and_compile",
            /* default*/ false)
        ? CxxPreprocessMode.COMBINED
        : CxxPreprocessMode.SEPARATE;
  }

  /*
   * Constructs the appropriate Archiver for the specified platform.
   */
  public Optional<Archiver> getArchiver(Tool ar) {
    Optional<Platform> archiverPlatform = delegate
        .getEnum(cxxSection, "archiver_platform", Platform.class);
    if (!archiverPlatform.isPresent()) {
      return Optional.absent();
    }
    Archiver result;
    switch (archiverPlatform.get()) {
      case MACOS:
        result = new BsdArchiver(ar);
        break;
      case LINUX:
      case WINDOWS:
        result = new GnuArchiver(ar);
        break;
      case UNKNOWN:
        result = new UnknownArchiver(ar);
        break;
      default:
        throw new RuntimeException(
            "Invalid platform for archiver. Must be one of {MACOS, LINUX, WINDOWS, UNKNOWN}");
    }
    return Optional.of(result);
  }

  /**
   * @return the maximum size in bytes of test output to report in test results.
   */
  public long getMaximumTestOutputSize() {
    return delegate.getLong(cxxSection, "max_test_output_size").or(DEFAULT_MAX_TEST_OUTPUT_SIZE);
  }

  private Optional<CxxToolProviderParams> getCxxToolProviderParams(
      String field,
      Optional<CxxToolProvider.Type> defaultType) {
    Optional<String> value = delegate.getValue(cxxSection, field);
    if (!value.isPresent()) {
      return Optional.absent();
    }
    String source = String.format("[%s] %s", cxxSection, field);
    Optional<BuildTarget> target = delegate.getMaybeBuildTarget(cxxSection, field);
    Optional<CxxToolProvider.Type> type =
        delegate.getEnum(cxxSection, field + "_type", CxxToolProvider.Type.class)
            .or(defaultType);
    if (target.isPresent()) {
      return Optional.of(
          CxxToolProviderParams.builder()
              .setSource(source)
              .setBuildTarget(target.get())
              .setType(type.or(CxxToolProvider.Type.DEFAULT))
              .build());
    } else {
      return Optional.of(
          CxxToolProviderParams.builder()
              .setSource(source)
              .setPath(delegate.getRequiredPath(cxxSection, field))
              .setType(type)
              .build());
    }
  }

  private Optional<PreprocessorProvider> getPreprocessorProvider(
      Flavor flavor,
      String field,
      Optional<CxxToolProvider.Type> defaultType) {
    Optional<CxxToolProviderParams> params =
        getCxxToolProviderParams(flavor.toString() + '_' + field, defaultType)
            .or(getCxxToolProviderParams(field, defaultType));
    if (!params.isPresent()) {
      return Optional.absent();
    }
    return Optional.of(params.get().getPreprocessorProvider());
  }

  public Optional<PreprocessorProvider> getPreprocessorProvider(Flavor flavor, String field) {
    return getPreprocessorProvider(flavor, field, Optional.<CxxToolProvider.Type>absent());
  }

  public Optional<PreprocessorProvider> getPreprocessorProvider(
      Flavor flavor,
      String field,
      CxxToolProvider.Type defaultType) {
    return getPreprocessorProvider(flavor, field, Optional.of(defaultType));
  }

  private Optional<CompilerProvider> getCompilerProvider(
      Flavor flavor,
      String field,
      Optional<CxxToolProvider.Type> defaultType) {
    Optional<CxxToolProviderParams> params =
        getCxxToolProviderParams(flavor.toString() + '_' + field, defaultType)
            .or(getCxxToolProviderParams(field, defaultType));
    if (!params.isPresent()) {
      return Optional.absent();
    }
    return Optional.of(params.get().getCompilerProvider());
  }

  public Optional<CompilerProvider> getCompilerProvider(Flavor flavor, String field) {
    return getCompilerProvider(flavor, field, Optional.<CxxToolProvider.Type>absent());
  }

  public Optional<CompilerProvider> getCompilerProvider(
      Flavor flavor,
      String field,
      CxxToolProvider.Type defaultType) {
    return getCompilerProvider(flavor, field, Optional.of(defaultType));
  }

  public Optional<LinkerProvider> getLinkerProvider(
      Flavor flavor,
      String field,
      LinkerProvider.Type defaultType) {
    Optional<ToolProvider> toolProvider =
        delegate.getToolProvider(cxxSection, flavor.toString() + '_' + field)
            .or(delegate.getToolProvider(cxxSection, field));
    if (!toolProvider.isPresent()) {
      return Optional.absent();
    }
    Optional<LinkerProvider.Type> type =
        delegate.getEnum(cxxSection, "linker_platform", LinkerProvider.Type.class);
    return Optional.<LinkerProvider>of(
        new DefaultLinkerProvider(type.or(defaultType), toolProvider.get()));
  }

  public HeaderVerification getHeaderVerification() {
    return HeaderVerification.builder()
        .setMode(
            delegate.getEnum(cxxSection, "untracked_headers", HeaderVerification.Mode.class)
                .or(HeaderVerification.Mode.IGNORE))
        .addAllWhitelist(delegate.getListWithoutComments(cxxSection, "untracked_headers_whitelist"))
        .build();
  }

  public Optional<RuleScheduleInfo> getLinkScheduleInfo() {
    Optional<Long> linkWeight = delegate.getLong(cxxSection, "link_weight");
    if (!linkWeight.isPresent()) {
      return Optional.absent();
    }
    return Optional.of(
        RuleScheduleInfo.builder()
            .setJobsMultiplier(linkWeight.get().intValue())
            .build());
  }

  public boolean shouldCacheLinks() {
    return delegate.getBooleanValue(cxxSection, "cache_links", true);
  }

  public Archive.Contents getArchiveContents() {
    return delegate.getEnum(cxxSection, "archive_contents", Archive.Contents.class)
        .or(Archive.Contents.NORMAL);
  }

  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractCxxToolProviderParams {

    public abstract String getSource();
    public abstract Optional<BuildTarget> getBuildTarget();
    public abstract Optional<Path> getPath();
    public abstract Optional<CxxToolProvider.Type> getType();

    @Value.Check
    protected void check() {
      Preconditions.checkState(getBuildTarget().isPresent() || getPath().isPresent());
      Preconditions.checkState(!getBuildTarget().isPresent() || getType().isPresent());
    }

    public PreprocessorProvider getPreprocessorProvider() {
      if (getBuildTarget().isPresent()) {
        return new PreprocessorProvider(
            new BinaryBuildRuleToolProvider(getBuildTarget().get(), getSource()),
            getType().get());
      } else {
        return new PreprocessorProvider(getPath().get(), getType());
      }
    }

    public CompilerProvider getCompilerProvider() {
      if (getBuildTarget().isPresent()) {
        return new CompilerProvider(
            new BinaryBuildRuleToolProvider(getBuildTarget().get(), getSource()),
            getType().get());
      } else {
        return new CompilerProvider(getPath().get(), getType());
      }
    }

  }

}
