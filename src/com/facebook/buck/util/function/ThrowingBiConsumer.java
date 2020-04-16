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

package com.facebook.buck.util.function;

import com.google.common.base.Throwables;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** The version of {@code Consumer<T>} that can throw an exception. */
@FunctionalInterface
public interface ThrowingBiConsumer<T, U, E extends Exception> {
  void accept(T t, U u) throws E;

  /**
   * Helper to package a {@link ThrowingBiConsumer} as a {@link Consumer} and apply it on action
   * expecting the latter. Checked exceptions thrown by the former are tunneled inside unchecked
   * exceptions and re-raised.
   */
  static <T, U, E extends Exception> void wrapAsUnchecked(
      Consumer<BiConsumer<T, U>> consumerConsumer, ThrowingBiConsumer<T, U, E> consumer) throws E {

    // Setup an consumer which runs the given checked consumer, catches the checked exception,
    // packages it in an unchecked exception, and re-throws it.
    BiConsumer<T, U> uncheckedConsumer =
        new BiConsumer<T, U>() {
          @Override
          public void accept(T t, U u) {
            try {
              consumer.accept(t, u);
            } catch (Exception exception) {
              // exception: E | RuntimeException | Error
              Throwables.throwIfUnchecked(exception);
              // exception: E
              throw new TunneledException(exception, this);
            }
          }
        };

    // Run the above unchecked consumer, un-packaging any checked exceptions and re-throwing them.
    try {
      consumerConsumer.accept(uncheckedConsumer);
    } catch (TunneledException e) {
      // This tunneled exception doesn't belong to us, so re-throw.
      if (e.owner != uncheckedConsumer) {
        throw e;
      }
      @SuppressWarnings("unchecked")
      E cause = (E) e.getCause();
      throw cause;
    }
  }

  /** An exception used to tunnel checked exceptions through a non-throwing interface. */
  final class TunneledException extends RuntimeException {
    final Object owner;

    TunneledException(Throwable cause, Object owner) {
      super(cause);
      this.owner = owner;
    }
  }
}
