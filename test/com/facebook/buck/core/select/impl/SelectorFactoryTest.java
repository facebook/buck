/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.select.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.select.Selector;
import com.facebook.buck.core.select.SelectorKey;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.BuildTargetTypeCoercer;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.FlavorTypeCoercer;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.syntax.Runtime;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class SelectorFactoryTest {

  private ProjectFilesystem projectFilesystem;
  private SelectorFactory selectorFactory;

  @Before
  public void setUp() throws Exception {
    projectFilesystem = new FakeProjectFilesystem();
    selectorFactory = new SelectorFactory(new BuildTargetTypeCoercer()::coerce);
  }

  @Test
  public void testCanCreateEmptySelector() throws CoerceFailedException {
    Selector<Flavor> selector =
        selectorFactory.createSelector(
            TestCellPathResolver.get(projectFilesystem),
            projectFilesystem,
            projectFilesystem.getRootPath(),
            ImmutableMap.of(),
            new FlavorTypeCoercer());

    assertTrue(selector.getConditions().isEmpty());
  }

  @Test
  public void testCanCreateSelectorWithDefaultValue() throws CoerceFailedException {
    Selector<Flavor> selector =
        selectorFactory.createSelector(
            TestCellPathResolver.get(projectFilesystem),
            projectFilesystem,
            projectFilesystem.getRootPath(),
            ImmutableMap.of("DEFAULT", "flavor1", "//:a", "flavor2"),
            new FlavorTypeCoercer());

    assertEquals(2, selector.getConditions().size());
    ImmutableMap<SelectorKey, Flavor> conditions = selector.getConditions();
    List<SelectorKey> keys = Lists.newArrayList(conditions.keySet());
    assertEquals(SelectorKey.DEFAULT, keys.get(0));
    assertEquals(InternalFlavor.of("flavor1"), conditions.get(keys.get(0)));
    assertEquals("//:a", keys.get(1).getBuildTarget().getFullyQualifiedName());
    assertEquals(InternalFlavor.of("flavor2"), conditions.get(keys.get(1)));
    assertTrue(selector.hasDefaultCondition());
  }

  @Test
  public void testCanCreateSelectorWithoutDefaultValues() throws CoerceFailedException {
    Selector<Flavor> selector =
        selectorFactory.createSelector(
            TestCellPathResolver.get(projectFilesystem),
            projectFilesystem,
            projectFilesystem.getRootPath(),
            ImmutableMap.of("//:z", "flavor1", "//:a", "flavor2"),
            new FlavorTypeCoercer());

    assertEquals(2, selector.getConditions().size());
    ImmutableMap<SelectorKey, Flavor> conditions = selector.getConditions();
    List<SelectorKey> keys = Lists.newArrayList(conditions.keySet());
    assertEquals("//:z", keys.get(0).getBuildTarget().getFullyQualifiedName());
    assertEquals(InternalFlavor.of("flavor1"), conditions.get(keys.get(0)));
    assertEquals("//:a", keys.get(1).getBuildTarget().getFullyQualifiedName());
    assertEquals(InternalFlavor.of("flavor2"), conditions.get(keys.get(1)));
    assertFalse(selector.hasDefaultCondition());
  }

  @Test
  public void testCanCreateSelectorWithOnlyDefaultValue() throws CoerceFailedException {
    Selector<Flavor> selector =
        selectorFactory.createSelector(
            TestCellPathResolver.get(projectFilesystem),
            projectFilesystem,
            projectFilesystem.getRootPath(),
            ImmutableMap.of("DEFAULT", "flavor1"),
            new FlavorTypeCoercer());

    assertEquals(1, selector.getConditions().size());
    ImmutableMap<SelectorKey, Flavor> conditions = selector.getConditions();
    List<SelectorKey> keys = Lists.newArrayList(conditions.keySet());
    assertEquals(SelectorKey.DEFAULT, keys.get(0));
    assertEquals(InternalFlavor.of("flavor1"), conditions.get(keys.get(0)));
    assertTrue(selector.hasDefaultCondition());
  }

  @Test
  public void testCanCreateSelectorWithNoneValues() throws CoerceFailedException {
    Selector<Flavor> selector =
        selectorFactory.createSelector(
            TestCellPathResolver.get(projectFilesystem),
            projectFilesystem,
            projectFilesystem.getRootPath(),
            ImmutableMap.of("//:z", Runtime.NONE, "//:a", "flavor2"),
            new FlavorTypeCoercer());

    assertEquals(1, selector.getConditions().size());
    ImmutableMap<SelectorKey, Flavor> conditions = selector.getConditions();
    List<SelectorKey> keys = Lists.newArrayList(conditions.keySet());
    assertEquals("//:a", keys.get(0).getBuildTarget().getFullyQualifiedName());
    assertEquals(InternalFlavor.of("flavor2"), conditions.get(keys.get(0)));
    assertFalse(selector.hasDefaultCondition());
  }
}
