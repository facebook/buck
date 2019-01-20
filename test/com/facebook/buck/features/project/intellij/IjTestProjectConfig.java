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

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.features.project.intellij.aggregation.AggregationMode;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;

public class IjTestProjectConfig {

  public static IjProjectConfig create() {
    return createBuilder(FakeBuckConfig.builder().build()).build();
  }

  public static IjProjectConfig.Builder createBuilder(BuckConfig buckConfig) {
    String projectRoot = "";
    return IjProjectBuckConfig.createBuilder(buckConfig)
        .setAggregationMode(AggregationMode.AUTO)
        .setModuleGroupName("modules")
        .setCleanerEnabled(false)
        .setRemovingUnusedLibrariesEnabled(false)
        .setExcludeArtifactsEnabled(true)
        .setIncludeTransitiveDependency(false)
        .setSkipBuildEnabled(true)
        .setKeepModuleFilesInModuleDirsEnabled(true)
        .setProjectRoot(projectRoot)
        .setProjectPaths(new IjProjectPaths(projectRoot, true));
  }
}
