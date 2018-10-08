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

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.watchman.WatchmanWatcher;
import com.facebook.buck.parser.api.Syntax;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable(builder = false, copy = false)
@BuckStyleImmutable
abstract class AbstractParserConfig implements ConfigView<BuckConfig> {

  public static final boolean DEFAULT_ALLOW_EMPTY_GLOBS = true;
  public static final String DEFAULT_BUILD_FILE_NAME = "BUCK";
  public static final String BUILDFILE_SECTION_NAME = "buildfile";
  public static final String INCLUDES_PROPERTY_NAME = "includes";

  private static final long NUM_PARSING_THREADS_DEFAULT = 1L;
  private static final int TARGET_PARSER_THRESHOLD = 100000;

  public enum GlobHandler {
    PYTHON,
    WATCHMAN,
    ;
  }

  /** Glob handler supported by Skylark parser. */
  public enum SkylarkGlobHandler {
    JAVA,
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

  /** Controls whether default flavors should be applied to unflavored targets. */
  public enum ApplyDefaultFlavorsMode {
    DISABLED,
    SINGLE,
    ALL
  }

  @Override
  @Value.Parameter
  public abstract BuckConfig getDelegate();

  @Value.Lazy
  public boolean getAllowEmptyGlobs() {
    return getDelegate()
        .getValue("build", "allow_empty_globs")
        .map(Boolean::parseBoolean)
        .orElse(DEFAULT_ALLOW_EMPTY_GLOBS);
  }

  @Value.Lazy
  public String getBuildFileName() {
    return getDelegate().getValue(BUILDFILE_SECTION_NAME, "name").orElse(DEFAULT_BUILD_FILE_NAME);
  }

  /**
   * A (possibly empty) sequence of paths to files that should be included by default when
   * evaluating a build file.
   */
  @Value.Lazy
  public Iterable<String> getDefaultIncludes() {
    ImmutableMap<String, String> entries =
        getDelegate().getEntriesForSection(BUILDFILE_SECTION_NAME);
    String includes = Strings.nullToEmpty(entries.get(INCLUDES_PROPERTY_NAME));
    return Splitter.on(' ').trimResults().omitEmptyStrings().split(includes);
  }

  @Value.Lazy
  public boolean getEnforceBuckPackageBoundary() {
    return getDelegate().getBooleanValue("project", "check_package_boundary", true);
  }

  /** A list of absolute paths under which buck package boundary checks should not be performed. */
  @Value.Lazy
  public ImmutableList<Path> getBuckPackageBoundaryExceptions() {
    return getDelegate()
        .getOptionalPathList("project", "package_boundary_exceptions", true)
        .orElse(ImmutableList.of());
  }

  @Value.Lazy
  public Optional<ImmutableList<Path>> getReadOnlyPaths() {
    return getDelegate().getOptionalPathList("project", "read_only_paths", false);
  }

  @Value.Lazy
  public AllowSymlinks getAllowSymlinks() {
    return getDelegate()
        .getEnum("project", "allow_symlinks", AllowSymlinks.class)
        .orElse(AllowSymlinks.FORBID);
  }

  @Value.Lazy
  public BuildFileSearchMethod getBuildFileSearchMethod() {
    Optional<BuildFileSearchMethod> buildFileSearchMethod =
        getDelegate().getEnum("project", "build_file_search_method", BuildFileSearchMethod.class);
    if (buildFileSearchMethod.isPresent()) {
      return buildFileSearchMethod.get();
    } else if (getAllowSymlinks() == ParserConfig.AllowSymlinks.FORBID) {
      // If unspecified, only use Watchman in repositories which enforce a "no symlinks" rule
      // (Watchman doesn't follow symlinks).
      return BuildFileSearchMethod.WATCHMAN;
    } else {
      return BuildFileSearchMethod.FILESYSTEM_CRAWL;
    }
  }

  @Value.Lazy
  public GlobHandler getGlobHandler() {
    return getDelegate()
        .getEnum("project", "glob_handler", GlobHandler.class)
        .orElse(GlobHandler.PYTHON);
  }

