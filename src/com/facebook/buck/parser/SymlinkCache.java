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

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.ParsingEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.parser.config.ParserConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

class SymlinkCache {
  private static final Logger LOG = Logger.get(SymlinkCache.class);

  private final BuckEventBus eventBus;
  private final DaemonicParserState daemonicParserState;

  /**
   * Build rule input files (e.g., paths in {@code srcs}) whose paths contain an element which
   * exists in {@code symlinkExistenceCache}.
   */
  private final Set<AbsPath> buildInputPathsUnderSymlink = Sets.newConcurrentHashSet();

  /**
   * Cache of (symlink path: symlink target) pairs used to avoid repeatedly checking for the
   * existence of symlinks in the source tree.
   */
  private final Map<Path, Optional<Path>> symlinkExistenceCache = new ConcurrentHashMap<>();

  private final Map<CanonicalCellName, ParserConfig.AllowSymlinks> cellSymlinkAllowability =
      new ConcurrentHashMap<>();

  public SymlinkCache(BuckEventBus eventBus, DaemonicParserState daemonicParserState) {
    this.eventBus = eventBus;
    this.daemonicParserState = daemonicParserState;
  }

  public void registerInputsUnderSymlinks(
      Cell currentCell, Cell targetCell, AbsPath buildFile, TargetNode<?> node) throws IOException {
    Map<Path, Path> newSymlinksEncountered =
        inputFilesUnderSymlink(node.getInputs(), node.getFilesystem());
    Optional<ImmutableList<Path>> readOnlyPaths =
        targetCell.getBuckConfig().getView(ParserConfig.class).getReadOnlyPaths();

    if (readOnlyPaths.isPresent() && currentCell != null) {
      newSymlinksEncountered =
          Maps.filterEntries(
              newSymlinksEncountered,
              entry -> {
                for (Path readOnlyPath : readOnlyPaths.get()) {
                  if (entry.getKey().startsWith(readOnlyPath)) {
                    LOG.debug(
                        "Target %s contains input files under a path which contains a symbolic "
                            + "link (%s). It will be cached because it belongs under %s, a "
                            + "read-only path white listed in .buckconfig. under [project] "
                            + "read_only_paths",
                        node.getBuildTarget(), entry, readOnlyPath);
                    return false;
                  }
                }
                return true;
              });
    }

    if (newSymlinksEncountered.isEmpty()) {
      return;
    }

    ParserConfig.AllowSymlinks allowSymlinks =
        Objects.requireNonNull(cellSymlinkAllowability.get(node.getBuildTarget().getCell()));
    if (allowSymlinks == ParserConfig.AllowSymlinks.FORBID) {
      throw new HumanReadableException(
          "Target %s contains input files under a path which contains a symbolic link "
              + "(%s). To resolve this, use separate rules and declare dependencies instead of "
              + "using symbolic links.\n"
              + "If the symlink points to a read-only filesystem, you can specify it in the "
              + "project.read_only_paths .buckconfig setting. Buck will assume files under that "
              + "path will never change.",
          node.getBuildTarget(), newSymlinksEncountered);
    }

    // If we're not explicitly forbidding symlinks, either warn to the console or the log file
    // depending on the config setting.
    String msg =
        String.format(
            "Disabling parser cache for target %s, because one or more input files are under a "
                + "symbolic link (%s). This will severely impact the time spent in parsing! To "
                + "resolve this, use separate rules and declare dependencies instead of using "
                + "symbolic links.",
            node.getBuildTarget(), newSymlinksEncountered);
    if (allowSymlinks == ParserConfig.AllowSymlinks.WARN) {
      eventBus.post(ConsoleEvent.warning(msg));
    } else {
      LOG.warn(msg);
    }

    eventBus.post(ParsingEvent.symlinkInvalidation(buildFile.toString()));
    buildInputPathsUnderSymlink.add(buildFile);
  }

  private Map<Path, Path> inputFilesUnderSymlink(
      // We use Collection<Path> instead of Iterable<Path> to prevent
      // accidentally passing in Path, since Path itself is Iterable<Path>.
      Collection<ForwardRelativePath> inputs, ProjectFilesystem projectFilesystem)
      throws IOException {
    Map<Path, Path> newSymlinksEncountered = new HashMap<>();
    for (ForwardRelativePath input : inputs) {
      Path inputPath = input.toPath(projectFilesystem.getFileSystem());
      for (int i = 1; i < inputPath.getNameCount(); i++) {
        Path subpath = inputPath.subpath(0, i);
        Optional<Path> resolvedSymlink = symlinkExistenceCache.get(subpath);
        if (resolvedSymlink != null) {
          if (resolvedSymlink.isPresent()) {
            LOG.verbose("Detected cached symlink %s -> %s", subpath, resolvedSymlink.get());
            newSymlinksEncountered.put(subpath, resolvedSymlink.get());
          }
          // If absent, not a symlink.
        } else {
          // Not cached, look it up.
          if (projectFilesystem.isSymLink(subpath)) {
            Path symlinkTarget = projectFilesystem.resolve(subpath).toRealPath();
            Path relativeSymlinkTarget =
                projectFilesystem.getPathRelativeToProjectRoot(symlinkTarget).orElse(symlinkTarget);
            LOG.verbose("Detected symbolic link %s -> %s", subpath, relativeSymlinkTarget);
            newSymlinksEncountered.put(subpath, relativeSymlinkTarget);
            symlinkExistenceCache.put(subpath, Optional.of(relativeSymlinkTarget));
          } else {
            symlinkExistenceCache.put(subpath, Optional.empty());
          }
        }
      }
    }
    return newSymlinksEncountered;
  }

  public void registerCell(Cell cell) {
    cellSymlinkAllowability.put(
        cell.getCanonicalName(),
        cell.getBuckConfig().getView(ParserConfig.class).getAllowSymlinks());
  }

  public void close() {
    if (!buildInputPathsUnderSymlink.isEmpty()) {
      LOG.debug(
          "Cleaning cache of build files with inputs under symlink %s",
          buildInputPathsUnderSymlink);
    }
    Set<AbsPath> buildInputPathsUnderSymlinkCopy = new HashSet<>(buildInputPathsUnderSymlink);
    buildInputPathsUnderSymlink.clear();
    for (AbsPath buildFilePath : buildInputPathsUnderSymlinkCopy) {
      daemonicParserState.invalidatePath(buildFilePath);
    }
  }
}
