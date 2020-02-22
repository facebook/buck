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

package com.facebook.buck.core.starlark.rule.attr.impl;

import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BoolAttributeTest {

  private final FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
  private final CellNameResolver cellNameResolver =
      TestCellPathResolver.get(filesystem).getCellNameResolver();

  @Rule public ExpectedException expected = ExpectedException.none();

  @Test
  public void coercesBoolsProperly() throws CoerceFailedException {

    BoolAttribute attr = ImmutableBoolAttribute.of(false, "", true);
    boolean coerced =
        attr.getValue(
            cellNameResolver,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            true);

    assertTrue(coerced);
  }

  @Test
  public void failsMandatoryCoercionProperly() throws CoerceFailedException {
    expected.expect(CoerceFailedException.class);

    BoolAttribute attr = ImmutableBoolAttribute.of(false, "", true);

    attr.getValue(
        cellNameResolver,
        filesystem,
        ForwardRelativePath.of(""),
        UnconfiguredTargetConfiguration.INSTANCE,
        UnconfiguredTargetConfiguration.INSTANCE,
        "foo");
  }
}
