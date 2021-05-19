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

public class BcUnpackTest {
  @Test
  public void unpack() throws Exception {
    String program =
        "" //
            + "def f(x):\n"
            + "  l = []\n"
            + "  l[0], z = x\n"
            + "f";
    StarlarkFunction f =
        (StarlarkFunction)
            Starlark.execFile(
                ParserInput.fromString(program, "f.star"),
                FileOptions.DEFAULT,
                Module.create(),
                new StarlarkThread(Mutability.create(), StarlarkSemantics.DEFAULT));
    assertEquals(
        f.compiled.toString(), BcInstrOpcode.LIST, f.compiled.instructions().get(0).opcode);
    BcInstrOpcode.Decoded unpack = f.compiled.instructions().get(1);
    assertEquals(f.compiled.toString(), BcInstrOpcode.UNPACK, unpack.opcode);
  }
}
