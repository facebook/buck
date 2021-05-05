package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
    int boundKeyCount = 0;
    // This variable is used only if a function has `**kwargs` param.
    BoundsKeys boundsKeys = null;

    int numParamsWithoutDefault = fn.numNonStarParams - fn.defaultValues.size();
    int numPositionalParams = fn.numNonStarParams - fn.numKeywordOnlyParams;

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
          ++boundKeyCount;
          if (fn.hasKwargs()) {
            if (boundsKeys == null) {
              int boundKeyCountUpperBound =
                  Math.min(starStarArgs.size(), paramFromArg.length - paramIndex);
              boundsKeys = new BoundsKeys(boundKeyCountUpperBound);
            }
            boundsKeys.add(name);
          }
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

      throw StarlarkFunctionLinkedError.error(fn, linkSig, args, starArgs, starStarArgs);
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
        throw StarlarkFunctionLinkedError.error(fn, linkSig, args, starArgs, starStarArgs);
      }
    }

    if (fn.kwargsIndex >= 0) {
      // there's **kwargs
      LinkedHashMap<String, Object> newKwargs =
          Maps.newLinkedHashMapWithExpectedSize(
              (starStarArgs != null ? starStarArgs.size() : 0)
                  + argToStarStar.length
                  - boundKeyCount);
      for (int i = 0; i < argToStarStar.length; ++i) {
        newKwargs.put(argToStarStarName[i], args[argToStarStar[i]]);
      }
      boolean haveUnboundStarStarArgs =
          starStarArgs != null && starStarArgs.size() != boundKeyCount;
      if (haveUnboundStarStarArgs) {
        for (Map.Entry<Object, Object> e : starStarArgs.contents.entrySet()) {
          if (!(e.getKey() instanceof String)) {
            throw StarlarkFunctionLinkedError.error(fn, linkSig, args, starArgs, starStarArgs);
          }
          String key = (String) e.getKey();
          if (boundsKeys != null && boundsKeys.contains(key)) {
            continue;
          }
          Object prev = newKwargs.put(key, e.getValue());
          if (prev != null) {
            throw StarlarkFunctionLinkedError.error(fn, linkSig, args, starArgs, starStarArgs);
          }
        }
      }
      locals[fn.kwargsIndex] = Dict.wrap(mu, newKwargs);
    } else {
      // there's no **kwargs
      if (starStarArgs != null && starStarArgs.size() != boundKeyCount) {
        throw StarlarkFunctionLinkedError.error(fn, linkSig, args, starArgs, starStarArgs);
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
    private final String[] keysArray;
    private int size = 0;

    BoundsKeys(int capacity) {
      keysArray = new String[capacity];
    }

    void add(String key) {
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

}
