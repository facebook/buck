package net.starlark.java.eval;

import static org.junit.Assert.*;

import org.junit.Test;

public class StarlarkCallableLinkSigTest {
  @Test
  public void isPosOnly() {
    assertTrue(StarlarkCallableLinkSig.positional(0).isPosOnly());
    assertTrue(StarlarkCallableLinkSig.positional(4).isPosOnly());

    assertFalse(StarlarkCallableLinkSig.of(1, new String[0], true, false).isPosOnly());
    assertFalse(StarlarkCallableLinkSig.of(1, new String[0], false, true).isPosOnly());
    assertFalse(StarlarkCallableLinkSig.of(1, new String[] {"x"}, false, false).isPosOnly());
    assertFalse(StarlarkCallableLinkSig.of(0, new String[] {"x"}, true, true).isPosOnly());
  }
}
