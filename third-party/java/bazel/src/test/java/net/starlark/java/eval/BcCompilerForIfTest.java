package net.starlark.java.eval;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Random;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import org.junit.Test;

public class BcCompilerForIfTest {

  /** Utility to count evaluation side effects. */
  private static class CountCalls {
    /** {@code true()} or {@code false()} function calls. */
    ArrayList<Boolean> calls = new ArrayList<>();
  }

  /** Test boolean value which is not const-propagated, and which counts the invocations. */
  @StarlarkBuiltin(name = "alt_bool")
  public static class AltBool extends StarlarkValue {
    private final CountCalls countCalls;
    private final boolean value;

    public AltBool(CountCalls countCalls, boolean value) {
      this.countCalls = countCalls;
      this.value = value;
    }

    @Override
    public boolean truth() {
      countCalls.calls.add(value);
      return value;
    }

    @Override
    public void repr(Printer printer) {
      printer.append(value ? "true" : "false");
    }
  }

  private Object exec(String program, CountCalls countCalls) throws Exception {
    StarlarkThread thread = new StarlarkThread(Mutability.create(), StarlarkSemantics.DEFAULT);
    Module module = Module.create();
    module.setGlobal("true", new AltBool(countCalls, true));
    module.setGlobal("false", new AltBool(countCalls, false));
    StarlarkFunction f = (StarlarkFunction) Starlark.execFile(
        ParserInput.fromString(program, "f.star"),
        FileOptions.DEFAULT, module,
        thread);
    return Starlark.call(thread, f, Tuple.empty(), Dict.empty());
  }

  private enum BinOp {
    AND("and"),
    OR("or"),
    ;
    final String expr;

    BinOp(String expr) {
      this.expr = expr;
    }

    boolean eval(boolean lhs, boolean rhs) {
      switch (this) {
        case AND: return lhs && rhs;
        case OR: return lhs || rhs;
        default: throw new AssertionError();
      }
    }
  }

  private static abstract class TestExpr {
    abstract String expr();

    abstract boolean eval(CountCalls countCalls);

    @Override
    public final String toString() {
      return expr();
    }
  }

  private static class TestValue extends TestExpr {
    private final boolean value;
    private final boolean constant;
    private final String expr;

    public TestValue(boolean value, boolean constant, String expr) {
      this.value = value;
      this.constant = constant;
      this.expr = expr;
    }

    @Override
    String expr() {
      return expr;
    }

    @Override
    boolean eval(CountCalls countCalls) {
      if (!constant) {
        countCalls.calls.add(value);
      }
      return value;
    }
  }

  private static final TestValue TRUE = new TestValue(true, true, "True");
  private static final TestValue ALT_TRUE = new TestValue(true, false, "true");
  private static final TestValue FALSE = new TestValue(false, true, "False");
  private static final TestValue ALT_FALSE = new TestValue(false, false, "false");

  private static final TestValue[] testValues = {
      TRUE,
      ALT_TRUE,
      FALSE,
      ALT_FALSE,
  };

  private static class Not extends TestExpr {
    private final TestExpr arg;

    private Not(TestExpr arg) {
      this.arg = arg;
    }

    @Override
    String expr() {
      return "not " + arg;
    }

    @Override
    boolean eval(CountCalls countCalls) {
      return !arg.eval(countCalls);
    }

  }

  private static class Logical extends TestExpr {
    private final TestExpr lhs;
    private final TestExpr rhs;
    private final BinOp binOp;

    public Logical(TestExpr lhs, TestExpr rhs, BinOp binOp) {
      this.lhs = lhs;
      this.rhs = rhs;
      this.binOp = binOp;
    }

    @Override
    String expr() {
      return "(" + lhs.expr() + " " + binOp.expr + " " + rhs.expr() + ")";
    }

    @Override
    boolean eval(CountCalls countCalls) {
      boolean lhsValue = lhs.eval(countCalls);
      if (lhsValue == (binOp != BinOp.AND)) {
        return lhsValue;
      }
      return binOp.eval(lhsValue, rhs.eval(countCalls));
    }

    static Logical and(TestExpr lhs, TestExpr rhs) {
      return new Logical(lhs, rhs, BinOp.AND);
    }

    static Logical or(TestExpr lhs, TestExpr rhs) {
      return new Logical(lhs, rhs, BinOp.OR);
    }
  }

  @Test
  public void values() throws Exception {
    for (TestValue value : testValues) {
      doTestIf(value);
    }
  }

  @Test
  public void and() throws Exception {
    for (TestValue lhs : testValues) {
      for (TestValue rhs : testValues) {
        doTestIfElse(Logical.and(lhs, rhs));
      }
    }
  }

  @Test
  public void or() throws Exception {
    for (TestValue lhs : testValues) {
      for (TestValue rhs : testValues) {
        doTestIfElse(Logical.or(lhs, rhs));
      }
    }
  }

  @Test
  public void andOrNot() throws Exception {
    for (TestValue lhs : testValues) {
      for (TestValue rhs : testValues) {
        for (boolean negateLhs : new boolean[] { true, false }) {
          for (boolean negateRhs : new boolean[] { true, false }) {
            for (BinOp binOp : BinOp.values()) {
              Logical expr = new Logical(
                  negateLhs ? new Not(lhs) : lhs,
                  negateRhs ? new Not(rhs) : rhs,
                  binOp);
              doTestIf(expr);
            }
          }
        }
      }
    }
  }

