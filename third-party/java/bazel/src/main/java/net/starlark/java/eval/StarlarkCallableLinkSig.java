package net.starlark.java.eval;

import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import java.util.Arrays;
import java.util.Objects;

/** Function "linking" signature. */
public class StarlarkCallableLinkSig {

  final int numPositionals;
  final String[] namedNames;
  final boolean hasStar;
  final boolean hasStarStar;

  // cache hash code
  private final int hashCode;

  private StarlarkCallableLinkSig(int numPositionals, String[] namedNames, boolean hasStar,
      boolean hasStarStar) {
    this.numPositionals = numPositionals;
    this.namedNames = namedNames;
    this.hasStar = hasStar;
    this.hasStarStar = hasStarStar;
    this.hashCode = Objects.hash(numPositionals, Arrays.hashCode(namedNames), hasStar, hasStarStar);
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
    return numPositionals + " " + String.join(",", namedNames) + (hasStar ? " *" : "") + (hasStarStar ? " **" : "");
  }
}
