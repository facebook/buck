package net.starlark.java.eval;

import static org.junit.Assert.*;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import javax.annotation.Nullable;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import org.junit.Test;

public class BcTest {
  @Test
  public void compiledToString() {
    BcInstrOperand.OpcodeVisitorFunctionContext fnCtx = new BcInstrOperand.OpcodeVisitorFunctionContext(
        ImmutableList.of("foo", "bar"), ImmutableList.of(), ImmutableList.of()
    );
    int[] code = {
        BcInstr.Opcode.RETURN.ordinal(),
        1 | BcSlot.LOCAL_FLAG,
    };
    assertEquals(
        "0: RETURN rl$1:bar; 2: EOF",
        Bc.Compiled.toStringImpl(code, fnCtx, ImmutableList.of(), ImmutableList.of()));
  }

  @Test
  public void currentModuleGlobalInlined() throws Exception {
    String program = "" //
        + "x = 17\n"
        + "def f():\n"
        + "  return x\n"
        + "f";
    StarlarkFunction f = (StarlarkFunction) Starlark.execFile(
        ParserInput.fromString(program, "f.star"),
        FileOptions.DEFAULT, Module.create(),
        new StarlarkThread(Mutability.create(), StarlarkSemantics.DEFAULT));
    BcInstr.Decoded ret = f.compiled.instructions().get(f.compiled.instructions().size() - 1);
    assertEquals(BcInstr.Opcode.RETURN, ret.opcode);
    assertEquals(BcSlot.constValue(0), ret.args[0]);
    assertEquals(StarlarkInt.of(17), f.compiled.constSlots[0]);
  }

  @Test
  public void importedInlined() throws Exception {
    String program = "" //
        + "load('imports.bzl', 'x')\n"
        + "def f():\n"
        + "  return x\n"
        + "f";
    StarlarkThread thread = new StarlarkThread(Mutability.create(), StarlarkSemantics.DEFAULT);
    thread.setLoader(module -> {
      assertEquals("imports.bzl", module);
      Module result = Module.create();
      result.setGlobal("x", StarlarkInt.of(19));
      return result;
    });
    StarlarkFunction f = (StarlarkFunction) Starlark.execFile(
        ParserInput.fromString(program, "f.star"),
        FileOptions.DEFAULT, Module.create(),
        thread);
    BcInstr.Decoded ret = f.compiled.instructions().get(f.compiled.instructions().size() - 1);
    assertEquals(BcInstr.Opcode.RETURN, ret.opcode);
    assertEquals(BcSlot.constValue(0), ret.args[0]);
    assertEquals(StarlarkInt.of(19), f.compiled.constSlots[0]);
  }
}
