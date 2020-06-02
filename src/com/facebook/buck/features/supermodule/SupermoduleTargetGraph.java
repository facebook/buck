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

package com.facebook.buck.features.supermodule;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.HasOutputName;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import java.nio.file.Path;

/** Rule that outputs a json representation of the target graph with deps and labels. */
public class SupermoduleTargetGraph extends ModernBuildRule<SupermoduleTargetGraph>
    implements HasOutputName, Buildable {
  @AddToRuleKey private final OutputPath output;

  /** Map from targets to a map of attribute values (specifically name, labels, and deps) */
  @AddToRuleKey private final ImmutableSortedMap<BuildTarget, TargetInfo> targetGraphMap;

  public SupermoduleTargetGraph(
      SourcePathRuleFinder ruleFinder,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      OutputPath output,
      ImmutableSortedMap<BuildTarget, TargetInfo> targetGraphMap) {
    super(buildTarget, projectFilesystem, ruleFinder, SupermoduleTargetGraph.class);

    this.output = output;
    this.targetGraphMap = targetGraphMap;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext buildContext,
      ProjectFilesystem filesystem,
      OutputPathResolver outputPathResolver,
      BuildCellRelativePathFactory buildCellPathFactory) {
    Path outputPath = outputPathResolver.resolvePath(output);
    return ImmutableList.of(
        MkdirStep.of(buildCellPathFactory.from(outputPath.getParent())),
        new GenerateSupermoduleTargetGraphJsonStep(filesystem, outputPath, targetGraphMap));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().output);
  }

  @Override
  public String getOutputName(OutputLabel outputLabel) {
    if (!outputLabel.isDefault()) {
      throw new HumanReadableException(
          "Unexpected output label [%s] for target %s.", outputLabel, this.getFullyQualifiedName());
    }
    return output.toString();
  }
}
