/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.config.BuckConfig;
import java.util.Optional;

public class DxConfig {

  private final BuckConfig delegate;

  public DxConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  /** @return The upper heap size limit for out of process dx if specified. */
  public Optional<String> getDxMaxHeapSize() {
    return delegate.getValue("dx", "max_heap_size");
  }

  /** @return The dx thread count. */
  public Optional<Integer> getDxMaxThreadCount() {
    return delegate.getInteger("dx", "max_threads");
  }
}
