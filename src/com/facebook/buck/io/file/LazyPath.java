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
package com.facebook.buck.io.file;

import com.facebook.buck.log.Logger;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Class is intended to provide Paths to be used by cache */
public abstract class LazyPath {
  private static final Logger LOG = Logger.get(LazyPath.class);
  private AtomicReference<Path> path = new AtomicReference<>();
  private Optional<IOException> exception = Optional.empty();

  /**
   * Creates an instance with given path.
   *
   * @param path The path that get()/getUnchecked() methods should return.
   * @return Instance that always returns the given path. It means there is no laziness.
   */
  public static LazyPath ofInstance(Path path) {
    LazyPath instance =
        new LazyPath() {
          @Override
          protected Path create() {
            return path;
          }
        };
    instance.path.set(path);
    return instance;
  }

  /**
   * On first access it will invoke the given supplier to obtain the value of the Path.
   *
   * @return Memoized path.
   * @throws IOException
   */
  public final Path get() throws IOException {
    synchronized (path) {
      Path result = path.get();
      if (result == null) {
        try {
          result = create();
          path.set(result);
        } catch (IOException e) {
          exception = Optional.of(e);
          LOG.warn(
              "Failed to initialize lazy path, exception: "
                  + e
                  + "\n"
                  + "StackTrace: "
                  + Throwables.getStackTraceAsString(e));
          throw e;
        }
      }
      return result;
    }
  }

  /**
   * Does not invoke the path supplier, assuming it was invoked previously.
   *
   * @return Memoized path.
   */
  public Path getUnchecked() {
    synchronized (path) {
      return Preconditions.checkNotNull(
          path.get(),
          "get() method must be called first to set initial value before invoking getUnchecked()");
    }
  }

  /**
   * @return Path that will be created lazily and memoized.
   * @throws IOException
   */
  protected abstract Path create() throws IOException;

  @Override
  public String toString() {
    synchronized (path) {
      Path result = path.get();
      if (result == null) {
        if (!exception.isPresent()) {
          return "Lazy path uninitialized";
        } else {
          return "Lazy path failed to initialize: "
              + exception.get()
              + "\n"
              + "Stacktrace: \n"
              + Throwables.getStackTraceAsString(exception.get());
        }
      } else {
        return result.toString();
      }
    }
  }
}
