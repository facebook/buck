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

package com.facebook.buck.parser.options;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.Watchman;
import com.facebook.buck.io.WatchmanFactory;
import com.facebook.buck.io.filesystem.PathOrGlobMatcher;
import com.facebook.buck.rules.Description;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractProjectBuildFileParserOptions {
  abstract Path getProjectRoot();

  abstract ImmutableMap<String, Path> getCellRoots();

  abstract String getPythonInterpreter();

  abstract Optional<String> getPythonModuleSearchPath();

  abstract boolean getAllowEmptyGlobs();

  abstract ImmutableSet<PathOrGlobMatcher> getIgnorePaths();

  abstract String getBuildFileName();

  abstract List<String> getDefaultIncludes();

  abstract ImmutableSet<Description<?>> getDescriptions();

  abstract ImmutableMap<String, ImmutableMap<String, String>> getRawConfig();

  @Value.Default
  public String getCellName() {
    return "";
  }

  @Value.Default
  public boolean getUseWatchmanGlob() {
    return false;
  }

  @Value.Default
  public boolean getWatchmanGlobStatResults() {
    return false;
  }

  @Value.Default
  public boolean getWatchmanUseGlobGenerator() {
    return false;
  }

  @Value.Default
  Watchman getWatchman() {
    return WatchmanFactory.NULL_WATCHMAN;
  }

  @Value.Default
  public boolean getEnableProfiling() {
    return false;
  }

  abstract Optional<Long> getWatchmanQueryTimeoutMs();

  abstract List<String> getBuildFileImportWhitelist();

  @Value.Default
  public boolean getDisableImplicitNativeRules() {
    return false;
  }

  @Value.Default
  public boolean isWarnAboutDeprecatedSyntax() {
    return true;
  }
}
