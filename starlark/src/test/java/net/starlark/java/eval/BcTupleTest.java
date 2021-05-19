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
import static org.junit.Assert.assertFalse;

import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import org.junit.Test;

/** Test tuple-specific compilation. */
public class BcTupleTest {

  @Test
  public void tupleConstPropagated() throws Exception {
    String program =
        "" //
            + "D = {}\n"
            + "def f():\n"
            + "  return ('x', 1, D)\n"
            + "f";
    StarlarkFunction f =
        (StarlarkFunction)
            Starlark.execFile(
                ParserInput.fromString(program, "f.star"),
                FileOptions.DEFAULT,
                Module.create(),
                new StarlarkThread(Mutability.create(), StarlarkSemantics.DEFAULT));
    Tuple returnedConst = (Tuple) f.returnsConst();
    assertEquals(Tuple.of("x", StarlarkInt.of(1), Dict.empty()), returnedConst);
    // Assert it is const-propagated even if contains mutable reference
    assertFalse(((Dict<?, ?>) returnedConst.get(2)).isImmutable());
  }
}
