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

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import org.junit.Assert;
import org.junit.Test;

public class BcTest {
  @Test
  public void compiledToString() {
    BcInstrOperand.OpcodePrinterFunctionContext fnCtx =
        new BcInstrOperand.OpcodePrinterFunctionContext(
            ImmutableList.of("foo", "bar"), ImmutableList.of(), ImmutableList.of());
    int[] code = {
      BcInstrOpcode.RETURN.ordinal(), 1 | BcSlot.LOCAL_FLAG,
    };
    assertEquals(
        "def test; 0: RETURN rl$1:bar; 2: EOF",
        BcCompiled.toStringImpl(
            "test", code, fnCtx, ImmutableList.of(), ImmutableList.of(), ImmutableList.of()));
  }

  @Test
  public void currentModuleGlobalInlined() throws Exception {
    String program =
        "" //
            + "x = 17\n"
            + "def f():\n"
            + "  return x\n"
            + "f";
    StarlarkFunction f =
        (StarlarkFunction)
            Starlark.execFile(
                ParserInput.fromString(program, "f.star"),
                FileOptions.DEFAULT,
                Module.create(),
                new StarlarkThread(Mutability.create(), StarlarkSemantics.DEFAULT));
    BcInstrOpcode.Decoded ret = f.compiled.instructions().get(f.compiled.instructions().size() - 1);
    assertEquals(BcInstrOpcode.RETURN, ret.opcode);
    assertEquals(BcSlot.constValue(0), ((BcInstrOperand.Register.Decoded) ret.args).register);
    assertEquals(StarlarkInt.of(17), f.compiled.constSlots[0]);
  }

  @Test
  public void importedInlined() throws Exception {
    String program =
        "" //
            + "load('imports.bzl', 'x')\n"
            + "def f():\n"
            + "  return x\n"
            + "f";
    StarlarkThread thread = new StarlarkThread(Mutability.create(), StarlarkSemantics.DEFAULT);
    thread.setLoader(
        module -> {
          assertEquals("imports.bzl", module);
          return new LoadedModule.Simple(ImmutableMap.of("x", StarlarkInt.of(19)));
        });
    StarlarkFunction f =
        (StarlarkFunction)
            Starlark.execFile(
                ParserInput.fromString(program, "f.star"),
                FileOptions.DEFAULT,
                Module.create(),
                thread);
    BcInstrOpcode.Decoded ret = f.compiled.instructions().get(f.compiled.instructions().size() - 1);
    assertEquals(BcInstrOpcode.RETURN, ret.opcode);
    assertEquals(BcSlot.constValue(0), ((BcInstrOperand.Register.Decoded) ret.args).register);
    assertEquals(StarlarkInt.of(19), f.compiled.constSlots[0]);
  }

  @Test
  public void getattrInlined() throws Exception {
    String program =
        "" //
            + "def f():\n"
            + "  return stru.y\n"
            + "f";
    StarlarkThread thread = new StarlarkThread(Mutability.create(), StarlarkSemantics.DEFAULT);
    Module module = Module.create();
    module.setGlobal(
        "stru",
        new Structure() {
          @Override
          public Object getField(String name) throws EvalException {
            assertEquals(name, "y");
            return StarlarkInt.of(23);
          }

          @Override
          public ImmutableCollection<String> getFieldNames() {
            Assert.fail();
            return ImmutableList.of();
          }

          @Override
          public String getErrorMessageForUnknownField(String field) {
            Assert.fail();
            return "";
          }
        });
    StarlarkFunction f =
        (StarlarkFunction)
            Starlark.execFile(
                ParserInput.fromString(program, "f.star"), FileOptions.DEFAULT, module, thread);
    BcInstrOpcode.Decoded ret = f.compiled.instructions().get(f.compiled.instructions().size() - 1);
    assertEquals(BcInstrOpcode.RETURN, ret.opcode);
    assertEquals(BcSlot.constValue(0), ((BcInstrOperand.Register.Decoded) ret.args).register);
    assertEquals(StarlarkInt.of(23), f.compiled.constSlots[0]);
  }

