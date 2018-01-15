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

import com.facebook.buck.io.file.FileScrubber;
import com.facebook.buck.rules.Tool;
import com.google.common.collect.ImmutableList;

/** Interface for a c/c++ archiver. */
public interface Archiver extends Tool {

  ImmutableList<FileScrubber> getScrubbers();

  boolean supportsThinArchives();

  ImmutableList<String> getArchiveOptions(boolean isThinArchive);

  ImmutableList<String> outputArgs(String outputPath);

  boolean isRanLibStepRequired();

  /**
   * Whether an argfile is required for a long command line (false means that it is possible to
   * split a long command line into chunks). Eg, ar on *nix allows to add new files to an already
   * created archive, but doesn't accept argfiles. On the contrary, VS lib.exe on windows always
   * overrides the previous archive, but supports argfiles.
   *
   * @return whether @argfile is required for a long command line
   */
  boolean isArgfileRequired();
}
