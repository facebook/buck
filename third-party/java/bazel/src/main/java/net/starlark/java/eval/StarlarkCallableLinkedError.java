package net.starlark.java.eval;

import javax.annotation.Nullable;

/**
 * Throw exception on call.
 *
 * This implementation is used when callable is known to throw exception
 * unconditionally on call, but we shouldn't call exceptions at link time. */
public class StarlarkCallableLinkedError extends StarlarkCallableLinked {
  private final StarlarkCallable orig;
  private final String error;

  public StarlarkCallableLinkedError(StarlarkCallable orig, StarlarkCallableLinkSig linkSig, String error) {
    super(linkSig, orig);
    this.orig = orig;
    this.error = error;
  }

  @Override
  public Object callLinked(
      StarlarkThread thread,
      Object[] args,
      @Nullable Sequence<?> starArgs,
      @Nullable Dict<?, ?> starStarArgs) throws EvalException, InterruptedException {
    throw new EvalException(error);
  }
}
