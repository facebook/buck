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

/**
 * Throw exception on call.
 *
 * <p>This implementation is used when callable is known to throw exception unconditionally on call,
 * but we shouldn't call exceptions at link time.
 */
public class StarlarkCallableLinkedError extends StarlarkCallableLinked {

  private final String error;

  public StarlarkCallableLinkedError(
      StarlarkCallable orig, StarlarkCallableLinkSig linkSig, String error) {
    super(linkSig, orig);
    this.error = error;
  }

  @Override
  public Object callLinked(
      StarlarkThread thread,
      Object[] args,
      @Nullable Sequence<?> starArgs,
      @Nullable Dict<?, ?> starStarArgs)
      throws EvalException, InterruptedException {
    throw new EvalException(error);
  }
}
