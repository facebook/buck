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

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.ConfigurationBuildTargetFactoryForTests;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.platform.ConstraintSetting;
import com.facebook.buck.core.model.platform.ConstraintValue;
import com.facebook.buck.core.select.ConfigSettingSelectable;
import com.facebook.buck.core.select.SelectableConfigurationContext;
import com.facebook.buck.core.select.SelectableConfigurationContextFactory;
import com.facebook.buck.core.select.Selector;
import com.facebook.buck.core.select.SelectorKey;
import com.facebook.buck.core.select.SelectorList;
import com.facebook.buck.core.select.SelectorListResolved;
import com.facebook.buck.core.select.TestSelectableResolver;
import com.facebook.buck.core.select.TestSelectables;
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
import org.junit.Before;
import org.junit.Test;

public class DefaultSelectorListResolverTest {

  private SelectableConfigurationContext configurationContext;

  @Before
  public void setUp() {
    configurationContext = SelectableConfigurationContextFactory.UNCONFIGURED;
  }

  @Test
  public void testResolvingEmptyListReturnsEmptyList() throws CoerceFailedException {
    DefaultSelectorListResolver resolver =
        new DefaultSelectorListResolver(new TestSelectableResolver());
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//a:b");
    SelectorList<ImmutableList<Flavor>> selectorList = createSelectorListForListsOfFlavors();

    DependencyStack dependencyStack = DependencyStack.root();
    SelectorListResolved<ImmutableList<Flavor>> selectorListResolved =
        resolver.resolveSelectorList(selectorList, dependencyStack);

    ImmutableList<Flavor> flavors =
        selectorListResolved.eval(
            configurationContext,
            flavorListTypeCoercer(),
            buildTarget,
            "some_attribute",
            dependencyStack);

    assertTrue(flavors.isEmpty());
  }

  @Test
  public void testResolvingListWithSingleElementReturnsSingleElement()
      throws CoerceFailedException {
    ConstraintSetting constraintSetting = TestSelectables.constraintSetting("//x:c");
    ConstraintValue constraintValue = TestSelectables.constraintValue("//x:y", constraintSetting);

    BuildTarget keyTarget = BuildTargetFactory.newInstance("//a:b");
    BuildTarget selectableTarget = ConfigurationBuildTargetFactoryForTests.newInstance("//x:y");
    SelectorList<Flavor> selectorList =
        createSelectorListForFlavors(ImmutableMap.of("DEFAULT", "flavor1", "//x:y", "flavor2"));
    DefaultSelectorListResolver resolver =
        new DefaultSelectorListResolver(
            new TestSelectableResolver(
                ImmutableMap.of(selectableTarget, TestSelectables.configSetting(constraintValue))));

    DependencyStack dependencyStack = DependencyStack.root();
    SelectorListResolved<Flavor> selectorListResolved =
        resolver.resolveSelectorList(selectorList, dependencyStack);

    Flavor flavor =
        selectorListResolved.eval(
            TestSelectables.selectableConfigurationContext(constraintValue),
            new FlavorTypeCoercer(),
            keyTarget,
            "some_attribute",
            dependencyStack);

    assertEquals("flavor2", flavor.getName());
  }

  @Test
  public void testResolvingListWithMultipleElementsNotSupportingConcatReturnsNull()
      throws CoerceFailedException {
    BuildTarget keyTarget = BuildTargetFactory.newInstance("//a:b");

    ConstraintSetting constraintSetting = TestSelectables.constraintSetting("//x:c");
    ConstraintValue constraintValue = TestSelectables.constraintValue("//x:v", constraintSetting);
    ConfigSettingSelectable configSetting = TestSelectables.configSetting(constraintValue);

    DefaultSelectorListResolver resolver =
        new DefaultSelectorListResolver(
            new TestSelectableResolver(
                ImmutableMap.of(
                    ConfigurationBuildTargetFactoryForTests.newInstance("//x:y"), configSetting)));
    SelectorList<Flavor> selectorList =
        createSelectorListForFlavors(
            ImmutableMap.of("DEFAULT", "flavor1", "//x:y", "flavor2"),
            ImmutableMap.of("DEFAULT", "flavor3", "//x:y", "flavor4"));

    DependencyStack dependencyStack = DependencyStack.root();
    SelectorListResolved<Flavor> selectorListResolved =
        resolver.resolveSelectorList(selectorList, dependencyStack);

    Flavor flavor =
        selectorListResolved.eval(
            TestSelectables.selectableConfigurationContext(),
            new FlavorTypeCoercer(),
            keyTarget,
            "some_attribute",
            dependencyStack);

    assertNull(flavor);
  }

