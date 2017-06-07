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
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableSortedSet;
import org.junit.Before;
import org.junit.Test;

public class JsLibraryDescriptionTest {

  private static final String targetDirectory = "arbitrary/path";
  private JsTestScenario.Builder scenarioBuilder;
  private BuildTarget target;

  @Before
  public void setUp() throws NoSuchBuildTargetException {
    scenarioBuilder = JsTestScenario.builder();
    target = BuildTargetFactory.newInstance(String.format("//%s:target", targetDirectory));
  }

  @Test
  public void subBasePathForSourceFiles() throws NoSuchBuildTargetException {
    final String basePath = "base/path";
    final String filePath = String.format("%s/sub/file.js", targetDirectory);
    final JsTestScenario scenario = buildScenario(basePath, new FakeSourcePath(filePath));

    assertEquals(
        "arbitrary/path/base/path/sub/file.js",
        findFileRule(scenario.resolver).getVirtualPath().get());
  }

  @Test
  public void relativeBasePathForSourceFiles() throws NoSuchBuildTargetException {
    final String basePath = "../base/path";
    final String filePath = String.format("%s/sub/file.js", targetDirectory);
    final JsTestScenario scenario = buildScenario(basePath, new FakeSourcePath(filePath));

    assertEquals(
        "arbitrary/base/path/sub/file.js", findFileRule(scenario.resolver).getVirtualPath().get());
  }

  @Test
  public void basePathReplacesBuildTargetSourcePath() throws NoSuchBuildTargetException {
    final String basePath = "base/path.js";
    final BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    scenarioBuilder.arbitraryRule(target);
    final JsTestScenario scenario =
        buildScenario(basePath, new DefaultBuildTargetSourcePath(target));

    assertEquals(
        "arbitrary/path/base/path.js", findFileRule(scenario.resolver).getVirtualPath().get());
  }

  @Test
  public void relativeBasePathReplacesBuildTargetSourcePath() throws NoSuchBuildTargetException {
    final String basePath = "../path.js";
    final BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    scenarioBuilder.arbitraryRule(target);
    final JsTestScenario scenario =
        buildScenario(basePath, new DefaultBuildTargetSourcePath(target));

    assertEquals("arbitrary/path.js", findFileRule(scenario.resolver).getVirtualPath().get());
  }

  @Test
  public void buildTargetWithSubpathPair() throws NoSuchBuildTargetException {
    final String basePath = ".";
    final BuildTarget target = BuildTargetFactory.newInstance("//:node_modules");
    scenarioBuilder.arbitraryRule(target);
    final JsTestScenario scenario =
        buildScenario(
            basePath,
            new Pair<>(new DefaultBuildTargetSourcePath(target), "node_modules/left-pad/index.js"));

    assertEquals(
        "arbitrary/path/node_modules/left-pad/index.js",
        findFileRule(scenario.resolver).getVirtualPath().get());
  }

  @Test
  public void propagatesReleaseAndPlatformFlavors() throws NoSuchBuildTargetException {
    ImmutableSortedSet<UserFlavor> flavors =
        ImmutableSortedSet.of(JsFlavors.IOS, JsFlavors.RELEASE);
    BuildTarget withFlavors = this.target.withFlavors(flavors);
    JsTestScenario scenario =
        scenarioBuilder
            .library(withFlavors, new FakeSourcePath("apples"), new FakeSourcePath("pears"))
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
  public void doesNotpropagatePlatformFlavorsWithoutRelease() throws NoSuchBuildTargetException {
    UserFlavor platformFlavor = JsFlavors.ANDROID;
    BuildTarget withPlatformFlavor = target.withFlavors(platformFlavor);
    JsTestScenario scenario =
        scenarioBuilder
            .library(withPlatformFlavor, new FakeSourcePath("apples"), new FakeSourcePath("pears"))
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

  private JsTestScenario buildScenario(String basePath, SourcePath source)
      throws NoSuchBuildTargetException {
    return scenarioBuilder.library(target, basePath, source).build();
  }

  private JsTestScenario buildScenario(String basePath, Pair<SourcePath, String> source)
      throws NoSuchBuildTargetException {
    return scenarioBuilder.library(target, basePath, source).build();
  }

  private JsFile.JsFileDev findFileRule(BuildRuleResolver resolver) {
    return RichStream.from(resolver.getRule(target).getBuildDeps())
        .filter(JsFile.JsFileDev.class)
        .findFirst()
        .get();
  }
}
