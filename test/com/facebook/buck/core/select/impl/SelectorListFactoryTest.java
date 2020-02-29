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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.select.SelectorList;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.syntax.ListWithSelects;
import com.facebook.buck.parser.syntax.SelectorValue;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;

public class SelectorListFactoryTest {

  private ProjectFilesystem projectFilesystem;
  private SelectorListFactory selectorListFactory;

  @Before
  public void setUp() {
    projectFilesystem = new FakeProjectFilesystem();
    selectorListFactory =
        new SelectorListFactory(
            new SelectorFactory(new ParsingUnconfiguredBuildTargetViewFactory()));
  }

  @Test
  public void testCreateThrowsWhenConcatenationNotSupportedWithMultipleElements()
      throws CoerceFailedException {
    try {
      selectorListFactory.create(
          TestCellPathResolver.get(projectFilesystem).getCellNameResolver(),
          ForwardRelativePath.of(""),
          ListWithSelects.of(ImmutableList.of(new Object(), new Object()), Method.class));
      fail("SelectorListFactory.create should throw an exception");
    } catch (HumanReadableException e) {
      assertEquals(
          "type 'java.lang.reflect.Method' doesn't support select concatenation",
          e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void testCreateUsesSingleElementWhenConcatenationNotSupported()
      throws CoerceFailedException {
    String flavorName = "test";
    SelectorList<Object> selectors =
        selectorListFactory.create(
            TestCellPathResolver.get(projectFilesystem).getCellNameResolver(),
            ForwardRelativePath.of(""),
            ListWithSelects.of(ImmutableList.of(flavorName), List.class));

    assertEquals(flavorName, selectors.getSelectors().get(0).getDefaultConditionValue());
  }

  @Test
  public void testCreateAcceptsEmptyList() throws CoerceFailedException {
    SelectorList<Object> selectors =
        selectorListFactory.create(
            TestCellPathResolver.get(projectFilesystem).getCellNameResolver(),
            ForwardRelativePath.of(""),
            ListWithSelects.of(ImmutableList.of(), List.class));

    assertTrue(selectors.getSelectors().isEmpty());
  }

  @Test
  public void testCreateAcceptsMultipleElements() throws CoerceFailedException {
    String flavorName1 = "test1";
    String flavorName2 = "test2";
    SelectorList<Object> selectors =
        selectorListFactory.create(
            TestCellPathResolver.get(projectFilesystem).getCellNameResolver(),
            ForwardRelativePath.of(""),
            ListWithSelects.of(
                ImmutableList.of(ImmutableList.of(flavorName1), ImmutableList.of(flavorName2)),
                List.class));

    assertEquals(
        Lists.newArrayList(flavorName1, flavorName2),
        selectors.getSelectors().stream()
            .flatMap(
                objectSelector -> ((List<?>) objectSelector.getDefaultConditionValue()).stream())
            .collect(Collectors.toList()));
  }

  @Test
  public void testCreateAcceptsMultipleElementsIncludingSelectorValue()
      throws CoerceFailedException {
    String flavorName1 = "test1";
    String flavorName2 = "test2";
    String message = "message about incorrect conditions";
    SelectorValue selectorValue =
        SelectorValue.of(ImmutableMap.of("DEFAULT", Lists.newArrayList(flavorName1)), message);

    SelectorList<Object> selectors =
        selectorListFactory.create(
            TestCellPathResolver.get(projectFilesystem).getCellNameResolver(),
            ForwardRelativePath.of(""),
            ListWithSelects.of(
                ImmutableList.of(selectorValue, Lists.newArrayList(flavorName2)), List.class));

    assertEquals(
        Lists.newArrayList(flavorName1, flavorName2),
        selectors.getSelectors().stream()
            .flatMap(
                objectSelector -> ((List<?>) objectSelector.getDefaultConditionValue()).stream())
            .collect(Collectors.toList()));
    assertEquals(message, selectors.getSelectors().get(0).getNoMatchMessage());
  }
}
