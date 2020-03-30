/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.parser.options;

import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.rules.providers.impl.BuiltInProvider;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.io.filesystem.PathMatcher;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.parser.implicit.ImplicitInclude;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.Optional;
import org.immutables.value.Value;

@BuckStyleValueWithBuilder
public abstract class ProjectBuildFileParserOptions {
  public abstract AbsPath getProjectRoot();

  public abstract ImmutableMap<String, AbsPath> getCellRoots();

  public abstract String getPythonInterpreter();

  public abstract Optional<String> getPythonModuleSearchPath();

  public abstract boolean getAllowEmptyGlobs();

  public abstract ImmutableSet<PathMatcher> getIgnorePaths();

  public abstract String getBuildFileName();

  public abstract List<String> getDefaultIncludes();

  public abstract ImmutableMap<String, ImplicitInclude> getPackageImplicitIncludes();

  public abstract ImmutableSet<BaseDescription<?>> getDescriptions();

  public abstract ImmutableMap<String, ImmutableMap<String, String>> getRawConfig();

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
  public Watchman getWatchman() {
    return WatchmanFactory.NULL_WATCHMAN;
  }

  @Value.Default
  public boolean getEnableProfiling() {
    return false;
  }

  public abstract Optional<Long> getWatchmanQueryTimeoutMs();

  public abstract List<String> getBuildFileImportWhitelist();

  public abstract ImmutableSet<BuiltInProvider<?>> getPerFeatureProviders();

  @Value.Default
  public ImplicitNativeRulesState getImplicitNativeRulesState() {
    return ImplicitNativeRulesState.ENABLED;
  }

  @Value.Default
  public UserDefinedRulesState getUserDefinedRulesState() {
    return UserDefinedRulesState.DISABLED;
  }

  @Value.Default
  public boolean isWarnAboutDeprecatedSyntax() {
    return true;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder extends ImmutableProjectBuildFileParserOptions.Builder {}
}
