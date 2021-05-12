package net.starlark.java.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import net.starlark.java.annot.FnPurity;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import org.junit.Test;

/** Test for function call compilation. */
public class BcCallCachedTest {
  @Test
  public void callCachedInstructionUsed() throws Exception {
    String programG = "" //
        + "def g(x): pass\n"
        + "g";
    StarlarkFunction g = BcTestUtil.makeFrozenFunction(programG);

    String program = "" //
        + "def f(): return g(1)\n"
        + "f";
    StarlarkThread thread = new StarlarkThread(Mutability.create(), StarlarkSemantics.DEFAULT);
    Module module = Module.create();
    module.setGlobal("g", g);
    StarlarkFunction f = (StarlarkFunction) Starlark.execFile(
        ParserInput.fromString(program, "f.star"),
        FileOptions.DEFAULT, module,
        thread);
    ImmutableList<BcInstr.Decoded> instructions = f.compiled.instructions();
    assertEquals(BcInstr.Opcode.CALL_CACHED, instructions.get(0).opcode);
  }

  @Test
  public void callCachedInstructionIsNotUsedWhenFunctionAccessesGlobals() throws Exception {
    String program = "" //
        + "L = []\n"
        + "def g(x): L.append(x)\n"
        + "def f(): return g(1)\n"
        + "f";
    StarlarkThread thread = new StarlarkThread(Mutability.create(), StarlarkSemantics.DEFAULT);
    Module module = Module.create();
    StarlarkFunction f = (StarlarkFunction) Starlark.execFile(
        ParserInput.fromString(program, "f.star"),
        FileOptions.DEFAULT, module,
        thread);
    ImmutableList<BcInstr.Decoded> instructions = f.compiled.instructions();
    // We are calling `g` with `CALL_LINKED`, not `CALL_CACHED`
    assertEquals(BcInstr.Opcode.CALL_LINKED, instructions.get(0).opcode);
  }

  @Test
  public void callCachedInstructionUsedButCallIsNotCached() throws Exception {
    String gProgram = "" //
        + "def g(x):\n"
        + "  return [x]\n"
        + "g";
    StarlarkFunction g = BcTestUtil.makeFrozenFunction(gProgram);

    String program = "" //
        + "def f(): return g(1)\n"
        + "f";
    StarlarkThread thread = new StarlarkThread(Mutability.create(), StarlarkSemantics.DEFAULT);
    Module module = Module.create();
    module.setGlobal("g", g);
    StarlarkFunction f = (StarlarkFunction) Starlark.execFile(
        ParserInput.fromString(program, "f.star"),
        FileOptions.DEFAULT, module,
        thread);
    ImmutableList<BcInstr.Decoded> instructions = f.compiled.instructions();
    // We compile `g(1)` as `CALL_CACHED`
    assertEquals(BcInstr.Opcode.CALL_CACHED, instructions.get(0).opcode);

    Object firstCallResult = Starlark.call(thread, f, Tuple.empty(), Dict.empty());
    Object secondCallResult = Starlark.call(thread, f, Tuple.empty(), Dict.empty());
    assertEquals(StarlarkList.immutableOf(StarlarkInt.of(1)), firstCallResult);
    assertEquals(StarlarkList.immutableOf(StarlarkInt.of(1)), secondCallResult);
    assertNotSame(
        "Second invocation produced different instance",
        firstCallResult,
        secondCallResult);
  }

  public static class SideEffectsPure extends StarlarkValue {
    @Override
    public boolean isImmutable() {
      return true;
    }

    private int called = 0;

    @StarlarkMethod(
        name = "pure",
        parameters = {
            @Param(
                name = "x"
            )
        },
        documented = false,
        purity = FnPurity.PURE)
    public String pure(Object x) {
      ++called;
      return Starlark.type(x);
    }
  }

