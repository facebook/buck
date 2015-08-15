/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.intellij.plugin.build;

import com.google.common.collect.Sets;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class BuckBuildTargetAliasParser {

  private static final String ALIAS_PREFIX = "[";
  private static final String ALIAS_TAG = "[alias]";
  private static final String COMMENT_PREFIX = "#";
  private static final char SEPARATOR = '=';

  public static Map<String, Set<String>> sTargetAlias = new HashMap<String, Set<String>>();

  private BuckBuildTargetAliasParser() {
  }

  /**
   * Get all alias declared in buck config file.
   *
   * @param baseDir The root folder of the project which contains ".buckconfig"
   */
  public static void parseAlias(String baseDir) {
    sTargetAlias.clear();
    String file = baseDir + File.separatorChar + BuckBuildUtil.BUCK_CONFIG_FILE;
    try {
      BufferedReader br = new BufferedReader(new FileReader(file));
      String line;
      boolean seenAliasTag = false;
      while ((line = br.readLine()) != null) {
        line = line.trim();
        if (!seenAliasTag) {
          if (line.startsWith(ALIAS_TAG)) {
            seenAliasTag = true;
          }
        } else {
          if (line.startsWith(COMMENT_PREFIX)) {
            // Ignore comments
            continue;
          } else if (line.startsWith(ALIAS_PREFIX)) {
            // Another tag
            break;
          } else {
            int separatorIndex = line.indexOf(SEPARATOR);
            if (separatorIndex == -1) {
              continue;
            }
            String alias = line.substring(0, separatorIndex).trim();
            String path = line.substring(separatorIndex + 1).trim();

            if (sTargetAlias.containsKey(path)) {
              sTargetAlias.get(path).add(alias);
            } else {
              sTargetAlias.put(path, Sets.newHashSet(alias));
            }
          }
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
