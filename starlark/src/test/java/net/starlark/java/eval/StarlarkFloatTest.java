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

import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import org.junit.Test;

public class StarlarkFloatTest {
  @Test
  public void canBeDisabled() throws Exception {
    try {
      Starlark.execFile(
          ParserInput.fromString("1 / 1", "f.star"),
          FileOptions.DEFAULT,
          Module.create(),
          new StarlarkThread(
              Mutability.create(),
              StarlarkSemantics.builder().setBool(StarlarkSemantics.ALLOW_FLOATS, false).build()));
      fail("expecting exception");
    } catch (EvalException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("floats are disabled by semantics"));
    }
  }
}
