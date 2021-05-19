/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
