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

package com.facebook.buck.rules.coercer;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.UnconfiguredBuildTargetFactoryForTests;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetFactory;
import com.facebook.buck.core.select.SelectorList;
import com.facebook.buck.core.select.impl.SelectorFactory;
import com.facebook.buck.core.select.impl.SelectorListFactory;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.syntax.ImmutableSelectorValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class SelectorListCoercerTest {

  private ProjectFilesystem projectFilesystem;
  private CellPathResolver cellPathResolver;
  private SelectorListFactory selectorListFactory;

  @Before
  public void setUp() {
    projectFilesystem = new FakeProjectFilesystem();
    cellPathResolver = TestCellPathResolver.get(projectFilesystem);
    selectorListFactory =
        new SelectorListFactory(
            new SelectorFactory(
                new UnconfiguredBuildTargetTypeCoercer(
                    new ParsingUnconfiguredBuildTargetFactory())));
  }

  @Test
  public void testHasElementClassReturnsTrueForElementClass() {
    SelectorListCoercer<Flavor> coercer =
        new SelectorListCoercer<>(null, new FlavorTypeCoercer(), null);

    assertTrue(coercer.hasElementClass(Flavor.class));
  }

  @Test
  public void testHasElementClassReturnsTrueForBuildTargetClass() {
    SelectorListCoercer<Flavor> coercer =
        new SelectorListCoercer<>(null, new FlavorTypeCoercer(), null);

    assertTrue(coercer.hasElementClass(BuildTarget.class));
  }

  @Test
  public void testTraverseEncountersKeysAndValues() throws CoerceFailedException {
    ListTypeCoercer<Flavor> elementTypeCoercer = new ListTypeCoercer<>(new FlavorTypeCoercer());
    SelectorListCoercer<ImmutableList<Flavor>> coercer =
        new SelectorListCoercer<>(
            new BuildTargetTypeCoercer(
                new UnconfiguredBuildTargetTypeCoercer(
                    new ParsingUnconfiguredBuildTargetFactory())),
            elementTypeCoercer,
            null);
    ImmutableSelectorValue selectorValue =
        ImmutableSelectorValue.of(
            ImmutableMap.of(
                "DEFAULT", Lists.newArrayList("test1"), "//a:b", Lists.newArrayList("test2")),
            "");
    SelectorList<ImmutableList<Flavor>> selectors =
        selectorListFactory.create(
            cellPathResolver,
            projectFilesystem,
            projectFilesystem.getRootPath(),
            EmptyTargetConfiguration.INSTANCE,
            Lists.newArrayList(selectorValue, Lists.newArrayList("test3")),
            elementTypeCoercer);

    List<Object> traversedObjects = Lists.newArrayList();
    coercer.traverse(cellPathResolver, selectors, traversedObjects::add);

    assertThat(traversedObjects, hasItem(selectors));
    assertThat(
        traversedObjects, hasItem(UnconfiguredBuildTargetFactoryForTests.newInstance("//a:b")));
    assertThat(traversedObjects, hasItem(InternalFlavor.of("test1")));
    assertThat(traversedObjects, hasItem(InternalFlavor.of("test2")));
    assertThat(traversedObjects, hasItem(InternalFlavor.of("test3")));
    assertEquals(
        1, traversedObjects.stream().filter(UnconfiguredBuildTargetView.class::isInstance).count());
  }
}
