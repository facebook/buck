package net.starlark.java.eval;

/** Compute hashes which are used in {@link DictMap}. */
class DictHash {
  /** Make a better hash. */
  // this is copy-paste from Guava
  private static int smear(int hashCode) {
    int C1 = 0xcc9e2d51;
    int C2 = 0x1b873593;
    return C2 * Integer.rotateLeft(hashCode * C1, 15);
  }

  /** Hash the map key, this is different from {@link Object#hashCode()}. */
  static int hash(Object key) {
    return smear(key.hashCode());
  }

  /** Compute hashes of multiple objects. */
  static <K> int[] hashes(K[] keys) {
    int[] hashes = ArraysForStarlark.newIntArray(keys.length);
    for (int i = 0; i < keys.length; i++) {
      K key = keys[i];
      hashes[i] = hash(key);
    }
    return hashes;
  }
}
