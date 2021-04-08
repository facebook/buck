package net.starlark.java.eval;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** Linked version of {@link StarlarkFunction}. */
class StarlarkFunctionLinked extends StarlarkFunctionLinkedBase {
  /**
   * Mapping of call args to function params.
   *
   * Array index is a param index, and array value is an arg index.
   */
  private final int[] paramFromArg;
  /** Spill positional arg to *args param. This is a list of arg indices. */
  private final int[] argToStar;
  /** Spill named arg to **kwargs param. This is a list of arg indices. */
  private final int[] argToStarStar;
  /** Spill named arg to **kwargs param. This is a list of param names. */
  private final String[] argToStarStarName;

  StarlarkFunctionLinked(
      StarlarkFunction fn, int[] paramFromArg, int[] argToStar,
      int[] argToStarStar, String[] argToStarStarName, StarlarkCallableLinkSig linkSig) {
    super(linkSig, fn);
    this.paramFromArg = paramFromArg;
    this.argToStar = argToStar;
    this.argToStarStar = argToStarStar;
    this.argToStarStarName = argToStarStarName;
    if (Bc.ASSERTIONS) {
      assertions();
    }
  }

  /** Self-test, only call when assertions enabled. */
  private void assertions() {
    int nParams = fn().numNonStarParams;

    Preconditions.checkState(paramFromArg.length == nParams);
    Preconditions.checkArgument(argToStarStar.length == argToStarStarName.length);

    if (argToStar.length != 0) {
      Preconditions.checkState(fn().hasVarargs(),
          "argToStar array can be non-empty only when function accept varargs");
    }
    if (argToStarStar.length != 0) {
      Preconditions.checkState(fn().hasKwargs(),
          "argToStarStar array can be non-empty only when function accepts kwargs");
    }

    // Assert all args are handled: either spilled into some param or to *args or **kwargs
    int nRegularArgs = linkSig.numPositionals + linkSig.namedNames.length;
    boolean[] regularArgsHandled = new boolean[nRegularArgs];
    for (int arg : paramFromArg) {
      if (arg >= 0) {
        Preconditions.checkState(!regularArgsHandled[arg],
            "Duplicate argument: %s", arg);
        regularArgsHandled[arg] = true;
      }
    }
    for (int arg : argToStar) {
      Preconditions.checkState(!regularArgsHandled[arg], "Argument handled more than once: %s", arg);
      regularArgsHandled[arg] = true;
    }
    for (int arg : argToStarStar) {
      Preconditions.checkState(!regularArgsHandled[arg], "Argument handled more than once: %s", arg);
      regularArgsHandled[arg] = true;
    }
    for (int i = 0; i < regularArgsHandled.length; i++) {
      boolean handled = regularArgsHandled[i];
      Preconditions.checkState(handled, "Argument is not handled: %s", i);
    }
  }

  @Override
  protected void processArgs(
      Mutability mu, Object[] args,
      @Nullable Sequence<?> starArgs, @Nullable Dict<Object, Object> starStarArgs,
      Object[] locals)
      throws EvalException
  {
    StarlarkFunction fn = fn();

    int starArgsPos = 0;
    int starArgsSize = starArgs != null ? starArgs.size() : 0;

    // Subset of `starStarArgs`, keys which were used in params
    BoundsKeys boundsKeys = null;

    int numParamsWithoutDefault = fn.numNonStarParams - fn.defaultValues.size();
    int numPositionalParams = fn.numNonStarParams - fn.numKeywordOnlyParams;

    MissingParams missing = null;

    ImmutableList<String> names = fn.getParameterNames();

    for (int paramIndex = 0; paramIndex < paramFromArg.length; ++paramIndex) {
      if (paramFromArg[paramIndex] >= 0) {
        locals[paramIndex] = args[paramFromArg[paramIndex]];
        continue;
      }
      if (paramIndex < numPositionalParams && starArgsPos < starArgsSize) {
        locals[paramIndex] = starArgs.get(starArgsPos++);
        continue;
      }
      if (starStarArgs != null) {
        String name = names.get(paramIndex);
        Object value = starStarArgs.get(name);
        if (value != null) {
          locals[paramIndex] = value;
          if (boundsKeys == null) {
            boundsKeys = new BoundsKeys();
          }
          boundsKeys.add(name);
          continue;
        }
      }

      if (paramIndex >= numParamsWithoutDefault) {
        Object defaultValue = fn.defaultValues.get(paramIndex - numParamsWithoutDefault);
        if (defaultValue != StarlarkFunction.MANDATORY) {
          locals[paramIndex] = defaultValue;
          continue;
        }
      }

      // Collect all the missing parameters to report all of them at once.
      if (missing == null) {
        missing = new MissingParams();
      }
      if (paramIndex < numPositionalParams) {
        missing.positional.add(names.get(paramIndex));
      } else {
        missing.named.add(names.get(paramIndex));
      }
    }

    if (missing != null) {
      throwOnMissingPositionalOrNamed(starStarArgs, missing);
    }

    // now all regular params handled, processing *args and **kwargs

    if (fn.varargsIndex >= 0) {
      // there's *args
      int newArgsSize = argToStar.length + (starArgs != null ? starArgs.size() - starArgsPos : 0);
      Object[] newArgs = ArraysForStarlark.newObjectArray(newArgsSize);
      int k = 0;
      for (int j : argToStar) {
        newArgs[k++] = args[j];
      }
      while (starArgsPos < starArgsSize) {
        newArgs[k++] = starArgs.get(starArgsPos);
        ++starArgsPos;
      }
      Preconditions.checkState(k == newArgsSize);
      locals[fn.varargsIndex] = Tuple.wrap(newArgs);
    } else {
      // there's no *args
      if (starArgsPos < starArgsSize) {
        throw new StarlarkFunctionLinkedErrorTooManyPositionals(fn, linkSig)
            .error(args, starArgs);
      }
    }

    if (fn.kwargsIndex >= 0) {
      // there's **kwargs
      LinkedHashMap<String, Object> newKwargs =
          new LinkedHashMap<>(
              (starStarArgs != null ? starStarArgs.size() : 0)
                  + argToStarStar.length
                  - (boundsKeys != null ? boundsKeys.size : 0));
      for (int i = 0; i < argToStarStar.length; ++i) {
        newKwargs.put(argToStarStarName[i], args[argToStarStar[i]]);
      }
      boolean haveUnboundStarStarArgs =
          starStarArgs != null && (boundsKeys == null || starStarArgs.size() != boundsKeys.size);
      if (haveUnboundStarStarArgs) {
        for (Map.Entry<Object, Object> e : starStarArgs.contents.entrySet()) {
          if (!(e.getKey() instanceof String)) {
            throwIfKwargsKeysAreNotStrings(starStarArgs);
          }
          String key = (String) e.getKey();
          if (boundsKeys != null && boundsKeys.contains(key)) {
            continue;
          }
          Object prev = newKwargs.put(key, e.getValue());
          if (prev != null) {
            throw Starlark.errorf("%s() got multiple values for keyword argument '%s'", fn.getName(), key);
          }
        }
      }
      locals[fn.kwargsIndex] = Dict.wrap(mu, newKwargs);
    } else {
      // there's no **kwargs
      if (starStarArgs != null && starStarArgs.size() != BoundsKeys.size(boundsKeys)) {
        throw unexpectedKeywordArgument(starStarArgs, boundsKeys);
      }
    }
  }

