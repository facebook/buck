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

package com.facebook.buck.core.sourcepath;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.UnconfiguredBuildTargetWithOutputs;
import com.facebook.buck.core.parser.buildtargetpattern.UnconfiguredBuildTargetParser;
import com.facebook.buck.core.path.ForwardRelativePath;

/** Utility to create {@link com.facebook.buck.core.sourcepath.UnconfiguredSourcePath} for tests. */
public class UnconfiguredSourcePathFactoryForTests {

  public static UnconfiguredSourcePath unconfiguredSourcePath(String path) {
    if (path.contains("//") || path.startsWith(":")) {
      return new UnconfiguredSourcePath.BuildTarget(
          UnconfiguredBuildTargetWithOutputs.of(
              UnconfiguredBuildTargetParser.parse(path), OutputLabel.defaultLabel()));
    } else {
      return new UnconfiguredSourcePath.Path(
          CellRelativePath.of(CanonicalCellName.rootCell(), ForwardRelativePath.of(path)));
    }
  }
}