  @Value.Lazy
  public WatchmanGlobSanityCheck getWatchmanGlobSanityCheck() {
    return getDelegate()
        .getEnum("project", "watchman_glob_sanity_check", WatchmanGlobSanityCheck.class)
        .orElse(WatchmanGlobSanityCheck.STAT);
  }

  @Value.Lazy
  public Optional<Long> getWatchmanQueryTimeoutMs() {
    return getDelegate().getLong("project", "watchman_query_timeout_ms");
  }

  @Value.Lazy
  public boolean getWatchCells() {
    return getDelegate().getBooleanValue("project", "watch_cells", true);
  }

  @Value.Lazy
  public WatchmanWatcher.CursorType getWatchmanCursor() {
    return getDelegate()
        .getEnum("project", "watchman_cursor", WatchmanWatcher.CursorType.class)
        .orElse(WatchmanWatcher.CursorType.CLOCK_ID);
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

    int value =
        getDelegate()
            .getLong("project", "parsing_threads")
            .orElse(NUM_PARSING_THREADS_DEFAULT)
            .intValue();

    return Math.min(value, getDelegate().getNumThreads());
  }

  @Value.Lazy
  public ApplyDefaultFlavorsMode getDefaultFlavorsMode() {
    return getDelegate()
        .getEnum("project", "default_flavors_mode", ApplyDefaultFlavorsMode.class)
        .orElse(ApplyDefaultFlavorsMode.SINGLE);
  }

  @Value.Lazy
  public ImmutableList<String> getBuildFileImportWhitelist() {
    return getDelegate().getListWithoutComments("project", "build_file_import_whitelist");
  }

  @Value.Lazy
  public Optional<String> getParserPythonInterpreterPath() {
    return getDelegate().getValue("parser", "python_interpreter");
  }

  /**
   * Returns the module search path PYTHONPATH to set for the parser, as specified by the
   * 'python_path' key of the 'parser' section.
   *
   * @return The PYTHONPATH value or an empty string if not set.
   */
  @Value.Lazy
  public Optional<String> getPythonModuleSearchPath() {
    return getDelegate().getValue("parser", "python_path");
  }

  /**
   * @return boolean flag indicating whether support for parsing build files using non default
   *     syntax (currently Python DSL).
   *     <p>For a list of supported syntax see {@link Syntax}.
   */
  @Value.Lazy
  public boolean isPolyglotParsingEnabled() {
    return getDelegate().getBooleanValue("parser", "polyglot_parsing_enabled", false);
  }

  /**
   * @return a syntax to assume for build files without explicit build file syntax marker. *
   *     <p>For a list of supported syntax see {@link Syntax}.
   */
  @Value.Lazy
  public Syntax getDefaultBuildFileSyntax() {
    return getDelegate()
        .getEnum("parser", "default_build_file_syntax", Syntax.class)
        .orElse(Syntax.PYTHON_DSL);
  }

  /**
   * @return whether native build rules are available for users in build files. If not, they are
   *     only accessible in extension files under the 'native' object
   */
  @Value.Lazy
  public boolean getDisableImplicitNativeRules() {
    return getDelegate().getBooleanValue("parser", "disable_implicit_native_rules", false);
  }

  /** @return whether Buck should warn about deprecated syntax. */
  @Value.Lazy
  public boolean isWarnAboutDeprecatedSyntax() {
    return getDelegate().getBooleanValue("parser", "warn_about_deprecated_syntax", true);
  }

  /** @return the type of the glob handler used by the Skylark parser. */
  @Value.Lazy
  public SkylarkGlobHandler getSkylarkGlobHandler() {
    return getDelegate()
        .getEnum("parser", "skylark_glob_handler", SkylarkGlobHandler.class)
        .orElse(SkylarkGlobHandler.JAVA);
  }

  /**
   * @return the parser target threshold. When the current targets produced exceed this value, a
   *     warning is emitted.
   */
  @Value.Lazy
  public int getParserTargetThreshold() {
    return getDelegate().getInteger("parser", "target_threshold").orElse(TARGET_PARSER_THRESHOLD);
  }

  @Value.Lazy
  public boolean getEnableConfigurableAttributes() {
    return getDelegate().getBooleanValue("parser", "enable_configurable_attributes", false);
  }
}
