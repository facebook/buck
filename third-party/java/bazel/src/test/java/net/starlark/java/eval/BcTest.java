package net.starlark.java.eval;

import static org.junit.Assert.*;

import com.google.common.collect.ImmutableList;
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
}
