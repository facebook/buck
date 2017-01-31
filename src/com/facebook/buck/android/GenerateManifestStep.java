/*
 * Copyright 2012-present Facebook, Inc.
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

import com.android.common.utils.ILogger;
import com.android.manifmerger.ManifestMerger2;
import com.android.manifmerger.MergingReport;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class GenerateManifestStep implements Step {

  private final ProjectFilesystem filesystem;
  private final Path skeletonManifestPath;
  private final ImmutableSet<Path> libraryManifestPaths;
  private Path outManifestPath;

  public GenerateManifestStep(
      ProjectFilesystem filesystem,
      Path skeletonManifestPath,
      ImmutableSet<Path> libraryManifestPaths,
      Path outManifestPath) {
    this.filesystem = filesystem;
    this.skeletonManifestPath = skeletonManifestPath;
    this.libraryManifestPaths = ImmutableSet.copyOf(libraryManifestPaths);
    this.outManifestPath = outManifestPath;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) {

    if (skeletonManifestPath.getNameCount() == 0) {
      throw new HumanReadableException("Skeleton manifest filepath is missing");
    }

    if (outManifestPath.getNameCount() == 0) {
      throw new HumanReadableException("Output Manifest filepath is missing");
    }

    outManifestPath = filesystem.resolve(outManifestPath);
    try {
      Files.createParentDirs(outManifestPath.toFile());
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return StepExecutionResult.ERROR;
    }

    List<File> libraryManifestFiles = Lists.newArrayList();

    for (Path path : libraryManifestPaths) {
      Path manifestPath = filesystem.getPathForRelativeExistingPath(path).toAbsolutePath();
      libraryManifestFiles.add(manifestPath.toFile());
    }

    File skeletonManifestFile =
        filesystem.getPathForRelativeExistingPath(skeletonManifestPath).toAbsolutePath().toFile();
    BuckEventAndroidLogger logger = new ManifestMergerLogger(context.getBuckEventBus());

    MergingReport mergingReport =
        mergeManifests(skeletonManifestFile, libraryManifestFiles, logger);

    String xmlText = mergingReport.getMergedDocument(MergingReport.MergedManifestKind.MERGED);
    if (context.getPlatform() == Platform.WINDOWS) {
      // Convert line endings to Lf on Windows.
      xmlText = xmlText.replace("\r\n", "\n");
    }
    try {
      filesystem.writeContentsToPath(xmlText, outManifestPath);
    } catch (IOException e) {
      throw new HumanReadableException(e, "Error writing manifest file");
    }

    return StepExecutionResult.SUCCESS;
  }

  private MergingReport mergeManifests(
      File mainManifestFile, List<File> libraryManifestFiles,
      BuckEventAndroidLogger logger) {
    try {
      MergingReport mergingReport = ManifestMerger2
          .newMerger(mainManifestFile, logger, ManifestMerger2.MergeType.APPLICATION)
          .withFeatures(
              ManifestMerger2.Invoker.Feature.NO_PLACEHOLDER_REPLACEMENT,
              ManifestMerger2.Invoker.Feature.REMOVE_TOOLS_DECLARATIONS,
              ManifestMerger2.Invoker.Feature.SKIP_BLAME)
          .addLibraryManifests(Iterables.toArray(libraryManifestFiles, File.class))
          .merge();
      if (mergingReport.getResult().isError()) {
        for (MergingReport.Record record : mergingReport.getLoggingRecords()) {
          logger.error(null, record.toString());
        }
        throw new HumanReadableException("Error generating manifest file");
      }
      return mergingReport;
    } catch (ManifestMerger2.MergeFailureException e) {
      throw new HumanReadableException(e, "Error generating manifest file");
    }
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("generate-manifest %s", skeletonManifestPath);
  }

  @Override
  public String getShortName() {
    return "generate_manifest";
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof GenerateManifestStep)) {
      return false;
    }

    GenerateManifestStep that = (GenerateManifestStep) obj;
    return Objects.equal(this.skeletonManifestPath, that.skeletonManifestPath) &&
        Objects.equal(this.libraryManifestPaths, that.libraryManifestPaths) &&
        Objects.equal(this.outManifestPath, that.outManifestPath);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        skeletonManifestPath,
        libraryManifestPaths,
        outManifestPath);
  }

  private static class ManifestMergerLogger extends BuckEventAndroidLogger implements ILogger {
    public ManifestMergerLogger(BuckEventBus eventBus) {
      super(eventBus);
    }

    @Override
    public void info(String msgFormat, Object... args) {
      // suppress
    }

    @Override
    public void verbose(String msgFormat, Object... args) {
      // suppress
    }
  }
}
