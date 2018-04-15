/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.testutil.endtoend;

import com.facebook.buck.testutil.PlatformUtils;
import java.util.HashMap;
import java.util.Map;

/** Builds some common .buckconfig configuration sets to be used with E2E tests */
public class ConfigSetBuilder {
  private Map<String, Map<String, String>> configSet = new HashMap<>();
  private PlatformUtils platformUtils = PlatformUtils.getForPlatform();

  public ConfigSetBuilder() {}

  private void ensureSection(String sectionName) {
    if (!configSet.containsKey(sectionName)) {
      configSet.put(sectionName, new HashMap<>());
    }
  }

  /** Adds configuration options to calculate source_abis instead of class_abis when building */
  public ConfigSetBuilder addSourceABIConfigSet() {
    ensureSection("java");
    configSet.get("java").put("source_level", "7");
    configSet.get("java").put("target_level", "7");
    configSet.get("java").put("track_class_usage", "true");
    configSet.get("java").put("compile_against_abis", "true");
    configSet.get("java").put("abi_generation_mode", "source_only");
    return this;
  }

  /** Adds configuration options to create shared library interfaces when building */
  public ConfigSetBuilder addShlibConfigSet() {
    ensureSection("cxx");
    configSet.get("cxx").put("shlib_interfaces", "enabled");
    configSet.get("cxx").put("independent_shlib_interfaces", "true");
    platformUtils.getObjcopy().ifPresent(o -> configSet.get("cxx").put("objcopy", o));
    return this;
  }

  /** Builds and returns configurationSet, and resets added sets to the builder */
  public Map<String, Map<String, String>> build() {
    Map<String, Map<String, String>> builtConfigSet = configSet;
    configSet = new HashMap<>();
    return builtConfigSet;
  }
}
