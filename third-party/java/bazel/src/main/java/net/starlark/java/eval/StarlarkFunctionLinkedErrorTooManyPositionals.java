package net.starlark.java.eval;

import javax.annotation.Nullable;

/** Special function which throws too many positionals error on invocation. */
class StarlarkFunctionLinkedErrorTooManyPositionals extends StarlarkCallableLinked {
  public StarlarkFunctionLinkedErrorTooManyPositionals(StarlarkFunction fn, StarlarkCallableLinkSig linkSig) {
    super(linkSig, fn);
  }

  private StarlarkFunction fn() {
    return (StarlarkFunction) orig;
  }

  @Override
  public Object callLinked(StarlarkThread thread, Object[] args,
      @Nullable Sequence<?> starArgs,
      @Nullable Dict<?, ?> starStarArgs) throws EvalException, InterruptedException {
    throw error(args, starArgs);
  }

  EvalException error(Object[] args, Sequence<?> starArgs) {
    // nparams is the number of ordinary parameters.
    int nparams = fn().rfn.numNonStarParams();

    // numPositionalParams is the number of non-kwonly parameters.
    int numPositionalParams = nparams - fn().rfn.numKeywordOnlyParams();

    int numPositionalArgs = args.length - linkSig.namedNames.length + (starArgs != null ? starArgs.size() : 0);

    if (numPositionalParams == 0) {
      return Starlark.errorf(
          "%s() does not accept positional arguments, but got %d",
          fn().getName(),
          numPositionalArgs);
    } else {
      return Starlark.errorf(
          "%s() accepts no more than %d positional argument%s but got %d",
          fn().getName(),
          numPositionalParams,
          StarlarkFunction.plural(numPositionalParams),
          numPositionalArgs);
    }
  }
}
