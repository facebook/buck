/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.util.concurrent;

import com.facebook.buck.core.util.log.Logger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Makes it simple to assert that access to a piece of code should be done from one thread at a time
 * in a non-reentrant manner.
 */
public class AssertScopeExclusiveAccess {
  private static final Logger LOG = Logger.get(AssertScopeExclusiveAccess.class);

  private final AtomicReference<Throwable> inScope = new AtomicReference<>();

  /**
   * Try to enter a scope, validating only one thread can get inside the same scope.
   *
   * @throws IllegalStateException when another thread wants to enter the same scope.
   * @return Closeable object to be used with try-with-resources to indicate the end of validated
   *     block
   */
  public Scope scope() {

    boolean firstOneInScope = inScope.compareAndSet(null, new Throwable());

    if (!firstOneInScope) {
      LOG.verbose(inScope.get(), "Indicating previous access to single threaded scope.");

      throw new IllegalStateException(
          "More than one thread attempting access to single-threaded scope.");
    }

    return () -> inScope.set(null);
  }

  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}