  @Test
  public void callWasCachedBecausePure() throws Exception {
    SideEffectsPure sideEffects = new SideEffectsPure();

    String programG = "" //
        + "def g(x): return side_effects.pure(x)\n"
        + "g";

    StarlarkFunction g = BcTestUtil.makeFrozenFunction(
        programG,
        ImmutableMap.of("side_effects", sideEffects));

    String program = "" //
        + "def f(): return g(1)\n"
        + "f";
    StarlarkThread thread = new StarlarkThread(Mutability.create(), StarlarkSemantics.DEFAULT);
    Module module = Module.create();
    module.setGlobal("g", g);
    StarlarkFunction f = (StarlarkFunction) Starlark.execFile(
        ParserInput.fromString(program, "f.star"),
        FileOptions.DEFAULT, module,
        thread);
    ImmutableList<BcInstr.Decoded> instructions = f.compiled.instructions();
    assertEquals(BcInstr.Opcode.CALL_CACHED, instructions.get(0).opcode);

    assertEquals("int", Starlark.call(thread, f, Tuple.of(), Dict.empty()));
    assertEquals("int", Starlark.call(thread, f, Tuple.of(), Dict.empty()));
    // Assert call was cached: `pure` function was used only once,
    // which means `g` was called only once as well
    assertEquals(1, sideEffects.called);
  }

  public static class SideEffectsNotPure extends StarlarkValue {
    @Override
    public boolean isImmutable() {
      return true;
    }

    private int called = 0;

    @StarlarkMethod(
        name = "not_pure",
        parameters = {
            @Param(
                name = "x"
            )
        },
        documented = false,
        purity = FnPurity.DEFAULT)
    public String notPure(Object x) {
      ++called;
      return Starlark.type(x);
    }
  }

  @Test
  public void callWasNotCachedBecauseNotPure() throws Exception {
    SideEffectsNotPure sideEffects = new SideEffectsNotPure();

    String programG = "" //
        + "def g(x): return side_effects.not_pure(x)\n"
        + "g";

    StarlarkFunction g = BcTestUtil.makeFrozenFunction(
        programG,
        ImmutableMap.of("side_effects", sideEffects));

    String program = "" //
        + "def f(): return g(1)\n"
        + "f";
    StarlarkThread thread = new StarlarkThread(Mutability.create(), StarlarkSemantics.DEFAULT);
    Module module = Module.create();
    module.setGlobal("g", g);
    StarlarkFunction f = (StarlarkFunction) Starlark.execFile(
        ParserInput.fromString(program, "f.star"),
        FileOptions.DEFAULT, module,
        thread);
    ImmutableList<BcInstr.Decoded> instructions = f.compiled.instructions();
    // Even if function is not pure, we are still using `CALL_CACHED` instruction
    assertEquals(BcInstr.Opcode.CALL_CACHED, instructions.get(0).opcode);

    assertEquals("int", Starlark.call(thread, f, Tuple.of(), Dict.empty()));
    assertEquals("int", Starlark.call(thread, f, Tuple.of(), Dict.empty()));
    // We assert that call was not cached: `not_pure` was called twice
    assertEquals(2, sideEffects.called);
  }

  public static class ThrowOnSecondInvocation extends StarlarkValue {
    private int called = 0;

    @StarlarkMethod(name = "t", documented = false, purity = FnPurity.DEFAULT)
    public void pure() throws EvalException {
      if (++called == 2) {
        throw Starlark.errorf("test");
      }
    }
  }

  @Test
  public void stackTrace() throws Exception {
    String program =
        "" //
            + "def f(): t.t()\n"
            + "f";
    StarlarkFunction f =
        BcTestUtil.makeFrozenFunction(program, ImmutableMap.of("t", new ThrowOnSecondInvocation()));

    try {
      String program1 = "[f() for x in range(2)]";
      BcTestUtil.eval(program1, ImmutableMap.of("f", f));
      fail("expecting to throw");
    } catch (EvalException e) {
      assertEquals(
          "Traceback (most recent call last):\n"
              + "\tFile \"f.star\", line 1, column 20, in <toplevel>\n"
              + "\tFile \"f.star\", line 1, column 13, in f\n"
              + "Error in t: test",
          e.getMessageWithStack());
    }
  }
}
