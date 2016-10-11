/*
 * Copyright 2016-present Facebook, Inc.
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
package com.facebook.buck.parser;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.config.ConfigView;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

@Value.Immutable(builder = false, copy = false)
@BuckStyleImmutable
abstract class AbstractParserConfig implements ConfigView<BuckConfig> {

  public static final boolean DEFAULT_ALLOW_EMPTY_GLOBS = true;
  public static final String DEFAULT_BUILD_FILE_NAME = "BUCK";
  public static final String BUILDFILE_SECTION_NAME = "buildfile";
  public static final String INCLUDES_PROPERTY_NAME = "includes";

  private static final long NUM_PARSING_THREADS_DEFAULT = 1L;

  public enum GlobHandler {
    PYTHON,
    WATCHMAN,
    ;
  }

  public enum WatchmanGlobSanityCheck {
    NONE,
    STAT,
    ;
  }

  public enum AllowSymlinks {
    ALLOW,
    WARN,
    FORBID,
    ;
  }

  public enum BuildFileSearchMethod {
    FILESYSTEM_CRAWL,
    WATCHMAN,
    ;
  }

  /**
   * Controls whether default flavors should be applied to unflavored targets.
   */
  public enum ApplyDefaultFlavorsMode {
    ENABLED,
    DISABLED
  }

  @Value.Lazy
  public boolean getAllowEmptyGlobs() {
    return getDelegate()
        .getValue("build", "allow_empty_globs")
        .transform(
            Boolean::parseBoolean)
        .or(DEFAULT_ALLOW_EMPTY_GLOBS);
  }

  @Value.Lazy
  public String getBuildFileName() {
    return getDelegate().getValue(BUILDFILE_SECTION_NAME, "name").or(DEFAULT_BUILD_FILE_NAME);
  }

  /**
   * A (possibly empty) sequence of paths to files that should be included by default when
   * evaluating a build file.
   */
  @Value.Lazy
  public Iterable<String> getDefaultIncludes() {
    ImmutableMap<String, String> entries =
        getDelegate().getEntriesForSection(BUILDFILE_SECTION_NAME);
    String includes = Strings.nullToEmpty(entries.get("includes"));
    return Splitter.on(' ').trimResults().omitEmptyStrings().split(includes);
  }

  @Value.Lazy
  public boolean getEnforceBuckPackageBoundary() {
    return getDelegate().getBooleanValue("project", "check_package_boundary", true);
  }

  @Value.Lazy
  public ImmutableSet<Pattern> getTempFilePatterns() {
    return FluentIterable
        .from(getDelegate().getListWithoutComments("project", "temp_files"))
        .transform(
            new Function<String, Pattern>() {
              @Nullable
              @Override
              public Pattern apply(String input) {
                return Pattern.compile(input);
              }
            })
        .toSet();
  }

  @Value.Lazy
  public Optional<ImmutableList<Path>> getReadOnlyPaths() {
    return getDelegate().getOptionalPathList("project", "read_only_paths");
  }

  @Value.Lazy
  public AllowSymlinks getAllowSymlinks() {
    return getDelegate().getEnum("project", "allow_symlinks", AllowSymlinks.class)
        .or(AllowSymlinks.WARN);
  }

  @Value.Lazy
  public Optional<BuildFileSearchMethod> getBuildFileSearchMethod() {
    return
        getDelegate().getEnum("project", "build_file_search_method", BuildFileSearchMethod.class);
  }

  @Value.Lazy
  public GlobHandler getGlobHandler() {
    return
        getDelegate().getEnum("project", "glob_handler", GlobHandler.class).or(GlobHandler.PYTHON);
  }

  @Value.Lazy
  public WatchmanGlobSanityCheck getWatchmanGlobSanityCheck() {
    return getDelegate()
        .getEnum("project", "watchman_glob_sanity_check", WatchmanGlobSanityCheck.class)
        .or(WatchmanGlobSanityCheck.STAT);
  }

  @Value.Lazy
  public Optional<Long> getWatchmanQueryTimeoutMs() {
    return getDelegate().getLong("project", "watchman_query_timeout_ms");
  }

  @Value.Lazy
  public boolean getEnableParallelParsing() {
    return getDelegate().getBooleanValue("project", "parallel_parsing", true);
  }

  @Value.Lazy
  public int getNumParsingThreads() {
    if (!getEnableParallelParsing()) {
      return 1;
    }

    int value = getDelegate()
        .getLong("project", "parsing_threads")
        .or(NUM_PARSING_THREADS_DEFAULT)
        .intValue();

    return Math.min(value, getDelegate().getNumThreads());
  }

  @Value.Lazy
  public ApplyDefaultFlavorsMode getDefaultFlavorsMode() {
    return getDelegate().getEnum("project", "default_flavors_mode", ApplyDefaultFlavorsMode.class)
        .or(ApplyDefaultFlavorsMode.ENABLED);
  }

  @Value.Lazy
  public boolean getEnableBuildFileSandboxing() {
    return getDelegate().getBooleanValue("project", "enable_build_file_sandboxing", false);
  }

  @Value.Lazy
  public ImmutableList<String> getBuildFileImportWhitelist() {
    return getDelegate().getListWithoutComments("project", "build_file_import_whitelist");
  }
}
