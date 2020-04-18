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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.targetgraph.impl.FlavoredVerifier;
import com.facebook.buck.parser.api.RawTargetNode;
import com.google.common.base.Joiner;
import java.nio.file.Path;

/** Verifies that the {@link BuildTarget} is valid during parsing */
public class BuiltTargetVerifier {

  /** @param buildFile Absolute path to build file that contains build target being verified */
  void verifyBuildTarget(
      Cell cell,
      RuleType buildRuleType,
      Path buildFile,
      UnconfiguredBuildTarget target,
      BaseDescription<?> description,
      RawTargetNode rawNode) {

    FlavoredVerifier.verify(target, buildRuleType, description);

    UnflavoredBuildTarget unflavoredBuildTarget = target.getUnflavoredBuildTarget();
    UnflavoredBuildTarget unflavoredBuildTargetFromRawData =
        UnflavoredBuildTargetFactory.createFromRawNode(
            cell.getRoot().getPath(), cell.getCanonicalName(), rawNode, buildFile);
    if (!unflavoredBuildTarget.equals(unflavoredBuildTargetFromRawData)) {
      throw new IllegalStateException(
          String.format(
              "Inconsistent internal state, target from data: %s, expected: %s, raw data: %s",
              unflavoredBuildTargetFromRawData,
              unflavoredBuildTarget,
              Joiner.on(',').withKeyValueSeparator("->").join(rawNode.getAttrs())));
    }
  }

  void verifyBuildTarget(
      Cell cell,
      RuleType buildRuleType,
      AbsPath buildFile,
      UnconfiguredBuildTarget target,
      BaseDescription<?> description,
      RawTargetNode rawNode) {
    verifyBuildTarget(cell, buildRuleType, buildFile.getPath(), target, description, rawNode);
  }
}
