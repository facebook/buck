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

package com.facebook.buck.android;

import com.facebook.buck.android.resources.ResourcesZipBuilder;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.Files;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Merges resources from third party jars for exo-for-resources. */
public class MergeThirdPartyJarResources extends ModernBuildRule<MergeThirdPartyJarResources>
    implements Buildable {
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> pathsToThirdPartyJars;
  @AddToRuleKey private final OutputPath mergedPath;

  protected MergeThirdPartyJarResources(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      ImmutableSet<SourcePath> pathsToThirdPartyJars) {
    super(buildTarget, projectFilesystem, ruleFinder, MergeThirdPartyJarResources.class);
    this.pathsToThirdPartyJars = ImmutableSortedSet.copyOf(pathsToThirdPartyJars);
    this.mergedPath = new OutputPath("java.resources");
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(mergedPath);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext buildContext,
      ProjectFilesystem filesystem,
      OutputPathResolver outputPathResolver,
      BuildCellRelativePathFactory buildCellPathFactory) {
    ImmutableSet<Path> thirdPartyJars =
        buildContext.getSourcePathResolver().getAllAbsolutePaths(pathsToThirdPartyJars);
    return ImmutableList.of(
        createMergedThirdPartyJarsStep(
            thirdPartyJars, filesystem.resolve(outputPathResolver.resolvePath(mergedPath))));
  }

  private Step createMergedThirdPartyJarsStep(
      ImmutableSet<Path> thirdPartyJars, Path absoluteMergedPath) {
    return new AbstractExecutionStep("merging_third_party_jar_resources") {
      @Override
      public StepExecutionResult execute(ExecutionContext context) throws IOException {
        try (ResourcesZipBuilder builder = new ResourcesZipBuilder(absoluteMergedPath)) {
          for (Path jar : thirdPartyJars) {
            try (ZipFile base = new ZipFile(jar.toFile())) {
              for (ZipEntry inputEntry : Collections.list(base.entries())) {
                if (inputEntry.isDirectory()) {
                  continue;
                }
                String name = inputEntry.getName();
                String ext = Files.getFileExtension(name);
                String filename = Paths.get(name).getFileName().toString();
                // Android's ApkBuilder filters out a lot of files from Java resources. Try to
                // match its behavior.
                // See
                // https://android.googlesource.com/platform/sdk/+/jb-release/sdkmanager/libs/sdklib/src/com/android/sdklib/build/ApkBuilder.java
                if (name.startsWith(".")
                    || name.endsWith("~")
                    || name.startsWith("META-INF")
                    || "aidl".equalsIgnoreCase(ext)
                    || "rs".equalsIgnoreCase(ext)
                    || "rsh".equalsIgnoreCase(ext)
                    || "d".equalsIgnoreCase(ext)
                    || "java".equalsIgnoreCase(ext)
                    || "scala".equalsIgnoreCase(ext)
                    || "class".equalsIgnoreCase(ext)
                    || "scc".equalsIgnoreCase(ext)
                    || "swp".equalsIgnoreCase(ext)
                    || "thumbs.db".equalsIgnoreCase(filename)
                    || "picasa.ini".equalsIgnoreCase(filename)
                    || "package.html".equalsIgnoreCase(filename)
                    || "overview.html".equalsIgnoreCase(filename)) {
                  continue;
                }
                try (InputStream inputStream = base.getInputStream(inputEntry)) {
                  builder.addEntry(
                      inputStream,
                      inputEntry.getSize(),
                      inputEntry.getCrc(),
                      name,
                      Deflater.NO_COMPRESSION,
                      false);
                }
              }
            }
          }
        }
        return StepExecutionResults.SUCCESS;
      }
    };
  }
}
