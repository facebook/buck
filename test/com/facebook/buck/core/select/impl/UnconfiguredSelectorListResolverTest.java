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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.ConfigurationBuildTargetFactoryForTests;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.select.NonCopyingSelectableConfigurationContext;
import com.facebook.buck.core.select.SelectableConfigurationContext;
import com.facebook.buck.core.select.Selector;
import com.facebook.buck.core.select.SelectorKey;
import com.facebook.buck.core.select.SelectorList;
import com.facebook.buck.core.select.TestSelectable;
import com.facebook.buck.core.select.TestSelectableResolver;
import com.facebook.buck.core.select.TestSelectorListFactory;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.FlavorTypeCoercer;
import com.facebook.buck.rules.coercer.ListTypeCoercer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import org.junit.Before;
import org.junit.Test;

public class UnconfiguredSelectorListResolverTest {

  private SelectableConfigurationContext configurationContext;

  @Before
  public void setUp() {
    configurationContext = NonCopyingSelectableConfigurationContext.INSTANCE;
  }

  @Test
  public void testResolvingEmptyListReturnsEmptyList() throws CoerceFailedException {
    UnconfiguredSelectorListResolver resolver =
        new UnconfiguredSelectorListResolver(new TestSelectableResolver());
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//a:b");
    SelectorList<ImmutableList<Flavor>> selectorList = createSelectorListForListsOfFlavors();

    ImmutableList<Flavor> flavors =
        resolver.resolveList(
            configurationContext,
            buildTarget,
            "some_attribute",
            selectorList,
            flavorListTypeCoercer(),
            DependencyStack.root());

    assertTrue(flavors.isEmpty());
  }

  @Test
  public void testResolvingListWithSingleElementReturnsSingleElement()
      throws CoerceFailedException {
    BuildTarget keyTarget = BuildTargetFactory.newInstance("//a:b");
    BuildTarget selectableTarget = ConfigurationBuildTargetFactoryForTests.newInstance("//x:y");
    SelectorList<Flavor> selectorList =
        createSelectorListForFlavors(ImmutableMap.of("DEFAULT", "flavor1", "//x:y", "flavor2"));
    UnconfiguredSelectorListResolver resolver =
        new UnconfiguredSelectorListResolver(
            new TestSelectableResolver(
                ImmutableList.of(new TestSelectable(selectableTarget, true))));

    Flavor flavor =
        resolver.resolveList(
            configurationContext,
            keyTarget,
            "some_attribute",
            selectorList,
            new FlavorTypeCoercer(),
            DependencyStack.root());

    assertEquals("flavor2", flavor.getName());
  }

  @Test
  public void testResolvingListWithMultipleElementsNotSupportingConcatReturnsNull()
      throws CoerceFailedException {
    BuildTarget keyTarget = BuildTargetFactory.newInstance("//a:b");
    BuildTarget selectableTarget = ConfigurationBuildTargetFactoryForTests.newInstance("//x:y");
    UnconfiguredSelectorListResolver resolver =
        new UnconfiguredSelectorListResolver(
            new TestSelectableResolver(
                ImmutableList.of(new TestSelectable(selectableTarget, true))));
    SelectorList<Flavor> selectorList =
        createSelectorListForFlavors(
            ImmutableMap.of("DEFAULT", "flavor1", "//x:y", "flavor2"),
            ImmutableMap.of("DEFAULT", "flavor3", "//x:y", "flavor4"));

    Flavor flavor =
        resolver.resolveList(
            configurationContext,
            keyTarget,
            "some_attribute",
            selectorList,
            new FlavorTypeCoercer(),
            DependencyStack.root());

    assertNull(flavor);
  }

