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

package com.facebook.buck.core.rules.impl;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rules.attr.HasMultipleOutputs;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.util.Map;

/**
 * A {@link PathReferenceRule} that supports multiple outputs. Returns specific sets of {@link
 * SourcePath} instances for specific {@link OutputLabel} instances. The label to sets of paths
 * mapping is provided during initialization.
 */
public class PathReferenceRuleWithMultipleOutputs extends PathReferenceRule
    implements HasMultipleOutputs {
  private final ImmutableMap<OutputLabel, ImmutableSortedSet<SourcePath>> outputLabelsToSourcePaths;

  public PathReferenceRuleWithMultipleOutputs(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      Path source,
      ImmutableMap<OutputLabel, ImmutableSet<Path>> outputLabelsToOutputs) {
    super(buildTarget, projectFilesystem, source);
    ImmutableMap.Builder<OutputLabel, ImmutableSortedSet<SourcePath>> builder =
        ImmutableMap.builderWithExpectedSize(1 + outputLabelsToOutputs.size());
    if (!outputLabelsToOutputs.containsKey(OutputLabel.defaultLabel())) {
      builder.put(
          OutputLabel.defaultLabel(),
          source == null
              ? ImmutableSortedSet.of()
              : ImmutableSortedSet.of(getSourcePathToOutput()));
    }
    for (Map.Entry<OutputLabel, ImmutableSet<Path>> entry : outputLabelsToOutputs.entrySet()) {
      builder.put(
          entry.getKey(),
          entry.getValue().stream()
              .map(path -> ExplicitBuildTargetSourcePath.of(getBuildTarget(), path))
              .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())));
    }
    outputLabelsToSourcePaths = builder.build();
  }

  @Override
  public ImmutableSortedSet<SourcePath> getSourcePathToOutput(OutputLabel outputLabel) {
    if (outputLabel.isDefault()) {
      SourcePath sourcePath = getSourcePathToOutput();
      if (sourcePath == null) {
        return ImmutableSortedSet.of();
      }
      return ImmutableSortedSet.of(sourcePath);
    }
    return outputLabelsToSourcePaths.get(outputLabel);
  }

  @Override
  public ImmutableMap<OutputLabel, ImmutableSortedSet<SourcePath>> getSourcePathsByOutputsLabels() {
    return outputLabelsToSourcePaths;
  }
}
