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

package com.facebook.buck.features.project.intellij.moduleinfo;

import com.google.common.collect.Maps;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/** A command line tool to look up ModuleInfos given a module name */
public class LookupTool {
  /** Entry point */
  public static void main(String[] args) throws IOException {
    if (args.length < 2) {
      System.err.println("Usage: lookuptool <key> <path/to/.idea>");
      System.exit(1);
    }

    String key = args[0];
    Path path = Paths.get(args[1]);

    Map<String, ModuleInfo> moduleInfoMap =
        Maps.uniqueIndex(new ModuleInfoBinaryIndex(path).read(), ModuleInfo::getModuleName);
    if (moduleInfoMap.containsKey(key)) {
      System.out.println(moduleInfoMap.get(key));
    } else {
      printMatches(moduleInfoMap, key);
    }
  }

  private static void printMatches(Map<String, ModuleInfo> moduleInfoMap, String key) {
    System.out.println("Can not find an exact match. Printing similar results...");
    for (Map.Entry<String, ModuleInfo> entry : moduleInfoMap.entrySet()) {
      if (entry.getKey().contains(key)) {
        System.out.println(entry.getValue());
      }
    }
  }
}
