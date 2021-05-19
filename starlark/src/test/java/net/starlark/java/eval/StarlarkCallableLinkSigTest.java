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
