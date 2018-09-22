/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.toolchain.DependencyTrackingMode;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Reporter following the Decorator pattern, which decides the appropriate specific reporter to use.
 */
class UntrackedHeaderReporterWithFallback implements UntrackedHeaderReporter {
  private static final Logger LOG = Logger.get(UntrackedHeaderReporterWithFallback.class);
  private UntrackedHeaderReporter headerReporter;

  UntrackedHeaderReporterWithFallback(
      DependencyTrackingMode dependencyTrackingMode,
      ProjectFilesystem filesystem,
      HeaderPathNormalizer headerPathNormalizer,
      Path sourceDepFile,
      Path inputPath) {
    switch (dependencyTrackingMode) {
      case SHOW_INCLUDES:
        this.headerReporter =
            new UntrackedHeaderReporterWithShowIncludes(
                filesystem, headerPathNormalizer, sourceDepFile, inputPath);
        break;
      case MAKEFILE:
        // Makefile doesn't output dependency tree structure, use basic report.
      case NONE:
        this.headerReporter = new UntrackedHeaderReporterBasic(filesystem, inputPath);
        break;
      default:
        // should never happen
        LOG.error(
            "Unknown dependency tracking mode (%s) while creating untracked header report.",
            dependencyTrackingMode);
        throw new IllegalStateException();
    }
  }

  @Override
  public String getErrorReport(Path header) throws IOException {
    return headerReporter.getErrorReport(header);
  }
}
