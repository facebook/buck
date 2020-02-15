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

package com.facebook.buck.features.js;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.UserFlavor;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.shell.WorkerTool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.json.JsonBuilder;
import com.facebook.buck.util.json.JsonBuilder.ObjectBuilder;
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
      String bundleName,
      WorkerTool worker) {
    super(buildTarget, projectFilesystem, params);
    this.extraJson = extraJson;
    this.bundleName = bundleName;
    this.entryPoints = entryPoints;
    this.libraries = libraries;
    this.worker = worker;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    SourcePathResolverAdapter sourcePathResolverAdapter = context.getSourcePathResolver();
    SourcePath jsOutputDir = getSourcePathToOutput();
    SourcePath sourceMapFile = getSourcePathToSourceMap();
    SourcePath resourcesDir = getSourcePathToResources();
    SourcePath miscDirPath = getSourcePathToMisc();

    ObjectBuilder jobArgs =
        getJobArgs(
            sourcePathResolverAdapter, jsOutputDir, sourceMapFile, resourcesDir, miscDirPath);

    buildableContext.recordArtifact(sourcePathResolverAdapter.getRelativePath(jsOutputDir));
    buildableContext.recordArtifact(sourcePathResolverAdapter.getRelativePath(sourceMapFile));
    buildableContext.recordArtifact(sourcePathResolverAdapter.getRelativePath(resourcesDir));
    buildableContext.recordArtifact(sourcePathResolverAdapter.getRelativePath(miscDirPath));

    return ImmutableList.<Step>builder()
        .addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    sourcePathResolverAdapter.getRelativePath(
                        JsUtil.relativeToOutputRoot(
                            getBuildTarget(), getProjectFilesystem(), "")))))
        .add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    sourcePathResolverAdapter.getRelativePath(jsOutputDir))),
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    sourcePathResolverAdapter.getRelativePath(sourceMapFile).getParent())),
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    sourcePathResolverAdapter.getRelativePath(resourcesDir))),
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    sourcePathResolverAdapter.getRelativePath(miscDirPath))),
            JsUtil.jsonWorkerShellStepAddingFlavors(
                worker,
                jobArgs,
                getBuildTarget(),
                sourcePathResolverAdapter,
                getProjectFilesystem()))
        .build();
  }

  private ObjectBuilder getJobArgs(
      SourcePathResolverAdapter sourcePathResolverAdapter,
      SourcePath jsOutputDir,
      SourcePath sourceMapFile,
      SourcePath resourcesDir,
      SourcePath miscDirPath) {

    ImmutableSortedSet<Flavor> flavors = getBuildTarget().getFlavors().getSet();

    return JsonBuilder.object()
        .addString(
            "assetsDirPath", sourcePathResolverAdapter.getAbsolutePath(resourcesDir).toString())
        .addString(
            "bundlePath",
            String.format(
                "%s/%s", sourcePathResolverAdapter.getAbsolutePath(jsOutputDir), bundleName))
        .addString("command", "bundle")
        .addArray("entryPoints", entryPoints.stream().collect(JsonBuilder.toArrayOfStrings()))
        .addArray(
            "libraries",
            libraries.stream()
                .map(sourcePathResolverAdapter::getAbsolutePath)
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
        .addString(
            "sourceMapPath", sourcePathResolverAdapter.getAbsolutePath(sourceMapFile).toString())
        .addString("miscDirPath", sourcePathResolverAdapter.getAbsolutePath(miscDirPath).toString())
        .addRaw("extraData", extraJson.map(a -> Arg.stringify(a, sourcePathResolverAdapter)));
  }

  @Override
  public String getBundleName() {
    return bundleName;
  }

  @Override
  public JsDependenciesOutputs getJsDependenciesOutputs(ActionGraphBuilder graphBuilder) {
    BuildTarget target = getBuildTarget().withAppendedFlavors(JsFlavors.DEPENDENCY_FILE);
    return (JsDependenciesOutputs) graphBuilder.requireRule(target);
  }
}
