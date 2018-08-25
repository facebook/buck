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

package com.facebook.buck.rules.modern.impl;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.DefaultBuildCellRelativePathFactory;
import java.nio.file.Path;
import java.util.Optional;

/**
 * ModernBuildableSupport provides methods to make using, implementing and migrating to
 * ModernBuildRules easier.
 */
public class ModernBuildableSupport {
  // Not intended to be instantiated.
  private ModernBuildableSupport() {}

  /** Creates a BuildCellRelativePathFactory for a build root and filesystem pair. */
  public static BuildCellRelativePathFactory newCellRelativePathFactory(
      Path buildCellRootPath, ProjectFilesystem projectFilesystem) {
    return new DefaultBuildCellRelativePathFactory(
        buildCellRootPath, projectFilesystem, Optional.empty());
  }
}
