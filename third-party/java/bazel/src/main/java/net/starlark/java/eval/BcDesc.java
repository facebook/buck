package net.starlark.java.eval;

/** Certain universe descriptors. */
class BcDesc {
  /** Builtin of {@code type()} function. */
  static final BuiltinFunction TYPE = (BuiltinFunction) Starlark.UNIVERSE.get("type");

  static {
    if (TYPE == null) {
      throw new AssertionError("universe must have 'type' function");
    }
  }
}
