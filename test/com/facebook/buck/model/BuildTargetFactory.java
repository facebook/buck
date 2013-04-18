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

package com.facebook.buck.model;

import static com.facebook.buck.util.BuckConstant.BUILD_RULES_FILE_NAME;

import com.google.common.base.Preconditions;

import java.io.File;

/**
 * Exposes some {@link com.facebook.buck.model.BuildTarget} logic that is only visible for testing.
 */
public class BuildTargetFactory {

  private BuildTargetFactory() {
    // Utility class
  }

  public static BuildTarget newInstance(String baseName, String shortName, File buildFile) {
    return new BuildTarget(baseName, shortName, buildFile);
  }

  public static BuildTarget newInstance(String baseName, String shortName) {
    Preconditions.checkNotNull(baseName);
    Preconditions.checkArgument(baseName.startsWith(BuildTarget.BUILD_TARGET_PREFIX),
        "baseName must start with // but was %s",
        baseName);
    Preconditions.checkNotNull(shortName);

    String prefix = baseName.substring(BuildTarget.BUILD_TARGET_PREFIX.length());
    if (!prefix.isEmpty()) {
      prefix += "/";
    }
    String buildFilePath = prefix + BUILD_RULES_FILE_NAME;
    File buildFile = new File(buildFilePath);
    return new BuildTarget(baseName, shortName, buildFile);
  }

  public static BuildTarget newInstance(String fullyQualifiedName) {
    String[] parts = fullyQualifiedName.split(":");
    Preconditions.checkArgument(parts.length == 2);
    return newInstance(parts[0], parts[1]);
  }
}
