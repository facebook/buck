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
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;

/** Buck sends this event for every file that was added, modified or deleted */
@BuckStyleValue
public abstract class WatchmanPathEvent implements WatchmanEvent {

  @Override
  public abstract AbsPath getCellPath();

  /** The kind of event that occurred. */
  public abstract WatchmanEvent.Kind getKind();

  /**
   * Relative path of the actual file the event is about. The path is relative to the cell path
   * returned by {@link #getCellPath()}.
   */
  public abstract RelPath getPath();

  public static WatchmanPathEvent of(AbsPath cellPath, WatchmanEvent.Kind kind, RelPath path) {
    return ImmutableWatchmanPathEvent.of(cellPath, kind, path);
  }
}
