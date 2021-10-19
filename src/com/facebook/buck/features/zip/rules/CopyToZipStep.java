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

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.features.zip.rules.utils.ZipUtils;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.util.zip.collect.OnDuplicateEntry;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * A {@link Step} that copies a set of files and entries from given zip files to a zip file.
 *
 * <p>This implementation does not use filesystem to store files temporarily.
 */
public class CopyToZipStep extends IsolatedStep {

  private final RelPath outputPath;
  private final ImmutableMap<RelPath, RelPath> entryMap;
  private final ImmutableList<RelPath> zipSources;
  private final ImmutableSet<Pattern> entriesToExclude;
  private final OnDuplicateEntry onDuplicateEntry;

  public CopyToZipStep(
      RelPath outputPath,
      ImmutableMap<RelPath, RelPath> entryMap,
      ImmutableList<RelPath> zipSources,
      ImmutableSet<Pattern> entriesToExclude,
      OnDuplicateEntry onDuplicateEntry) {
    this.outputPath = outputPath;
    this.zipSources = zipSources;
    this.entryMap = entryMap;
    this.entriesToExclude = entriesToExclude;
    this.onDuplicateEntry = onDuplicateEntry;
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {
    AbsPath ruleCellRoot = context.getRuleCellRoot();
    if (ProjectFilesystemUtils.exists(ruleCellRoot, outputPath.getPath())) {
      context.postEvent(
          ConsoleEvent.severe("Attempting to overwrite an existing zip: %s", outputPath));
      return StepExecutionResults.ERROR;
    }

    try {
      ZipUtils.createZipFile(
          ruleCellRoot, entryMap, zipSources, entriesToExclude, onDuplicateEntry, outputPath);
      return StepExecutionResults.SUCCESS;
    } catch (IOException e) {
      throw new HumanReadableException(e, "Cannot create zip %s: %s", outputPath, e.getMessage());
    }
  }

  @Override
  public String getShortName() {
    return "zip";
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    StringBuilder result = new StringBuilder();
    result.append("Create zip archive ").append(outputPath);
    if (!entryMap.isEmpty()) {
      result.append(" with source files [");
      Joiner.on(", ").withKeyValueSeparator('=').appendTo(result, entryMap);
      result.append("]");
      if (!zipSources.isEmpty()) {
        result.append(" and");
      }
    }
    if (!zipSources.isEmpty()) {
      result.append(" with source zip files [");
      Joiner.on(", ").appendTo(result, zipSources);
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
