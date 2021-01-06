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

package com.facebook.buck.core.select.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.select.Selector;
import com.facebook.buck.core.select.SelectorKey;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.CoerceFailedException;
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
  public void setUp() {
    projectFilesystem = new FakeProjectFilesystem();
    selectorFactory = new SelectorFactory(new ParsingUnconfiguredBuildTargetViewFactory());
  }

  @Test
  public void testCanCreateEmptySelector() {
    Selector<Object> selector =
        selectorFactory.createSelector(
            TestCellPathResolver.get(projectFilesystem).getCellNameResolver(),
            ForwardRelativePath.of(""),
            ImmutableMap.of());

    assertTrue(selector.getConditions().isEmpty());
  }

  @Test
  public void testCanCreateSelectorWithDefaultValue() {
    Selector<Object> selector =
        selectorFactory.createSelector(
            TestCellPathResolver.get(projectFilesystem).getCellNameResolver(),
            ForwardRelativePath.of(""),
            ImmutableMap.of("DEFAULT", "flavor1", "//:a", "flavor2"));

    assertEquals(2, selector.getConditions().size());
    ImmutableMap<SelectorKey, Object> conditions = selector.getConditions();
    List<SelectorKey> keys = Lists.newArrayList(conditions.keySet());
    assertEquals(SelectorKey.DEFAULT, keys.get(0));
    assertEquals("flavor1", conditions.get(keys.get(0)));
    assertEquals("//:a", keys.get(1).getBuildTarget().getFullyQualifiedName());
    assertEquals("flavor2", conditions.get(keys.get(1)));
    assertNotNull(selector.getDefaultConditionValue());
  }

  @Test
  public void testCanCreateSelectorWithoutDefaultValues() {
    Selector<Object> selector =
        selectorFactory.createSelector(
            TestCellPathResolver.get(projectFilesystem).getCellNameResolver(),
            ForwardRelativePath.of(""),
            ImmutableMap.of("//:z", "flavor1", "//:a", "flavor2"));

    assertEquals(2, selector.getConditions().size());
    ImmutableMap<SelectorKey, Object> conditions = selector.getConditions();
    List<SelectorKey> keys = Lists.newArrayList(conditions.keySet());
    assertEquals("//:z", keys.get(0).getBuildTarget().getFullyQualifiedName());
    assertEquals("flavor1", conditions.get(keys.get(0)));
    assertEquals("//:a", keys.get(1).getBuildTarget().getFullyQualifiedName());
    assertEquals("flavor2", conditions.get(keys.get(1)));
    assertNull(selector.getDefaultConditionValue());
  }

  @Test
  public void testCanCreateSelectorWithOnlyDefaultValue() {
    Selector<Object> selector =
        selectorFactory.createSelector(
            TestCellPathResolver.get(projectFilesystem).getCellNameResolver(),
            ForwardRelativePath.of(""),
            ImmutableMap.of("DEFAULT", "flavor1"));

    assertEquals(1, selector.getConditions().size());
    ImmutableMap<SelectorKey, Object> conditions = selector.getConditions();
    List<SelectorKey> keys = Lists.newArrayList(conditions.keySet());
    assertEquals(SelectorKey.DEFAULT, keys.get(0));
    assertEquals("flavor1", conditions.get(keys.get(0)));
    assertNotNull(selector.getDefaultConditionValue());
  }

  @Test
  public void testCanCreateSelectorWithNoneValues() throws CoerceFailedException {
    Selector<Object> selector =
        selectorFactory.createSelector(
            TestCellPathResolver.get(projectFilesystem).getCellNameResolver(),
            ForwardRelativePath.of(""),
            ImmutableMap.of("//:z", Runtime.NONE, "//:a", "flavor2"));

    assertEquals(1, selector.getConditions().size());
    ImmutableMap<SelectorKey, Object> conditions = selector.getConditions();
    List<SelectorKey> keys = Lists.newArrayList(conditions.keySet());
    assertEquals("//:a", keys.get(0).getBuildTarget().getFullyQualifiedName());
    assertEquals("flavor2", conditions.get(keys.get(0)));
    assertNull(selector.getDefaultConditionValue());
  }
}
