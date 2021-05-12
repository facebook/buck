package net.starlark.java.eval;

import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.Map;
import javax.annotation.Nullable;

/** Utilities for callable implementations. */
class StarlarkCallableUtils {
  /** Error message when keyword is not string. */
  static EvalException errorKeywordIsNotString(Object key) {
    return Starlark.errorf("keywords must be strings, not %s", Starlark.type(key));
  }

  /**
   * Check if {@code linkSig} and {@code kwargs} has intersecting keys
   *
   * @return a key common in both sets of null
   */
  @Nullable
  static String hasKeywordCollision(
      StarlarkCallableLinkSig linkSig, @Nullable Dict<Object, Object> kwargs) {
    // If any keys is empty, there are definitely no common keys
    if (linkSig.namedNames.length == 0 || kwargs == null || kwargs.isEmpty()) {
      return null;
    }

    // Linear over smaller collection, then constant over larger collection
    if (linkSig.namedNames.length <= kwargs.contents.size()) {
      for (int i = 0; i < linkSig.namedNames.length; i++) {
        String key = linkSig.namedNames[i];
        int keyHash = linkSig.namedNameDictHashes[i];
        if (kwargs.contents.containsKey(key, keyHash)) {
          return key;
        }
      }
    } else {
      DictMap.Node<String, NoneType> node = linkSig.namedNamesSet().getFirst();
      while (node != null) {
        if (kwargs.contents.containsKey(node.key, node.keyHash)) {
          return node.key;
        }
        node = node.getNext();
      }
    }
    return null;
  }

  /** Positional and named args. */
  static class FoldedArgs {
    final ImmutableList<Object> positional;
    final ImmutableList<Object> named;

    FoldedArgs(ImmutableList<Object> positional, ImmutableList<Object> named) {
      this.positional = positional;
      this.named = named;
    }
  }

  /** Fold positional, named, star and star-star into positional and named. */
  static FoldedArgs foldArgs(
      StarlarkCallableLinkSig linkSig,
      Object[] args,
      @Nullable Sequence<?> starArgs,
      @Nullable Dict<?, ?> starStarArgs)
      throws EvalException {
    ImmutableList.Builder<Object> positional = ImmutableList.builder();
    positional.addAll(Arrays.asList(args).subList(0, linkSig.numPositionals));
    if (starArgs != null) {
      positional.addAll(starArgs.asList());
    }

    ImmutableList.Builder<Object> named = ImmutableList.builder();
    for (int i = 0; i < linkSig.namedNames.length; ++i) {
      named.add(linkSig.namedNames[i]);
      named.add(args[linkSig.numPositionals + i]);
    }
    if (starStarArgs != null) {
      for (Map.Entry<?, ?> entry : starStarArgs.entrySet()) {
        if (!(entry.getKey() instanceof String)) {
          throw StarlarkCallableUtils.errorKeywordIsNotString(entry.getKey());
        }
        named.add(entry.getKey());
        named.add(entry.getValue());
      }
    }

    return new FoldedArgs(positional.build(), named.build());
  }
}
