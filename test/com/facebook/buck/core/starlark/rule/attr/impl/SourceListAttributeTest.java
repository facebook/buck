/*
 * Copyright 2019-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.core.starlark.rule.attr.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.google.common.collect.ImmutableList;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class SourceListAttributeTest {

  private final FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
  private final CellPathResolver cellRoots = TestCellPathResolver.get(filesystem);

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void coercesListsProperly() throws CoerceFailedException {
    BuildTarget target = BuildTargetFactory.newInstance("//foo/bar:baz");
    ImmutableList<SourcePath> expected =
        ImmutableList.of(
            PathSourcePath.of(filesystem, Paths.get("foo", "bar.cpp")),
            DefaultBuildTargetSourcePath.of(target));

    SourceListAttribute attr = new ImmutableSourceListAttribute(ImmutableList.of(), "", true, true);
    ImmutableList<SourcePath> coerced =
        attr.getValue(
            cellRoots,
            filesystem,
            Paths.get(""),
            EmptyTargetConfiguration.INSTANCE,
            ImmutableList.of("foo/bar.cpp", "//foo/bar:baz"));

    assertEquals(expected, coerced);
  }

  @Test
  public void failsMandatoryCoercionProperly() throws CoerceFailedException {
    thrown.expect(CoerceFailedException.class);

    SourceListAttribute attr = new ImmutableSourceListAttribute(ImmutableList.of(), "", true, true);

    attr.getValue(cellRoots, filesystem, Paths.get(""), EmptyTargetConfiguration.INSTANCE, "foo");
  }

  @Test
  public void failsMandatoryCoercionWithWrongListType() throws CoerceFailedException {
    thrown.expect(CoerceFailedException.class);

    SourceListAttribute attr = new ImmutableSourceListAttribute(ImmutableList.of(), "", true, true);

    attr.getValue(
        cellRoots,
        filesystem,
        Paths.get(""),
        EmptyTargetConfiguration.INSTANCE,
        ImmutableList.of(1));
  }

  @Test
  public void failsIfEmptyListProvidedAndNotAllowed() throws CoerceFailedException {
    thrown.expect(CoerceFailedException.class);
    thrown.expectMessage("may not be empty");

    SourceListAttribute attr =
        new ImmutableSourceListAttribute(ImmutableList.of(), "", true, false);

    attr.getValue(
        cellRoots,
        filesystem,
        Paths.get(""),
        EmptyTargetConfiguration.INSTANCE,
        ImmutableList.of());
  }

  @Test
  public void succeedsIfEmptyListProvidedAndAllowed() throws CoerceFailedException {

    SourceListAttribute attr = new ImmutableSourceListAttribute(ImmutableList.of(), "", true, true);

    ImmutableList<SourcePath> value =
        attr.getValue(
            cellRoots,
            filesystem,
            Paths.get(""),
            EmptyTargetConfiguration.INSTANCE,
            ImmutableList.of());
    assertTrue(value.isEmpty());
  }

  @Test
  public void doesNotAllowAbsolutePaths() throws CoerceFailedException {
    thrown.expect(CoerceFailedException.class);
    thrown.expectMessage("cannot contain an absolute path");

    String absolutePathString = filesystem.resolve("foo").toAbsolutePath().toString();

    SourceListAttribute attr = new ImmutableSourceListAttribute(ImmutableList.of(), "", true, true);

    attr.getValue(
        cellRoots,
        filesystem,
        Paths.get(""),
        EmptyTargetConfiguration.INSTANCE,
        ImmutableList.of(absolutePathString));
  }
}
