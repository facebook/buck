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

package com.facebook.buck.parser;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

public class ParserConfig {
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

  public enum AllowSymlinks {
    ALLOW,
    FORBID,
    ;
  }

  public enum BuildFileSearchMethod {
    FILESYSTEM_CRAWL,
    WATCHMAN,
    ;
  }

  private final BuckConfig delegate;

  public ParserConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  public boolean getAllowEmptyGlobs() {
    return delegate
        .getValue("build", "allow_empty_globs")
        .transform(
            new Function<String, Boolean>() {
              @Override
              public Boolean apply(String input) {
                return Boolean.parseBoolean(input);
              }
            })
        .or(DEFAULT_ALLOW_EMPTY_GLOBS);
  }

  public String getBuildFileName() {
    return delegate.getValue(BUILDFILE_SECTION_NAME, "name").or(DEFAULT_BUILD_FILE_NAME);
  }

  /**
   * A (possibly empty) sequence of paths to files that should be included by default when
   * evaluating a build file.
   */
  public Iterable<String> getDefaultIncludes() {
    ImmutableMap<String, String> entries = delegate.getEntriesForSection(BUILDFILE_SECTION_NAME);
    String includes = Strings.nullToEmpty(entries.get("includes"));
    return Splitter.on(' ').trimResults().omitEmptyStrings().split(includes);
  }

  public boolean getEnforceBuckPackageBoundary() {
    return delegate.getBooleanValue("project", "check_package_boundary", true);
  }

  public ImmutableSet<Pattern> getTempFilePatterns() {
    return FluentIterable
        .from(delegate.getListWithoutComments("project", "temp_files"))
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

  public ImmutableSet<Path> getReadOnlyPaths() {
    return FluentIterable
        .from(delegate.getListWithoutComments("project", "read_only_paths"))
        .transform(
            new Function<String, Path>() {
              @Override
              public Path apply(String input) {
                Path path = Paths.get(input);
                if (Files.notExists(path)) {
                  throw new HumanReadableException("Path %s, specified under read_only_paths " +
                      "does not exist.", path);
                }
                return path;
              }
            })
        .toSet();
  }

  public AllowSymlinks getAllowSymlinks() {
    return delegate.getEnum("project", "allow_symlinks", AllowSymlinks.class)
        .or(AllowSymlinks.ALLOW);
  }

  public Optional<BuildFileSearchMethod> getBuildFileSearchMethod() {
    return delegate.getEnum("project", "build_file_search_method", BuildFileSearchMethod.class);
  }

  public GlobHandler getGlobHandler() {
    return delegate.getEnum("project", "glob_handler", GlobHandler.class).or(GlobHandler.PYTHON);
  }

  public Optional<Long> getWatchmanQueryTimeoutMs() {
    return delegate.getLong("project", "watchman_query_timeout_ms");
  }

  public boolean getEnableParallelParsing() {
    return delegate.getBooleanValue("project", "parallel_parsing", true);
  }

  public int getNumParsingThreads() {
    if (!getEnableParallelParsing()) {
      return 1;
    }

    int value = delegate
        .getLong("project", "parsing_threads")
        .or(NUM_PARSING_THREADS_DEFAULT)
        .intValue();

    return Math.min(value, delegate.getNumThreads());
  }
}
