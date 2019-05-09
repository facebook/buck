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

package com.facebook.buck.io.watchman;

import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import java.nio.file.Path;
import org.immutables.value.Value;

/** Interface for all Watchman events, requires them to have a base path */
public interface WatchmanEvent {
  /** Absolute cell path root being watched. */
  Path getCellPath();

  /** The kind of event that occurred in watched file system, like creation of a new file */
  enum Kind {
    /** A new entity, like file or directory, was created */
    CREATE,
    /** An existing entity, like file or directory, was modified */
    MODIFY,
    /** An entity, like file or directory, was deleted */
    DELETE,
  }
}

/**
 * Buck sends this event when Watchman is unable to correctly determine the whole set of changes in
 * the filesystem, or if too many files have changed
 */
@Value.Immutable(copy = false, builder = false)
@BuckStyleTuple
abstract class AbstractWatchmanOverflowEvent implements WatchmanEvent {
  @Override
  public abstract Path getCellPath();

  public abstract String getReason();
}
