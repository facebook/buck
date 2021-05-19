// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package net.starlark.java.eval;

/**
 * A StarlarkIterable value may be iterated by Starlark language constructs such as {@code for}
 * loops, list and dict comprehensions, and {@code f(*args)}.
 *
 * <p>Functionally this interface is equivalent to {@code java.lang.Iterable}, but it additionally
 * affirms that the iterability of a Java class should be exposed to Starlark programs.
 */
public abstract class StarlarkIterable<T> extends StarlarkValue implements Iterable<T> {

  /**
   * Checks whether the Freezable Starlark value is frozen or temporarily immutable due to active
   * iterators.
   *
   * @throws EvalException if the value is not mutable.
   */
  protected void checkMutable() throws EvalException {
    if (mutability().isFrozen()) {
      throw Starlark.errorf("trying to mutate a frozen %s value", Starlark.type(this));
    }
    if (updateIteratorCount(0)) {
      throw Starlark.errorf(
          "%s value is temporarily immutable due to active for-loop iteration",
          Starlark.type(this));
    }
  }

  /**
   * Returns the {@link Mutability} associated with this. This should not change over the lifetime
   * of the object.
   */
  public Mutability mutability() {
    return Mutability.IMMUTABLE;
  }

  /**
   * Registers a change to this Freezable's iterator count and reports whether it is temporarily
   * immutable.
   *
   * <p>If the value is permanently frozen ({@code mutability().isFrozen()), this function is a
   * no-op that returns false.
   *
   * <p>Otherwise, if delta is positive, this increments the count of active iterators over the
   * value, causing it to appear temporarily frozen (if it wasn't already). If delta is negative,
   * the counter is decremented, and if delta is zero the counter is unchanged. It is illegal to
   * decrement the counter if it was already zero. The return value is true if the count is
   * positive after the change, and false otherwise.
   *
   * <p>The default implementation stores the counter of iterators in a hash table in the
   * Mutability, but a subclass of Freezable may define a more efficient implementation such as an
   * integer field in the freezable value itself.
   *
   * <p>Call this function with a positive value when starting an iteration and with a negative
   * value when ending it.
   */
  public boolean updateIteratorCount(int delta) {
    return mutability().updateIteratorCount(this, delta);
  }
}
