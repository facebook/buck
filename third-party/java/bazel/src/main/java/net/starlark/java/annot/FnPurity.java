package net.starlark.java.annot;

/** Function purity level. */
public enum FnPurity {
  /**
   * Function is safe to call speculatively (i. e. at compile time).
   *
   * Example functions: {@code tuple}, {@code len}.
   */
  SPEC_SAFE,
  /**
   * A function is pure if it does not modify global state.
   *
   * Example functions: {@code list}, {@code dict}: it is pointless to call them speculatively
   * (because they return mutable value), but they can still be used in pure code.
   *
   * Functions like {@code list.append} are also considered pure.
   */
  PURE,
  /** Function is not pure. */
  DEFAULT,
  ;
}