  @Test
  public void testResolvingListWithMultipleElementsSupportingConcatReturnsCompleteList()
      throws CoerceFailedException {
    BuildTarget keyTarget = BuildTargetFactory.newInstance("//a:b");

    BuildTarget selectableTarget = ConfigurationBuildTargetFactoryForTests.newInstance("//x:y");

    ConstraintSetting constraintSetting = TestSelectables.constraintSetting("//x:c");
    ConstraintValue constraintValue = TestSelectables.constraintValue("//x:v", constraintSetting);
    ConfigSettingSelectable configSetting = TestSelectables.configSetting(constraintValue);

    DefaultSelectorListResolver resolver =
        new DefaultSelectorListResolver(
            new TestSelectableResolver(ImmutableMap.of(selectableTarget, configSetting)));
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

    DependencyStack dependencyStack = DependencyStack.root();
    SelectorListResolved<ImmutableList<Flavor>> selectorListResolved =
        resolver.resolveSelectorList(selectorList, dependencyStack);

    ImmutableList<Flavor> flavors =
        selectorListResolved.eval(
            TestSelectables.selectableConfigurationContext(constraintValue),
            flavorListTypeCoercer(),
            keyTarget,
            "some_attribute",
            dependencyStack);

    assertEquals(
        Lists.newArrayList("flavor21", "flavor22", "flavor41", "flavor42"),
        flavors.stream().map(Flavor::getName).collect(Collectors.toList()));
  }

  @Test
  public void testResolvingListWithMultipleDefaultMatchesReturnsList()
      throws CoerceFailedException {
    ConstraintSetting os = TestSelectables.constraintSetting("//c:os");
    ConstraintValue linux = TestSelectables.constraintValue("//c:linux", os);
    ConstraintValue windows = TestSelectables.constraintValue("//c:windows", os);

    BuildTarget keyTarget = BuildTargetFactory.newInstance("//a:b");
    BuildTarget selectableTarget = ConfigurationBuildTargetFactoryForTests.newInstance("//x:y");
    DefaultSelectorListResolver resolver =
        new DefaultSelectorListResolver(
            new TestSelectableResolver(
                ImmutableMap.of(selectableTarget, TestSelectables.configSetting(linux))));
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

    DependencyStack dependencyStack = DependencyStack.root();
    SelectorListResolved<ImmutableList<Flavor>> selectorListResolved =
        resolver.resolveSelectorList(selectorList, dependencyStack);

    ImmutableList<Flavor> flavors =
        selectorListResolved.eval(
            TestSelectables.selectableConfigurationContext(windows),
            flavorListTypeCoercer(),
            keyTarget,
            "some_attribute",
            dependencyStack);

    assertEquals(
        Lists.newArrayList("flavor11", "flavor12", "flavor31", "flavor32"),
        flavors.stream().map(Flavor::getName).collect(Collectors.toList()));
  }

  @Test
  public void testResolvingListWithRefinedConditionsPicksMostSpecializedCondition()
      throws CoerceFailedException {
    ConstraintSetting os = TestSelectables.constraintSetting("//c:os");
    ConstraintValue linux = TestSelectables.constraintValue("//c:linux", os);
    ConstraintSetting cpu = TestSelectables.constraintSetting("//c:cpu");
    ConstraintValue arm64 = TestSelectables.constraintValue("//c:arm64", cpu);

    BuildTarget keyTarget = BuildTargetFactory.newInstance("//a:b");
    BuildTarget selectableTarget1 = ConfigurationBuildTargetFactoryForTests.newInstance("//x:y");
    BuildTarget selectableTarget2 = ConfigurationBuildTargetFactoryForTests.newInstance("//x:z");
    DefaultSelectorListResolver resolver =
        new DefaultSelectorListResolver(
            new TestSelectableResolver(
                ImmutableMap.of(
                    selectableTarget1,
                    TestSelectables.configSetting(linux),
                    selectableTarget2,
                    TestSelectables.configSetting(linux, arm64))));
    SelectorList<ImmutableList<Flavor>> selectorList =
        createSelectorListForListsOfFlavors(
            ImmutableMap.of(
                "//x:y",
                Lists.newArrayList("flavor11", "flavor12"),
                "//x:z",
                Lists.newArrayList("flavor21", "flavor22")));

    DependencyStack dependencyStack = DependencyStack.root();
    SelectorListResolved<ImmutableList<Flavor>> selectorListResolved =
        resolver.resolveSelectorList(selectorList, dependencyStack);

    ImmutableList<Flavor> flavors =
        selectorListResolved.eval(
            TestSelectables.selectableConfigurationContext(linux, arm64),
            flavorListTypeCoercer(),
            keyTarget,
            "some_attribute",
            dependencyStack);

    assertEquals(
        Lists.newArrayList("flavor21", "flavor22"),
        flavors.stream().map(Flavor::getName).collect(Collectors.toList()));
  }

