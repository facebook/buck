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

package com.facebook.buck.io.file;

import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

public interface FileContentsScrubber extends FileScrubber {

  /**
   * Scrubs the file as needed.
   *
   * @param file The file channel to be scrubbed
   * @param filePath path to the file to be scrubbed
   * @param processExecutor executor for running commands to scrub file contents
   * @param environment environment to run the file scrub commands
   * @throws IOException raised when there are errors during file I/O.
   * @throws ScrubException typically raised when exceptions occurred during scrubbing
   * @throws InterruptedException typically raised when the file scrubbing processes are
   *     interrupted.
   */
  void scrubFile(
      FileChannel file,
      Path filePath,
      ProcessExecutor processExecutor,
      ImmutableMap<String, String> environment)
      throws IOException, ScrubException, InterruptedException;
}
