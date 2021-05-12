package net.starlark.java.eval;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import javax.annotation.Nullable;
import net.starlark.java.spelling.SpellChecker;

/** Special function which is known to produce linking error on invocation. */
class StarlarkFunctionLinkedError extends StarlarkFunctionLinkedBase {
  public StarlarkFunctionLinkedError(StarlarkFunction fn, StarlarkCallableLinkSig linkSig) {
    super(linkSig, fn);
  }

  @Override
  protected void processArgs(
      Mutability mu,
      Object[] args,
      @Nullable Sequence<?> starArgs,
      @Nullable Dict<Object, Object> starStarArgs,
      Object[] locals)
      throws EvalException {
    throw error(args, starArgs, starStarArgs);
  }

  /** Compute the error. */
  private EvalException error(
      Object[] args, @Nullable Sequence<?> starArgs, @Nullable Dict<Object, Object> starStarArgs)
      throws EvalException {

    StarlarkCallableUtils.FoldedArgs foldedArgs =
        StarlarkCallableUtils.foldArgs(linkSig, args, starArgs, starStarArgs);

    ImmutableList<Object> positional = foldedArgs.positional;
    ImmutableList<Object> named = foldedArgs.named;

    ImmutableList<String> names = fn().getParameterNames();

    Object[] arguments = new Object[names.size()];

    // nparams is the number of ordinary parameters.
    int nparams =
        fn().getParameterNames().size() - (fn().hasKwargs() ? 1 : 0) - (fn().hasVarargs() ? 1 : 0);

    // numPositionalParams is the number of non-kwonly parameters.
    int numPositionalParams = nparams - fn().numKeywordOnlyParams;

    // Too many positional args?
    int n = positional.size();
    if (n > numPositionalParams) {
      if (!fn().hasVarargs()) {
        if (numPositionalParams > 0) {
          return Starlark.errorf(
              "%s() accepts no more than %d positional argument%s but got %d",
              fn().getName(), numPositionalParams, StarlarkFunction.plural(numPositionalParams), n);
        } else {
          return Starlark.errorf(
              "%s() does not accept positional arguments, but got %d", fn().getName(), n);
        }
      }
      n = numPositionalParams;
    }
    // Inv: n is number of positional arguments that are not surplus.

    // Bind positional arguments to non-kwonly parameters.
    for (int i = 0; i < n; i++) {
      arguments[i] = positional.get(i);
    }

    // Bind surplus positional arguments to *args parameter.
    if (fn().hasVarargs()) {
      arguments[nparams] =
          Tuple.wrap(Arrays.copyOfRange(positional.toArray(), n, positional.size()));
    }

    List<String> unexpected = null;

    // named arguments
    LinkedHashMap<String, Object> kwargs = null;
    if (fn().hasKwargs()) {
      kwargs = new LinkedHashMap<>();
      arguments[fn().getParameterNames().size() - 1] = kwargs;
    }

    for (int i = 0; i != named.size(); i += 2) {
      String keyword = (String) named.get(i);
      Object value = named.get(i + 1);
      int pos = names.indexOf(keyword);
      if (0 <= pos && pos < nparams) {
        // keyword is the name of a named parameter
        if (arguments[pos] != null) {
          return Starlark.errorf(
              "%s() got multiple values for parameter '%s'", fn().getName(), keyword);
        }
        arguments[pos] = value;

      } else if (kwargs != null) {
        // residual keyword argument
        int sz = kwargs.size();
        kwargs.put(keyword, value);
        if (kwargs.size() == sz) {
          return Starlark.errorf(
              "%s() got multiple values for keyword argument '%s'", fn().getName(), keyword);
        }

      } else {
        // unexpected keyword argument
        if (unexpected == null) {
          unexpected = new ArrayList<>();
        }
        unexpected.add(keyword);
      }
    }
    if (unexpected != null) {
      // Give a spelling hint if there is exactly one.
      // More than that suggests the wrong function was called.
      return Starlark.errorf(
          "%s() got unexpected keyword argument%s: %s%s",
          fn().getName(),
          StarlarkFunction.plural(unexpected.size()),
          Joiner.on(", ").join(unexpected),
          unexpected.size() == 1
              ? SpellChecker.didYouMean(unexpected.get(0), names.subList(0, nparams))
              : "");
    }

    // Apply defaults and report errors for missing required arguments.
    int m = nparams - fn().defaultValues.size(); // first default
    MissingParams missing = null;
    for (int i = n; i < nparams; i++) {
      // provided?
      if (arguments[i] != null) {
        continue;
      }

      // optional?
      if (i >= m) {
        Object dflt = fn().defaultValues.get(i - m);
        if (dflt != StarlarkFunction.MANDATORY) {
          arguments[i] = dflt;
          continue;
        }
      }

      if (missing == null) {
        missing = new MissingParams(fn().getName());
      }
      if (i < numPositionalParams) {
        missing.addPositional(names.get(i));
      } else {
        missing.addNamed(names.get(i));
      }
    }
    if (missing != null) {
      return missing.error();
    }

    throw new AssertionError(
        String.format(
            "this code is meant to be called when there's linked errors, but no error found;"
                + " fn: %s, linkSig: %s, starArgs: %s, named starStarArgs: %s",
            fn().getName(),
            linkSig,
            starArgs != null ? starArgs.size() : 0,
            starStarArgs != null ? starStarArgs.keySet() : Collections.emptySet()));
  }

  static EvalException error(
      StarlarkFunction fn,
      StarlarkCallableLinkSig linkSig,
      Object[] args,
      @Nullable Sequence<?> starArgs,
      @Nullable Dict<Object, Object> starStarArgs)
      throws EvalException {
    return new StarlarkFunctionLinkedError(fn, linkSig).error(args, starArgs, starStarArgs);
  }
}
