/*
 * Copyright 2013-present Facebook, Inc.
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
package com.facebook.buck.event;

import com.facebook.buck.core.model.BuildId;
import java.io.Closeable;

/**
 * Thin wrapper around guava event bus.
 *
 * <p>This interface exists only to break circular Buck target dependencies.
 */
public interface BuckEventBus extends Closeable, EventDispatcher {
  BuildId getBuildId();

  /** Register a listener to process events */
  void register(Object object);

  /** Remove a listener previously specified with {@code register()} */
  void unregister(Object object);

  /**
   * Wait for all currently running events to dispatch and finish executing
   *
   * @param timeout Time in milliseconds to wait for completion, if timeout is not greater than 0
   *     then it will wait indefinitely
   * @return true if all events were handled and false if timeout was hit
   */
  boolean waitEvents(long timeout);
}
