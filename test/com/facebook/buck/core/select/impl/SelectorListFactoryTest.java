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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.select.Selector;
import com.facebook.buck.core.select.SelectorList;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.FlavorTypeCoercer;
import com.facebook.buck.rules.coercer.ListTypeCoercer;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.syntax.SelectorValue;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;

public class SelectorListFactoryTest {

  private ProjectFilesystem projectFilesystem;
  private SelectorListFactory selectorListFactory;

  @Before
  public void setUp() throws Exception {
    projectFilesystem = new FakeProjectFilesystem();
    selectorListFactory = new SelectorListFactory(new SelectorFactory());
  }

  @Test
  public void testCreateThrowsWhenConcatenationNotSupportedWithMultipleElements()
      throws CoerceFailedException {
    try {
      selectorListFactory.create(
          TestCellPathResolver.get(projectFilesystem),
          projectFilesystem,
          projectFilesystem.getRootPath(),
          Lists.newArrayList(new Object(), new Object()),
          new FlavorTypeCoercer());
      fail("SelectorListFactory.create should throw an exception");
    } catch (HumanReadableException e) {
      assertEquals(
          "type 'interface com.facebook.buck.core.model.Flavor' doesn't support select concatenation",
          e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void testCreateUsesSingleElementWhenConcatenationNotSupported()
      throws CoerceFailedException {
    FlavorTypeCoercer coercer = new FlavorTypeCoercer();
    String flavorName = "test";
    SelectorList<Flavor> selectors =
        selectorListFactory.create(
            TestCellPathResolver.get(projectFilesystem),
            projectFilesystem,
            projectFilesystem.getRootPath(),
            Lists.newArrayList(flavorName),
            coercer);

    assertEquals(
        InternalFlavor.of(flavorName), selectors.getSelectors().get(0).getDefaultConditionValue());
    assertEquals(coercer, selectors.getConcatable());
  }

  @Test
  public void testCreateAcceptsEmptyList() throws CoerceFailedException {
    FlavorTypeCoercer coercer = new FlavorTypeCoercer();
    SelectorList<Flavor> selectors =
        selectorListFactory.create(
            TestCellPathResolver.get(projectFilesystem),
            projectFilesystem,
            projectFilesystem.getRootPath(),
            Lists.newArrayList(),
            coercer);

    assertTrue(selectors.getSelectors().isEmpty());
    assertEquals(coercer, selectors.getConcatable());
  }

  @Test
  public void testCreateAcceptsMultipleElements() throws CoerceFailedException {
    ListTypeCoercer<Flavor> coercer = new ListTypeCoercer<Flavor>(new FlavorTypeCoercer());
    String flavorName1 = "test1";
    String flavorName2 = "test2";
    SelectorList<ImmutableList<Flavor>> selectors =
        selectorListFactory.create(
            TestCellPathResolver.get(projectFilesystem),
            projectFilesystem,
            projectFilesystem.getRootPath(),
            Lists.newArrayList(Lists.newArrayList(flavorName1), Lists.newArrayList(flavorName2)),
            coercer);

    assertEquals(
        Lists.newArrayList(InternalFlavor.of(flavorName1), InternalFlavor.of(flavorName2)),
        selectors
            .getSelectors()
            .stream()
            .map(Selector::getDefaultConditionValue)
            .flatMap(ImmutableList::stream)
            .collect(Collectors.toList()));
    assertEquals(coercer, selectors.getConcatable());
  }

  @Test
  public void testCreateAcceptsMultipleElementsIncludingSelectorValue()
      throws CoerceFailedException {
    ListTypeCoercer<Flavor> coercer = new ListTypeCoercer<Flavor>(new FlavorTypeCoercer());
    String flavorName1 = "test1";
    String flavorName2 = "test2";
    String message = "message about incorrect conditions";
    SelectorValue selectorValue =
        new SelectorValue(ImmutableMap.of("DEFAULT", Lists.newArrayList(flavorName1)), message);

    SelectorList<ImmutableList<Flavor>> selectors =
        selectorListFactory.create(
            TestCellPathResolver.get(projectFilesystem),
            projectFilesystem,
            projectFilesystem.getRootPath(),
            Lists.newArrayList(selectorValue, Lists.newArrayList(flavorName2)),
            coercer);

    assertEquals(
        Lists.newArrayList(InternalFlavor.of(flavorName1), InternalFlavor.of(flavorName2)),
        selectors
            .getSelectors()
            .stream()
            .map(Selector::getDefaultConditionValue)
            .flatMap(ImmutableList::stream)
            .collect(Collectors.toList()));
    assertEquals(message, selectors.getSelectors().get(0).getNoMatchMessage());
    assertEquals(coercer, selectors.getConcatable());
  }
}
