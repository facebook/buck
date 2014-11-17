/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.shell;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@link Step} that takes a collection of entries in a directory and creates a set of read-only
 * symlinks (with the same structure) to the original entries in a new directory.
 */
public class SymlinkFilesIntoDirectoryStep extends AbstractExecutionStep {

  private final Path srcDir;
  private final ImmutableSet<Path> entries;
  private final Path outDir;

  /**
   * @param srcDir relative to the project root that contains the {@code entries}.
   * @param entries that exist in {@code srcDir}.
   * @param outDir relative to the project root where the symlinks will be created.
   */
  public SymlinkFilesIntoDirectoryStep(Path srcDir, Iterable<Path> entries, Path outDir) {
    super("symlinking files into " + outDir);
    this.srcDir = srcDir;
    this.entries = ImmutableSet.copyOf(entries);
    this.outDir = outDir;
  }

  @Override
  public int execute(ExecutionContext context) {
    // Note that because these paths are resolved to absolute paths, the symlinks will be absolute
    // paths, as well.
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    Path outDir = projectFilesystem.resolve(this.outDir);
    Path srcDir = projectFilesystem.resolve(this.srcDir);

    for (Path entry : entries) {
      Path link = outDir.resolve(entry);
      Path target = srcDir.resolve(entry);
      try {
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, target);
      } catch (IOException e) {
        context.logError(e, "Failed to create symlink from %s to %s.", link, target);
        return 1;
      }
    }
    return 0;
  }

}
