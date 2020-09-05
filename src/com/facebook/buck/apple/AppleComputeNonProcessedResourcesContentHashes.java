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

package com.facebook.buck.apple;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Rule that calculates hashes of files and directories for bundle resources (provided via
 * `apple_resource` rule) that should not be processed before copying to result bundle. Those hashes
 * are written in a JSON file which is the output of this rule. Hashing of processed resources is
 * handled by {@link AppleProcessResources} rule and cannot be done here because the processing
 * results are not ready yet to be hashed.
 */
public class AppleComputeNonProcessedResourcesContentHashes
    extends ModernBuildRule<AppleComputeNonProcessedResourcesContentHashes.Impl> {

  public static final Flavor FLAVOR = InternalFlavor.of("apple-non-processed-resources-hashes");

  public AppleComputeNonProcessedResourcesContentHashes(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      AppleBundleResources resources,
      AppleBundleDestinations destinations) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new AppleComputeNonProcessedResourcesContentHashes.Impl(resources, destinations));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().output);
  }

  /** Internal buildable implementation */
  static class Impl implements Buildable {
    @AddToRuleKey private final OutputPath output;
    @AddToRuleKey private final AppleBundleResources resources;
    @AddToRuleKey private final AppleBundleDestinations destinations;

    Impl(AppleBundleResources resources, AppleBundleDestinations destinations) {
      this.resources = resources;
      this.destinations = destinations;
      output = new OutputPath("non-processed-resources-hashes.json");
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {

      ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();
      ImmutableMap.Builder<RelPath, String> pathToHashBuilder = ImmutableMap.builder();

      Stream.concat(resources.getResourceFiles().stream(), resources.getResourceDirs().stream())
          .filter(
              pathWithDestination ->
                  !AppleProcessResources.shouldBeProcessed(
                      pathWithDestination, buildContext.getSourcePathResolver()))
          .forEach(
              pathWithDestination -> {
                StringBuilder hashBuilder = new StringBuilder();
                AbsPath absPath =
                    buildContext
                        .getSourcePathResolver()
                        .getAbsolutePath(pathWithDestination.getSourcePath());
                stepsBuilder.add(
                    new AppleComputeFileOrDirectoryHashStep(
                        hashBuilder, absPath, filesystem, true, true));
                stepsBuilder.add(
                    new AbstractExecutionStep("memoize-hash-per-file-or-directory") {
                      @Override
                      public StepExecutionResult execute(StepExecutionContext context) {
                        RelPath destinationDirectoryPath =
                            RelPath.of(pathWithDestination.getDestination().getPath(destinations));
                        Path fileName =
                            buildContext
                                .getSourcePathResolver()
                                .getAbsolutePath(pathWithDestination.getSourcePath())
                                .getFileName();
                        RelPath destinationFilePath =
                            destinationDirectoryPath.resolveRel(fileName.toString());
                        pathToHashBuilder.put(destinationFilePath, hashBuilder.toString());
                        return StepExecutionResults.SUCCESS;
                      }
                    });
              });

      for (SourcePathWithAppleBundleDestination pathWithDestination :
          resources.getDirsContainingResourceDirs()) {
        AbsPath absPath =
            buildContext
                .getSourcePathResolver()
                .getAbsolutePath(pathWithDestination.getSourcePath());
        ImmutableMap.Builder<RelPath, String> builder = ImmutableMap.builder();
        stepsBuilder.add(
            new AppleComputeDirectoryFirstLevelContentHashesStep(absPath, filesystem, builder));
        stepsBuilder.add(
            new AbstractExecutionStep("memoize-directory-content-hashes") {
              @Override
              public StepExecutionResult execute(StepExecutionContext context) {
                Path destinationPath = pathWithDestination.getDestination().getPath(destinations);
                builder
                    .build()
                    .forEach(
                        (pathRelativeToContainerDir, hash) -> {
                          RelPath pathRelativeToBundleRoot =
                              RelPath.of(destinationPath).resolve(pathRelativeToContainerDir);
                          pathToHashBuilder.put(pathRelativeToBundleRoot, hash);
                        });
                return StepExecutionResults.SUCCESS;
              }
            });
      }

      stepsBuilder.add(
          new AppleWriteHashPerFileStep(
              "persist-non-processed-resources-hashes",
              pathToHashBuilder::build,
              outputPathResolver.resolvePath(output).getPath(),
              filesystem));

      return stepsBuilder.build();
    }
  }
}
