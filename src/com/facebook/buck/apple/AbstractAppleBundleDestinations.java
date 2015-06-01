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

package com.facebook.buck.apple;

import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.util.immutables.BuckStyleImmutable;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.nio.file.Paths;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractAppleBundleDestinations implements RuleKeyAppendable {
  @Value.Parameter
  public abstract Path getMetadataPath();

  @Value.Parameter
  public abstract Path getResourcesPath();

  @Value.Parameter
  public abstract Path getExecutablesPath();

  @Value.Parameter
  public abstract Path getFrameworksPath();

  @Value.Parameter
  public abstract Path getPlugInsPath();

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    return builder
        .setReflectively("metadata_path", getMetadataPath().toString())
        .setReflectively("resources_path", getResourcesPath().toString())
        .setReflectively("executables_path", getExecutablesPath().toString())
        .setReflectively("frameworks_path", getFrameworksPath().toString())
        .setReflectively("plugins_path", getPlugInsPath().toString());
  }

  private static final Path OSX_CONTENTS_PATH = Paths.get("Contents");
  public static final AppleBundleDestinations OSX_DESTINATIONS =
      AppleBundleDestinations.builder()
          .setMetadataPath(OSX_CONTENTS_PATH)
          .setResourcesPath(OSX_CONTENTS_PATH.resolve("Resources"))
          .setExecutablesPath(OSX_CONTENTS_PATH.resolve("MacOS"))
          .setFrameworksPath(OSX_CONTENTS_PATH.resolve("Frameworks"))
          .setPlugInsPath(OSX_CONTENTS_PATH.resolve("PlugIns"))
          .build();

  private static final Path IOS_CONTENTS_PATH = Paths.get("");
  public static final AppleBundleDestinations IOS_DESTINATIONS =
      AppleBundleDestinations.builder()
          .setMetadataPath(IOS_CONTENTS_PATH)
          .setResourcesPath(IOS_CONTENTS_PATH)
          .setExecutablesPath(IOS_CONTENTS_PATH)
          .setFrameworksPath(IOS_CONTENTS_PATH)
          .setPlugInsPath(IOS_CONTENTS_PATH)
          .build();

  public static AppleBundleDestinations platformDestinations(ApplePlatform platform) {
    if (platform.getName().contains("osx")) {
      return AppleBundleDestinations.OSX_DESTINATIONS;
    } else {
      return AppleBundleDestinations.IOS_DESTINATIONS;
    }
  }
}
