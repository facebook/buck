/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.util.unarchive;

/** How existing files should be handled in a destination */
public enum ExistingFileMode {
  /**
   * Just overwrite existing files. If a file is in the destination that is not in the archive, it
   * will not be removed
   */
  OVERWRITE,
  /**
   * Overwrite existing files, and make sure that directories do not have any extra files that are
   * not in the archive
   */
  OVERWRITE_AND_CLEAN_DIRECTORIES,
}
