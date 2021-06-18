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
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
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
import java.nio.file.Path;
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
    @AddToRuleKey private final SourcePath src;
    @AddToRuleKey private final Optional<String> subPath;
    @AddToRuleKey private final Optional<String> virtualPath;
    @AddToRuleKey private final boolean release;

    JsFileBuildable(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        Optional<Arg> extraJson,
        WorkerTool workerTool,
        SourcePath src,
        Optional<String> subPath,
        Optional<Path> virtualPath,
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
      this.subPath = subPath;
      this.virtualPath = virtualPath.map(PathFormatter::pathWithUnixSeparators);
      this.release = release;
      this.withDownwardApi = withDownwardApi;
    }

    @VisibleForTesting
    SourcePath getSource() {
      return src;
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
              getJobArgs(sourcePathResolverAdapter, outputPath)
                  .addRaw("extraData", getExtraJson(sourcePathResolverAdapter)),
              buildTarget,
              sourcePathResolverAdapter,
              filesystem,
              withDownwardApi));
    }

    ObjectBuilder getJobArgs(
        SourcePathResolverAdapter sourcePathResolverAdapter, String outputPath) {
      AbsPath srcPath = sourcePathResolverAdapter.getAbsolutePath(src);
      return JsonBuilder.object()
          .addString("command", "transform")
          .addString("outputFilePath", outputPath)
          .addString("sourceJsFilePath", subPath.map(srcPath::resolve).orElse(srcPath).toString())
          .addString(
              "sourceJsFileName",
              virtualPath.orElseGet(
                  () ->
                      PathFormatter.pathWithUnixSeparators(
                          sourcePathResolverAdapter.getCellUnsafeRelPath(src))))
          .addString("transformProfile", transformProfile)
          .addBoolean("release", release);
    }

    @Nullable
    BuildTarget getSourceBuildTarget() {
      return Stream.of(src)
          .filter(BuildTargetSourcePath.class::isInstance)
          .map(BuildTargetSourcePath.class::cast)
          .map(BuildTargetSourcePath::getTarget)
          .findAny()
          .orElse(null);
    }

    private Optional<String> getExtraJson(SourcePathResolverAdapter sourcePathResolverAdapter) {
      return extraJson.map(a -> Arg.stringify(a, sourcePathResolverAdapter));
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
      SourcePath src,
      Optional<String> subPath,
      Optional<Path> virtualPath,
      boolean withDownwardApi,
      boolean release) {
    JsFileBuildable buildable =
        new JsFileBuildable(
            buildTarget,
            projectFilesystem,
            extraJson,
            worker,
            src,
            subPath,
            virtualPath,
            withDownwardApi,
            release);
    return new JsFile(buildTarget, projectFilesystem, ruleFinder, buildable);
  }
}
