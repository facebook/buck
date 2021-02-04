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

package com.facebook.buck.skylark;

import com.facebook.buck.core.starlark.testutil.TestStarlarkParser;
import com.google.common.collect.ImmutableMap;
import net.starlark.java.eval.StarlarkInt;
import org.junit.Assert;
import org.junit.Test;

public class StarlarkRecursionTest {
  @Test
  public void recursionAllowed() throws Exception {
    // Recursion is enabled in our fork; make sure it is not regressed accidentally
    Object r =
        TestStarlarkParser.eval(
            "def fact(n): return 1 if n <= 1 else n * fact(n - 1)\nfact(5)", ImmutableMap.of());
    Assert.assertEquals(StarlarkInt.of(120), r);
  }
}
