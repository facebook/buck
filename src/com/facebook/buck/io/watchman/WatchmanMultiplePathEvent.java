/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.io.watchman;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;

/**
 * Buck sends this event for all files, directories and symlinks that were changed since last
 * invalidation
 */
@BuckStyleValue
public abstract class WatchmanMultiplePathEvent implements WatchmanEvent {

  /** Contains path and type of file system change */
  @BuckStyleValue
  public abstract static class Change {

    /** Type of the file that was changed, like a regular file or a directory */
    public abstract WatchmanEvent.Type getType();

    /** Path to a changed file or directory, relative to monitored root (cell root) */
    public abstract Path getPath();

    /** Kind of a file system change, like modification or deletion of the file */
    public abstract WatchmanEvent.Kind getKind();
  }

  @Override
  public abstract Path getCellPath();

  /** All changes to monitored file system that occurred since last invalidation */
  public abstract ImmutableList<Change> getChanges();
}
