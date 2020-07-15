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

package com.facebook.buck.apple;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
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
import com.facebook.buck.step.fs.WriteFileStep;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;

/** A BuildRule for generation of PkgInfo file to be put later into bundle */
public class ApplePkgInfo extends ModernBuildRule<ApplePkgInfo> implements Buildable {

  public static final Flavor FLAVOR = InternalFlavor.of("apple-pkg-info");

  // TODO(bhamiltoncx): This is only appropriate for .app bundles.
  public static boolean isPkgInfoNeeded(String extension) {
    return !(extension.equals(AppleBundleExtension.XPC.fileExtension)
        || extension.equals(AppleBundleExtension.QLGENERATOR.fileExtension));
  }

  @AddToRuleKey private final OutputPath output;

  public ApplePkgInfo(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder) {
    super(buildTarget, projectFilesystem, ruleFinder, ApplePkgInfo.class);
    output = new OutputPath("PkgInfo");
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().output);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext buildContext,
      ProjectFilesystem filesystem,
      OutputPathResolver outputPathResolver,
      BuildCellRelativePathFactory buildCellPathFactory) {
    Path outputPath = outputPathResolver.resolvePath(output).getPath();
    boolean isExecutable = false;
    return ImmutableList.of(new WriteFileStep(filesystem, "APPLWRUN", outputPath, isExecutable));
  }
}
