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
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.externalactions.android.AndroidManifestExternalAction;
import com.facebook.buck.externalactions.android.AndroidManifestExternalActionArgs;
import com.facebook.buck.externalactions.utils.ExternalActionsUtils;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.rules.modern.BuildableWithExternalAction;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.rules.modern.model.BuildableCommand;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;

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
      ImmutableList<SourcePath> manifestFiles,
      ManifestEntries manifestEntries,
      boolean shouldExecuteInSeparateProcess) {
    super(
        buildTarget,
        projectFilesystem,
        finder,
        new Impl(
            skeletonFile,
            module,
            manifestFiles,
            manifestEntries,
            new OutputPath(
                String.format(
                    "AndroidManifest__%s__.xml", buildTarget.getShortNameAndFlavorPostfix())),
            new OutputPath("merge-report.txt"),
            shouldExecuteInSeparateProcess));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().outputPath);
  }

  static class Impl extends BuildableWithExternalAction {
    private static final String TEMP_FILE_PREFIX = "android_manifest_";
    private static final String TEMP_FILE_SUFFIX = "";

    @AddToRuleKey private final SourcePath skeletonFile;
    @AddToRuleKey private final String moduleName;
    /** These must be sorted so the rule key is stable. */
    @AddToRuleKey private final ImmutableList<SourcePath> manifestFiles;

    @AddToRuleKey private final ManifestEntries manifestEntries;

    @AddToRuleKey private final OutputPath outputPath;
    @AddToRuleKey private final OutputPath mergeReportOutputPath;

    Impl(
        SourcePath skeletonFile,
        APKModule module,
        ImmutableList<SourcePath> manifestFiles,
        ManifestEntries manifestEntries,
        OutputPath outputPath,
        OutputPath mergeReportOutputPath,
        boolean shouldExecuteInSeparateProcess) {
      super(shouldExecuteInSeparateProcess);
      this.skeletonFile = skeletonFile;
      this.manifestFiles = manifestFiles;
      this.manifestEntries = manifestEntries;
      this.moduleName = module.getName();
      this.outputPath = outputPath;
      this.mergeReportOutputPath = mergeReportOutputPath;
    }

    @Override
    public BuildableCommand getBuildableCommand(
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildContext buildContext) {
      SourcePathResolverAdapter sourcePathResolverAdapter = buildContext.getSourcePathResolver();
      Path jsonFilePath = createTempFile(filesystem);
      AndroidManifestExternalActionArgs jsonArgs =
          AndroidManifestExternalActionArgs.of(
              sourcePathResolverAdapter.getRelativePath(filesystem, skeletonFile).toString(),
              sourcePathResolverAdapter.getAllRelativePaths(filesystem, manifestFiles).stream()
                  .map(RelPath::toString)
                  .collect(ImmutableList.toImmutableList()),
              outputPathResolver.resolvePath(outputPath).toString(),
              outputPathResolver.resolvePath(mergeReportOutputPath).toString(),
              moduleName,
              manifestEntries.getPlaceholders().orElse(ImmutableMap.of()));
      ExternalActionsUtils.writeJsonArgs(jsonFilePath, jsonArgs);

      return BuildableCommand.newBuilder()
          .addExtraFiles(jsonFilePath.toString())
          .setExternalActionClass(AndroidManifestExternalAction.class.getName())
          .build();
    }

    private Path createTempFile(ProjectFilesystem filesystem) {
      try {
        return filesystem.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX);
      } catch (IOException e) {
        throw new IllegalStateException(
            "Failed to create temp file when creating android manifest buildable command");
      }
    }
  }
}
