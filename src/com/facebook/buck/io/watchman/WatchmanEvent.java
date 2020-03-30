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

package com.facebook.buck.io.watchman;

import com.facebook.buck.core.filesystems.AbsPath;

/** Interface for all Watchman events, requires them to have a base path */
public interface WatchmanEvent {
  /**
   * Absolute cell path root being watched.
   *
   * @return
   */
  AbsPath getCellPath();

  /** The kind of event that occurred in watched file system, like creation of a new file */
  enum Kind {
    /** A new entity, like file or directory, was created */
    CREATE,
    /** An existing entity, like file or directory, was modified */
    MODIFY,
    /** An entity, like file or directory, was deleted */
    DELETE,
  }

  /** Type of the file that was changed, like a regular file or a directory */
  enum Type {
    /** Regular file */
    FILE,
    /** Directory (folder that contains other files) */
    DIRECTORY,
    /** Symbolic link to another file */
    SYMLINK
  }
}
