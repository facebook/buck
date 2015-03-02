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

package com.facebook.buck.io;

import com.google.common.base.Preconditions;

import java.io.File;
import java.nio.file.Path;

public class MorePathsForTests {
  /** Utility class: do not instantiate. */
  private MorePathsForTests() {}

  /**
   * A cross-platform way to get a root-relative Path.
   * @param location The path to the file relative to a root.
   * @return Path object representing location.
   */
  public static Path rootRelativePath(String location) {
    File[] roots = File.listRoots();
    Preconditions.checkState(roots.length > 0);
    return roots[0].toPath().resolve(location);
  }
}
