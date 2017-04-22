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

package com.facebook.buck.util;

import com.facebook.buck.util.immutables.BuckStyleTuple;

import org.immutables.value.Value;

import java.nio.file.Path;

public interface WatchmanEvent {
}

@Value.Immutable(copy = false, builder = false)
@BuckStyleTuple
abstract class AbstractWatchmanOverflowEvent implements WatchmanEvent {
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

  /**
   * The kind of event that occurred.
   */
  public abstract Kind getKind();

  /**
   * Relative path of the actual file the event is about. The path is relative to the watched path.
   */
  public abstract Path getPath();
}
