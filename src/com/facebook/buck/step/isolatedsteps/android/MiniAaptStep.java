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

package com.facebook.buck.step.isolatedsteps.android;

import com.facebook.buck.android.aapt.RDotTxtEntry;
import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.util.ThrowingPrintWriter;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;
import javax.xml.xpath.XPathExpressionException;

/**
 * Step which parses resources in an android {@code res} directory and compiles them into a {@code
 * R.txt} file, following the exact same format as the Android build tool {@code aapt}.
 *
 * <p>
 */
public class MiniAaptStep extends IsolatedStep {

  private final RelPath resDirectory;
  private final RelPath pathToOutputFile;
  private final ImmutableSet<RelPath> pathsToSymbolsOfDeps;

  public MiniAaptStep(
      RelPath resDirectory, RelPath pathToOutputFile, ImmutableSet<RelPath> pathsToSymbolsOfDeps) {
    this.resDirectory = resDirectory;
    this.pathToOutputFile = pathToOutputFile;
    this.pathsToSymbolsOfDeps = pathsToSymbolsOfDeps;
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {
    ImmutableSet<RDotTxtEntry> references;

    MiniAapt miniAapt =
        new MiniAapt(
            pathsToSymbolsOfDeps.stream()
                .map(
                    path ->
                        ProjectFilesystemUtils.getPathForRelativePath(
                            context.getRuleCellRoot(), path))
                .collect(ImmutableSet.toImmutableSet()));
    try {
      ImmutableMap<Path, Path> files = getAllResourceFiles(context.getRuleCellRoot(), resDirectory);
      references = miniAapt.processAllFiles(files);
    } catch (MiniAapt.ResourceParseException | XPathExpressionException e) {
      context.logError(e, "Error parsing resources to generate resource IDs for %s.", resDirectory);
      return StepExecutionResults.ERROR;
    }

    Set<RDotTxtEntry> missing = miniAapt.verifyReferences(references);
    if (!missing.isEmpty()) {
      context
          .getIsolatedEventBus()
          .post(
              ConsoleEvent.severe(
                  "The following resources were not found when processing %s: \n%s\n",
                  resDirectory, Joiner.on('\n').join(missing)));
      return StepExecutionResults.ERROR;
    }

    try (ThrowingPrintWriter writer =
        new ThrowingPrintWriter(
            ProjectFilesystemUtils.newFileOutputStream(
                context.getRuleCellRoot(), pathToOutputFile.getPath()))) {
      Set<RDotTxtEntry> sortedResources =
          ImmutableSortedSet.copyOf(
              Ordering.natural(), miniAapt.getResourceCollector().getResources());
      for (RDotTxtEntry entry : sortedResources) {
        writer.printf("%s %s %s %s\n", entry.idType, entry.type, entry.name, entry.idValue);
      }
    }

    return StepExecutionResults.SUCCESS;
  }

  private static ImmutableMap<Path, Path> getAllResourceFiles(
      AbsPath root, RelPath resourceDirectory) throws IOException {
    return ProjectFilesystemUtils.getFilesUnderPath(
            root,
            resourceDirectory.getPath(),
            EnumSet.of(FileVisitOption.FOLLOW_LINKS),
            ProjectFilesystemUtils.getIgnoreFilter(root, false, ImmutableSet.of()))
        .stream()
        .collect(
            ImmutableMap.toImmutableMap(
                path -> MorePaths.relativize(resourceDirectory.getPath(), path),
                path -> ProjectFilesystemUtils.getPathForRelativePath(root, path)));
  }

  @Override
  public String getShortName() {
    return "generate_resource_ids";
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    return getShortName() + " " + resDirectory;
  }
}
