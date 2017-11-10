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

import com.facebook.buck.cxx.toolchain.objectfile.ObjectFileScrubbers;
import com.facebook.buck.io.file.FileScrubber;
import com.facebook.buck.rules.DelegatingTool;
import com.facebook.buck.rules.Tool;
import com.google.common.collect.ImmutableList;

/** Archiver implementation for a BSD-based toolchain. */
public class BsdArchiver extends DelegatingTool implements Archiver {
  public BsdArchiver(Tool tool) {
    super(tool);
  }

  @Override
  public ImmutableList<FileScrubber> getScrubbers() {
    return ImmutableList.of(
        ObjectFileScrubbers.createDateUidGidScrubber(ObjectFileScrubbers.PaddingStyle.RIGHT));
  }

  @Override
  public boolean supportsThinArchives() {
    return false;
  }

  @Override
  public ImmutableList<String> getArchiveOptions(boolean isThinArchive) {
    String options = isThinArchive ? "qcT" : "qc";
    return ImmutableList.of(options);
  }

  @Override
  public ImmutableList<String> outputArgs(String outputPath) {
    return ImmutableList.of(outputPath);
  }

  @Override
  public boolean isRanLibStepRequired() {
    return true;
  }

  @Override
  public boolean isArgfileRequired() {
    return false;
  }
}
