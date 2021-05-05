package net.starlark.java.eval;

import javax.annotation.Nullable;

/** Base class for all starlark functions (as opposed to builtin) linked functions. */
abstract class StarlarkFunctionLinkedBase extends StarlarkCallableLinked {
  protected StarlarkFunctionLinkedBase(StarlarkCallableLinkSig linkSig, StarlarkFunction fn) {
    super(linkSig, fn);
  }

  protected StarlarkFunction fn() {
    return (StarlarkFunction) orig;
  }

  @SuppressWarnings("unchecked")
  @Override
  public Object callLinked(StarlarkThread thread, Object[] args, Sequence<?> starArgs,
      Dict<?, ?> starStarArgs) throws EvalException, InterruptedException {
    StarlarkFunction fn = fn();
    StarlarkThread.Frame fr;

    if (StarlarkRuntimeStats.ENABLED) {
      StarlarkRuntimeStats.enter(StarlarkRuntimeStats.WhereWeAre.DEF_PREPARE_ARGS);
    }

    try {
      if (!thread.isRecursionAllowed() && thread.isRecursiveCall(fn)) {
        throw Starlark.errorf("function '%s' called recursively", fn.getName());
      }

      fr = thread.frame(0);
      fr.locals = new Object[fn.compiled.slotCount];

      // Compute the effective parameter values
      // and update the corresponding variables.
      processArgs(thread.mutability(), args, starArgs, (Dict<Object, Object>) starStarArgs, fr.locals);

      // Spill indicated locals to cells.
      for (int index : fn.cellIndices) {
        fr.locals[index] = new StarlarkFunction.Cell(fr.locals[index]);
      }

    } finally {
      if (StarlarkRuntimeStats.ENABLED) {
        StarlarkRuntimeStats.leave();
      }
    }

    return BcEval.eval(fr, fn);
  }

  protected abstract void processArgs(
      Mutability mu, Object[] args, @Nullable Sequence<?> starArgs, @Nullable Dict<Object, Object> starStarArgs,
      Object[] locals)
      throws EvalException;
}
