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

package com.facebook.buck.util;

import java.util.function.Function;
import org.junit.Assert;
import org.junit.Test;

public class MoreFunctionsTest {

  @Test
  public void memoize() {
    Function<String, String[]> memoize = MoreFunctions.memoize((String x) -> new String[] {x});
    String[] a = memoize.apply("a");
    Assert.assertArrayEquals(new String[] {"a"}, a);
    Assert.assertSame(a, memoize.apply("a"));

    try {
      memoize.apply(null);
      Assert.fail();
    } catch (NullPointerException e) {
      // expected
    }
  }
}
