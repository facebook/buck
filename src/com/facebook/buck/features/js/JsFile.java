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
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.features.js.JsFile.AbstractImpl;
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
public class JsFile<T extends AbstractImpl> extends ModernBuildRule<T> {

  private JsFile(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      T impl) {
    super(buildTarget, projectFilesystem, ruleFinder, impl);
  }

  /** Internal JsFile specific abstract class with general implementation for Buildable interface */
  abstract static class AbstractImpl implements Buildable {

    @AddToRuleKey private final BuildTarget buildTarget;
    @AddToRuleKey private final Optional<Arg> extraJson;
    @AddToRuleKey private final WorkerTool workerTool;
    @AddToRuleKey final OutputPath output;

    AbstractImpl(
        BuildTarget buildTarget,
        Optional<Arg> extraJson,
        WorkerTool workerTool,
        ProjectFilesystem projectFilesystem) {
      this.buildTarget = buildTarget;
      this.extraJson = extraJson;
      this.workerTool = workerTool;
      this.output =
          new PublicOutputPath(
              BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s.jsfile"));
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
              filesystem));
    }

    abstract ObjectBuilder getJobArgs(
        SourcePathResolverAdapter sourcePathResolverAdapter, String outputPath);

    @Nullable
    abstract BuildTarget getSourceBuildTarget(SourcePathRuleFinder ruleFinder);

    private Optional<String> getExtraJson(SourcePathResolverAdapter sourcePathResolverAdapter) {
      return extraJson.map(a -> Arg.stringify(a, sourcePathResolverAdapter));
    }
  }

  @Override
  public BuildTargetSourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().output);
  }

  BuildTarget getSourceBuildTarget(SourcePathRuleFinder ruleFinder) {
    return getBuildable().getSourceBuildTarget(ruleFinder);
  }

  /** Creates JS file dev rule implementation */
  public static JsFile<JsFileDev> create(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      Optional<Arg> extraJson,
      WorkerTool worker,
      SourcePath src,
      Optional<String> subPath,
      Optional<Path> virtualPath) {
    return new JsFile<>(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new JsFileDev(
            buildTarget, projectFilesystem, extraJson, worker, src, subPath, virtualPath));
  }

  /** JS file dev rule implementation */
  static class JsFileDev extends AbstractImpl {
    @AddToRuleKey private final SourcePath src;
    @AddToRuleKey private final Optional<String> subPath;
    @AddToRuleKey private final Optional<String> virtualPath;

    private JsFileDev(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        Optional<Arg> extraJson,
        WorkerTool workerTool,
        SourcePath src,
        Optional<String> subPath,
        Optional<Path> virtualPath) {
      super(buildTarget, extraJson, workerTool, projectFilesystem);
      this.src = src;
      this.subPath = subPath;
      this.virtualPath = virtualPath.map(PathFormatter::pathWithUnixSeparators);
    }

    @Override
    ObjectBuilder getJobArgs(
        SourcePathResolverAdapter sourcePathResolverAdapter, String outputPath) {
      Path srcPath = sourcePathResolverAdapter.getAbsolutePath(src);
      return JsonBuilder.object()
          .addString("command", "transform")
          .addString("outputFilePath", outputPath)
          .addString("sourceJsFilePath", subPath.map(srcPath::resolve).orElse(srcPath).toString())
          .addString(
              "sourceJsFileName",
              virtualPath.orElseGet(
                  () ->
                      PathFormatter.pathWithUnixSeparators(
                          sourcePathResolverAdapter.getRelativePath(src))));
    }

    @Nullable
    @Override
    BuildTarget getSourceBuildTarget(SourcePathRuleFinder ruleFinder) {
      return Stream.of(src)
          .filter(BuildTargetSourcePath.class::isInstance)
          .map(BuildTargetSourcePath.class::cast)
          .map(BuildTargetSourcePath::getTarget)
          .findAny()
          .orElse(null);
    }

    @VisibleForTesting
    SourcePath getSource() {
      return src;
    }

    @VisibleForTesting
    Optional<String> getVirtualPath() {
      return virtualPath;
    }
  }

  /** Creates JS file release rule implementation */
  public static JsFile<JsFileRelease> create(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      Optional<Arg> extraJson,
      WorkerTool worker,
      BuildTargetSourcePath devFile) {
    return new JsFile<>(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new JsFileRelease(buildTarget, projectFilesystem, extraJson, worker, devFile));
  }

  /** JS file release rule implementation */
  static class JsFileRelease extends AbstractImpl {

    @AddToRuleKey private final BuildTargetSourcePath devFile;

    JsFileRelease(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        Optional<Arg> extraJson,
        WorkerTool workerTool,
        BuildTargetSourcePath devFile) {
      super(buildTarget, extraJson, workerTool, projectFilesystem);
      this.devFile = devFile;
    }

    @Override
    ObjectBuilder getJobArgs(
        SourcePathResolverAdapter sourcePathResolverAdapter, String outputPath) {
      return JsonBuilder.object()
          .addString("command", "optimize")
          .addString("outputFilePath", outputPath)
          .addString(
              "transformedJsFilePath",
              sourcePathResolverAdapter.getAbsolutePath(devFile).toString());
    }

    @Override
    @Nullable
    @SuppressWarnings("unchecked")
    BuildTarget getSourceBuildTarget(SourcePathRuleFinder ruleFinder) {
      JsFile<JsFileDev> rule = (JsFile<JsFileDev>) ruleFinder.getRule(devFile);
      JsFileDev devRule = rule.getBuildable();
      return devRule.getSourceBuildTarget(ruleFinder);
    }
  }
}
