/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A command line tool to look up ModuleInfos given a list of module name */
public class LookupTool {
  /** Entry point */
  public static void main(String[] args) throws IOException {
    if (args.length < 2) {
      System.err.println("Usage: lookuptool <path/to/.idea> <module name> <module name> ...");
      System.exit(1);
    }

    Path ideaPath = Paths.get(args[0]);
    List<String> modules = Arrays.asList(args).subList(1, args.length);

    Map<String, ModuleInfo> moduleInfoMap =
        Maps.uniqueIndex(new ModuleInfoBinaryIndex(ideaPath).read(), ModuleInfo::getModuleName);
    Map<String, Map<String, Object>> outputs = new HashMap<>();
    for (String module : modules) {
      Map<String, Object> outputForModule = new HashMap<>();
      if (moduleInfoMap.containsKey(module)) {
        outputForModule.put("success", true);
        outputForModule.put("results", moduleInfoMap.get(module));
      } else {
        outputForModule.put("success", false);
      }
      outputs.put(module, outputForModule);
    }

    System.out.println((new ObjectMapper()).writeValueAsString(outputs));
  }
}
