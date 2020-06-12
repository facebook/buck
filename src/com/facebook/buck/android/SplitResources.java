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

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.DefaultOutputPathResolver;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.isolatedsteps.android.SplitResourcesStep;
import com.facebook.buck.step.isolatedsteps.android.ZipalignStep;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Implementation for the graph enhancement bit of exo-for-resources.
 *
 * <p>SplitResources has three outputs:
 *
 * <ul>
 *   <li>Primary resources zip
 *   <li>Exo resources zip
 *   <li>R.txt
 * </ul>
 *
 * These are copies from aapt's outputs. The exo resources zip gets zipaligned.
 */
public class SplitResources extends ModernBuildRule<SplitResources.Impl> {
  private static final String EXO_RESOURCES_APK_FILE_NAME = "exo-resources.apk";
  private static final String PRIMARY_RESOURCES_APK_FILE_NAME = "primary-resources.apk";
  private static final String R_TXT_FILE_NAME = "R.txt";

  public SplitResources(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      SourcePath pathToAaptResources,
      SourcePath pathToOriginalRDotTxt,
      Tool zipalignTool,
      boolean withDownwardApi) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new SplitResources.Impl(
            buildTarget,
            zipalignTool,
            pathToAaptResources,
            pathToOriginalRDotTxt,
            Paths.get(EXO_RESOURCES_APK_FILE_NAME),
            Paths.get(PRIMARY_RESOURCES_APK_FILE_NAME),
            Paths.get(R_TXT_FILE_NAME),
            withDownwardApi));
  }

  SourcePath getPathToRDotTxt() {
    return getSourcePath(getBuildable().getPathToRDotTxt());
  }

  SourcePath getPathToPrimaryResources() {
    return getSourcePath(getBuildable().getPathToPrimaryResources());
  }

  SourcePath getPathToExoResources() {
    return getSourcePath(getBuildable().getPathToExoResources());
  }

  /** Buildable implementation for {@link com.facebook.buck.android.SplitResources}. */
  static class Impl implements Buildable {

    private static final String EXO_RESOURCES_UNALIGNED_ZIP_NAME = "exo-resources.unaligned.zip";

    @AddToRuleKey private final BuildTarget buildTarget;
    @AddToRuleKey private final Tool zipalignTool;
    @AddToRuleKey private final SourcePath pathToAaptResources;
    @AddToRuleKey private final SourcePath pathToOriginalRDotTxt;

    @AddToRuleKey private final OutputPath exoResourcesOutputPath;
    @AddToRuleKey private final OutputPath primaryResourcesOutputPath;
    @AddToRuleKey private final OutputPath rDotTxtOutputPath;

    @AddToRuleKey private final boolean withDownwardApi;

    Impl(
        BuildTarget buildTarget,
        Tool zipalignTool,
        SourcePath pathToAaptResources,
        SourcePath pathToOriginalRDotTxt,
        Path exoResourcesOutputPath,
        Path primaryResourcesOutputPath,
        Path rDotTxtOutputPath,
        boolean withDownwardApi) {
      this.buildTarget = buildTarget;
      this.exoResourcesOutputPath = new OutputPath(exoResourcesOutputPath);
      this.primaryResourcesOutputPath = new OutputPath(primaryResourcesOutputPath);
      this.rDotTxtOutputPath = new OutputPath(rDotTxtOutputPath);
      this.pathToAaptResources = pathToAaptResources;
      this.pathToOriginalRDotTxt = pathToOriginalRDotTxt;
      this.zipalignTool = zipalignTool;
      this.withDownwardApi = withDownwardApi;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      SourcePathResolverAdapter sourcePathResolverAdapter = buildContext.getSourcePathResolver();
      return ImmutableList.<Step>builder()
          .add(
              new SplitResourcesStep(
                  RelPath.of(sourcePathResolverAdapter.getRelativePath(pathToAaptResources)),
                  RelPath.of(sourcePathResolverAdapter.getRelativePath(pathToOriginalRDotTxt)),
                  RelPath.of(outputPathResolver.resolvePath(primaryResourcesOutputPath)),
                  RelPath.of(getUnalignedExoPath(filesystem)),
                  RelPath.of(outputPathResolver.resolvePath(rDotTxtOutputPath))))
          .add(
              new ZipalignStep(
                  filesystem.getRootPath(),
                  ProjectFilesystemUtils.relativize(
                      filesystem.getRootPath(), buildContext.getBuildCellRootPath()),
                  RelPath.of(getUnalignedExoPath(filesystem)),
                  RelPath.of(outputPathResolver.resolvePath(exoResourcesOutputPath)),
                  zipalignTool.getCommandPrefix(buildContext.getSourcePathResolver()),
                  withDownwardApi))
          .build();
    }

    private RelPath getScratchDirectory(ProjectFilesystem filesystem) {
      return new DefaultOutputPathResolver(filesystem, buildTarget).getTempPath();
    }

    private Path getUnalignedExoPath(ProjectFilesystem filesystem) {
      return getScratchDirectory(filesystem).resolve(EXO_RESOURCES_UNALIGNED_ZIP_NAME);
    }

    private OutputPath getPathToExoResources() {
      return exoResourcesOutputPath;
    }

    private OutputPath getPathToRDotTxt() {
      return rDotTxtOutputPath;
    }

    private OutputPath getPathToPrimaryResources() {
      return primaryResourcesOutputPath;
    }
  }
}
