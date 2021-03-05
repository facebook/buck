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
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.externalactions.android.MergeJarResourcesExternalAction;
import com.facebook.buck.externalactions.android.MergeJarResourcesExternalActionArgs;
import com.facebook.buck.externalactions.utils.ExternalActionsUtils;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.DefaultJavaLibraryRules;
import com.facebook.buck.rules.modern.BuildableWithExternalAction;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.rules.modern.model.BuildableCommand;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Merges resources from third party jars for exo-for-resources. */
public class MergeThirdPartyJarResources extends ModernBuildRule<MergeThirdPartyJarResources.Impl> {

  protected MergeThirdPartyJarResources(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      ImmutableCollection<SourcePath> pathsToThirdPartyJars,
      boolean shouldExecuteInSeparateProcess,
      Tool javaRuntimeLauncher) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new Impl(
            ImmutableSortedSet.copyOf(pathsToThirdPartyJars),
            new OutputPath("java.resources"),
            shouldExecuteInSeparateProcess,
            javaRuntimeLauncher,
            DefaultJavaLibraryRules.getExternalActionsSourcePathSupplier(projectFilesystem)));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().mergedPath);
  }

  static class Impl extends BuildableWithExternalAction {
    private static final String TEMP_FILE_PREFIX = "merge_jar_resources_";
    private static final String TEMP_FILE_SUFFIX = "";

    @AddToRuleKey private final ImmutableSortedSet<SourcePath> pathsToThirdPartyJars;
    @AddToRuleKey private final OutputPath mergedPath;

    public Impl(
        ImmutableSortedSet<SourcePath> pathsToThirdPartyJars,
        OutputPath mergedPath,
        boolean shouldExecuteInSeparateProcess,
        Tool javaRuntimeLauncher,
        Supplier<SourcePath> externalActionsSourcePathSupplier) {
      super(shouldExecuteInSeparateProcess, javaRuntimeLauncher, externalActionsSourcePathSupplier);
      this.pathsToThirdPartyJars = pathsToThirdPartyJars;
      this.mergedPath = mergedPath;
    }

    @Override
    public BuildableCommand getBuildableCommand(
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildContext buildContext) {
      ImmutableSortedSet<RelPath> thirdPartyJars =
          buildContext
              .getSourcePathResolver()
              .getAllRelativePaths(filesystem, pathsToThirdPartyJars);
      Path jsonFilePath = createTempFile(filesystem);

      MergeJarResourcesExternalActionArgs jsonArgs =
          MergeJarResourcesExternalActionArgs.of(
              thirdPartyJars.stream().map(RelPath::toString).collect(Collectors.toList()),
              filesystem.resolve(outputPathResolver.resolvePath(mergedPath).getPath()).toString());
      ExternalActionsUtils.writeJsonArgs(jsonFilePath, jsonArgs);

      return BuildableCommand.newBuilder()
          .addExtraFiles(jsonFilePath.toString())
          .setExternalActionClass(MergeJarResourcesExternalAction.class.getName())
          .build();
    }

    private Path createTempFile(ProjectFilesystem filesystem) {
      try {
        return filesystem.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX);
      } catch (IOException e) {
        throw new IllegalStateException(
            "Failed to create temp file when creating merge jar resources buildable command");
      }
    }
  }
}
