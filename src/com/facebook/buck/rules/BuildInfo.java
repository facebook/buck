/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;

import java.nio.file.Path;

/**
 * Shared utilities for {@link BuildInfoRecorder} and {@link OnDiskBuildInfo}.
 */
class BuildInfo {

  /**
   * Key for {@link OnDiskBuildInfo} to identify the RuleKey for a build rule.
   */
  static final String METADATA_KEY_FOR_RULE_KEY = "RULE_KEY";

  /**
   * Key for {@link OnDiskBuildInfo} to identify the RuleKey without deps for a build rule.
   */
  static final String METADATA_KEY_FOR_RULE_KEY_WITHOUT_DEPS = "RULE_KEY_NO_DEPS";

  /** Utility class: do not instantiate. */
  private BuildInfo() {}

  /**
   * Returns the path to a directory where metadata files for a build rule with the specified
   * target should be stored.
   * @return A path relative to the project root that includes a trailing slash.
   */
  static Path getPathToMetadataDirectory(BuildTarget target) {
    return BuildTargets.getScratchPath(target, ".%s/metadata/");
  }
}
