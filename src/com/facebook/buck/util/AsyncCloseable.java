/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.log.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/** Allows for conveniently expressing that a resource should be closed asynchronously. */
public class AsyncCloseable implements AutoCloseable {

  private static final Logger LOG = Logger.get(AsyncCloseable.class);

  private final ExecutorService executorService;
  private final List<Runnable> pendingClosers;

  /** @param executorService the service that will be used to close the registered objects. */
  public AsyncCloseable(ExecutorService executorService) {
    this.executorService = executorService;
    this.pendingClosers = new ArrayList<>();
  }

  /**
   * @param asyncCloseable the object that will be close when this instance of {@link
   *     AsyncCloseable} will be closed. Exceptions thrown by registered instances are only logged.
   * @param <T>
   * @return input arg (for intialization convenience).
   */
  public <T extends AutoCloseable> T closeAsync(final T asyncCloseable) {
    pendingClosers.add(
        () -> {
          try {
            asyncCloseable.close();
          } catch (Exception e) {
            LOG.warn(e, "Exception when performing async close of %s.", asyncCloseable);
          }
        });
    return asyncCloseable;
  }

  /** schedule the closing of all registered objects. */
  @Override
  public void close() {
    for (Runnable closer : pendingClosers) {
      executorService.submit(closer);
    }
  }
}
