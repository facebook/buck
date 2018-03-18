/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.js;

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.UserFlavor;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.shell.WorkerTool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.json.JsonBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;

public class JsBundle extends AbstractBuildRuleWithDeclaredAndExtraDeps implements JsBundleOutputs {

  @AddToRuleKey private final String bundleName;

  @AddToRuleKey private final ImmutableSet<String> entryPoints;

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> libraries;

  @AddToRuleKey private final ImmutableList<ImmutableSet<SourcePath>> libraryPathGroups;

  @AddToRuleKey private final Optional<Arg> extraJson;

  @AddToRuleKey private final WorkerTool worker;

  private static final ImmutableMap<UserFlavor, String> RAM_BUNDLE_STRINGS =
      ImmutableMap.of(
          JsFlavors.RAM_BUNDLE_INDEXED, "indexed",
          JsFlavors.RAM_BUNDLE_FILES, "files");

  protected JsBundle(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ImmutableSortedSet<SourcePath> libraries,
      ImmutableSet<String> entryPoints,
      Optional<Arg> extraJson,
      ImmutableList<ImmutableSet<SourcePath>> libraryPathGroups,
      String bundleName,
      WorkerTool worker) {
    super(buildTarget, projectFilesystem, params);
    this.extraJson = extraJson;
    this.bundleName = bundleName;
    this.entryPoints = entryPoints;
    this.libraryPathGroups = libraryPathGroups;
    this.libraries = libraries;
    this.worker = worker;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    SourcePathResolver sourcePathResolver = context.getSourcePathResolver();
    SourcePath jsOutputDir = getSourcePathToOutput();
    SourcePath sourceMapFile = getSourcePathToSourceMap();
    SourcePath resourcesDir = getSourcePathToResources();
    SourcePath miscDirPath = getSourcePathToMisc();

    String jobArgs =
        getJobArgs(sourcePathResolver, jsOutputDir, sourceMapFile, resourcesDir, miscDirPath);

    buildableContext.recordArtifact(sourcePathResolver.getRelativePath(jsOutputDir));
    buildableContext.recordArtifact(sourcePathResolver.getRelativePath(sourceMapFile));
    buildableContext.recordArtifact(sourcePathResolver.getRelativePath(resourcesDir));
    buildableContext.recordArtifact(sourcePathResolver.getRelativePath(miscDirPath));

    return ImmutableList.<Step>builder()
        .addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    sourcePathResolver.getRelativePath(
                        JsUtil.relativeToOutputRoot(
                            getBuildTarget(), getProjectFilesystem(), "")))))
        .add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    sourcePathResolver.getRelativePath(jsOutputDir))),
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    sourcePathResolver.getRelativePath(sourceMapFile).getParent())),
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    sourcePathResolver.getRelativePath(resourcesDir))),
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    sourcePathResolver.getRelativePath(miscDirPath))),
            JsUtil.workerShellStep(
                worker, jobArgs, getBuildTarget(), sourcePathResolver, getProjectFilesystem()))
        .build();
  }

  private String getJobArgs(
      SourcePathResolver sourcePathResolver,
      SourcePath jsOutputDir,
      SourcePath sourceMapFile,
      SourcePath resourcesDir,
      SourcePath miscDirPath) {

    ImmutableSortedSet<Flavor> flavors = getBuildTarget().getFlavors();

    return JsonBuilder.object()
        .addString("assetsDirPath", sourcePathResolver.getAbsolutePath(resourcesDir).toString())
        .addString(
            "bundlePath",
            String.format("%s/%s", sourcePathResolver.getAbsolutePath(jsOutputDir), bundleName))
        .addString("command", "bundle")
        .addArray("entryPoints", entryPoints.stream().collect(JsonBuilder.toArrayOfStrings()))
        .addArray(
            "libraryGroups",
            libraryPathGroups
                .stream()
                .map(
                    sourcePaths ->
                        sourcePaths
                            .stream()
                            .map(sourcePathResolver::getAbsolutePath)
                            .map(Path::toString)
                            .collect(JsonBuilder.toArrayOfStrings()))
                .collect(JsonBuilder.toArrayOfArrays()))
        .addArray(
            "libraries",
            libraries
                .stream()
                .map(sourcePathResolver::getAbsolutePath)
                .map(Path::toString)
                .collect(JsonBuilder.toArrayOfStrings()))
        .addString("platform", JsUtil.getPlatformString(flavors))
        .addString(
            "ramBundle",
            JsFlavors.RAM_BUNDLE_DOMAIN
                .getFlavor(flavors)
                .map(mode -> JsUtil.getValueForFlavor(RAM_BUNDLE_STRINGS, mode)))
        .addBoolean("release", flavors.contains(JsFlavors.RELEASE))
        .addString("rootPath", getProjectFilesystem().getRootPath().toString())
        .addString("sourceMapPath", sourcePathResolver.getAbsolutePath(sourceMapFile).toString())
        .addString("miscDirPath", sourcePathResolver.getAbsolutePath(miscDirPath).toString())
        .addRaw("extraData", extraJson.map(a -> Arg.stringify(a, sourcePathResolver)))
        .toString();
  }

  @Override
  public String getBundleName() {
    return bundleName;
  }
}
