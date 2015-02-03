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
import com.facebook.buck.python.PythonBuckConfig;
import com.google.common.base.Function;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.regex.Pattern;

import javax.annotation.Nullable;

public class ParserConfig {
  private static final String DEFAULT_ALLOW_EMPTY_GLOBS = "true";
  private static final String DEFAULT_BUILD_FILE_NAME = "BUCK";

  private final BuckConfig delegate;
  private final PythonBuckConfig pythonBuckConfig;

  public ParserConfig(BuckConfig delegate) {
    this.delegate = delegate;
    this.pythonBuckConfig = new PythonBuckConfig(delegate);
  }

  public boolean getAllowEmptyGlobs() {
    return Boolean.parseBoolean(
        delegate.getValue("build", "allow_empty_globs").or(DEFAULT_ALLOW_EMPTY_GLOBS)
    );
  }

  public String getBuildFileName() {
    return delegate.getValue("buildfile", "name").or(DEFAULT_BUILD_FILE_NAME);
  }

  /**
   * A (possibly empty) sequence of paths to files that should be included by default when
   * evaluating a build file.
   */
  public Iterable<String> getDefaultIncludes() {
    ImmutableMap<String, String> entries = delegate.getEntriesForSection("buildfile");
    String includes = Strings.nullToEmpty(entries.get("includes"));
    return Splitter.on(' ').trimResults().omitEmptyStrings().split(includes);
  }

  public boolean getEnforceBuckPackageBoundary() {
    return delegate.getBooleanValue("project", "check_package_boundary", true);
  }

  public String getPythonInterpreter() {
    return pythonBuckConfig.getPythonInterpreter();
  }

  public ImmutableSet<Pattern> getTempFilePatterns() {
    return FluentIterable
        .from(delegate.asListWithoutComments(delegate.getValue("project", "temp_files")))
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

}
