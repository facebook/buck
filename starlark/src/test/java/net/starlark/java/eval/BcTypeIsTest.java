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

import com.google.common.collect.ImmutableList;
import org.junit.Test;

public class BcTypeIsTest {
  @Test
  public void typeIsStringOpcodes() throws Exception {
    String program =
        "" //
            + "def is_string(x):\n"
            + "  return type(x) == type('')\n"
            + "is_string";
    assertEquals(
        ImmutableList.of(BcInstrOpcode.TYPE_IS, BcInstrOpcode.RETURN), BcTestUtil.opcodes(program));
  }

  @Test
  public void typeIsTupleOpcodes() throws Exception {
    String program =
        "" //
            + "def is_tuple(x):\n"
            + "  return type(x) == type(())\n"
            + "is_tuple";
    assertEquals(
        ImmutableList.of(BcInstrOpcode.TYPE_IS, BcInstrOpcode.RETURN), BcTestUtil.opcodes(program));
  }

  @Test
  public void typeIsOpcodesRev() throws Exception {
    String program =
        "" //
            + "def is_int(x):\n"
            + "  return type(1) == type(x)\n"
            + "is_int";
    assertEquals(
        ImmutableList.of(BcInstrOpcode.TYPE_IS, BcInstrOpcode.RETURN), BcTestUtil.opcodes(program));
  }

  @Test
  public void typeIsOpcodeForNonTrivialExpression() throws Exception {
    String program =
        "" //
            + "def test(x): return type(x + x) == 'xxx'\n"
            + "test";
    assertEquals(
        ImmutableList.of(BcInstrOpcode.PLUS, BcInstrOpcode.TYPE_IS, BcInstrOpcode.RETURN),
        BcTestUtil.opcodes(program));
  }

  @Test
  public void evalTypeIs() throws Exception {
    String program =
        "" //
            + "def is_string(x):\n"
            + "  return type(x) == type('')\n"
            + "is_string(1)";
    assertEquals(false, BcTestUtil.eval(program));
  }

  @Test
  public void typeIsInlined() throws Exception {
    String program =
        "" //
            + "def is_tuple(x): return type(x) == type(())\n"
            + "def f(x): return not is_tuple(x)\n"
            + "f";
    assertEquals(
        ImmutableList.of(BcInstrOpcode.TYPE_IS, BcInstrOpcode.NOT, BcInstrOpcode.RETURN),
        BcTestUtil.opcodes(program));
  }

  @Test
  public void typeIsInlinedIfNonTrivialExpr() throws Exception {
    String program =
        "" //
            + "def is_tuple(x): return type(x) == type(())\n"
            + "def f(x): return not is_tuple(x + x)\n"
            + "f";
    assertEquals(
        ImmutableList.of(
            BcInstrOpcode.PLUS, BcInstrOpcode.TYPE_IS, BcInstrOpcode.NOT, BcInstrOpcode.RETURN),
        BcTestUtil.opcodes(program));
  }
}
