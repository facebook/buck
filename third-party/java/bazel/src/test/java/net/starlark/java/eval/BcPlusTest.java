package net.starlark.java.eval;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import org.junit.Test;

public class BcPlusTest {

  @Test
  public void plusString() throws Exception {
    String programPlusConst = "" //
        + "def f(a):\n"
        + "  return a + 'b'\n"
        + "f('a')";
    assertEquals("ab", BcTestUtil.eval(programPlusConst));
    String programConstPlus = "" //
        + "def f(y):\n"
        + "  return 'x' + y\n"
        + "f('y')";
    assertEquals("xy", BcTestUtil.eval(programConstPlus));
  }

  @Test
  public void plusStringInstructions() throws Exception {
    String programPlusConst = "" //
        + "def f(a):\n"
        + "  return a + 'b'\n"
        + "f";
    assertEquals(
        ImmutableList.of(BcInstr.Opcode.PLUS_STRING, BcInstr.Opcode.RETURN),
        BcTestUtil.opcodes(programPlusConst));
    String programConstPlus = "" //
        + "def f(y):\n"
        + "  return 'x' + y\n"
        + "f";
    assertEquals(
        ImmutableList.of(BcInstr.Opcode.PLUS_STRING, BcInstr.Opcode.RETURN),
        BcTestUtil.opcodes(programConstPlus));
  }

  @Test
  public void plusListOfConsts() throws Exception {
    String program = "" //
        + "def f(x):\n"
        + "  return x + ['b']\n"
        + "f(['a'])";
    assertEquals(StarlarkList.immutableOf("a", "b"), BcTestUtil.eval(program));
  }

  @Test
  public void plusListEmpty() throws Exception {
    String program = "" //
        + "def f(x):\n"
        + "  return x + []\n"
        + "f(['a'])";
    assertEquals(StarlarkList.immutableOf("a"), BcTestUtil.eval(program));
  }

  @Test
  public void plusListConstInstructions() throws Exception {
    String program = "" //
        + "def f(x):\n"
        + "  return x + ['c']\n"
        + "f";
    assertEquals(
        ImmutableList.of(BcInstr.Opcode.PLUS_LIST, BcInstr.Opcode.RETURN),
        BcTestUtil.opcodes(program));
  }

  @Test
  public void plusListVar() throws Exception {
    String program = "" //
        + "def f(x, y):\n"
        + "  return x + [y]\n"
        + "f(['a'], 'b')";
    assertEquals(StarlarkList.immutableOf("a", "b"), BcTestUtil.eval(program));
  }

  @Test
  public void plusListVarInstructions() throws Exception {
    String program = "" //
        + "def f(x, y):\n"
        + "  return x + [y]\n"
        + "f";
    assertEquals(
        ImmutableList.of(BcInstr.Opcode.PLUS_LIST, BcInstr.Opcode.RETURN),
        BcTestUtil.opcodes(program));
  }

  @Test
  public void plusListMixed() throws Exception {
    String program = "" //
        + "def f(x, y):\n"
        + "  return x + [y, 'c']\n"
        + "f(['a'], 'b')";
    assertEquals(StarlarkList.immutableOf("a", "b", "c"), BcTestUtil.eval(program));
  }

  @Test
  public void plusListMixedInstructions() throws Exception {
    String program = "" //
        + "def f(x, y):\n"
        + "  return x + [y, 'c']\n"
        + "f";
    assertEquals(
        ImmutableList.of(BcInstr.Opcode.PLUS_LIST, BcInstr.Opcode.RETURN),
        BcTestUtil.opcodes(program));
  }

  @Test
  public void constListPlus() throws Exception {
    String program = "" //
        + "def f(y):\n"
        + "  return ['x'] + y\n"
        + "f(['y'])";
    assertEquals(StarlarkList.immutableOf("x", "y"), BcTestUtil.eval(program));
  }

  @Test
  public void plusStringInPlaceInstructions() throws Exception {
    String program = "" //
        + "def f(x):\n"
        + "  x += 'a'\n"
        + "f";
    assertEquals(
        ImmutableList.of(BcInstr.Opcode.PLUS_STRING_IN_PLACE),
        BcTestUtil.opcodes(program));
  }

  @Test
  public void plusStringInPlace() throws Exception {
    String program = "" //
        + "def f(x):\n"
        + "  x += 'a'\n"
        + "  return x\n"
        + "f('x')";
    assertEquals(
        "xa",
        BcTestUtil.eval(program));
  }

  @Test
  public void plusListInPlaceInstructions() throws Exception {
    String program = "" //
        + "def f(x):\n"
        + "  x += [1]\n"
        + "f";
    assertEquals(ImmutableList.of(BcInstr.Opcode.PLUS_LIST_IN_PLACE), BcTestUtil.opcodes(program));
  }

  @Test
  public void plusListInPlace() throws Exception {
    String program = "" //
        + "def f(x):\n"
        + "  x += ['1']\n"
        + "  return x\n"
        + "f(['2'])";
    assertEquals(StarlarkList.immutableOf("2", "1"), BcTestUtil.eval(program));
  }
}
