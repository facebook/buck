/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.util.io;

import java.io.Closeable;
import java.io.IOException;

/** IO utilities. */
public class IoUtil {
  /** Like {@link java.util.function.Function} but allows throwing {@link IOException}. */
  public interface IoFunction<A, B> {
    /** Apply the function. */
    B apply(A a) throws IOException;
  }

  /** Apply a function to just opened output stream; close the stream if function fails. */
  public static <I extends Closeable, R> R mapJustOpened(I justOpened, IoFunction<I, R> f)
      throws IOException {
    try {
      return f.apply(justOpened);
    } catch (Throwable e) {
      try {
        justOpened.close();
      } catch (Throwable ignore) {
        // ignore
      }
      // original exception is more important, and generally close should not throw
      throw e;
    }
  }
}
