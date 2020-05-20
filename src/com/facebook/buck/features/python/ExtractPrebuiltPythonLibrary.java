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

package com.facebook.buck.features.python;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
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
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.unarchive.UnzipStep;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;

/** Extract a prebuilt Python library/wheel. */
class ExtractPrebuiltPythonLibrary extends ModernBuildRule<ExtractPrebuiltPythonLibrary.Impl> {

  public ExtractPrebuiltPythonLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      SourcePathRuleFinder finder,
      SourcePath binarySrc) {
    super(buildTarget, filesystem, finder, new Impl(binarySrc));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(Impl.OUTPUT);
  }

  protected static class Impl implements Buildable {

    private static final OutputPath OUTPUT = new OutputPath("extracted");

    @AddToRuleKey private final SourcePath binarySrc;

    public Impl(SourcePath binarySrc) {
      this.binarySrc = binarySrc;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      Path extractedOutput = outputPathResolver.resolvePath(OUTPUT);
      return ImmutableList.<Step>builder()
          .addAll(
              MakeCleanDirectoryStep.of(
                  buildCellPathFactory.from(outputPathResolver.resolvePath(OUTPUT))))
          .add(
              new UnzipStep(
                  filesystem,
                  buildContext.getSourcePathResolver().getAbsolutePath(binarySrc),
                  extractedOutput,
                  Optional.empty()))
          .add(new MovePythonWhlDataStep(filesystem, extractedOutput))
          .build();
    }
  }
}
