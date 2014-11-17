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

package com.facebook.buck.apple.xcode.xcspec;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListParser;
import com.google.common.collect.ImmutableMap;

import java.io.InputStream;

/**
 * Parsing logic for SDKSettings property list file that ships with Xcode.
 */
public final class SDKSettings {
  /**
   * Key into the default properties dictionary for the default compiler identifier.
   */
  public static final String DEFAULT_COMPILER_KEY = "DEFAULT_COMPILER";
  private static final String DEFAULT_PROPERTIES_KEY = "DefaultProperties";

  // Utility class. Do not instantiate.
  private SDKSettings() { }

  /**
   * Parses the contents of the provided SDKSettings.plist input stream
   * and extracts the dictionary of DefaultProperties values.
   */
  public static void parseDefaultPropertiesFromPlist(
      InputStream sdkSettingsPlist,
      ImmutableMap.Builder<String, String> defaultPropertiesBuilder)
      throws Exception {
    NSObject sdkSettings = PropertyListParser.parse(sdkSettingsPlist);
    if (!(sdkSettings instanceof NSDictionary)) {
      throw new RuntimeException(
          "Unexpected SDKSettings.plist contents (expected NSDictionary, got " + sdkSettings + ")");
    }
    getDefaultPropertiesFromNSDictionary((NSDictionary) sdkSettings, defaultPropertiesBuilder);
  }

  private static void getDefaultPropertiesFromNSDictionary(
      NSDictionary sdkSettingsDict,
      ImmutableMap.Builder<String, String> defaultPropertiesBuilder) {
    NSObject defaultProperties = sdkSettingsDict.objectForKey(DEFAULT_PROPERTIES_KEY);
    if (!(defaultProperties instanceof NSDictionary)) {
      throw new RuntimeException(
          "Unexpected " + DEFAULT_PROPERTIES_KEY + " contents (expected NSDictionary, got " +
          defaultProperties + ")");
    }
    NSDictionary defaultPropertiesDict = (NSDictionary) defaultProperties;
    for (String key : defaultPropertiesDict.allKeys()) {
      NSObject value = defaultPropertiesDict.objectForKey(key);
      if (!(value instanceof NSString)) {
        throw new RuntimeException(
            "Unexpected key " + key + " contents (expected NSString, got " +
            value + ")");
      }
      NSString stringValue = (NSString) value;
      defaultPropertiesBuilder.put(key, stringValue.toString());
    }
  }
}
