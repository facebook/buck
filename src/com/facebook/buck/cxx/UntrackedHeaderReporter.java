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

import java.io.IOException;
import java.nio.file.Path;

/**
 * Produces the error message when an untracked header is detected.
 *
 * <p>When preprocessing/compiling, we may encounter some headers that are missing from BUCK file.
 * If the file is not whitelisted classes implementing this interface generate the message for this
 * error.
 */
public interface UntrackedHeaderReporter {
  boolean isDetailed();

  /**
   * @throws Exception in case there is some error while generating the report. Some reporters
   *     access the disk, which can fail.
   */
  String getErrorReport(Path header) throws IOException;
}
