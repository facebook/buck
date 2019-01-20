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

import com.facebook.buck.apple.platform_type.ApplePlatformType;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractAppleBundleDestinations implements AddsToRuleKey {
  @AddToRuleKey(stringify = true)
  @Value.Parameter
  public abstract Path getMetadataPath();

  @AddToRuleKey(stringify = true)
  @Value.Parameter
  public abstract Path getResourcesPath();

  @AddToRuleKey(stringify = true)
  @Value.Parameter
  public abstract Path getExecutablesPath();

  @AddToRuleKey(stringify = true)
  @Value.Parameter
  public abstract Path getFrameworksPath();

  @AddToRuleKey(stringify = true)
  @Value.Parameter
  public abstract Path getPlugInsPath();

  @AddToRuleKey(stringify = true)
  @Value.Parameter
  public abstract Path getWatchAppPath();

  @AddToRuleKey(stringify = true)
  @Value.Parameter
  public abstract Path getHeadersPath();

  @AddToRuleKey(stringify = true)
  @Value.Parameter
  public abstract Path getModulesPath();

  @AddToRuleKey(stringify = true)
  @Value.Parameter
  public abstract Path getXPCServicesPath();

  @AddToRuleKey(stringify = true)
  @Value.Parameter
  public abstract Path getQuickLookPath();

  private static final Path OSX_CONTENTS_PATH = Paths.get("Contents");
  public static final AppleBundleDestinations OSX_DESTINATIONS =
      AppleBundleDestinations.builder()
          .setMetadataPath(OSX_CONTENTS_PATH)
          .setResourcesPath(OSX_CONTENTS_PATH.resolve("Resources"))
          .setExecutablesPath(OSX_CONTENTS_PATH.resolve("MacOS"))
          .setFrameworksPath(OSX_CONTENTS_PATH.resolve("Frameworks"))
          .setPlugInsPath(OSX_CONTENTS_PATH.resolve("PlugIns"))
          .setWatchAppPath(OSX_CONTENTS_PATH)
          .setHeadersPath(OSX_CONTENTS_PATH)
          .setModulesPath(OSX_CONTENTS_PATH)
          .setXPCServicesPath(OSX_CONTENTS_PATH.resolve("XPCServices"))
          .setQuickLookPath(OSX_CONTENTS_PATH.resolve("Library/QuickLook"))
          .build();

  private static final Path OSX_FRAMEWORK_CONTENTS_PATH = Paths.get("");
  public static final AppleBundleDestinations OSX_FRAMEWORK_DESTINATIONS =
      AppleBundleDestinations.builder()
          .setMetadataPath(OSX_FRAMEWORK_CONTENTS_PATH.resolve("Resources"))
          .setResourcesPath(OSX_FRAMEWORK_CONTENTS_PATH.resolve("Resources"))
          .setExecutablesPath(OSX_FRAMEWORK_CONTENTS_PATH)
          .setFrameworksPath(OSX_FRAMEWORK_CONTENTS_PATH.resolve("Frameworks"))
          .setPlugInsPath(OSX_FRAMEWORK_CONTENTS_PATH)
          .setWatchAppPath(OSX_FRAMEWORK_CONTENTS_PATH)
          .setHeadersPath(OSX_FRAMEWORK_CONTENTS_PATH.resolve("Headers"))
          .setModulesPath(OSX_FRAMEWORK_CONTENTS_PATH.resolve("Modules"))
          .setXPCServicesPath(OSX_FRAMEWORK_CONTENTS_PATH.resolve("XPCServices"))
          .setQuickLookPath(OSX_FRAMEWORK_CONTENTS_PATH)
          .build();

  private static final Path IOS_CONTENTS_PATH = Paths.get("");
  public static final AppleBundleDestinations IOS_DESTINATIONS =
      AppleBundleDestinations.builder()
          .setMetadataPath(IOS_CONTENTS_PATH)
          .setResourcesPath(IOS_CONTENTS_PATH)
          .setExecutablesPath(IOS_CONTENTS_PATH)
          .setFrameworksPath(IOS_CONTENTS_PATH.resolve("Frameworks"))
          .setPlugInsPath(IOS_CONTENTS_PATH.resolve("PlugIns"))
          .setWatchAppPath(IOS_CONTENTS_PATH.resolve("Watch"))
          .setHeadersPath(IOS_CONTENTS_PATH)
          .setModulesPath(IOS_CONTENTS_PATH)
          .setXPCServicesPath(IOS_CONTENTS_PATH.resolve("XPCServices"))
          .setQuickLookPath(IOS_CONTENTS_PATH.resolve("Library/QuickLook"))
          .build();

  private static final Path IOS_FRAMEWORK_CONTENTS_PATH = Paths.get("");
  public static final AppleBundleDestinations IOS_FRAMEWORK_DESTINATIONS =
      AppleBundleDestinations.builder()
          .setMetadataPath(IOS_FRAMEWORK_CONTENTS_PATH)
          .setResourcesPath(IOS_FRAMEWORK_CONTENTS_PATH)
          .setExecutablesPath(IOS_FRAMEWORK_CONTENTS_PATH)
          .setFrameworksPath(IOS_FRAMEWORK_CONTENTS_PATH.resolve("Frameworks"))
          .setPlugInsPath(IOS_FRAMEWORK_CONTENTS_PATH)
          .setWatchAppPath(IOS_FRAMEWORK_CONTENTS_PATH)
          .setHeadersPath(IOS_FRAMEWORK_CONTENTS_PATH.resolve("Headers"))
          .setModulesPath(IOS_FRAMEWORK_CONTENTS_PATH.resolve("Modules"))
          .setXPCServicesPath(IOS_FRAMEWORK_CONTENTS_PATH.resolve("XPCServices"))
          .setQuickLookPath(IOS_FRAMEWORK_CONTENTS_PATH)
          .build();

  public static AppleBundleDestinations platformDestinations(ApplePlatform platform) {
    if (platform.getType() == ApplePlatformType.MAC) {
      return AppleBundleDestinations.OSX_DESTINATIONS;
    } else {
      return AppleBundleDestinations.IOS_DESTINATIONS;
    }
  }

  public static AppleBundleDestinations platformFrameworkDestinations(ApplePlatform platform) {
    if (platform.getType() == ApplePlatformType.MAC) {
      return AppleBundleDestinations.OSX_FRAMEWORK_DESTINATIONS;
    } else {
      return AppleBundleDestinations.IOS_FRAMEWORK_DESTINATIONS;
    }
  }
}