  private EvalException unexpectedKeywordArgument(Dict<Object, Object> starStarArgs, @Nullable BoundsKeys boundsKeys)
      throws EvalException {
    throwIfKwargsKeysAreNotStrings(starStarArgs);
    for (Object key : starStarArgs.keySet()) {
      String keyString = (String) key;
      if (boundsKeys != null) {
        if (boundsKeys.contains(keyString)) {
          continue;
        }
      }
      if (fn().getParameterNames().contains(keyString)) {
        return Starlark.errorf("%s() got multiple values for parameter '%s'", fn().getName(), keyString);
      } else {
        return Starlark.errorf("%s() got unexpected keyword argument: %s", fn().getName(), keyString);
      }
    }
    throw new AssertionError();
  }

  private void throwOnMissingPositionalOrNamed(
      Dict<Object, Object> starStarArgs,
      MissingParams missing) throws EvalException {
    throwIfKwargsKeysAreNotStrings(starStarArgs);
    if (!missing.positional.isEmpty()) {
      throw Starlark.errorf(
          "%s() missing %d required positional argument%s: %s",
          fn().getName(),
          missing.positional.size(),
          StarlarkFunction.plural(missing.positional.size()),
          Joiner.on(", ").join(missing.positional));
    }
    if (!missing.named.isEmpty()) {
      throw Starlark.errorf(
          "%s() missing %d required keyword-only argument%s: %s",
          fn().getName(),
          missing.named.size(),
          StarlarkFunction.plural(missing.named.size()),
          Joiner.on(", ").join(missing.named));
    }
    throw new AssertionError();
  }

  private void throwIfKwargsKeysAreNotStrings(@Nullable Dict<?, ?> kwargs) throws EvalException {
    if (kwargs != null) {
      for (Object key : kwargs.keySet()) {
        if (!(key instanceof String)) {
          throw Starlark.errorf("keywords must be strings, not %s", Starlark.type(key));
        }
      }
    }
  }

  /**
   * We store removed keys in linear set, because the most common cases are these:
   * <ul>
   *   <li>the number of bound parameters is small, so linear search is fast</li>
   *   <li>there's no **args parameter, so don't need to compare contents at all</li>
   * </ul>
   *
   * Consequently, this won't work well when we have many named parameters,
   * and also non-empty dict assigned to **kwargs parameter.
  */
  private static class BoundsKeys {
    private String[] keysArray = new String[16];
    private int size = 0;

    private static int size(@Nullable BoundsKeys boundsKeys) {
      return boundsKeys != null ? boundsKeys.size : 0;
    }

    void add(String key) {
      if (size == keysArray.length) {
        keysArray = Arrays.copyOf(keysArray, keysArray.length * 2);
      }
      keysArray[size++] = key;
    }

    boolean contains(String key) {
      for (int i = 0; i < size; ++i) {
        // Identity comparison is much cheaper than equals, try it first.
        // Note parameter names and arg names interned by the parser.
        if (keysArray[i] == key) {
          return true;
        }
      }
      for (int i = 0; i < size; ++i) {
        if (keysArray[i].equals(key)) {
          return true;
        }
      }
      return false;
    }
  }

  private static class MissingParams {
    private List<String> positional = new ArrayList<>();
    private List<String> named = new ArrayList<>();
  }
}
