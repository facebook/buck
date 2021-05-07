package net.starlark.java.eval;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import org.junit.Test;

public class BcTypeIsTest {
  @Test
  public void typeIsOpcodes() throws Exception {
    String program = "" //
        + "def is_string(x):\n"
        + "  return type(x) == type('')\n"
        + "is_string";
    assertEquals(
        ImmutableList.of(BcInstr.Opcode.TYPE_IS, BcInstr.Opcode.RETURN),
        BcTestUtil.opcodes(program));
  }

  @Test
  public void typeIsOpcodesRev() throws Exception {
    String program = "" //
        + "def is_int(x):\n"
        + "  return type(1) == type(x)\n"
        + "is_int";
    assertEquals(
        ImmutableList.of(BcInstr.Opcode.TYPE_IS, BcInstr.Opcode.RETURN),
        BcTestUtil.opcodes(program));
  }

  @Test
  public void typeIsOpcodeForNonTrivialExpression() throws Exception {
    String program = "" //
        + "def test(x): return type(x + x) == 'xxx'\n"
        + "test";
    assertEquals(
        ImmutableList.of(BcInstr.Opcode.PLUS, BcInstr.Opcode.TYPE_IS, BcInstr.Opcode.RETURN),
        BcTestUtil.opcodes(program));
  }

  @Test
  public void evalTypeIs() throws Exception {
    String program = "" //
        + "def is_string(x):\n"
        + "  return type(x) == type('')\n"
        + "is_string(1)";
    assertEquals(false, BcTestUtil.eval(program));
  }
}
