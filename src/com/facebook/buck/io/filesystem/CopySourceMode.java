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

package com.facebook.buck.io.filesystem;

/** Controls the behavior of how the source should be treated when copying. */
public enum CopySourceMode {
  /** Copy the single source file into the destination path. */
  FILE,

  /**
   * Treat the source as a directory and copy each file inside it to the destination path, which
   * must be a directory.
   */
  DIRECTORY_CONTENTS_ONLY,

  /**
   * Treat the source as a directory. Copy the directory and its contents to the destination path,
   * which must be a directory.
   */
  DIRECTORY_AND_CONTENTS,
}
