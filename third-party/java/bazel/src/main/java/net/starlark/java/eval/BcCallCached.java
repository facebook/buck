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

import com.google.common.base.Preconditions;
import javax.annotation.Nullable;

/**
 * Cached call site: invoke a function with constant immutable arguments, and if computation
 * produced no side effects, reuse the compulation result on next invocation.
 */
class BcCallCached {

  final StarlarkCallableLinked fn;
  private final Object[] args;

  BcCallCached(StarlarkFunction fn, StarlarkCallableLinkSig linkSig, Object[] args) {
    this.fn = fn.linkCall(linkSig);
    this.args = args;
    Preconditions.checkArgument(!linkSig.hasStars());

    if (StarlarkAssertions.ENABLED) {
      for (Object arg : args) {
        Preconditions.checkState(Starlark.isImmutable(arg));
      }
    }
  }

  @Nullable private Object cachedValue = null;
  /**
   * {@link Starlark#isImmutable(Object)} check is costly, so do not cache if a function returned
   * mutable value once.
   */
  private boolean cannotCache = false;

  Object call(StarlarkThread thread) throws InterruptedException, EvalException {
    if (cachedValue != null) {
      if (StarlarkRuntimeStats.ENABLED) {
        StarlarkRuntimeStats.recordCallCached(
            fn.getName(), StarlarkRuntimeStats.CallCachedResult.HIT);
      }
      return cachedValue;
    }
    if (cannotCache) {
      if (StarlarkRuntimeStats.ENABLED) {
        StarlarkRuntimeStats.recordCallCached(
            fn.getName(), StarlarkRuntimeStats.CallCachedResult.SKIP);
      }
      return BcCall.callLinked(thread, fn, args, null, null);
    }

    boolean savedExternalSideEffects = thread.pushSideEffect();
    try {
      Object result = BcCall.callLinked(thread, fn, args, null, null);
      StarlarkRuntimeStats.CallCachedResult callCachedResult;
      if (thread.wasSideEffect()) {
        cannotCache = true;
        callCachedResult = StarlarkRuntimeStats.CallCachedResult.SIDE_EFFECT;
      } else if (!Starlark.isImmutable(result)) {
        cannotCache = true;
        callCachedResult = StarlarkRuntimeStats.CallCachedResult.MUTABLE;
      } else {
        cachedValue = result;
        callCachedResult = StarlarkRuntimeStats.CallCachedResult.STORE;
      }
      if (StarlarkRuntimeStats.ENABLED) {
        StarlarkRuntimeStats.recordCallCached(fn.getName(), callCachedResult);
      }
      return result;
    } finally {
      thread.popSideEffect(savedExternalSideEffects);
    }
  }
}
