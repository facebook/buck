package net.starlark.java.eval;

import javax.annotation.Nullable;

class BcDynCallSite {
  private final StarlarkCallableLinkSig linkSig;

  BcDynCallSite(StarlarkCallableLinkSig linkSig) {
    this.linkSig = linkSig;
  }

  @Nullable
  private StarlarkCallableLinked linkedCache;

  Object call(StarlarkCallable callable, StarlarkThread thread, Object[] args, @Nullable Sequence<?> starArgs, @Nullable Dict<?, ?> kwargs)
      throws InterruptedException, EvalException {
    StarlarkCallableLinked linked = this.linkedCache;
    if (linked == null || linked.orig != callable) {
      this.linkedCache = linked = callable.linkCall(linkSig);
    }
    return linked.callLinked(thread, args, starArgs, kwargs);
  }
}
