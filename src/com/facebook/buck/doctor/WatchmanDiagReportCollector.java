/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.doctor;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.WatchmanFactory;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/** Gets watchman diagnostics using the watchman-diag command. */
public class WatchmanDiagReportCollector {
  private final ProjectFilesystem projectFilesystem;
  private final String watchmanDiagCommand;
  private final ProcessExecutor processExecutor;

  public WatchmanDiagReportCollector(
      ProjectFilesystem projectFilesystem,
      String watchmanDiagCommand,
      ProcessExecutor processExecutor) {
    this.projectFilesystem = projectFilesystem;
    this.watchmanDiagCommand = watchmanDiagCommand;
    this.processExecutor = processExecutor;
  }

  public Path run()
      throws IOException, InterruptedException, ExtraInfoCollector.ExtraInfoExecutionException {

    Path watchmanDiagReport =
        projectFilesystem.getBuckPaths().getLogDir().resolve("watchman-diag-report");
    projectFilesystem.deleteFileAtPathIfExists(watchmanDiagReport);

    String extraInfoCommandOutput =
        DefaultExtraInfoCollector.runCommandAndGetStdout(
            ImmutableList.of(watchmanDiagCommand), projectFilesystem, processExecutor);

    projectFilesystem.writeContentsToPath(extraInfoCommandOutput, watchmanDiagReport);

    return watchmanDiagReport;
  }

  public static Optional<WatchmanDiagReportCollector> newInstanceIfWatchmanUsed(
      Cell rootCell,
      ProjectFilesystem projectFilesystem,
      ProcessExecutor processExecutor,
      ExecutableFinder executableFinder,
      ImmutableMap<String, String> environment) {

    // We only want to gather watchman diagnostics if any of the cells are actually using Watchman.
    ImmutableCollection<Path> allCellRoots = rootCell.getCellPathResolver().getCellPaths().values();
    boolean watchmanEverUsed = rootCell.getWatchman() != WatchmanFactory.NULL_WATCHMAN;
    for (Path cellRoot : allCellRoots) {
      if (watchmanEverUsed) {
        break;
      }
      watchmanEverUsed =
          watchmanEverUsed
              || rootCell.getCell(cellRoot).getWatchman() != WatchmanFactory.NULL_WATCHMAN;
    }
    if (!watchmanEverUsed) {
      return Optional.empty();
    }

    Optional<Path> optionalExecutable =
        executableFinder.getOptionalExecutable(Paths.get("watchman-diag"), environment);
    if (!optionalExecutable.isPresent()) {
      return Optional.empty();
    }
    return Optional.of(
        new WatchmanDiagReportCollector(
            projectFilesystem,
            optionalExecutable.get().toAbsolutePath().toString(),
            processExecutor));
  }
}
