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

package com.facebook.buck.shell;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.HasOutputName;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.HasMultipleOutputs;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

/**
 * Abstract parent class of all Genrules, containing some common functionality while remaining
 * generic over the {@link com.facebook.buck.rules.modern.Buildable}, to be provided by child
 * classes.
 *
 * @param <T> Buildable instance for the Genrule implementation.
 */
public abstract class BaseGenrule<T extends GenruleBuildable> extends ModernBuildRule<T>
    implements HasOutputName, HasMultipleOutputs {
  protected BaseGenrule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver buildRuleResolver,
      T buildable) {
    super(buildTarget, projectFilesystem, buildRuleResolver, buildable);
  }

  /**
   * Returns the output defined in 'out'. Should not be used with multiple outputs. Use {@link
   * #getSourcePathToOutput(OutputLabel)} instead.
   */
  @Override
  public SourcePath getSourcePathToOutput() {
    ImmutableSortedSet<SourcePath> sourcePaths = getSourcePathToOutput(OutputLabel.defaultLabel());
    Preconditions.checkState(
        sourcePaths != null && !sourcePaths.isEmpty(),
        "Unexpectedly cannot find genrule single default output for target %s",
        getBuildTarget());
    return Iterables.getOnlyElement(sourcePaths);
  }

  @Override
  public ImmutableSortedSet<SourcePath> getSourcePathToOutput(OutputLabel outputLabel) {
    return getSourcePaths(getBuildable().getOutputs(outputLabel));
  }

  @Override
  public ImmutableSet<OutputLabel> getOutputLabels() {
    return getBuildable().getOutputLabels();
  }

  @Override
  public String getType() {
    return super.getType() + (getBuildable().type.map(typeStr -> "_" + typeStr).orElse(""));
  }

  /** Get the output name of the generated file, as listed in the BUCK file. */
  @Override
  public String getOutputName(OutputLabel outputLabel) {
    return getBuildable().getOutputName(outputLabel);
  }

  /** Get whether or not the output of this genrule can be cached. */
  @Override
  public final boolean isCacheable() {
    return getBuildable().isCacheable;
  }
}
