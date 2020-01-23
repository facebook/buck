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

package com.facebook.buck.core.rules.attr;

import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import javax.annotation.Nullable;

/**
 * {@link com.facebook.buck.core.rules.BuildRule} instances that support multiple outputs via output
 * labels.
 */
public interface HasMultipleOutputs extends BuildRule {
  /**
   * Returns {@link SourcePath} instances to the outputs associated with the given output label, or
   * {@code null} if the output label does not exist. If the default output label is given, returns
   * the default outputs associated with the rule.
   */
  @Nullable
  ImmutableSortedSet<SourcePath> getSourcePathToOutput(OutputLabel outputLabel);

  /** returns a set of {@link OutputLabel} instances associated with this build rule. */
  ImmutableSet<OutputLabel> getOutputLabels();

  @Nullable
  @Override
  default SourcePath getSourcePathToOutput() {
    ImmutableSortedSet<SourcePath> sourcePaths = getSourcePathToOutput(OutputLabel.defaultLabel());
    return sourcePaths == null || sourcePaths.isEmpty()
        ? null
        : Iterables.getOnlyElement(sourcePaths);
  }
}
