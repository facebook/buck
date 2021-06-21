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

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.rules.modern.PublicOutputPath;
import com.facebook.buck.shell.WorkerTool;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.json.JsonBuilder;
import com.facebook.buck.util.json.JsonBuilder.ObjectBuilder;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/** JS file rule converted to MBR */
public class JsFile extends ModernBuildRule<JsFile.JsFileBuildable> {

  private JsFile(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      JsFileBuildable buildable) {
    super(buildTarget, projectFilesystem, ruleFinder, buildable);
  }

  /** Internal JsFile implementation for Buildable interface */
  static class JsFileBuildable implements Buildable {

    @AddToRuleKey private final BuildTarget buildTarget;
    @AddToRuleKey private final Optional<Arg> extraJson;
    @AddToRuleKey private final WorkerTool workerTool;
    @AddToRuleKey final OutputPath output;
    @AddToRuleKey final Optional<String> transformProfile;
    @AddToRuleKey private final boolean withDownwardApi;
    @AddToRuleKey private final JsSourcePath src;
    @AddToRuleKey private final Optional<String> basePath;
    @AddToRuleKey private final boolean release;

    JsFileBuildable(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        Optional<Arg> extraJson,
        WorkerTool workerTool,
        JsSourcePath src,
        Optional<String> basePath,
        boolean withDownwardApi,
        boolean release) {
      this.buildTarget = buildTarget;
      this.extraJson = extraJson;
      this.workerTool = workerTool;
      this.output =
          new PublicOutputPath(
              BuildTargetPaths.getGenPath(
                  projectFilesystem.getBuckPaths(), buildTarget, "%s.jsfile"));
      this.transformProfile = JsFlavors.transformProfileArg(buildTarget.getFlavors());
      this.src = src;
      this.basePath = basePath;
      this.release = release;
      this.withDownwardApi = withDownwardApi;
    }

    @VisibleForTesting
    SourcePath getSource() {
      return src.getPath();
    }

    @VisibleForTesting
    boolean isRelease() {
      return release;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {

      SourcePathResolverAdapter sourcePathResolverAdapter = buildContext.getSourcePathResolver();
      String outputPath = filesystem.resolve(outputPathResolver.resolvePath(output)).toString();

      return ImmutableList.of(
          JsUtil.jsonWorkerShellStepAddingFlavors(
              workerTool,
              getJobArgs(buildContext, filesystem, outputPath)
                  .addRaw("extraData", getExtraJson(sourcePathResolverAdapter)),
              buildTarget,
              sourcePathResolverAdapter,
              filesystem,
              withDownwardApi));
    }

    ObjectBuilder getJobArgs(
        BuildContext buildContext, ProjectFilesystem filesystem, String outputPath) {
      SourcePathResolverAdapter sourcePathResolverAdapter = buildContext.getSourcePathResolver();
      AbsPath srcPath = sourcePathResolverAdapter.getAbsolutePath(src.getPath());
      return JsonBuilder.object()
          .addString("command", "transform")
          .addString("outputFilePath", outputPath)
          .addString(
              "sourceJsFilePath",
              src.getInnerPath().map(srcPath::resolve).orElse(srcPath).toString())
          .addString("sourceJsFileName", getVirtualPath(buildContext, filesystem, src))
          .addString("transformProfile", transformProfile)
          .addBoolean("release", release);
    }

    @Nullable
    BuildTarget getSourceBuildTarget() {
      return Stream.of(src.getPath())
          .filter(BuildTargetSourcePath.class::isInstance)
          .map(BuildTargetSourcePath.class::cast)
          .map(BuildTargetSourcePath::getTarget)
          .findAny()
          .orElse(null);
    }

    private Optional<String> getExtraJson(SourcePathResolverAdapter sourcePathResolverAdapter) {
      return extraJson.map(a -> Arg.stringify(a, sourcePathResolverAdapter));
    }

    private String getVirtualPath(
        BuildContext buildContext, ProjectFilesystem projectFilesystem, JsSourcePath source) {
      RelPath virtualPath =
          basePath
              .map(
                  basePath ->
                      changePathPrefix(
                              source.getPath(),
                              basePath,
                              projectFilesystem,
                              buildContext.getSourcePathResolver(),
                              buildContext.getCellPathResolver(),
                              buildTarget.getUnflavoredBuildTarget())
                          .resolveRel(source.getInnerPath().orElse("")))
              .orElse(buildContext.getSourcePathResolver().getCellUnsafeRelPath(src.getPath()));
      return PathFormatter.pathWithUnixSeparators(virtualPath);
    }

    private static RelPath changePathPrefix(
        SourcePath sourcePath,
        String basePath,
        ProjectFilesystem projectFilesystem,
        SourcePathResolverAdapter sourcePathResolverAdapter,
        CellPathResolver cellPathResolver,
        UnflavoredBuildTarget target) {
      AbsPath directoryOfBuildFile =
          cellPathResolver.resolveCellRelativePath(target.getCellRelativeBasePath());
      AbsPath transplantTo = MorePaths.normalize(directoryOfBuildFile.resolve(basePath));
      AbsPath absolutePath =
          PathSourcePath.from(sourcePath)
              .map(
                  pathSourcePath -> // for sub paths, replace the leading directory with the base
                      // path
                      transplantTo.resolve(
                          MorePaths.relativize(
                              directoryOfBuildFile,
                              sourcePathResolverAdapter.getAbsolutePath(sourcePath))))
              .orElse(transplantTo); // build target output paths are replaced completely

      return (projectFilesystem
          .getPathRelativeToProjectRoot(absolutePath.getPath())
          .map(RelPath::of)
          .orElseThrow(
              () ->
                  new HumanReadableException(
                      "%s: Using '%s' as base path for '%s' would move the file "
                          + "out of the project root.",
                      target,
                      basePath,
                      sourcePathResolverAdapter.getCellUnsafeRelPath(sourcePath))));
    }
  }

  @Override
  public BuildTargetSourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().output);
  }

  BuildTarget getSourceBuildTarget() {
    return getBuildable().getSourceBuildTarget();
  }

  /** Creates a JS File */
  public static JsFile create(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      Optional<Arg> extraJson,
      WorkerTool worker,
      JsSourcePath src,
      Optional<String> basePath,
      boolean withDownwardApi,
      boolean release) {
    JsFileBuildable buildable =
        new JsFileBuildable(
            buildTarget,
            projectFilesystem,
            extraJson,
            worker,
            src,
            basePath,
            withDownwardApi,
            release);
    return new JsFile(buildTarget, projectFilesystem, ruleFinder, buildable);
  }
}
