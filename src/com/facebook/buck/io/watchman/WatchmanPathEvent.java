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

import java.nio.file.Path;
import org.immutables.value.Value;

/** Buck sends this event for every file that was added, modified or deleted */
@Value.Immutable(copy = false, builder = false)
public abstract class WatchmanPathEvent implements WatchmanEvent {

  @Value.Parameter
  @Override
  public abstract Path getCellPath();

  /** The kind of event that occurred. */
  @Value.Parameter
  public abstract WatchmanEvent.Kind getKind();

  /**
   * Relative path of the actual file the event is about. The path is relative to the cell path
   * returned by {@link #getCellPath()}.
   */
  @Value.Parameter
  public abstract Path getPath();
}
