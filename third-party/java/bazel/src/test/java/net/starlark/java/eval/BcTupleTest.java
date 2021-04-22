package net.starlark.java.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import org.junit.Test;

/** Test tuple-specific compilation. */
public class BcTupleTest {

  @Test
  public void tupleConstPropagated() throws Exception {
    String program = "" //
        + "D = {}\n"
        + "def f():\n"
        + "  return ('x', 1, D)\n"
        + "f";
    StarlarkFunction f = (StarlarkFunction) Starlark.execFile(
        ParserInput.fromString(program, "f.star"),
        FileOptions.DEFAULT, Module.create(),
        new StarlarkThread(Mutability.create(), StarlarkSemantics.DEFAULT));
    Tuple returnedConst = (Tuple) f.returnsConst();
    assertEquals(Tuple.of("x", StarlarkInt.of(1), Dict.empty()), returnedConst);
    // Assert it is const-propagated even if contains mutable reference
    assertFalse(((Dict<?, ?>) returnedConst.get(2)).isImmutable());
  }

}
