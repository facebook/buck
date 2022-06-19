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

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.TargetConfigurationHasher;
import com.facebook.buck.features.project.intellij.model.IjLibrary;
import com.facebook.buck.features.project.intellij.model.IjModule;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** This will create a JSON file containing mapping between configuration and buck-out hash. */
public class TargetConfigurationInfoManager {

  static final String TARGET_CONFIG_INFO_FILENAME = "target-configuration-info.json";
  static final String CONFIG_HASH_KEY = "hash";
  private final ProjectFilesystem outFilesystem;
  private final Path targetConfigInfoMapPath;
  private final Map<String, Map<String, String>> targetConfigInfoMap;

  public TargetConfigurationInfoManager(
      IjProjectConfig projectConfig, ProjectFilesystem outFilesystem) {
    this.outFilesystem = outFilesystem;
    this.targetConfigInfoMapPath =
        projectConfig.getProjectPaths().getIdeaConfigDir().resolve(TARGET_CONFIG_INFO_FILENAME);
    this.targetConfigInfoMap = Maps.newTreeMap();
  }

  /**
   * This method will iterate over the targets of all libraries and modules and writes a JSON file
   * with mappings of each configuration and hash value of each configuration.
   */
  public void write(
      ImmutableSet<IjModule> modules, ImmutableSet<IjLibrary> libraries, IJProjectCleaner cleaner)
      throws IOException {
    modules.forEach(module -> module.getTargets().forEach(this::addTargetConfigToMap));
    libraries.forEach(library -> library.getTargets().forEach(this::addTargetConfigToMap));

    try (JsonGenerator generator =
        ObjectMappers.createGenerator(outFilesystem.newFileOutputStream(targetConfigInfoMapPath))
            .useDefaultPrettyPrinter()) {
      generator.writeObject(targetConfigInfoMap);
      cleaner.doNotDelete(targetConfigInfoMapPath);
    }
  }

  private void addTargetConfigToMap(BuildTarget target) {
    String targetConfigKey = target.getTargetConfiguration().toString();
    if (!targetConfigInfoMap.containsKey(targetConfigKey)) {
      Map<String, String> targetConfigMap = new HashMap<>();
      targetConfigMap.put(
          CONFIG_HASH_KEY, TargetConfigurationHasher.hash(target.getTargetConfiguration()));
      targetConfigInfoMap.put(targetConfigKey, targetConfigMap);
    }
  }

  @VisibleForTesting
  String readTargetConfigInfoMapAsString() throws IOException {
    return String.join(System.lineSeparator(), outFilesystem.readLines(targetConfigInfoMapPath));
  }
}