  @Test
  public void testResolvingListWithMultipleMatchingConditionsThrowsException()
      throws CoerceFailedException {
    ConstraintSetting os = TestSelectables.constraintSetting("//c:os");
    ConstraintValue linux = TestSelectables.constraintValue("//c:linux", os);
    ConstraintSetting cpu = TestSelectables.constraintSetting("//c:cpu");
    ConstraintValue arm64 = TestSelectables.constraintValue("//c:arm64", cpu);

    BuildTarget keyTarget = BuildTargetFactory.newInstance("//a:b");
    BuildTarget selectableTarget1 = ConfigurationBuildTargetFactoryForTests.newInstance("//x:y");
    BuildTarget selectableTarget2 = ConfigurationBuildTargetFactoryForTests.newInstance("//x:z");
    DefaultSelectorListResolver resolver =
        new DefaultSelectorListResolver(
            new TestSelectableResolver(
                ImmutableMap.of(
                    selectableTarget1,
                    TestSelectables.configSetting(linux),
                    selectableTarget2,
                    TestSelectables.configSetting(arm64))));
    SelectorList<ImmutableList<Flavor>> selectorList =
        createSelectorListForListsOfFlavors(
            ImmutableMap.of(
                "//x:y",
                Lists.newArrayList("flavor11", "flavor12"),
                "//x:z",
                Lists.newArrayList("flavor21", "flavor22")));

    try {
      DependencyStack dependencyStack = DependencyStack.root();
      SelectorListResolved<ImmutableList<Flavor>> selectorListResolved =
          resolver.resolveSelectorList(selectorList, dependencyStack);

      selectorListResolved.eval(
          TestSelectables.selectableConfigurationContext(linux, arm64),
          flavorListTypeCoercer(),
          keyTarget,
          "some_attribute",
          dependencyStack);
    } catch (HumanReadableException e) {
      assertEquals(
          "Ambiguous keys in select: //x:y and //x:z;"
              + " keys must have at least one different constraint or config property",
          e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void testResolvingListWithNoMatchesThrowsException() throws CoerceFailedException {
    BuildTarget keyTarget = BuildTargetFactory.newInstance("//a:b");
    BuildTarget selectableTarget = ConfigurationBuildTargetFactoryForTests.newInstance("//x:y");
    DefaultSelectorListResolver resolver =
        new DefaultSelectorListResolver(
            new TestSelectableResolver(
                ImmutableMap.of(
                    selectableTarget,
                    TestSelectables.configSetting(
                        TestSelectables.constraintValue("//c:linux", "//c:os")))));
    SelectorList<ImmutableList<Flavor>> selectorList =
        createSelectorListForListsOfFlavors(
            ImmutableMap.of("//x:y", Lists.newArrayList("flavor11", "flavor12")));

    try {
      DependencyStack dependencyStack = DependencyStack.root();
      SelectorListResolved<ImmutableList<Flavor>> selectorListResolved =
          resolver.resolveSelectorList(selectorList, dependencyStack);

      selectorListResolved.eval(
          TestSelectables.selectableConfigurationContext(
              TestSelectables.constraintValue("//c:windows", "//c:os")),
          flavorListTypeCoercer(),
          keyTarget,
          "some_attribute",
          dependencyStack);
    } catch (HumanReadableException e) {
      assertEquals(
          "None of the conditions in attribute \"some_attribute\" of //a:b match the configuration.\nChecked conditions:\n"
              + " //x:y",
          e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void testResolvingListWithNoMatchesThrowsExceptionWithCustomMessage() {
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
    DefaultSelectorListResolver resolver =
        new DefaultSelectorListResolver(
            new TestSelectableResolver(
                ImmutableMap.of(
                    selectableTarget,
                    TestSelectables.configSetting(
                        TestSelectables.constraintValue("//c:linux", "//c:os")))));
    SelectorList<ImmutableList<Flavor>> selectorList =
        new SelectorList<>(ImmutableList.of(selector));

    try {
      DependencyStack dependencyStack = DependencyStack.root();
      SelectorListResolved<ImmutableList<Flavor>> selectorListResolved =
          resolver.resolveSelectorList(selectorList, dependencyStack);

      selectorListResolved.eval(
          TestSelectables.selectableConfigurationContext(
              TestSelectables.constraintValue("//c:windows", "//c:os")),
          flavorListTypeCoercer,
          keyTarget,
          "some_attribute",
          dependencyStack);
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

  private static ListTypeCoercer<Flavor, Flavor> flavorListTypeCoercer() {
    return new ListTypeCoercer<>(new FlavorTypeCoercer());
  }
}
