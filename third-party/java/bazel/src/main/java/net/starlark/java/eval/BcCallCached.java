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
            fn.getName(),
            StarlarkRuntimeStats.CallCachedResult.HIT);
      }
      return cachedValue;
    }
    if (cannotCache) {
      if (StarlarkRuntimeStats.ENABLED) {
        StarlarkRuntimeStats.recordCallCached(
            fn.getName(),
            StarlarkRuntimeStats.CallCachedResult.SKIP);
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
    } finally{
      thread.popSideEffect(savedExternalSideEffects);
    }
  }
}
