package net.starlark.java.eval;

import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import java.util.Arrays;
import java.util.Objects;

/** Function "linking" signature. */
public class StarlarkCallableLinkSig {

  final int numPositionals;
  final String[] namedNames;
  // `DictMap` hashes of `namedNames`
  final int[] namedNameDictHashes;
  final boolean hasStar;
  final boolean hasStarStar;

  // signature has no named args and no stars, cache
  private final boolean isPosOnly;

  // cache hash code
  private final int hashCode;

  private StarlarkCallableLinkSig(int numPositionals, String[] namedNames, boolean hasStar,
      boolean hasStarStar) {
    this.numPositionals = numPositionals;
    this.namedNames = namedNames;
    this.namedNameDictHashes = DictHash.hashes(namedNames);
    this.hasStar = hasStar;
    this.hasStarStar = hasStarStar;

    this.isPosOnly = namedNames.length == 0 && !hasStar && !hasStarStar;

    this.hashCode = Objects.hash(numPositionals, Arrays.hashCode(namedNames), hasStar, hasStarStar);
  }

  /** Signature has star or star-star argument. */
  boolean hasStars() {
    return hasStar || hasStarStar;
  }

  /** Positional arguments-only call, no named args, no stars. */
  boolean isPosOnly() {
    return isPosOnly;
  }

  /** Number of fixed arguments. */
  int fixedArgCount() {
    return numPositionals + namedNames.length;
  }

  private volatile DictMap<String, NoneType> namedNamesSet;

  /** {@link #namedNames} as set. */
  DictMap<String, NoneType> namedNamesSet() {
    DictMap<String, NoneType> namedNamesSet = this.namedNamesSet;
    if (namedNamesSet == null) {
      return this.namedNamesSet = DictMap.makeSetFromUniqueKeys(namedNames, namedNameDictHashes);
    } else {
      return namedNamesSet;
    }
  }

  private static final Interner<StarlarkCallableLinkSig> interner = Interners.newWeakInterner();

  private static StarlarkCallableLinkSig[] initPosOnly() {
    StarlarkCallableLinkSig[] array = new StarlarkCallableLinkSig[10];
    for (int numPositionals = 0; numPositionals < array.length; numPositionals++) {
      array[numPositionals] = interner.intern(
          new StarlarkCallableLinkSig(numPositionals, ArraysForStarlark.EMPTY_STRING_ARRAY, false, false));
    }
    return array;
  }

  private static final StarlarkCallableLinkSig[] posOnly = initPosOnly();

  private static final StarlarkCallableLinkSig kwargsOnly =
      interner.intern(new StarlarkCallableLinkSig(0, ArraysForStarlark.EMPTY_STRING_ARRAY, false, true));

  public static StarlarkCallableLinkSig of(int numPositionals, String[] namedNames,
      boolean hasStar, boolean hasStarStar) {
    if (numPositionals < posOnly.length && namedNames.length == 0 && !hasStar && !hasStarStar) {
      return posOnly[numPositionals];
    }
    if (numPositionals == 0 && namedNames.length == 0 && !hasStar && hasStarStar) {
      return kwargsOnly;
    }
    return interner.intern(new StarlarkCallableLinkSig(numPositionals, namedNames, hasStar, hasStarStar));
  }

  public static StarlarkCallableLinkSig positional(int count) {
    return of(count, ArraysForStarlark.EMPTY_STRING_ARRAY, false, false);
  }

  @Override
  public int hashCode() {
    return hashCode;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || this.getClass() != obj.getClass()) {
      return false;
    }
    StarlarkCallableLinkSig that = (StarlarkCallableLinkSig) obj;
    if (this.hashCode != that.hashCode) {
      return false;
    }

    return this.numPositionals == that.numPositionals
        && this.hasStar == that.hasStar
        && this.hasStarStar == that.hasStarStar
        && Arrays.equals(this.namedNames, that.namedNames);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(numPositionals);
    if (namedNames.length != 0) {
      sb.append(" ");
      sb.append(String.join(",", namedNames));
    }
    if (hasStar) {
      sb.append(" *");
    }
    if (hasStarStar) {
      sb.append(" **");
    }
    return sb.toString();
  }
}
