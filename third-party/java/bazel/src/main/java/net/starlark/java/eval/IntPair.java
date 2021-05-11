package net.starlark.java.eval;

/** Two integers encoded as long. */
class IntPair {
  static long pack(int lo, int hi) {
    return (((long) hi) << 32) | (lo & 0xffffffffL);
  }

  static int lo(long x) {
    return (int) x;
  }

  static int hi(long x) {
    return (int) (x >>> 32);
  }
}
