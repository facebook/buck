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

    ImmutableSet<BcInstrOpcode> mapped =
        Arrays.stream(BcOpcodeNumber.values())
            .map(BcInstrOpcode::fromNumber)
            .collect(ImmutableSet.toImmutableSet());

    ImmutableSet<BcInstrOpcode> opcodes = ImmutableSet.copyOf(BcInstrOpcode.values());

    Sets.SetView<BcInstrOpcode> unmappedOpcodes = Sets.difference(opcodes, mapped);
    assertTrue("Some opcodes are not mapped: " + unmappedOpcodes, unmappedOpcodes.isEmpty());

    // self-test
    assertEquals(opcodes, mapped);
  }
}
