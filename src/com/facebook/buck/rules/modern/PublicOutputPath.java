/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.modern;

import com.google.common.base.Preconditions;
import java.nio.file.Path;

/**
 * Used for an output path that is not contained within a rule's unique output directories.
 * Typically used for an output that is in the root directory shared with other files in the same
 * BUCK file. This should be used sparingly.
 *
 * <p>An example is a cxx_binary with target //some/package:my_binary. As a non-ModernBuildRule, the
 * binary will be placed at buck-out/gen/some/package/my_binary. Without PublicOutputPath,
 * ModernBuildRule outputs are all forced into a unique directory not shared with any other target
 * and so the output would be changed to buck-out/gen/some/package/my_binary__/my_binary.
 * PublicOutputPath just allows MBR to have outputs outside of that rule-specific directory, mostly
 * to maintain previous behavior as things are migrated (but in some limited cases we may keep them
 * as public long-term).
 */
public class PublicOutputPath extends OutputPath {
  public PublicOutputPath(Path path) {
    super(path);
    Preconditions.checkState(
        !path.isAbsolute(), "Expected relative path, got %s.", path.toString());
  }
}
