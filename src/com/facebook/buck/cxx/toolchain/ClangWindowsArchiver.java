/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.toolchain.tool.DelegatingTool;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.file.FileScrubber;
import com.google.common.collect.ImmutableList;

/**
 * Archiver implementation for the Clang for Windows toolchain.
 *
 * <p>The implementation currently is only suitable for generating .lib archives for
 * Windows-targeted builds, for ingestion by either link.exe or lld-link.exe. If used with
 * lld-link.exe, thin archives are an option. link.exe cannot ingest thin archives generated with
 * this tool.
 */
public class ClangWindowsArchiver extends DelegatingTool implements Archiver {
  public ClangWindowsArchiver(Tool tool) {
    super(tool);
  }

  @Override
  public ImmutableList<FileScrubber> getScrubbers() {
    return ImmutableList.of();
  }

  @Override
  public boolean supportsThinArchives() {
    return true;
  }

  @Override
  public ImmutableList<String> getArchiveOptions(boolean isThinArchive) {
    if (isThinArchive) {
      return ImmutableList.of("/llvmlibthin");
    }
    return ImmutableList.of();
  }

  @Override
  public ImmutableList<String> outputArgs(String outputPath) {
    return ImmutableList.of("/OUT:" + outputPath);
  }

  @Override
  public boolean isRanLibStepRequired() {
    return false;
  }

  @Override
  public boolean isArgfileRequired() {
    return true;
  }
}
