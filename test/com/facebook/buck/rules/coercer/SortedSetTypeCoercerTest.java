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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.cell.nameresolver.TestCellNameResolver;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import org.junit.Assert;
import org.junit.Test;

public class SortedSetTypeCoercerTest {

  @Test
  public void coerceToUnconfiguredOptimizesIdentity() throws Exception {
    SortedSetTypeCoercer<String, String> coercer =
        new SortedSetTypeCoercer<>(new StringTypeCoercer());

    ImmutableList<String> input = ImmutableList.of("ab", "cd");
    ImmutableList<String> coerced =
        coercer.coerceToUnconfigured(
            TestCellNameResolver.forRoot(),
            new FakeProjectFilesystem(),
            ForwardRelativePath.EMPTY,
            input);
    Assert.assertSame(input, coerced);
  }
}
