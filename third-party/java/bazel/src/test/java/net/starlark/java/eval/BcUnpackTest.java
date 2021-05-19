package net.starlark.java.eval;

import static org.junit.Assert.assertEquals;

import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import org.junit.Test;

public class BcUnpackTest {
  @Test
  public void unpack() throws Exception {
    String program = "" //
        + "def f(x):\n"
        + "  l = []\n"
        + "  l[0], z = x\n"
        + "f";
    StarlarkFunction f = (StarlarkFunction) Starlark.execFile(
        ParserInput.fromString(program, "f.star"),
        FileOptions.DEFAULT, Module.create(),
        new StarlarkThread(Mutability.create(), StarlarkSemantics.DEFAULT));
    assertEquals(f.compiled.toString(), BcInstrOpcode.LIST, f.compiled.instructions().get(0).opcode);
    BcInstrOpcode.Decoded unpack = f.compiled.instructions().get(1);
    assertEquals(f.compiled.toString(), BcInstrOpcode.UNPACK, unpack.opcode);
  }
}
