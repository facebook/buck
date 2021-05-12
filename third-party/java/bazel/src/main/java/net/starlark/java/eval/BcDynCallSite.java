package net.starlark.java.eval;

import javax.annotation.Nullable;

/** Call site for {@link BcInstr.Opcode#CALL} instruction. */
class BcDynCallSite {
  private final StarlarkCallableLinkSig linkSig;

  BcDynCallSite(StarlarkCallableLinkSig linkSig) {
    this.linkSig = linkSig;
  }

  @Nullable private StarlarkCallableLinked linkedCache;

  Object call(
      StarlarkCallable callable,
      StarlarkThread thread,
      Object[] args,
      @Nullable Sequence<?> starArgs,
      @Nullable Dict<?, ?> kwargs)
      throws InterruptedException, EvalException {
    if (callable instanceof BuiltinFunction) {
      // Skip caching for builtin functions for two reasons:
      // * linking does not really link anything for builtin functions
      // * when `CALL` instruction is used for builtin (as opposed to `CALL_LINKED`)
      //     it is likely instance call like `x.append`, so a function is a fresh object,
      //     and it will be cache miss anyway
      BuiltinFunction builtinFunction = (BuiltinFunction) callable;
      return builtinFunction.linkAndCall(linkSig, thread, args, starArgs, kwargs);
    }

    StarlarkCallableLinked linked = this.linkedCache;
    if (linked == null || linked.orig != callable) {
      this.linkedCache = linked = callable.linkCall(linkSig);
    }
    return linked.callLinked(thread, args, starArgs, kwargs);
  }
}
