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
import com.facebook.buck.externalactions.android.SplitResourcesExternalAction;
import com.facebook.buck.externalactions.android.SplitResourcesExternalActionArgs;
import com.facebook.buck.externalactions.utils.ExternalActionsUtils;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.rules.modern.BuildableWithExternalAction;
import com.facebook.buck.rules.modern.DefaultOutputPathResolver;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.rules.modern.model.BuildableCommand;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Supplier;

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
  private static final String BUILDABLE_COMMAND_TEMP_FILE_PREFIX = "split_resources_";
  private static final String BUILDABLE_COMMAND_TEMP_FILE_SUFFIX = "";

  public SplitResources(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      SourcePath pathToAaptResources,
      SourcePath pathToOriginalRDotTxt,
      Tool zipalignTool,
      boolean withDownwardApi,
      boolean shouldExecuteInSeparateProcess,
      Tool javaRuntimeLauncher,
      Supplier<SourcePath> externalActionsSourcePathSupplier) {
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
            withDownwardApi,
            shouldExecuteInSeparateProcess,
            javaRuntimeLauncher,
            externalActionsSourcePathSupplier));
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
  static class Impl extends BuildableWithExternalAction {

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
        boolean withDownwardApi,
        boolean shouldExecuteInSeparateProcess,
        Tool javaRuntimeLauncher,
        Supplier<SourcePath> externalActionsSourcePathSupplier) {
      super(shouldExecuteInSeparateProcess, javaRuntimeLauncher, externalActionsSourcePathSupplier);
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
    public BuildableCommand getBuildableCommand(
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildContext buildContext) {
      SourcePathResolverAdapter sourcePathResolverAdapter = buildContext.getSourcePathResolver();
      Path jsonFilePath = createTempFile(filesystem);
      SplitResourcesExternalActionArgs jsonArgs =
          SplitResourcesExternalActionArgs.of(
              sourcePathResolverAdapter.getCellUnsafeRelPath(pathToAaptResources).toString(),
              sourcePathResolverAdapter.getCellUnsafeRelPath(pathToOriginalRDotTxt).toString(),
              outputPathResolver.resolvePath(primaryResourcesOutputPath).toString(),
              RelPath.of(getUnalignedExoPath(filesystem)).toString(),
              outputPathResolver.resolvePath(rDotTxtOutputPath).toString(),
              filesystem.getRootPath().toString(),
              ProjectFilesystemUtils.relativize(
                      filesystem.getRootPath(), buildContext.getBuildCellRootPath())
                  .toString(),
              RelPath.of(getUnalignedExoPath(filesystem)).toString(),
              outputPathResolver.resolvePath(exoResourcesOutputPath).toString(),
              withDownwardApi,
              zipalignTool.getCommandPrefix(buildContext.getSourcePathResolver()));
      ExternalActionsUtils.writeJsonArgs(jsonFilePath, jsonArgs);

      return BuildableCommand.newBuilder()
          .addExtraFiles(jsonFilePath.toString())
          .putAllEnv(ImmutableMap.of())
          .setExternalActionClass(SplitResourcesExternalAction.class.getName())
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

    private Path createTempFile(ProjectFilesystem filesystem) {
      try {
        return filesystem.createTempFile(
            BUILDABLE_COMMAND_TEMP_FILE_PREFIX, BUILDABLE_COMMAND_TEMP_FILE_SUFFIX);
      } catch (IOException e) {
        throw new IllegalStateException(
            "Failed to create temp file when creating split resources buildable command");
      }
    }
  }
}
