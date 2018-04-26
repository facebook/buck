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

package com.facebook.buck.io;

import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import java.nio.file.Path;
import org.immutables.value.Value;

public interface WatchmanEvent {
  /** Cell path being watched. */
  Path getCellPath();
}

@Value.Immutable(copy = false, builder = false)
@BuckStyleTuple
abstract class AbstractWatchmanOverflowEvent implements WatchmanEvent {
  @Override
  public abstract Path getCellPath();

  public abstract String getReason();
}

@Value.Immutable(copy = false, builder = false)
@BuckStyleTuple
abstract class AbstractWatchmanPathEvent implements WatchmanEvent {
  public enum Kind {
    CREATE,
    MODIFY,
    DELETE,
  }

  @Override
  public abstract Path getCellPath();

  /** The kind of event that occurred. */
  public abstract Kind getKind();

  /**
   * Relative path of the actual file the event is about. The path is relative to the cell path
   * returned by {@link #getCellPath()}.
   */
  public abstract Path getPath();
}
