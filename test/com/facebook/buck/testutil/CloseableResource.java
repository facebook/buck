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
package com.facebook.buck.testutil;

import com.facebook.buck.util.ThrowingCloseableMemoizedSupplier;
import com.google.common.base.Supplier;
import org.junit.rules.ExternalResource;

/** A closeable resource for the lifetime of the junit test, but is cleaned up after the test. */
public class CloseableResource<T extends AutoCloseable> extends ExternalResource {

  private final ThrowingCloseableMemoizedSupplier<T, Exception> resource;

  private CloseableResource(ThrowingCloseableMemoizedSupplier<T, Exception> resource) {
    this.resource = resource;
  }

  /**
   * @param resourceSupplier creates the resource on command
   * @param <U> the type of the resource
   * @return a {@link org.junit.rules.TestRule} that initializes and closes the resource per test
   *     case
   */
  public static <U extends AutoCloseable> CloseableResource<U> of(Supplier<U> resourceSupplier) {
    return new CloseableResource<>(
        ThrowingCloseableMemoizedSupplier.of(resourceSupplier, resource -> resource.close()));
  }

  /** @return the resource this wraps */
  public T get() {
    return resource.get();
  }

  @Override
  protected void before() {
    resource.get();
  }

  @Override
  protected void after() {
    try {
      resource.close();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
