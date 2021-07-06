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

package com.facebook.buck.features.js;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableSet;

/** JS specific buck config */
@BuckStyleValue
public abstract class JsConfig implements ConfigView<BuckConfig> {

  private static final String SECTION = "js";

  private static final ImmutableSet<String> DEFAULT_ASSET_EXTENSIONS =
      // For convenience, matches the default value for resolver.assetExts in metro-config
      ImmutableSet.of(
          // Image formats
          "bmp",
          "gif",
          "jpg",
          "jpeg",
          "png",
          "psd",
          "svg",
          "webp",
          // Video formats
          "m4v",
          "mov",
          "mp4",
          "mpeg",
          "mpg",
          "webm",
          // Audio formats
          "aac",
          "aiff",
          "caf",
          "m4a",
          "mp3",
          "wav",
          // Document formats
          "html",
          "pdf",
          "yaml",
          "yml",
          // Font formats
          "otf",
          "ttf",
          // Archives (virtual files)
          "zip");

  private static final ImmutableSet<String> DEFAULT_ASSET_PLATFORMS =
      // For convenience, matches the default value for resolver.platforms in metro-config
      ImmutableSet.of("ios", "android", "windows", "web");

  public static JsConfig of(BuckConfig delegate) {
    return ImmutableJsConfig.ofImpl(delegate);
  }

  /**
   * Whether to treat unflavored js_library rules as dummy rules ( = not emit their js_file
   * dependencies).
   */
  public boolean getRequireTransformProfileFlavorForFiles() {
    return isEnabled("require_transform_profile_flavor_for_files");
  }

  private boolean isEnabled(String configKey) {
    return getDelegate().getBooleanValue(SECTION, configKey, false);
  }

  public ImmutableSet<String> getAssetExtensions() {
    return getDelegate()
        .getOptionalListWithoutComments(SECTION, "asset_extensions")
        .map(ImmutableSet::copyOf)
        .orElse(DEFAULT_ASSET_EXTENSIONS);
  }

  public ImmutableSet<String> getAssetPlatforms() {
    return getDelegate()
        .getOptionalListWithoutComments(SECTION, "asset_platforms")
        .map(ImmutableSet::copyOf)
        .orElse(DEFAULT_ASSET_PLATFORMS);
  }

  @Override
  public abstract BuckConfig getDelegate();
}
