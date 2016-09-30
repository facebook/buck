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
package com.facebook.buck.versions;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.util.MoreStrings;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import java.util.Map;

public class VersionBuckConfig implements VersionConfig {

  private static final String UNIVERSE_SECTION_PREFIX = "version_universe#";

  private final BuckConfig delegate;

  public VersionBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  private VersionUniverse getVersionUniverse(String name) {
    VersionUniverse.Builder universe = VersionUniverse.builder();
    for (Map.Entry<String, String> ent :
         delegate.getEntriesForSection(UNIVERSE_SECTION_PREFIX + name).entrySet()) {
      universe.putVersions(
          delegate.getBuildTargetForFullyQualifiedTarget(ent.getKey()),
          Version.of(ent.getValue()));
    }
    return universe.build();
  }

  @Override
  public ImmutableMap<String, VersionUniverse> getVersionUniverses() {
    ImmutableMap.Builder<String, VersionUniverse> universes = ImmutableMap.builder();
    for (String section : delegate.getSections()) {
      Optional<String> name = MoreStrings.stripPrefix(section, UNIVERSE_SECTION_PREFIX);
      if (name.isPresent()) {
        universes.put(name.get(), getVersionUniverse(name.get()));
      }
    }
    return universes.build();
  }

}
