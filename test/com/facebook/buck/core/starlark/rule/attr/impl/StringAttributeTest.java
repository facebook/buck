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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.google.common.collect.ImmutableList;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class StringAttributeTest {
  private final FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
  private final CellNameResolver cellRoots =
      TestCellPathResolver.get(filesystem).getCellNameResolver();

  @Rule public ExpectedException expected = ExpectedException.none();

  @Test
  public void coercesStringsProperly() throws CoerceFailedException {

    StringAttribute attr = ImmutableStringAttribute.of("foobaz", "", true, ImmutableList.of());
    String coerced =
        attr.getValue(
            cellRoots,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            "bar");

    assertEquals("bar", coerced);
  }

  @Test
  public void failsMandatoryCoercionProperly() throws CoerceFailedException {
    expected.expect(CoerceFailedException.class);

    StringAttribute attr = ImmutableStringAttribute.of("foobaz", "", true, ImmutableList.of());

    attr.getValue(
        cellRoots,
        filesystem,
        ForwardRelativePath.of(""),
        UnconfiguredTargetConfiguration.INSTANCE,
        UnconfiguredTargetConfiguration.INSTANCE,
        1);
  }

  @Test
  public void succeedsIfValueInArray() throws CoerceFailedException {

    StringAttribute attr =
        ImmutableStringAttribute.of("foobaz", "", true, ImmutableList.of("foo", "bar", "baz"));
    String value =
        attr.getValue(
            cellRoots,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            "bar");
    assertEquals("bar", value);
  }

  @Test
  public void allowsAnyValueIfValuesIsEmptyList() throws CoerceFailedException {
    StringAttribute attr = ImmutableStringAttribute.of("foobaz", "", true, ImmutableList.of());
    String value =
        attr.getValue(
            cellRoots,
            filesystem,
            ForwardRelativePath.of(""),
            UnconfiguredTargetConfiguration.INSTANCE,
            UnconfiguredTargetConfiguration.INSTANCE,
            "bar");

    assertEquals("bar", value);
  }

  @Test
  public void failsIfValueNotInArray() throws CoerceFailedException {
    expected.expect(CoerceFailedException.class);
    expected.expectMessage("must be one of 'foo', 'baz' instead of 'bar'");

    StringAttribute attr =
        ImmutableStringAttribute.of("foobaz", "", true, ImmutableList.of("foo", "baz"));

    attr.getValue(
        cellRoots,
        filesystem,
        ForwardRelativePath.of(""),
        UnconfiguredTargetConfiguration.INSTANCE,
        UnconfiguredTargetConfiguration.INSTANCE,
        "bar");
  }
}
