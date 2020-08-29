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
import java.nio.file.Path;

/** Created by beefon on 06/06/2016. */
public interface FileAttributesScrubber extends FileScrubber {
  /**
   * Override this method to perform the modification of the file attributes (modification date,
   * creation date, etc.) WARNING: You should not delete, rename or move the file, as the the
   * behaviour is undefined.
   *
   * @param path the path to the file to be scrubbed
   * @param processExecutor executor to run shell commands, used when file scrubbing involve running
   *     shell commands, for example: "strip -S file_path" to strip debug symbol tables
   * @param environment the environment variables when running the command through processExecutor
   */
  void scrubFileWithPath(
      Path path, ProcessExecutor processExecutor, ImmutableMap<String, String> environment)
      throws IOException, InterruptedException;
}
