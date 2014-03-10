/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.google.common.collect.ImmutableMap;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for ProGuard-generated mapping files.  Currently only handles class mapping.
 */
public class ProguardMapping {

  /** Utility class: do not instantiate. */
  private ProguardMapping() {}

  private static final Pattern CLASS_LINE_PATTERN = Pattern.compile("([\\w.$]+) -> ([\\w.$]+):");

  public static Map<String, String> readClassMapping(Iterable<String> lines) {
    ImmutableMap.Builder<String, String> classMappingBuilder = ImmutableMap.builder();

    for (String line : lines) {
      if (line.charAt(0) == ' ') {
        // This is a member mapping, which we don't handle yet.
        continue;
      }

      Matcher matcher = CLASS_LINE_PATTERN.matcher(line);
      if (!matcher.matches()) {
        throw new IllegalArgumentException("Invalid line in proguard mapping: " + line);
      }

      classMappingBuilder.put(matcher.group(1), matcher.group(2));
    }

    return classMappingBuilder.build();
  }
}