  @Test
  public void testResolvingListWithMultipleElementsSupportingConcatReturnsCompleteList()
      throws CoerceFailedException {
    BuildTarget keyTarget = BuildTargetFactory.newInstance("//a:b");
    BuildTarget selectableTarget = ConfigurationBuildTargetFactoryForTests.newInstance("//x:y");
    UnconfiguredSelectorListResolver resolver =
        new UnconfiguredSelectorListResolver(
            new TestSelectableResolver(
                ImmutableList.of(new TestSelectable(selectableTarget, true))));
    SelectorList<ImmutableList<Flavor>> selectorList =
        createSelectorListForListsOfFlavors(
            ImmutableMap.of(
                "DEFAULT",
                Lists.newArrayList("flavor11", "flavor12"),
                "//x:y",
                Lists.newArrayList("flavor21", "flavor22")),
            ImmutableMap.of(
                "DEFAULT",
                Lists.newArrayList("flavor31", "flavor32"),
                "//x:y",
                Lists.newArrayList("flavor41", "flavor42")));

    ImmutableList<Flavor> flavors =
        resolver.resolveList(
            configurationContext,
            keyTarget,
            "some_attribute",
            selectorList,
            flavorListTypeCoercer(),
            DependencyStack.root());

    assertEquals(
        Lists.newArrayList(
            "flavor11",
            "flavor12",
            "flavor21",
            "flavor22",
            "flavor31",
            "flavor32",
            "flavor41",
            "flavor42"),
        flavors.stream().map(Flavor::getName).collect(Collectors.toList()));
  }

  @Test
  public void testResolvingListWithMultipleDefaultMatchesReturnsList()
      throws CoerceFailedException {
    BuildTarget keyTarget = BuildTargetFactory.newInstance("//a:b");
    BuildTarget selectableTarget = ConfigurationBuildTargetFactoryForTests.newInstance("//x:y");
    UnconfiguredSelectorListResolver resolver =
        new UnconfiguredSelectorListResolver(
            new TestSelectableResolver(
                ImmutableList.of(new TestSelectable(selectableTarget, false))));
    SelectorList<ImmutableList<Flavor>> selectorList =
        createSelectorListForListsOfFlavors(
            ImmutableMap.of(
                "DEFAULT",
                Lists.newArrayList("flavor11", "flavor12"),
                "//x:y",
                Lists.newArrayList("flavor21", "flavor22")),
            ImmutableMap.of(
                "DEFAULT",
                Lists.newArrayList("flavor31", "flavor32"),
                "//x:y",
                Lists.newArrayList("flavor41", "flavor42")));

    ImmutableList<Flavor> flavors =
        resolver.resolveList(
            configurationContext,
            keyTarget,
            "some_attribute",
            selectorList,
            flavorListTypeCoercer(),
            DependencyStack.root());

    assertEquals(
        Lists.newArrayList(
            "flavor11",
            "flavor12",
            "flavor21",
            "flavor22",
            "flavor31",
            "flavor32",
            "flavor41",
            "flavor42"),
        flavors.stream().map(Flavor::getName).collect(Collectors.toList()));
  }

  @Test
  public void testResolvingListWithRefinedConditionsPicksMostSpecializedCondition()
      throws CoerceFailedException {
    BuildTarget keyTarget = BuildTargetFactory.newInstance("//a:b");
    BuildTarget selectableTarget1 = ConfigurationBuildTargetFactoryForTests.newInstance("//x:y");
    BuildTarget selectableTarget2 = ConfigurationBuildTargetFactoryForTests.newInstance("//x:z");
    UnconfiguredSelectorListResolver resolver =
        new UnconfiguredSelectorListResolver(
            new TestSelectableResolver(
                ImmutableList.of(
                    new TestSelectable(selectableTarget1, true),
                    new TestSelectable(
                        selectableTarget2, true, ImmutableMap.of(selectableTarget1, true)))));
    SelectorList<ImmutableList<Flavor>> selectorList =
        createSelectorListForListsOfFlavors(
            ImmutableMap.of(
                "//x:y",
                Lists.newArrayList("flavor11", "flavor12"),
                "//x:z",
                Lists.newArrayList("flavor21", "flavor22")));

    ImmutableList<Flavor> flavors =
        resolver.resolveList(
            configurationContext,
            keyTarget,
            "some_attribute",
            selectorList,
            flavorListTypeCoercer(),
            DependencyStack.root());

    assertEquals(
        Lists.newArrayList("flavor21", "flavor22"),
        flavors.stream().map(Flavor::getName).collect(Collectors.toList()));
  }

