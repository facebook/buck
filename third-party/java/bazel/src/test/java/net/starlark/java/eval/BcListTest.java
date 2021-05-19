package net.starlark.java.eval;

import static org.junit.Assert.assertEquals;

import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import org.junit.Test;

public class BcListTest {
  @Test
  public void list() throws Exception {
    String program = "" //
        + "def f():\n"
        + "  li = [1]\n"
        + "  return li\n"
        + "f";
    StarlarkFunction f = (StarlarkFunction) Starlark.execFile(
        ParserInput.fromString(program, "f.star"),
        FileOptions.DEFAULT, Module.create(),
        new StarlarkThread(Mutability.create(), StarlarkSemantics.DEFAULT));
    BcInstrOpcode.Decoded list = f.compiled.instructions().get(0);
    assertEquals(f.compiled.toString(), BcInstrOpcode.LIST, list.opcode);
    // Assert we are assigning straight to local variable skipping the temporary slot
    assertEquals(f.compiled.toString(), 0, list.args.asFixed().operands.get(1).asRegister().register);
  }
}