  private TestExpr randomExpr(Random random, int maxDepth) {
    if (maxDepth == 0) {
      return testValues[random.nextInt(testValues.length)];
    }
    switch (random.nextInt(4)) {
      case 0:
        return testValues[random.nextInt(testValues.length)];
      case 1:
        return new Not(randomExpr(random, maxDepth - 1));
      case 2:
        return Logical.and(randomExpr(random, maxDepth - 1), randomExpr(random, maxDepth - 1));
      case 3:
        return Logical.or(randomExpr(random, maxDepth - 1), randomExpr(random, maxDepth - 1));
      default:
        throw new AssertionError();
    }
  }

  private static final int RANDOM_ITERATIONS = 1_000;

  @Test
  public void tempTest() throws Exception {
    // ((true or not true) or (not false or True))
    doTestIfElse(Logical.or(Logical.or(ALT_TRUE, FALSE), Logical.or(ALT_TRUE, TRUE)));
  }

  private int maxDepthForIteration(int i) {
    if (i < 5) {
      return 0;
    } else if (i < RANDOM_ITERATIONS / 100) {
      return 1;
    } else if (i < RANDOM_ITERATIONS / 30) {
      return 2;
    } else if (i < RANDOM_ITERATIONS / 10) {
      return 3;
    } else if (i < RANDOM_ITERATIONS / 3) {
      return 4;
    } else if (i < RANDOM_ITERATIONS / 2) {
      return 5;
    } else {
      return 20;
    }
  }

  @Test
  public void randomIf() throws Exception {
    Random random = new Random(1);
    for (int i = 0; i < RANDOM_ITERATIONS; ++i) {
      TestExpr testExpr = randomExpr(random, maxDepthForIteration(i));
      doTestIf(testExpr);
    }
  }

  @Test
  public void randomIfElse() throws Exception {
    Random random = new Random(2);
    for (int i = 0; i < RANDOM_ITERATIONS; ++i) {
      TestExpr testExpr = randomExpr(random, maxDepthForIteration(i));
      doTestIfElse(testExpr);
    }
  }

  private void doTestProgram(TestExpr testExpr, String program) throws Exception {
    program = program.replace("EXPR", testExpr.expr());

    CountCalls programCalls = new CountCalls();
    CountCalls expectedCalls = new CountCalls();

    Object callResult = exec(program, programCalls);
    assertEquals(
        testExpr.toString(),
        testExpr.eval(expectedCalls) ? "t" : "f",
        callResult);
    assertEquals(
        testExpr.toString(),
        expectedCalls.calls,
        programCalls.calls);
  }

  private void doTestIf(TestExpr testExpr) throws Exception {
    String program = "" //
        + "def test():\n"
        + "  if EXPR:\n"
        + "    return 't'\n"
        + "  return 'f'\n"
        + "test";
    doTestProgram(testExpr, program);
  }

  private void doTestIfElse(TestExpr testExpr) throws Exception {
    String program = "" //
        + "def test():\n"
        + "  if EXPR:\n"
        + "    return 't'\n"
        + "  else:\n"
        + "    return 'f'\n"
        + "test";
    doTestProgram(testExpr, program);
  }

  @Test
  public void eqOpcodes() throws Exception {
    String program = "" //
        + "def f(x):\n"
        + "  if x == 1:\n"
        + "    return 2\n"
        + "f";
    assertEquals(
        ImmutableList.of(BcInstr.Opcode.IF_NOT_EQ_BR, BcInstr.Opcode.RETURN),
        BcTestUtil.opcodes(program));
  }

  @Test
  public void inOpcodes() throws Exception {
    String program = "" //
        + "def f(x):\n"
        + "  if 1 not in x:\n"
        + "    return 2\n"
        + "f";
    assertEquals(
        ImmutableList.of(BcInstr.Opcode.IF_IN_BR, BcInstr.Opcode.RETURN),
        BcTestUtil.opcodes(program));
  }

  @Test
  public void typeIsOpcodes() throws Exception {
    String program = "" //
        + "def f(x):\n"
        + "  if type(x) == 'list':\n"
        + "    return 1\n"
        + "f";
    assertEquals(
        ImmutableList.of(BcInstr.Opcode.IF_NOT_TYPE_IS_BR, BcInstr.Opcode.RETURN),
        BcTestUtil.opcodes(program));
  }

  @Test
  public void notTypeIsOpcodes() throws Exception {
    String program = "" //
        + "def f(x):\n"
        + "  if not type(x) == 'list':\n"
        + "    return 1\n"
        + "f";
    assertEquals(
        ImmutableList.of(BcInstr.Opcode.IF_TYPE_IS_BR, BcInstr.Opcode.RETURN),
        BcTestUtil.opcodes(program));
  }

  @Test
  public void typeIsEval() throws Exception {
    String program = "" //
        + "def f(x):\n"
        + "  if type(x) == 'list':\n"
        + "    return 1\n"
        + "f([])";
    assertEquals(
        StarlarkInt.of(1),
        BcTestUtil.eval(program));
  }
}
