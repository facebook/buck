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

package com.facebook.buck.apple;

import com.facebook.buck.apple.platform_type.ApplePlatformType;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.nio.file.Path;
import java.nio.file.Paths;

@BuckStyleValue
abstract class AppleBundleDestinations implements AddsToRuleKey {
  @AddToRuleKey(stringify = true)
  public abstract Path getMetadataPath();

  @AddToRuleKey(stringify = true)
  public abstract Path getResourcesPath();

  @AddToRuleKey(stringify = true)
  public abstract Path getExecutablesPath();

  @AddToRuleKey(stringify = true)
  public abstract Path getFrameworksPath();

  @AddToRuleKey(stringify = true)
  public abstract Path getPlugInsPath();

  @AddToRuleKey(stringify = true)
  public abstract Path getWatchAppPath();

  @AddToRuleKey(stringify = true)
  public abstract Path getHeadersPath();

  @AddToRuleKey(stringify = true)
  public abstract Path getModulesPath();

  @AddToRuleKey(stringify = true)
  public abstract Path getXPCServicesPath();

  @AddToRuleKey(stringify = true)
  public abstract Path getQuickLookPath();

  private static final Path OSX_CONTENTS_PATH = Paths.get("Contents");
  public static final AppleBundleDestinations OSX_DESTINATIONS =
      ImmutableAppleBundleDestinations.of(
          OSX_CONTENTS_PATH,
          OSX_CONTENTS_PATH.resolve("Resources"),
          OSX_CONTENTS_PATH.resolve("MacOS"),
          OSX_CONTENTS_PATH.resolve("Frameworks"),
          OSX_CONTENTS_PATH.resolve("PlugIns"),
          OSX_CONTENTS_PATH,
          OSX_CONTENTS_PATH,
          OSX_CONTENTS_PATH,
          OSX_CONTENTS_PATH.resolve("XPCServices"),
          OSX_CONTENTS_PATH.resolve("Library/QuickLook"));

  private static final Path OSX_FRAMEWORK_CONTENTS_PATH = Paths.get("");
  public static final AppleBundleDestinations OSX_FRAMEWORK_DESTINATIONS =
      ImmutableAppleBundleDestinations.of(
          OSX_FRAMEWORK_CONTENTS_PATH.resolve("Resources"),
          OSX_FRAMEWORK_CONTENTS_PATH.resolve("Resources"),
          OSX_FRAMEWORK_CONTENTS_PATH,
          OSX_FRAMEWORK_CONTENTS_PATH.resolve("Frameworks"),
          OSX_FRAMEWORK_CONTENTS_PATH,
          OSX_FRAMEWORK_CONTENTS_PATH,
          OSX_FRAMEWORK_CONTENTS_PATH.resolve("Headers"),
          OSX_FRAMEWORK_CONTENTS_PATH.resolve("Modules"),
          OSX_FRAMEWORK_CONTENTS_PATH.resolve("XPCServices"),
          OSX_FRAMEWORK_CONTENTS_PATH);

  private static final Path IOS_CONTENTS_PATH = Paths.get("");
  public static final AppleBundleDestinations IOS_DESTINATIONS =
      ImmutableAppleBundleDestinations.of(
          IOS_CONTENTS_PATH,
          IOS_CONTENTS_PATH,
          IOS_CONTENTS_PATH,
          IOS_CONTENTS_PATH.resolve("Frameworks"),
          IOS_CONTENTS_PATH.resolve("PlugIns"),
          IOS_CONTENTS_PATH.resolve("Watch"),
          IOS_CONTENTS_PATH,
          IOS_CONTENTS_PATH,
          IOS_CONTENTS_PATH.resolve("XPCServices"),
          IOS_CONTENTS_PATH.resolve("Library/QuickLook"));

  private static final Path IOS_FRAMEWORK_CONTENTS_PATH = Paths.get("");
  public static final AppleBundleDestinations IOS_FRAMEWORK_DESTINATIONS =
      ImmutableAppleBundleDestinations.of(
          IOS_FRAMEWORK_CONTENTS_PATH,
          IOS_FRAMEWORK_CONTENTS_PATH,
          IOS_FRAMEWORK_CONTENTS_PATH,
          IOS_FRAMEWORK_CONTENTS_PATH.resolve("Frameworks"),
          IOS_FRAMEWORK_CONTENTS_PATH,
          IOS_FRAMEWORK_CONTENTS_PATH,
          IOS_FRAMEWORK_CONTENTS_PATH.resolve("Headers"),
          IOS_FRAMEWORK_CONTENTS_PATH.resolve("Modules"),
          IOS_FRAMEWORK_CONTENTS_PATH.resolve("XPCServices"),
          IOS_FRAMEWORK_CONTENTS_PATH);

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
