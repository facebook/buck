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

package net.starlark.java.eval;

import javax.annotation.Nullable;

/** Variable of {@link StarlarkCallable} which knows how it will be called. */
public abstract class StarlarkCallableLinked {
  /** Link signature for this linked call. */
  final StarlarkCallableLinkSig linkSig;
  /** Link target. */
  final StarlarkCallable orig;

  protected StarlarkCallableLinked(StarlarkCallableLinkSig linkSig, StarlarkCallable orig) {
    this.linkSig = linkSig;
    this.orig = orig;
  }

  /**
   * Perform the linked call.
   *
   * <p>{@code args} here contains positional arguments followed by named arguments, and the number
   * of argument is consistent with {@code linkSig}.
   */
  public abstract Object callLinked(
      StarlarkThread thread,
      Object[] args,
      @Nullable Sequence<?> starArgs,
      @Nullable Dict<?, ?> starStarArgs)
      throws EvalException, InterruptedException;

  /** Function name. */
  public String getName() {
    return orig.getName();
  }

  @Override
  public String toString() {
    return orig.toString() + "(" + linkSig + ")";
  }
}
