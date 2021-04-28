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

package com.facebook.buck.cxx;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.HasBrokenInputBasedRuleKey;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.types.Pair;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

/** Modern build rule used to capture c++ transitive dependencies */
public class CxxInferCaptureTransitiveRule
    extends ModernBuildRule<CxxInferCaptureTransitiveRule.Impl> {

  @VisibleForTesting static final RelPath OUTPUT_PATH = RelPath.get("infer", "infer-deps.txt");

  public CxxInferCaptureTransitiveRule(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      SourcePathRuleFinder ruleFinder,
      ImmutableSet<CxxInferCapture> captureRules) {
    super(buildTarget, filesystem, ruleFinder, new Impl(captureRules));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().output);
  }

  /** Internal buildable implementation */
  static class Impl
      implements Buildable,
          // TODO: HEADER_SYMLINK_TREE doesn't work with input based keys
          HasBrokenInputBasedRuleKey {

    @AddToRuleKey private final OutputPath output;
    @AddToRuleKey private final ImmutableSet<BuildTargetSourcePath> captureRulesOutputs;

    public Impl(ImmutableSet<CxxInferCapture> captureRules) {
      this.output = new OutputPath(OUTPUT_PATH);
      this.captureRulesOutputs =
          captureRules.stream()
              .map(CxxInferCapture::getSourcePathToOutput)
              .collect(ImmutableSet.toImmutableSet());
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {

      SourcePathResolverAdapter sourcePathResolver = context.getSourcePathResolver();
      ImmutableSet<Pair<BuildTarget, AbsPath>> captureRulesPairs =
          captureRulesOutputs.stream()
              .map(sourcePath -> toCaptureRulesPair(sourcePathResolver, sourcePath))
              .collect(ImmutableSet.toImmutableSet());

      RelPath outputPath = outputPathResolver.resolvePath(output);
      return ImmutableList.of(
          new CxxCollectAndLogInferDependenciesStep(captureRulesPairs, outputPath));
    }

    private Pair<BuildTarget, AbsPath> toCaptureRulesPair(
        SourcePathResolverAdapter resolver, BuildTargetSourcePath sourcePath) {
      return new Pair<>(sourcePath.getTarget(), resolver.getAbsolutePath(sourcePath));
    }
  }
}
