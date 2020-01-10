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

package com.facebook.buck.features.zip.rules;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.zip.collect.OnDuplicateEntry;
import com.facebook.buck.util.zip.collect.ZipEntrySourceCollection;
import com.facebook.buck.util.zip.collect.ZipEntrySourceCollectionBuilder;
import com.facebook.buck.util.zip.collect.ZipEntrySourceCollectionWriter;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A {@link Step} that copies a set of files and entries from given zip files to a zip file.
 *
 * <p>This implementation does not use filesystem to store files temporarily.
 */
public class CopyToZipStep implements Step {

  private final ProjectFilesystem projectFilesystem;
  private final Path outputPath;
  private final ImmutableMap<Path, Path> entryPathToAbsolutePathMap;
  private final ImmutableList<Path> zipSourceAbsolutePaths;
  private final ImmutableSet<Pattern> entriesToExclude;
  private final OnDuplicateEntry onDuplicateEntry;

  public CopyToZipStep(
      ProjectFilesystem projectFilesystem,
      Path outputPath,
      ImmutableMap<Path, Path> entryPathToAbsolutePathMap,
      ImmutableList<Path> zipSourceAbsolutePaths,
      ImmutableSet<Pattern> entriesToExclude,
      OnDuplicateEntry onDuplicateEntry) {
    this.outputPath = outputPath;
    this.zipSourceAbsolutePaths = zipSourceAbsolutePaths;
    this.projectFilesystem = projectFilesystem;
    this.entryPathToAbsolutePathMap = entryPathToAbsolutePathMap;
    this.entriesToExclude = entriesToExclude;
    this.onDuplicateEntry = onDuplicateEntry;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    if (projectFilesystem.exists(outputPath)) {
      context.postEvent(
          ConsoleEvent.severe("Attempting to overwrite an existing zip: %s", outputPath));
      return StepExecutionResults.ERROR;
    }

    ZipEntrySourceCollection zipEntrySourceCollection = buildCollection();
    try {
      new ZipEntrySourceCollectionWriter(projectFilesystem)
          .copyToZip(zipEntrySourceCollection, outputPath);
      return StepExecutionResults.SUCCESS;
    } catch (IOException e) {
      throw new HumanReadableException(e, "Cannot create zip %s: %s", outputPath, e.getMessage());
    }
  }

  private ZipEntrySourceCollection buildCollection() {
    ZipEntrySourceCollectionBuilder builder =
        new ZipEntrySourceCollectionBuilder(entriesToExclude, onDuplicateEntry);
    for (Path zipPath : zipSourceAbsolutePaths) {
      try {
        builder.addZipFile(zipPath);
      } catch (IOException e) {
        throw new HumanReadableException(
            e, "Error while reading archive entries from %s: %s", zipPath, e.getMessage());
      }
    }
    for (Map.Entry<Path, Path> pathEntry : entryPathToAbsolutePathMap.entrySet()) {
      builder.addFile(pathEntry.getKey().toString(), pathEntry.getValue());
    }
    return builder.build();
  }

  @Override
  public String getShortName() {
    return "zip";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    StringBuilder result = new StringBuilder();
    result.append("Create zip archive ").append(outputPath);
    if (!entryPathToAbsolutePathMap.isEmpty()) {
      result.append(" with source files [");
      Joiner.on(", ").withKeyValueSeparator('=').appendTo(result, entryPathToAbsolutePathMap);
      result.append("]");
      if (!zipSourceAbsolutePaths.isEmpty()) {
        result.append(" and");
      }
    }
    if (!zipSourceAbsolutePaths.isEmpty()) {
      result.append(" with source zip files [");
      Joiner.on(", ").appendTo(result, zipSourceAbsolutePaths);
      result.append("]");
    }
    if (!entriesToExclude.isEmpty()) {
      result.append(" excluding entries matching [");
      Joiner.on(", ").appendTo(result, entriesToExclude);
      result.append("]");
    }
    return result.toString();
  }
}
