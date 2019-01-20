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

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * CloseableHolder is used to pass ownership of a Closeable. This can make it easier to properly
 * close the underlying Closeable.
 *
 * <p>For example, in the following code, if writeSomethingToTempFile() throws an exception, the
 * tempFile will be deleted, but otherwise the ownership is passed to
 * doSomethingAsyncWithTempFile().
 *
 * <pre>{@code
 * try (CloseableHolder<NamedTemporaryFile> tempFile = new CloseableHolder<>(createTempFile())) {
 *   writeSomethingToTempFile(tempFile.get());
 *   doSomethingAsyncWithTempFile(tempFile.release());
 * }
 *
 *
 * }</pre>
 */
public class CloseableHolder<T extends Closeable> implements Closeable {
  @Nullable T instance;

  public CloseableHolder(T value) {
    instance = value;
  }

  public T get() {
    return Objects.requireNonNull(instance);
  }

  public T release() {
    T value = Objects.requireNonNull(instance);
    instance = null;
    return value;
  }

  @Override
  public void close() throws IOException {
    if (instance != null) instance.close();
  }
}