  @Test
  public void callLinked() throws Exception {
    String program =
        "" //
            + "def g(x, y, *a, **kw):\n"
            + "  print(1)\n"
            + "def f():\n"
            + "  return g(1, b=2, *[], **{})\n"
            + "f";
    StarlarkFunction f =
        (StarlarkFunction)
            Starlark.execFile(
                ParserInput.fromString(program, "f.star"),
                FileOptions.DEFAULT,
                Module.create(),
                new StarlarkThread(Mutability.create(), StarlarkSemantics.DEFAULT));
    ImmutableList<BcInstrOpcode.Decoded> callInstrs =
        f.compiled.instructions().stream()
            .filter(d -> d.opcode == BcInstrOpcode.CALL || d.opcode == BcInstrOpcode.CALL_LINKED)
            .collect(ImmutableList.toImmutableList());
    assertEquals(1, callInstrs.size());
    assertEquals(BcInstrOpcode.CALL_LINKED, callInstrs.get(0).opcode);
  }

  @Test
  public void typeStringCallInlined() throws Exception {
    String program =
        "" //
            + "def f():\n"
            + "  return type('some random string')\n"
            + "f";
    StarlarkThread thread = new StarlarkThread(Mutability.create(), StarlarkSemantics.DEFAULT);
    Module module = Module.create();
    StarlarkFunction f =
        (StarlarkFunction)
            Starlark.execFile(
                ParserInput.fromString(program, "f.star"), FileOptions.DEFAULT, module, thread);
    BcInstrOpcode.Decoded ret = f.compiled.instructions().get(f.compiled.instructions().size() - 1);
    assertEquals(BcInstrOpcode.RETURN, ret.opcode);
    assertEquals(BcSlot.constValue(0), ((BcInstrOperand.Register.Decoded) ret.args).register);
    assertEquals("string", f.compiled.constSlots[0]);
  }

  @Test
  public void strFormat() throws Exception {
    String program =
        "" //
            + "def f(x): return 'a{}b'.format(x)\n"
            + "f";
    StarlarkThread thread = new StarlarkThread(Mutability.create(), StarlarkSemantics.DEFAULT);
    Module module = Module.create();
    StarlarkFunction f =
        (StarlarkFunction)
            Starlark.execFile(
                ParserInput.fromString(program, "f.star"), FileOptions.DEFAULT, module, thread);
    ImmutableList<BcInstrOpcode.Decoded> instructions = f.compiled.instructions();
    assertEquals("" + f.compiled, 2, instructions.size());
    assertEquals(BcInstrOpcode.CALL_LINKED_1, instructions.get(0).opcode);
    StarlarkCallableLinked format =
        (StarlarkCallableLinked) f.compiled.objects[instructions.get(0).getArgObject(1)];
    assertEquals("format", format.orig.getName());
  }

  @Test
  public void callsInlined() throws Exception {
    String program =
        ""
            + "def g():\n"
            + "  return type('xx') == 'string' or [1, 2]\n"
            + "def f():\n"
            + "  return g()\n"
            + "f";
    StarlarkThread thread = new StarlarkThread(Mutability.create(), StarlarkSemantics.DEFAULT);
    Module module = Module.create();
    StarlarkFunction f =
        (StarlarkFunction)
            Starlark.execFile(
                ParserInput.fromString(program, "f.star"), FileOptions.DEFAULT, module, thread);
    assertEquals(true, f.compiled.returnConst());
  }

  @Test
  public void readForEffectIsNotErasedOpcodes() throws Exception {
    String program = "" + "def f(): x = x\n" + "f";
    assertEquals(ImmutableList.of(BcInstrOpcode.CP_LOCAL), BcTestUtil.opcodes(program));
  }

  @Test
  public void readForEffectIsNotErasedEval() throws Exception {
    String program = "" + "def f(): x = x\n" + "f()";
    try {
      BcTestUtil.eval(program);
      fail("expecting variable is referenced before assignment");
    } catch (EvalException e) {
      assertTrue(
          e.getMessage(),
          e.getMessage().contains("local variable 'x' is referenced before assignment"));
    }
  }

  @Test
  public void readForEffectIsNotNeededForParameter() throws Exception {
    String program =
        "" //
            + "def f(x): x = x\n"
            + "f";
    assertEquals(ImmutableList.of(), BcTestUtil.opcodes(program));
  }

  @Test
  public void doNotCompileAfterReturn() throws Exception {
    String program =
        "" //
            + "def f(x):\n"
            + "  if True:\n"
            + "    return 1\n"
            + "  print('never')\n"
            + "f";
    // Print call is not compiled
    assertEquals(ImmutableList.of(BcInstrOpcode.RETURN), BcTestUtil.opcodes(program));
  }
}
