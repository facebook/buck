package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import javax.annotation.Nullable;

/** Linked version of {@link StarlarkFunction}. */
class StarlarkFunctionLinked extends StarlarkFunctionLinkedBase {
  private final String[] paramNames;
  private final int[] paramNameDictHashes;
  /**
   * Mapping of call args to function params.
   *
   * <p>Array index is a param index, and array value is an arg index.
   */
  private final int[] paramFromArg;
  /** Spill positional arg to *args param. This is a list of arg indices. */
  private final int[] argToStar;
  /** Spill named arg to **kwargs param. This is a list of arg indices. */
  private final int[] argToStarStar;
  /** Spill named arg to **kwargs param. This is a list of param names. */
  private final String[] argToStarStarName;
  /** {@link DictMap} compatible hashed for names. */
  private final int[] argToStarStarNameDictHashes;

  StarlarkFunctionLinked(
      StarlarkFunction fn,
      int[] paramFromArg,
      int[] argToStar,
      int[] argToStarStar,
      String[] argToStarStarName,
      StarlarkCallableLinkSig linkSig) {
    super(linkSig, fn);

    this.paramNames = fn.getParameterNamesArray();
    this.paramNameDictHashes = fn.getParameterNamesDictHashes();

    this.paramFromArg = paramFromArg;
    this.argToStar = argToStar;
    this.argToStarStar = argToStarStar;
    this.argToStarStarName = argToStarStarName;
    this.argToStarStarNameDictHashes = DictHash.hashes(argToStarStarName);
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
      Preconditions.checkState(
          fn().hasVarargs(), "argToStar array can be non-empty only when function accept varargs");
    }
    if (argToStarStar.length != 0) {
      Preconditions.checkState(
          fn().hasKwargs(),
          "argToStarStar array can be non-empty only when function accepts kwargs");
    }

    // Assert all args are handled: either spilled into some param or to *args or **kwargs
    int nRegularArgs = linkSig.numPositionals + linkSig.namedNames.length;
    boolean[] regularArgsHandled = new boolean[nRegularArgs];
    for (int arg : paramFromArg) {
      if (arg >= 0) {
        Preconditions.checkState(!regularArgsHandled[arg], "Duplicate argument: %s", arg);
        regularArgsHandled[arg] = true;
      }
    }
    for (int arg : argToStar) {
      Preconditions.checkState(
          !regularArgsHandled[arg], "Argument handled more than once: %s", arg);
      regularArgsHandled[arg] = true;
    }
    for (int arg : argToStarStar) {
      Preconditions.checkState(
          !regularArgsHandled[arg], "Argument handled more than once: %s", arg);
      regularArgsHandled[arg] = true;
    }
    for (int i = 0; i < regularArgsHandled.length; i++) {
      boolean handled = regularArgsHandled[i];
      Preconditions.checkState(handled, "Argument is not handled: %s", i);
    }
  }

  @Override
  protected void processArgs(
      Mutability mu,
      Object[] args,
      @Nullable Sequence<?> starArgs,
      @Nullable Dict<Object, Object> starStarArgs,
      Object[] locals)
      throws EvalException {
    StarlarkFunction fn = fn();

    int starArgsPos = 0;
    int starArgsSize = starArgs != null ? starArgs.size() : 0;

    // Subset of `starStarArgs`, keys which were used in params
    int boundKeyCount = 0;
    // This variable is used only if a function has `**kwargs` param.
    BoundKeys boundKeys = null;

    int numParamsWithoutDefault = fn.numNonStarParams - fn.defaultValues.size();
    int numPositionalParams = fn.numNonStarParams - fn.numKeywordOnlyParams;

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
        String key = paramNames[paramIndex];
        int keyHash = paramNameDictHashes[paramIndex];
        DictMap.Node<?, ?> node = starStarArgs.contents.getNode(key, keyHash);
        if (node != null) {
          locals[paramIndex] = node.getValue();
          ++boundKeyCount;
          if (fn.hasKwargs()) {
            if (boundKeys == null) {
              int boundKeyCountUpperBound =
                  Math.min(starStarArgs.size(), paramFromArg.length - paramIndex);
              boundKeys = new BoundKeys(boundKeyCountUpperBound);
            }
            boundKeys.add(node);
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

      // Only check for collision if there's **kwargs param,
      // otherwise duplicates will be handled when populating named parameters.
      if (StarlarkCallableUtils.hasKeywordCollision(linkSig, starStarArgs) != null) {
        throw StarlarkFunctionLinkedError.error(fn, linkSig, args, starArgs, starStarArgs);
      }

      // Exact size of the `kwargs`
      int kwargsSize =
          argToStarStar.length + (starStarArgs != null ? starStarArgs.size() : 0) - boundKeyCount;
      DictMap<String, Object> newKwargs = new DictMap<>(kwargsSize);
      for (int i = 0; i < argToStarStar.length; ++i) {
        String key = argToStarStarName[i];
        int keyHash = argToStarStarNameDictHashes[i];
        Object value = args[argToStarStar[i]];

        // We checked above that keys are unique, so we can skip eviction here.
        newKwargs.putNoEvictNoResize(key, keyHash, value);
      }
      boolean haveUnboundStarStarArgs =
          starStarArgs != null && starStarArgs.size() != boundKeyCount;
      if (haveUnboundStarStarArgs) {
        DictMap.Node<Object, Object> node = starStarArgs.contents.getFirst();
        while (node != null) {
          if (!(node.key instanceof String)) {
            throw StarlarkFunctionLinkedError.error(fn, linkSig, args, starArgs, starStarArgs);
          }
          String key = (String) node.key;
          if (boundKeys != null && boundKeys.contains(node)) {
            node = node.getNext();
            continue;
          }
          // We checked above that keys are unique, so we can skip eviction here.
          newKwargs.putNoEvictNoResize(key, node.keyHash, node.getValue());
          node = node.getNext();
        }
      }
      Preconditions.checkState(newKwargs.size() == kwargsSize);
      locals[fn.kwargsIndex] = Dict.wrap(mu, newKwargs);
    } else {
      // there's no **kwargs
      if (starStarArgs != null && starStarArgs.size() != boundKeyCount) {
        throw StarlarkFunctionLinkedError.error(fn, linkSig, args, starArgs, starStarArgs);
      }
    }
  }

  /**
   * We store removed entries in linear set because the number of bound parameters is usually small,
   * and we use this structure only when there's no {@code **kwargs} parameter.
   */
  private static class BoundKeys {
    private final DictMap.Node<?, ?>[] nodes;
    private int size = 0;

    BoundKeys(int capacity) {
      nodes = new DictMap.Node<?, ?>[capacity];
    }

    void add(DictMap.Node<?, ?> node) {
      nodes[size++] = node;
    }

    boolean contains(DictMap.Node<?, ?> node) {
      for (int i = 0; i < size; ++i) {
        if (nodes[i] == node) {
          return true;
        }
      }
      return false;
    }
  }
}
