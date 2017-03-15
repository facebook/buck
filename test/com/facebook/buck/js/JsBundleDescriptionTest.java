/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.js;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Before;
import org.junit.Test;

import java.util.Collection;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JsBundleDescriptionTest {

  private static final BuildTarget directDependencyTarget =
      BuildTargetFactory.newInstance("//libs:direct");
  private static final BuildTarget level2 =
      BuildTargetFactory.newInstance("//libs:level2");
  private static final BuildTarget level1_1 =
      BuildTargetFactory.newInstance("//libs:level1.1");
  private static final BuildTarget level1_2 =
      BuildTargetFactory.newInstance("//libs:level1.2");

  private JsTestScenario.Builder scenarioBuilder;

  static Collection<BuildTarget> allLibaryTargets(Flavor... flavors) {
    return Stream.of(level2, level1_1, level1_2, directDependencyTarget)
        .map(t -> t.withAppendedFlavors(flavors))
        .collect(Collectors.toList());
  }

  @Before
  public void setUp() {
    scenarioBuilder = JsTestScenario.builder();
    scenarioBuilder
        .library(level2)
        .library(level1_1, level2)
        .library(level1_2, level2)
        .library(JsBundleDescriptionTest.directDependencyTarget, level1_1, level1_2);
  }

  @Test
  public void testTransitiveLibraryDependencies() throws NoSuchBuildTargetException {
    JsTestScenario scenario = scenarioBuilder.build();

    JsBundle jsBundle = scenario.createBundle(
        "//arbitrary:target",
        ImmutableSortedSet.of(directDependencyTarget));

    assertThat(allLibaryTargets(), everyItem(in(dependencyTargets(jsBundle))));
  }

  @Test
  public void testTransitiveLibraryDependenciesWithFlavors() throws NoSuchBuildTargetException {
    JsTestScenario scenario = scenarioBuilder.build();

    JsBundle jsBundle = scenario.createBundle(
        "//arbitrary:target#ios,prod",
        ImmutableSortedSet.of(directDependencyTarget));

    assertThat(
        allLibaryTargets(JsFlavors.IOS, JsFlavors.PROD),
        everyItem(in(dependencyTargets(jsBundle))));
  }

  @Test
  public void testFlavoredBundleDoesNotDependOnUnflavoredLibs() throws NoSuchBuildTargetException {
    JsTestScenario scenario = scenarioBuilder.build();

    JsBundle jsBundle = scenario.createBundle(
        "//arbitrary:target#ios,prod",
        ImmutableSortedSet.of(directDependencyTarget));

    assertThat(
        allLibaryTargets(),
        everyItem(not(in(dependencyTargets(jsBundle)))));
  }

  private static Collection<BuildTarget> dependencyTargets(JsBundle jsBundle) {
    return jsBundle.getDeps()
        .stream()
        .map(BuildRule::getBuildTarget)
        .collect(Collectors.toSet());
  }
}
