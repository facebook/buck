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

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.parser.api.RawTargetNode;
import com.facebook.buck.rules.param.CommonParamNames;
import com.google.common.base.Joiner;
import javax.annotation.Nullable;

public class UnflavoredBuildTargetFactory {

  private UnflavoredBuildTargetFactory() {}

  /**
   * @param cellRoot Absolute path to the root of the cell the rule is defined in.
   * @param map the map of values that define the rule.
   * @param buildFilePath Absolute path to the build file the rule is defined in
   * @return the build target defined by the rule.
   */
  public static UnflavoredBuildTarget createFromRawNode(
      AbsPath cellRoot, CanonicalCellName cellName, RawTargetNode map, AbsPath buildFilePath) {
    ForwardRelPath basePath = map.getBasePath();
    @Nullable String name = (String) map.get(CommonParamNames.NAME);
    if (name == null) {
      throw new IllegalStateException(
          String.format(
              "Attempting to parse build target from malformed raw data in %s: %s.",
              buildFilePath, Joiner.on(",").withKeyValueSeparator("->").join(map.getAttrs())));
    }
    RelPath otherBasePath =
        cellRoot.relativize(MorePaths.getParentOrEmpty(buildFilePath.getPath()));
    if (!otherBasePath.getPath().equals(basePath.toPath(otherBasePath.getFileSystem()))) {
      throw new IllegalStateException(
          String.format(
              "Raw data claims to come from [%s], but we tried rooting it at [%s].",
              basePath, otherBasePath));
    }
    return UnflavoredBuildTarget.of(CellRelativePath.of(cellName, basePath), name);
  }
}
