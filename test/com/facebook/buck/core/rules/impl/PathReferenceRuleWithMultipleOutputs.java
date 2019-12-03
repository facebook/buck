/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.rules.impl;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rules.attr.HasMultipleOutputs;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;

/**
 * A {@link PathReferenceRule} that supports multiple outputs. Returns specific sets of {@link
 * SourcePath} instances for specific {@link OutputLabel} instances. The label to sets of paths
 * mapping is provided during initialization.
 */
public class PathReferenceRuleWithMultipleOutputs extends PathReferenceRule
    implements HasMultipleOutputs {
  private final ImmutableMap<OutputLabel, Path> outputLabelToSource;

  public PathReferenceRuleWithMultipleOutputs(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      Path source,
      ImmutableMap<OutputLabel, Path> outputLabelToSource) {
    super(buildTarget, projectFilesystem, source);
    this.outputLabelToSource = outputLabelToSource;
  }

  @Override
  public ImmutableSortedSet<SourcePath> getSourcePathToOutput(OutputLabel outputLabel) {
    if (!outputLabel.getLabel().isPresent()) {
      return ImmutableSortedSet.of(getSourcePathToOutput());
    }
    Path path = outputLabelToSource.get(outputLabel);
    if (path == null) {
      return ImmutableSortedSet.of();
    }
    return ImmutableSortedSet.of(ExplicitBuildTargetSourcePath.of(getBuildTarget(), path));
  }

  @Override
  public ImmutableMap<OutputLabel, ImmutableSortedSet<SourcePath>> getSourcePathsByOutputsLabels() {
    return null;
  }
}
