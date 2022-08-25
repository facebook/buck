/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.android.exopackage;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.SimplePerfEvent;
import java.util.Optional;

/** Utils that helps with Scope/AutoCloseable creation. */
public class ScopeUtils {

  private static final AutoCloseable EMPTY_AUTO_CLOSABLE = () -> {};

  private ScopeUtils() {}

  /** Returns AutoCloseable that could be Scope or just no-op depending on passing event bus. */
  public static AutoCloseable getEventScope(Optional<BuckEventBus> eventBus, String name) {
    if (eventBus.isPresent()) {
      return SimplePerfEvent.scope(eventBus.get().isolated(), name);
    }
    return EMPTY_AUTO_CLOSABLE;
  }

  /** Returns AutoCloseable that could be Scope or just no-op depending on passing event bus. */
  public static AutoCloseable getEventScope(
      Optional<BuckEventBus> eventBus,
      SimplePerfEvent.PerfEventTitle perfEventTitle,
      String k1,
      Object v1) {
    if (eventBus.isPresent()) {
      return SimplePerfEvent.scope(eventBus.get().isolated(), perfEventTitle, k1, v1);
    }
    return EMPTY_AUTO_CLOSABLE;
  }
}
