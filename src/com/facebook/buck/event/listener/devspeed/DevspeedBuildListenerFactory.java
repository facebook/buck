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
package com.facebook.buck.event.listener.devspeed;

import com.facebook.buck.event.BuckEventListener;

/**
 * Creates {@link BuckEventListener}s to report status to DevSpeed Logging. Which status information
 * is reported is up to the sublcass.
 */
public interface DevspeedBuildListenerFactory extends AutoCloseable {
  /**
   * @return a new {@link BuckEventListener} that reports status information to DevSpeed Logging.
   */
  BuckEventListener newBuildListener();

  /** Releases any resources held by the factory. */
  @Override
  void close();
}
