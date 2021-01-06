/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.rules.modern;

import com.facebook.buck.io.BuildCellRelativePath;
import java.nio.file.Path;

// TODO(cjhopman): Buildable's shouldn't really need to deal with this, but it simplifies dealing
// with steps that take BuildCellRelativePath.
public interface BuildCellRelativePathFactory {
  /** Converts a Path relative to the Buildable's ProjectFilesystem to a BuildCellRelativePath. */
  BuildCellRelativePath from(Path buildableRelativePath);

  /** Converts an OutputPath to a BuildCellRelativePath. */
  BuildCellRelativePath from(OutputPath outputPath);
}
