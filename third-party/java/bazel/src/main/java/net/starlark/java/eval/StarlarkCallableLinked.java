package net.starlark.java.eval;

import javax.annotation.Nullable;

/** Variable of {@link StarlarkCallable} which knows how it will be called. */
public abstract class StarlarkCallableLinked {
  /** Link signature for this linked call. */
  final StarlarkCallableLinkSig linkSig;
  /** Link target. */
  final StarlarkCallable orig;

  protected StarlarkCallableLinked(StarlarkCallableLinkSig linkSig,
      StarlarkCallable orig) {
    this.linkSig = linkSig;
    this.orig = orig;
  }

  /**
   * Perform the linked call.
   *
   * {@code args} here contains positional arguments followed by named arguments,
   * and the number of argument is consistent with {@code linkSig}.
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
}
