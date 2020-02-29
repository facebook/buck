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

import static org.junit.Assert.assertSame;

import com.facebook.buck.core.cell.nameresolver.TestCellNameResolver;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableSet;
import org.junit.Test;

public class SetTypeCoercerTest {

  @Test
  public void unconfiguredOnly() throws Exception {
    SetTypeCoercer<String, String> coercer = new SetTypeCoercer<>(new StringTypeCoercer());

    ImmutableSet<String> list = ImmutableSet.of("a", "b");

    ImmutableSet<String> result =
        coercer.coerce(
            TestCellNameResolver.forRoot(),
            new FakeProjectFilesystem(),
            ForwardRelativePath.EMPTY,
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            list);

    assertSame(list, result);
  }
}
