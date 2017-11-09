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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Pair;
import com.facebook.buck.model.UserFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import org.junit.Before;
import org.junit.Test;

public class JsLibraryDescriptionTest {

  private static final String targetDirectory = "arbitrary/path";
  private JsTestScenario.Builder scenarioBuilder;
  private BuildTarget target;

  @Before
  public void setUp() {
    scenarioBuilder = JsTestScenario.builder();
    target = BuildTargetFactory.newInstance(String.format("//%s:target", targetDirectory));
  }

  @Test
  public void subBasePathForSourceFiles() {
    final String basePath = "base/path";
    final String filePath = String.format("%s/sub/file.js", targetDirectory);
    final JsTestScenario scenario = buildScenario(basePath, FakeSourcePath.of(filePath));

    assertEquals(
        "arbitrary/path/base/path/sub/file.js",
        findFileRule(scenario.resolver).getVirtualPath().get());
  }

  @Test
  public void relativeBasePathForSourceFiles() {
    final String basePath = "../base/path";
    final String filePath = String.format("%s/sub/file.js", targetDirectory);
    final JsTestScenario scenario = buildScenario(basePath, FakeSourcePath.of(filePath));

    assertEquals(
        "arbitrary/base/path/sub/file.js", findFileRule(scenario.resolver).getVirtualPath().get());
  }

  @Test
  public void basePathReplacesBuildTargetSourcePath() {
    final String basePath = "base/path.js";
    final BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    scenarioBuilder.arbitraryRule(target);
    final JsTestScenario scenario =
        buildScenario(basePath, DefaultBuildTargetSourcePath.of(target));

    assertEquals(
        "arbitrary/path/base/path.js", findFileRule(scenario.resolver).getVirtualPath().get());
  }

  @Test
  public void relativeBasePathReplacesBuildTargetSourcePath() {
    final String basePath = "../path.js";
    final BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    scenarioBuilder.arbitraryRule(target);
    final JsTestScenario scenario =
        buildScenario(basePath, DefaultBuildTargetSourcePath.of(target));

    assertEquals("arbitrary/path.js", findFileRule(scenario.resolver).getVirtualPath().get());
  }

  @Test
  public void buildTargetWithSubpathPair() {
    final String basePath = ".";
    final BuildTarget target = BuildTargetFactory.newInstance("//:node_modules");
    scenarioBuilder.arbitraryRule(target);
    final JsTestScenario scenario =
        buildScenario(
            basePath,
            new Pair<>(DefaultBuildTargetSourcePath.of(target), "node_modules/left-pad/index.js"));

    assertEquals(
        "arbitrary/path/node_modules/left-pad/index.js",
        findFileRule(scenario.resolver).getVirtualPath().get());
  }

  @Test
  public void propagatesReleaseAndPlatformFlavors() {
    ImmutableSortedSet<UserFlavor> flavors =
        ImmutableSortedSet.of(JsFlavors.IOS, JsFlavors.RELEASE);
    BuildTarget withFlavors = this.target.withFlavors(flavors);
    JsTestScenario scenario =
        scenarioBuilder
            .library(withFlavors, FakeSourcePath.of("apples"), FakeSourcePath.of("pears"))
            .build();

    RichStream.from(scenario.resolver.getRule(withFlavors).getBuildDeps())
        .filter(JsFile.class)
        .map(JsFile::getBuildTarget)
        .forEach(
            target ->
                assertThat(
                    String.format(
                        "JsFile dependency `%s` of JsLibrary `%s` must have flavors `%s`",
                        target, withFlavors, flavors),
                    flavors,
                    everyItem(in(target.getFlavors()))));
  }

  @Test
  public void doesNotpropagatePlatformFlavorsWithoutRelease() {
    UserFlavor platformFlavor = JsFlavors.ANDROID;
    BuildTarget withPlatformFlavor = target.withFlavors(platformFlavor);
    JsTestScenario scenario =
        scenarioBuilder
            .library(withPlatformFlavor, FakeSourcePath.of("apples"), FakeSourcePath.of("pears"))
            .build();

    long numFileDeps =
        RichStream.from(scenario.resolver.getRule(withPlatformFlavor).getBuildDeps())
            .filter(JsFile.class)
            .map(JsFile::getBuildTarget)
            .peek(
                fileTarget ->
                    assertFalse(
                        String.format(
                            "JsFile dependency `%s` of JsLibrary `%s` must not have flavor `%s`",
                            fileTarget, withPlatformFlavor, platformFlavor),
                        fileTarget.getFlavors().contains(platformFlavor)))
            .count();
    assertNotEquals(0, numFileDeps);
  }

  @Test
  public void supportsDepsQuery() {
    BuildTarget a = BuildTargetFactory.newInstance("//query-deps:a");
    BuildTarget b = BuildTargetFactory.newInstance("//query-deps:b");
    BuildTarget c = BuildTargetFactory.newInstance("//query-deps:c");
    BuildTarget l = BuildTargetFactory.newInstance("//query-deps:l");
    BuildTarget x = BuildTargetFactory.newInstance("//query-deps:x");

    JsTestScenario scenario =
        scenarioBuilder
            .library(a)
            .library(b)
            .library(c, b)
            .appleLibraryWithDeps(l, a, c)
            .bundleWithDeps(x, l)
            .bundleWithDeps(BuildTargetFactory.newInstance("//query-deps:bundle"))
            .library(target, Query.of(String.format("deps(%s)", x)))
            .build();

    TargetNode<?, ?> node = scenario.targetGraph.get(target);
    assertThat(x, in(node.getBuildDeps()));

    JsLibrary lib = scenario.resolver.getRuleWithType(target, JsLibrary.class);
    ImmutableSortedSet<BuildRule> deps = scenario.resolver.getAllRules(ImmutableList.of(a, b, c));
    assertThat(deps, everyItem(in(lib.getBuildDeps())));
    assertEquals(
        deps.stream()
            .map(BuildRule::getSourcePathToOutput)
            .collect(MoreCollectors.toImmutableSortedSet()),
        lib.getLibraryDependencies());
  }

  @Test
  public void supportsDepsAndDepsQuery() {
    BuildTarget a = BuildTargetFactory.newInstance("//query:dep");
    BuildTarget b = BuildTargetFactory.newInstance("//direct:dep");

    JsTestScenario scenario =
        scenarioBuilder.library(a).library(b).library(target, Query.of(a.toString()), b).build();

    JsLibrary lib = scenario.resolver.getRuleWithType(target, JsLibrary.class);
    ImmutableSortedSet<BuildRule> deps = scenario.resolver.getAllRules(ImmutableList.of(a, b));
    assertThat(deps, everyItem(in(lib.getBuildDeps())));
    assertEquals(
        deps.stream()
            .map(BuildRule::getSourcePathToOutput)
            .collect(MoreCollectors.toImmutableSortedSet()),
        lib.getLibraryDependencies());
  }

  private JsTestScenario buildScenario(String basePath, SourcePath source) {
    return scenarioBuilder.library(target, basePath, source).build();
  }

  private JsTestScenario buildScenario(String basePath, Pair<SourcePath, String> source) {
    return scenarioBuilder.library(target, basePath, source).build();
  }

  private JsFile.JsFileDev findFileRule(BuildRuleResolver resolver) {
    return RichStream.from(resolver.getRule(target).getBuildDeps())
        .filter(JsFile.JsFileDev.class)
        .findFirst()
        .get();
  }
}
