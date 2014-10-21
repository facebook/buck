/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.google.common.base.Optional;

import java.util.HashMap;

public class AndroidBuckConfig {

  private static final String DEFAULT_MANIFEST_TOKEN = "default_android_manifest_path";
  private static final String DEFAULT_MANIFEST_PATH_TOKEN = "default_android_manifest_path";
  private static final String DEFAULT_RESOURCES_PATH_TOKEN = "default_android_resource_path";
  private static final String DEFAULT_ASSETS_PATH_TOKEN = "default_android_assets_path";

  private final HashMap<String, Optional<String>> values;

  public AndroidBuckConfig(HashMap<String, Optional<String>> values) {
    this.values = values;
  }

  public static AndroidBuckConfig emptyAndroidConfig() {
    HashMap<String, Optional<String>> values = new HashMap<String, Optional<String>>();
    for (String token : getConfigTokens()) {
      values.put(token, Optional.<String>absent());
    }
    return new AndroidBuckConfig(values);
  }

  public static String[] getConfigTokens() {
    String[] supportedConfigProperties = {
        DEFAULT_MANIFEST_TOKEN,
        DEFAULT_MANIFEST_PATH_TOKEN,
        DEFAULT_RESOURCES_PATH_TOKEN,
        DEFAULT_ASSETS_PATH_TOKEN
    };
    return supportedConfigProperties;
  }

  public Optional<String> getDefaultManifest() {
    return values.get(DEFAULT_MANIFEST_TOKEN);
  }

  public String resolveManifestRelativePath() {
    return values.get(DEFAULT_MANIFEST_PATH_TOKEN).orNull();
  }

  public String getResourceDefaultRelativePath() {
    return values.get(DEFAULT_RESOURCES_PATH_TOKEN).orNull();
  }

  public String getAssetsDefaultRelativePath() {
    return values.get(DEFAULT_ASSETS_PATH_TOKEN).orNull();
  }
}
