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

import static org.junit.Assert.assertEquals;

import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import org.junit.Test;

public class BcListTest {
  @Test
  public void list() throws Exception {
    String program =
        "" //
            + "def f():\n"
            + "  li = [1]\n"
            + "  return li\n"
            + "f";
    StarlarkFunction f =
        (StarlarkFunction)
            Starlark.execFile(
                ParserInput.fromString(program, "f.star"),
                FileOptions.DEFAULT,
                Module.create(),
                new StarlarkThread(Mutability.create(), StarlarkSemantics.DEFAULT));
    BcInstrOpcode.Decoded list = f.compiled.instructions().get(0);
    assertEquals(f.compiled.toString(), BcInstrOpcode.LIST, list.opcode);
    // Assert we are assigning straight to local variable skipping the temporary slot
    assertEquals(
        f.compiled.toString(), 0, list.args.asFixed().operands.get(1).asRegister().register);
  }
}