  @Test
  public void testResolvingListWithMultipleMatchingConditionsThrowsException()
      throws CoerceFailedException {
    BuildTarget keyTarget = BuildTargetFactory.newInstance("//a:b");
    BuildTarget selectableTarget1 = ConfigurationBuildTargetFactoryForTests.newInstance("//x:y");
    BuildTarget selectableTarget2 = ConfigurationBuildTargetFactoryForTests.newInstance("//x:z");
    UnconfiguredSelectorListResolver resolver =
        new UnconfiguredSelectorListResolver(
            new TestSelectableResolver(
                ImmutableList.of(
                    new TestSelectable(selectableTarget1, true),
                    new TestSelectable(selectableTarget2, true))));
    SelectorList<ImmutableList<Flavor>> selectorList =
        createSelectorListForListsOfFlavors(
            ImmutableMap.of(
                "//x:y",
                Lists.newArrayList("flavor11", "flavor12"),
                "//x:z",
                Lists.newArrayList("flavor21", "flavor22")));

    try {
      resolver.resolveList(
          configurationContext,
          keyTarget,
          "some_attribute",
          selectorList,
          flavorListTypeCoercer(),
          DependencyStack.root());
      fail("unreachable");
    } catch (HumanReadableException e) {
      assertEquals(
          "Multiple matches found when resolving configurable attribute \"some_attribute\" in //a:b:\n"
              + "//x:y\n"
              + "//x:z\n"
              + "Multiple matches are not allowed unless one is unambiguously more specialized.",
          e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void testResolvingListWithNoMatchesThrowsException() throws CoerceFailedException {
    BuildTarget keyTarget = BuildTargetFactory.newInstance("//a:b");
    BuildTarget selectableTarget = ConfigurationBuildTargetFactoryForTests.newInstance("//x:y");
    UnconfiguredSelectorListResolver resolver =
        new UnconfiguredSelectorListResolver(
            new TestSelectableResolver(
                ImmutableList.of(new TestSelectable(selectableTarget, false))));
    SelectorList<ImmutableList<Flavor>> selectorList =
        createSelectorListForListsOfFlavors(
            ImmutableMap.of("//x:y", Lists.newArrayList("flavor11", "flavor12")));

    try {
      resolver.resolveList(
          configurationContext,
          keyTarget,
          "some_attribute",
          selectorList,
          flavorListTypeCoercer(),
          DependencyStack.root());
      fail("unreachable");
    } catch (HumanReadableException e) {
      assertEquals(
          "None of the conditions in attribute \"some_attribute\" of //a:b match the configuration.\nChecked conditions:\n"
              + " //x:y",
          e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void testResolvingListWithNoMatchesThrowsExceptionWithCustomMessage()
      throws CoerceFailedException {
    BuildTarget keyTarget = BuildTargetFactory.newInstance("//a:b");
    BuildTarget selectableTarget = ConfigurationBuildTargetFactoryForTests.newInstance("//x:y");
    ListTypeCoercer<Flavor, Flavor> flavorListTypeCoercer = flavorListTypeCoercer();
    Selector<ImmutableList<Flavor>> selector =
        new Selector<>(
            ImmutableMap.of(
                new SelectorKey(ConfigurationBuildTargetFactoryForTests.newInstance("//x:y")),
                ImmutableList.of(InternalFlavor.of("flavor11"), InternalFlavor.of("flavor12"))),
            ImmutableSet.of(),
            "Custom message");
    UnconfiguredSelectorListResolver resolver =
        new UnconfiguredSelectorListResolver(
            new TestSelectableResolver(
                ImmutableList.of(new TestSelectable(selectableTarget, false))));
    SelectorList<ImmutableList<Flavor>> selectorList =
        new SelectorList<>(ImmutableList.of(selector));

    try {
      resolver.resolveList(
          configurationContext,
          keyTarget,
          "some_attribute",
          selectorList,
          flavorListTypeCoercer,
          DependencyStack.root());
      fail("unreachable");
    } catch (HumanReadableException e) {
      assertEquals(
          "None of the conditions in attribute \"some_attribute\" of //a:b match the configuration: Custom message",
          e.getHumanReadableErrorMessage());
    }
  }

  private SelectorList<Flavor> createSelectorListForFlavors(Map<String, ?>... selectors)
      throws CoerceFailedException {
    return TestSelectorListFactory.createSelectorListForCoercer(new FlavorTypeCoercer(), selectors);
  }

  private SelectorList<ImmutableList<Flavor>> createSelectorListForListsOfFlavors(
      Map<String, ?>... selectors) throws CoerceFailedException {
    return TestSelectorListFactory.createSelectorListForCoercer(flavorListTypeCoercer(), selectors);
  }

  @Nonnull
  private static ListTypeCoercer<Flavor, Flavor> flavorListTypeCoercer() {
    return new ListTypeCoercer<>(new FlavorTypeCoercer());
  }
}
