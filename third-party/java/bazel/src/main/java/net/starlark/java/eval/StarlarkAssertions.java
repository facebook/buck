package net.starlark.java.eval;

/** Starlark runtime assertions. */
class StarlarkAssertions {

  /**
   * This constant enables/disables assertions in Starlark interpreter: when turned on, it checks
   * that:
   *
   * <ul>
   *   <li>Compiler generates valid opcode arguments, according to opcode spec
   *   <li>Interpreter decodes opcode arguments correctly (e. g. does not consume extra undeclared
   *       argument)
   * </ul>
   *
   * Turn assertions on when debugging the compiler or interpreter.
   *
   * <p>Note the assertions are turned on when tests are launched from Bazel.
   */
  public static final boolean ENABLED =
      Boolean.getBoolean("starlark.assertions") || System.getenv("STARLARK_ASSERTIONS") != null;

  static {
    if (ENABLED) {
      System.err.println();
      System.err.println();
      System.err.println("Java Starlark internal runtime assertions enabled.");
      System.err.println();
      System.err.println();
    }
  }
}
