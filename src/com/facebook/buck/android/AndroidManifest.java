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

package com.facebook.buck.android;

import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Collection;

/**
 * {@link AndroidManifest} is a {@link BuildRule} that can generate an Android manifest from a
 * skeleton manifest and the library manifests from its dependencies.
 *
 * <pre>
 * android_manifest(
 *   name = 'my_manifest',
 *   skeleton = 'AndroidManifestSkeleton.xml',
 *   deps = [
 *     ':sample_manifest',
 *     # Additional dependent android_resource and android_library rules would be listed here,
 *     # as well.
 *   ],
 * )
 * </pre>
 *
 * This will produce a file under buck-out/gen that will be parameterized by the name of the {@code
 * android_manifest} rule. This can be used as follows:
 *
 * <pre>
 * android_binary(
 *   name = 'my_app',
 *   manifest = ':my_manifest',
 *   ...
 * )
 * </pre>
 */
public class AndroidManifest extends ModernBuildRule<AndroidManifest.Impl> {

  protected AndroidManifest(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder finder,
      SourcePath skeletonFile,
      APKModule module,
      Collection<SourcePath> manifestFiles) {
    super(
        buildTarget,
        projectFilesystem,
        finder,
        new Impl(
            skeletonFile,
            module,
            ImmutableSortedSet.copyOf(manifestFiles),
            new OutputPath(
                String.format(
                    "AndroidManifest__%s__.xml", buildTarget.getShortNameAndFlavorPostfix())),
            new OutputPath("merge-report.txt")));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().outputPath);
  }

  static class Impl implements Buildable {
    @AddToRuleKey private final SourcePath skeletonFile;
    @AddToRuleKey private final String moduleName;
    /** These must be sorted so the rule key is stable. */
    @AddToRuleKey private final ImmutableSortedSet<SourcePath> manifestFiles;

    @AddToRuleKey private final OutputPath outputPath;
    @AddToRuleKey private final OutputPath mergeReportOutputPath;

    Impl(
        SourcePath skeletonFile,
        APKModule module,
        ImmutableSortedSet<SourcePath> manifestFiles,
        OutputPath outputPath,
        OutputPath mergeReportOutputPath) {
      this.skeletonFile = skeletonFile;
      this.manifestFiles = manifestFiles;
      this.moduleName = module.getName();
      this.outputPath = outputPath;
      this.mergeReportOutputPath = mergeReportOutputPath;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      return ImmutableList.of(
          new GenerateManifestStep(
              buildContext.getSourcePathResolver().getRelativePath(filesystem, skeletonFile),
              moduleName,
              buildContext.getSourcePathResolver().getAllRelativePaths(filesystem, manifestFiles),
              outputPathResolver.resolvePath(outputPath),
              outputPathResolver.resolvePath(mergeReportOutputPath)));
    }
  }
}
