package net.starlark.java.eval;

import static org.junit.Assert.*;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.util.Arrays;
import net.starlark.java.annot.internal.BcOpcodeNumber;
import org.junit.Test;

public class BcEvalDispatchTest {
  @Test
  public void allOpcodesHandled() {
    assertTrue(
        "some opcode numbers are not implemented: "
            + Arrays.toString(BcEvalDispatch.UNIMPLEMENTED_OPCODES),
        BcEvalDispatch.UNIMPLEMENTED_OPCODES.length == 0);

    ImmutableSet<BcInstr.Opcode> mapped =
        Arrays.stream(BcOpcodeNumber.values())
            .map(BcInstr.Opcode::fromNumber)
            .collect(ImmutableSet.toImmutableSet());

    ImmutableSet<BcInstr.Opcode> opcodes = ImmutableSet.copyOf(BcInstr.Opcode.values());

    Sets.SetView<BcInstr.Opcode> unmappedOpcodes = Sets.difference(opcodes, mapped);
    assertTrue(
        "Some opcodes are not mapped: " + unmappedOpcodes, unmappedOpcodes.isEmpty());

    // self-test
    assertEquals(opcodes, mapped);
  }
}
