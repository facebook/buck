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

package com.facebook.buck.versions;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.Optional;

public class VersionBuckConfig {

  private static final String UNIVERSES_SECTION = "version_universes";
  private static final long DEFAULT_TIMEOUT = 20;

  private final BuckConfig delegate;

  public VersionBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  private VersionUniverse getVersionUniverse(
      String name, Optional<TargetConfiguration> targetConfiguration) {
    VersionUniverse.Builder universe = VersionUniverse.builder();
    ImmutableList<String> vals = delegate.getListWithoutComments(UNIVERSES_SECTION, name);
    for (String val : vals) {
      List<String> parts = Splitter.on('=').limit(2).trimResults().splitToList(val);
      if (parts.size() != 2) {
        throw new HumanReadableException(
            "`%s:%s`: must specify version selections as a comma-separated list of "
                + "`//build:target=<version>` pairs: \"%s\"",
            UNIVERSES_SECTION, name, val);
      }
      // TODO(nga): ignores default_target_platform and configuration detector
      universe.putVersions(
          delegate.getBuildTargetForFullyQualifiedTarget(
              parts.get(0), targetConfiguration.orElse(UnconfiguredTargetConfiguration.INSTANCE)),
          Version.of(parts.get(1)));
    }
    return universe.build();
  }

  public ImmutableMap<String, VersionUniverse> getVersionUniverses(
      Optional<TargetConfiguration> targetConfiguration) {
    ImmutableSet<String> entries = delegate.getEntriesForSection(UNIVERSES_SECTION).keySet();
    ImmutableMap.Builder<String, VersionUniverse> universes =
        ImmutableMap.builderWithExpectedSize(entries.size());
    for (String name : entries) {
      universes.put(name, getVersionUniverse(name, targetConfiguration));
    }
    return universes.build();
  }

  public long getVersionTargetGraphTimeoutSeconds() {
    return delegate.getLong("build", "version_tg_timeout").orElse(DEFAULT_TIMEOUT);
  }
}
