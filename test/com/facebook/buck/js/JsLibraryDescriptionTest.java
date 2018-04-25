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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.UserFlavor;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.js.JsFile.JsFileDev;
import com.facebook.buck.js.JsLibrary.Files;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.util.Arrays;
import java.util.stream.Stream;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
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
  public void ruleDependsOnInternalFileRule() {
    JsTestScenario scenario = scenarioBuilder.library(target).build();
    BuildRule library = scenario.resolver.requireRule(target);
    assertThat(
        library.getBuildDeps(),
        hasItem(
            scenario.resolver.requireRule(target.withAppendedFlavors(JsFlavors.LIBRARY_FILES))));
  }

  @Test
  public void ruleDependsOnDeps() {
    BuildTarget[] depTargets = {
      BuildTargetFactory.newInstance("//dep:a"),
      BuildTargetFactory.newInstance("//dep:b"),
      BuildTargetFactory.newInstance("//dep:c"),
    };

    for (BuildTarget depTarget : depTargets) {
      scenarioBuilder.library(depTarget);
    }
    JsTestScenario scenario = scenarioBuilder.library(target, depTargets).build();
    BuildRule library = scenario.resolver.requireRule(target);

    BuildRule[] deps =
        scenario.resolver.getAllRules(Arrays.asList(depTargets)).stream().toArray(BuildRule[]::new);
    assertThat(library.getBuildDeps(), hasItems(deps));
  }

  @Test
  public void doesNotDependOnJsFileRules() {
    PathSourcePath a = FakeSourcePath.of("source/a");
    PathSourcePath b = FakeSourcePath.of("source/b");
    PathSourcePath c = FakeSourcePath.of("source/c");
    JsTestScenario scenario = scenarioBuilder.library(target, a, b, c).build();

    BuildRule library = scenario.resolver.requireRule(target);
    assertThat(library.getBuildDeps(), not(hasItem(instanceOf(JsFile.class))));
  }

  @Test
  public void propagatesFlavorsToInternalFileRule() {
    Flavor[] extraFlavors = {JsFlavors.ANDROID, JsFlavors.RELEASE};
    BuildTarget withFlavors = target.withFlavors(extraFlavors);
    JsTestScenario scenario = scenarioBuilder.library(withFlavors).build();

    BuildRule library = scenario.resolver.requireRule(withFlavors);
    BuildRule filesRule =
        library
            .getBuildDeps()
            .stream()
            .filter(rule -> rule instanceof JsLibrary.Files)
            .findAny()
            .get();

    assertThat(filesRule.getBuildTarget().getFlavors(), hasItems(extraFlavors));
  }

  @Test
  public void internalFileRuleDependsOnWorker() {
    JsTestScenario scenario = scenarioBuilder.library(target).build();
    BuildRule filesRule = internalFileRule(scenario.resolver);
    assertThat(filesRule.getBuildDeps(), hasItem(scenario.resolver.getRule(scenario.workerTarget)));
  }

  @Test
  public void internalFileRuleDependsOnJsFileRules() {
    PathSourcePath a = FakeSourcePath.of("source/a");
    PathSourcePath b = FakeSourcePath.of("source/b");
    PathSourcePath c = FakeSourcePath.of("source/c");
    JsTestScenario scenario = scenarioBuilder.library(target, a, b, c).build();

    BuildRule filesRule = internalFileRule(scenario.resolver);
    assertThat(
        filesRule.getBuildDeps(),
        hasItems(Stream.of(a, b, c).map(JsFileMatcher::new).toArray(JsFileMatcher[]::new)));
  }

  @Test
  public void subBasePathForSourceFiles() {
    String basePath = "base/path";
    String filePath = String.format("%s/sub/file.js", targetDirectory);
    JsTestScenario scenario = buildScenario(basePath, FakeSourcePath.of(filePath));

    assertEquals(
        "arbitrary/path/base/path/sub/file.js",
        findFirstJsFileDevRule(scenario.resolver).getVirtualPath().get());
  }

  @Test
  public void relativeBasePathForSourceFiles() {
    String basePath = "../base/path";
    String filePath = String.format("%s/sub/file.js", targetDirectory);
    JsTestScenario scenario = buildScenario(basePath, FakeSourcePath.of(filePath));

    assertEquals(
        "arbitrary/base/path/sub/file.js",
        findFirstJsFileDevRule(scenario.resolver).getVirtualPath().get());
  }

  @Test
  public void basePathReplacesBuildTargetSourcePath() {
    String basePath = "base/path.js";
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    scenarioBuilder.arbitraryRule(target);
    JsTestScenario scenario = buildScenario(basePath, DefaultBuildTargetSourcePath.of(target));

    assertEquals(
        "arbitrary/path/base/path.js",
        findFirstJsFileDevRule(scenario.resolver).getVirtualPath().get());
  }

  @Test
  public void relativeBasePathReplacesBuildTargetSourcePath() {
    String basePath = "../path.js";
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    scenarioBuilder.arbitraryRule(target);
    JsTestScenario scenario = buildScenario(basePath, DefaultBuildTargetSourcePath.of(target));

    assertEquals(
        "arbitrary/path.js", findFirstJsFileDevRule(scenario.resolver).getVirtualPath().get());
  }

  @Test
  public void buildTargetWithSubpathPair() {
    String basePath = ".";
    BuildTarget target = BuildTargetFactory.newInstance("//:node_modules");
    scenarioBuilder.arbitraryRule(target);
    JsTestScenario scenario =
        buildScenario(
            basePath,
            new Pair<>(DefaultBuildTargetSourcePath.of(target), "node_modules/left-pad/index.js"));

    assertEquals(
        "arbitrary/path/node_modules/left-pad/index.js",
        findFirstJsFileDevRule(scenario.resolver).getVirtualPath().get());
  }

  @Test
  public void propagatesReleaseAndPlatformFlavors() {
    ImmutableSortedSet<UserFlavor> flavors =
        ImmutableSortedSet.of(JsFlavors.IOS, JsFlavors.RELEASE);
    target = target.withFlavors(flavors);
    JsTestScenario scenario =
        scenarioBuilder
            .library(target, FakeSourcePath.of("apples"), FakeSourcePath.of("pears"))
            .build();

    findJsFileRules(scenario.resolver)
        .map(JsFile::getBuildTarget)
        .peek(
            depTarget ->
                assertThat(
                    String.format(
                        "JsFile dependency `%s` of JsLibrary `%s` must have flavors `%s`",
                        depTarget, target, flavors),
                    flavors,
                    everyItem(in(depTarget.getFlavors()))))
        .findAny()
        .orElseThrow(() -> new IllegalStateException("No JsFile dependencies found for " + target));
  }

  @Test
  public void doesNotpropagatePlatformFlavorsWithoutRelease() {
    UserFlavor platformFlavor = JsFlavors.ANDROID;
    BuildTarget withPlatformFlavor = target.withFlavors(platformFlavor);
    JsTestScenario scenario =
        scenarioBuilder
            .library(withPlatformFlavor, FakeSourcePath.of("apples"), FakeSourcePath.of("pears"))
            .build();

    findJsFileRules(scenario.resolver)
        .map(JsFile::getBuildTarget)
        .peek(
            fileTarget ->
                assertFalse(
                    String.format(
                        "JsFile dependency `%s` of JsLibrary `%s` must not have flavor `%s`",
                        fileTarget, withPlatformFlavor, platformFlavor),
                    fileTarget.getFlavors().contains(platformFlavor)))
        .findAny()
        .orElseThrow(() -> new IllegalStateException("No JsFile dependencies found for " + target));
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
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())),
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
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())),
        lib.getLibraryDependencies());
  }

  private JsTestScenario buildScenario(String basePath, SourcePath source) {
    return scenarioBuilder.library(target, basePath, source).build();
  }

  private JsTestScenario buildScenario(String basePath, Pair<SourcePath, String> source) {
    return scenarioBuilder.library(target, basePath, source).build();
  }

  private RichStream<JsFile> findJsFileRules(BuildRuleResolver resolver) {
    return RichStream.from(internalFileRule(resolver).getBuildDeps()).filter(JsFile.class);
  }

  private JsFile.JsFileDev findFirstJsFileDevRule(BuildRuleResolver resolver) {
    return findJsFileRules(resolver).filter(JsFileDev.class).findFirst().get();
  }

  private JsLibrary.Files internalFileRule(BuildRuleResolver resolver) {
    return (Files) resolver.requireRule(target.withAppendedFlavors(JsFlavors.LIBRARY_FILES));
  }

  private static class JsFileMatcher extends BaseMatcher<BuildRule> {
    private final PathSourcePath source;

    public JsFileMatcher(PathSourcePath source) {
      this.source = source;
    }

    @Override
    public boolean matches(Object o) {
      return o instanceof JsFile.JsFileDev && ((JsFile.JsFileDev) o).getSource().equals(source);
    }

    @Override
    public void describeTo(Description description) {
      description.appendText(String.format("<JsFile:%s>", source));
    }
  }
}
