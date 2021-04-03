package net.starlark.java.eval;

import javax.annotation.Nullable;

class StarlarkFunctionLinkedPos extends StarlarkFunctionLinkedBase {

  protected StarlarkFunctionLinkedPos(StarlarkCallableLinkSig linkSig, StarlarkFunction fn) {
    super(linkSig, fn);
  }

  @Override
  protected void processArgs(Mutability mu, Object[] args,
      @Nullable Sequence<?> starArgs, @Nullable Dict<Object, Object>
      starStarArgs, Object[] locals) throws EvalException {
    System.arraycopy(args, 0, locals, 0, args.length);
  }
}
