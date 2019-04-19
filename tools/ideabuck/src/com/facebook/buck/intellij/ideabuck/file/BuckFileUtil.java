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

package com.facebook.buck.intellij.ideabuck.file;

import com.google.common.base.MoreObjects;
import com.intellij.openapi.vfs.VirtualFile;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.ini4j.Wini;

public final class BuckFileUtil {

  private static final String DEFAULT_BUILD_FILE = "BUCK";
  private static final String BUCK_CONFIG_FILE = ".buckconfig";
  private static final Map<String, String> buildFileNames = new HashMap<>();
  private static final String SAMPLE_BUCK_FILE =
      "# Thanks for installing Buck Plugin for IDEA!\n"
          + "android_library(\n"
          + "  name = 'bar',\n"
          + "  srcs = glob(['**/*.java']),\n"
          + "  deps = [\n"
          + "    '//android_res/com/foo/interfaces:res',\n"
          + "    '//android_res/com/foo/common/strings:res',\n"
          + "    '//android_res/com/foo/custom:res,'\n"
          + "  ],\n"
          + "  visibility = [\n"
          + "    'PUBLIC',\n"
          + "  ],\n"
          + ")\n"
          + "\n"
          + "project_config(\n"
          + "  src_target = ':bar',\n"
          + ")\n";

  private BuckFileUtil() {}

  public static String getBuildFileName() {
    // TODO(#7908500): Read from ".buckconfig".
    return DEFAULT_BUILD_FILE;
  }

  public static String getSampleBuckFile() {
    return SAMPLE_BUCK_FILE;
  }

  public static VirtualFile getBuckFile(VirtualFile virtualFile) {

    if (virtualFile == null) {
      return null;
    }

    VirtualFile parent = virtualFile.getParent();
    if (parent == null) {
      return null;
    }
    VirtualFile buckFile = parent.findChild(BuckFileUtil.getBuildFileName());
    while ((buckFile == null && parent != null) || (buckFile != null && buckFile.isDirectory())) {
      buckFile = parent.findChild(BuckFileUtil.getBuildFileName());
      parent = parent.getParent();
    }
    return buckFile;
  }

  public static String getBuildFileName(String projectPath) {
    if (!buildFileNames.containsKey(projectPath)) {
      buildFileNames.put(projectPath, getBuildFileNameFromBuckConfig(projectPath));
    }
    return buildFileNames.get(projectPath);
  }

  private static String getBuildFileNameFromBuckConfig(String projectPath) {
    Path buckConfigPath = getPathToBuckConfig(Paths.get(projectPath));
    if (buckConfigPath == null) {
      return DEFAULT_BUILD_FILE;
    }
    Wini ini = null;
    try {
      ini = new Wini(buckConfigPath.toFile());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return MoreObjects.firstNonNull(ini.get("buildfile", "name"), DEFAULT_BUILD_FILE);
  }

  private static Path getPathToBuckConfig(Path startPath) {
    Path curPath = startPath;
    while (curPath != null) {
      Path pathToBuckConfig = curPath.resolve(BUCK_CONFIG_FILE);
      if (pathToBuckConfig.toFile().exists()) {
        return pathToBuckConfig;
      }
      curPath = curPath.getParent();
    }
    return null;
  }
}
