/*
 * Copyright 2016-present Facebook, Inc.
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
package com.facebook.buck.jvm.java.intellij;


import com.facebook.buck.cli.BuckConfig;

public class IjProjectBuckConfig {

  private static final String PROJECT_BUCK_CONFIG_SECTION = "project";

  private IjProjectBuckConfig() {
  }

  public static IjProjectConfig create(BuckConfig buckConfig) {
    return IjProjectConfig.builder()
        .setAutogenerateAndroidFacetSourcesEnabled(
            !buckConfig.getBooleanValue(
                PROJECT_BUCK_CONFIG_SECTION,
                "disable_r_java_idea_generator",
                false)
        )
        .setJdkAliases(buckConfig.getEntriesForSection("intellij_jdk_aliases"))
        .build();
  }
}
