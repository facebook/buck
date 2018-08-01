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

package com.facebook.buck.features.js;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeFalse;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.UserFlavor;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.features.js.JsFile.JsFileDev;
import com.facebook.buck.features.js.JsLibrary.Files;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collector.Characteristics;
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
    BuildRule library = scenario.graphBuilder.requireRule(target);
    assertThat(
        library.getBuildDeps(),
        hasItem(
            scenario.graphBuilder.requireRule(
                target.withAppendedFlavors(JsFlavors.LIBRARY_FILES))));
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
    BuildRule library = scenario.graphBuilder.requireRule(target);

    BuildRule[] deps =
        scenario
            .graphBuilder
            .getAllRules(Arrays.asList(depTargets))
            .stream()
            .toArray(BuildRule[]::new);
    assertThat(library.getBuildDeps(), hasItems(deps));
  }

  @Test
  public void rulePropagatesFlavorsToDeps() {
    BuildTarget[] depTargets = {
      BuildTargetFactory.newInstance("//dep:a"),
      BuildTargetFactory.newInstance("//dep:b"),
      BuildTargetFactory.newInstance("//dep:c"),
    };

    for (BuildTarget depTarget : depTargets) {
      scenarioBuilder.library(depTarget);
    }
    JsTestScenario scenario = scenarioBuilder.library(target, depTargets).build();
    BuildRule library =
        scenario.graphBuilder.requireRule(target.withAppendedFlavors(JsFlavors.RELEASE));

    BuildRule[] flavoredDeps =
        scenario
            .graphBuilder
            .getAllRules(
                Stream.of(depTargets)
                    .map(x -> x.withAppendedFlavors(JsFlavors.RELEASE))
                    .collect(ImmutableList.toImmutableList()))
            .stream()
            .toArray(BuildRule[]::new);
    assertThat(library.getBuildDeps(), hasItems(flavoredDeps));
    BuildRule[] unflavoredDeps =
        scenario
            .graphBuilder
            .getAllRules(Arrays.asList(depTargets))
            .stream()
            .toArray(BuildRule[]::new);
    assertThat(Arrays.asList(unflavoredDeps), everyItem(not(in(library.getBuildDeps()))));
  }

  @Test
  public void doesNotDependOnJsFileRules() {
    PathSourcePath a = FakeSourcePath.of("source/a");
    PathSourcePath b = FakeSourcePath.of("source/b");
    PathSourcePath c = FakeSourcePath.of("source/c");
    JsTestScenario scenario = scenarioBuilder.library(target, a, b, c).build();

    BuildRule library = scenario.graphBuilder.requireRule(target);
    assertThat(library.getBuildDeps(), not(hasItem(instanceOf(JsFile.class))));
  }

  @Test
  public void propagatesFlavorsToInternalFileRule() {
    Flavor[] extraFlavors = {JsFlavors.ANDROID, JsFlavors.RELEASE};
    BuildTarget withFlavors = target.withFlavors(extraFlavors);
    JsTestScenario scenario = scenarioBuilder.library(withFlavors).build();

    BuildRule library = scenario.graphBuilder.requireRule(withFlavors);
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
    BuildRule filesRule = internalFileRule(scenario.graphBuilder);
    assertThat(
        filesRule.getBuildDeps(), hasItem(scenario.graphBuilder.getRule(scenario.workerTarget)));
  }

  @Test
  public void internalFileRuleDependsOnJsFileRules() {
    PathSourcePath a = FakeSourcePath.of("source/a");
    PathSourcePath b = FakeSourcePath.of("source/b");
    PathSourcePath c = FakeSourcePath.of("source/c");
    JsTestScenario scenario = scenarioBuilder.library(target, a, b, c).build();

    BuildRule filesRule = internalFileRule(scenario.graphBuilder);
    assertThat(
        filesRule.getBuildDeps(),
        hasItems(Stream.of(a, b, c).map(JsFileMatcher::new).toArray(JsFileMatcher[]::new)));
  }

  @Test
  public void internalFileRuleDoesNotDependOnLibDeps() {
    BuildTarget a = BuildTargetFactory.newInstance("//libary:a");
    BuildTarget b = BuildTargetFactory.newInstance("//libary:b");
    JsTestScenario scenario = scenarioBuilder.library(a).library(b).library(target, a, b).build();

    BuildRule filesRule = internalFileRule(scenario.graphBuilder);
    assertThat(
        scenario.graphBuilder.getAllRules(ImmutableList.of(a, b)),
        everyItem(not(in(filesRule.getBuildDeps()))));
  }

  @Test
  public void fileRulesDoNotDependOnLibDeps() {
    ImmutableList<BuildTarget> libDeps =
        ImmutableList.of(
            BuildTargetFactory.newInstance("//libary:a"),
            BuildTargetFactory.newInstance("//libary:b"));

    for (BuildTarget lib : libDeps) {
      scenarioBuilder.library(lib);
    }
    JsTestScenario scenario =
        scenarioBuilder
            .library(
                target,
                libDeps,
                ImmutableList.of(FakeSourcePath.of("apples"), FakeSourcePath.of("pears")))
            .build();

    findJsFileRules(scenario.graphBuilder)
        .map(JsLibraryDescriptionTest::getBuildDepsAsTargets)
        .collect(countAssertions(deps -> assertThat(libDeps, everyItem(not(in(deps))))));
  }

  @Test
  public void fileRulesDoNotDependOnGeneratedSourcesOfOtherFileRules() {
    BuildTargetSourcePath a =
        DefaultBuildTargetSourcePath.of(BuildTargetFactory.newInstance("//gen:a"));
    BuildTargetSourcePath b =
        DefaultBuildTargetSourcePath.of(BuildTargetFactory.newInstance("//gen:b"));

    JsTestScenario scenario =
        scenarioBuilder
            .arbitraryRule(a.getTarget())
            .arbitraryRule(b.getTarget())
            .library(target, a, b)
            .build();

    ImmutableMap<SourcePath, JsFileDev> fileRules =
        findJsFileRules(scenario.graphBuilder)
            .filter(JsFileDev.class)
            .collect(ImmutableMap.toImmutableMap(JsFileDev::getSource, Function.identity()));

    assertThat(a.getTarget(), in(getBuildDepsAsTargets(fileRules.get(a))));
    assertThat(b.getTarget(), not(in(getBuildDepsAsTargets(fileRules.get(a)))));
    assertThat(b.getTarget(), in(getBuildDepsAsTargets(fileRules.get(b))));
    assertThat(a.getTarget(), not(in(getBuildDepsAsTargets(fileRules.get(b)))));
  }

  @Test
  public void subBasePathForSourceFiles() {
    String basePath = "base/path";
    String filePath = String.format("%s/sub/file.js", targetDirectory);
    JsTestScenario scenario = buildScenario(basePath, FakeSourcePath.of(filePath));

    assertEquals(
        "arbitrary/path/base/path/sub/file.js",
        findFirstJsFileDevRule(scenario.graphBuilder).getVirtualPath().get());
  }

  @Test
  public void relativeBasePathForSourceFiles() {
    String basePath = "../base/path";
    String filePath = String.format("%s/sub/file.js", targetDirectory);
    JsTestScenario scenario = buildScenario(basePath, FakeSourcePath.of(filePath));

    assertEquals(
        "arbitrary/base/path/sub/file.js",
        findFirstJsFileDevRule(scenario.graphBuilder).getVirtualPath().get());
  }

  @Test
  public void basePathReplacesBuildTargetSourcePath() {
    String basePath = "base/path.js";
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    scenarioBuilder.arbitraryRule(target);
    JsTestScenario scenario = buildScenario(basePath, DefaultBuildTargetSourcePath.of(target));

    assertEquals(
        "arbitrary/path/base/path.js",
        findFirstJsFileDevRule(scenario.graphBuilder).getVirtualPath().get());
  }

  @Test
  public void relativeBasePathReplacesBuildTargetSourcePath() {
    String basePath = "../path.js";
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    scenarioBuilder.arbitraryRule(target);
    JsTestScenario scenario = buildScenario(basePath, DefaultBuildTargetSourcePath.of(target));

    assertEquals(
        "arbitrary/path.js", findFirstJsFileDevRule(scenario.graphBuilder).getVirtualPath().get());
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
        findFirstJsFileDevRule(scenario.graphBuilder).getVirtualPath().get());
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

    findJsFileRules(scenario.graphBuilder)
        .map(JsFile::getBuildTarget)
        .collect(
            countAssertions(
                depTarget ->
                    assertThat(
                        String.format(
                            "JsFile dependency `%s` of JsLibrary `%s` must have flavors `%s`",
                            depTarget, target, flavors),
                        flavors,
                        everyItem(in(depTarget.getFlavors())))));
  }

  @Test
  public void doesNotpropagatePlatformFlavorsWithoutRelease() {
    UserFlavor platformFlavor = JsFlavors.ANDROID;
    BuildTarget withPlatformFlavor = target.withFlavors(platformFlavor);
    JsTestScenario scenario =
        scenarioBuilder
            .library(withPlatformFlavor, FakeSourcePath.of("apples"), FakeSourcePath.of("pears"))
            .build();

    findJsFileRules(scenario.graphBuilder)
        .map(JsFile::getBuildTarget)
        .collect(
            countAssertions(
                fileTarget ->
                    assertFalse(
                        String.format(
                            "JsFile dependency `%s` of JsLibrary `%s` must not have flavor `%s`",
                            fileTarget, withPlatformFlavor, platformFlavor),
                        fileTarget.getFlavors().contains(platformFlavor))));
  }

  @Test
  public void supportsDepsQueryAllPlatforms() {
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
            .bundleWithDeps(l, a, c)
            .bundleWithDeps(x, l)
            .bundleWithDeps(BuildTargetFactory.newInstance("//query-deps:bundle"))
            .library(
                target,
                Query.of(String.format("deps(%s)", x)),
                FakeSourcePath.of("arbitrary/source"))
            .build();

    TargetNode<?> node = scenario.targetGraph.get(target);
    assertThat(x, in(node.getBuildDeps()));

    JsLibrary lib = scenario.graphBuilder.getRuleWithType(target, JsLibrary.class);
    ImmutableSortedSet<BuildRule> deps =
        scenario.graphBuilder.getAllRules(ImmutableList.of(a, b, c));
    assertThat(deps, everyItem(in(lib.getBuildDeps())));
    assertEquals(
        deps.stream()
            .map(BuildRule::getSourcePathToOutput)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())),
        lib.getLibraryDependencies());

    assertThat(deps, everyItem(not(in(internalFileRule(scenario.graphBuilder).getBuildDeps()))));
    findJsFileRules(scenario.graphBuilder)
        .collect(
            countAssertions(jsFile -> assertThat(deps, everyItem(not(in(jsFile.getBuildDeps()))))));
  }

  @Test
  public void supportsDepsQueryAppleToolchain() {
    assumeFalse(
        "Only for Apple OS. Invokes the AppleLibraryDescription",
        Platform.detect() == Platform.WINDOWS);
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
            .library(
                target,
                Query.of(String.format("deps(%s)", x)),
                FakeSourcePath.of("arbitrary/source"))
            .build();

    TargetNode<?> node = scenario.targetGraph.get(target);
    assertThat(x, in(node.getBuildDeps()));

    JsLibrary lib = scenario.graphBuilder.getRuleWithType(target, JsLibrary.class);
    ImmutableSortedSet<BuildRule> deps =
        scenario.graphBuilder.getAllRules(ImmutableList.of(a, b, c));
    assertThat(deps, everyItem(in(lib.getBuildDeps())));
    assertEquals(
        deps.stream()
            .map(BuildRule::getSourcePathToOutput)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())),
        lib.getLibraryDependencies());

    assertThat(deps, everyItem(not(in(internalFileRule(scenario.graphBuilder).getBuildDeps()))));
    findJsFileRules(scenario.graphBuilder)
        .collect(
            countAssertions(jsFile -> assertThat(deps, everyItem(not(in(jsFile.getBuildDeps()))))));
  }

  @Test
  public void supportsDepsAndDepsQuery() {
    BuildTarget a = BuildTargetFactory.newInstance("//query:dep");
    BuildTarget b = BuildTargetFactory.newInstance("//direct:dep");

    JsTestScenario scenario =
        scenarioBuilder.library(a).library(b).library(target, Query.of(a.toString()), b).build();

    JsLibrary lib = scenario.graphBuilder.getRuleWithType(target, JsLibrary.class);
    ImmutableSortedSet<BuildRule> deps = scenario.graphBuilder.getAllRules(ImmutableList.of(a, b));
    assertThat(deps, everyItem(in(lib.getBuildDeps())));
    assertEquals(
        deps.stream()
            .map(BuildRule::getSourcePathToOutput)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())),
        lib.getLibraryDependencies());
  }

  @Test
  public void libraryRulesDoesNotDependOnGeneratedSources() {
    BuildTarget a = BuildTargetFactory.newInstance("//:node_modules");
    BuildTarget b = BuildTargetFactory.newInstance("//generated:dep");
    scenarioBuilder.arbitraryRule(a).arbitraryRule(b);
    JsTestScenario scenario =
        buildScenario(
            ".",
            new Pair<>(DefaultBuildTargetSourcePath.of(a), "node_modules/left-pad/index.js"),
            new Pair<>(DefaultBuildTargetSourcePath.of(b), "generated.png"));

    ImmutableSortedSet<BuildRule> generatedSourcesRules =
        scenario.graphBuilder.getAllRules(ImmutableList.of(a, b));

    BuildRule filesRule =
        scenario.graphBuilder.getRule(target.withAppendedFlavors(JsFlavors.LIBRARY_FILES));
    assertThat(generatedSourcesRules, everyItem(not(in(filesRule.getBuildDeps()))));

    BuildRule libRule = scenario.graphBuilder.getRule(target);
    assertThat(generatedSourcesRules, everyItem(not(in(libRule.getBuildDeps()))));
  }

  @Test
  public void locationMacrosInExtraJsonAddBuildDeps() {
    BuildTarget referencedTarget = BuildTargetFactory.newInstance("//:ref");
    JsTestScenario scenario =
        scenarioBuilder
            .arbitraryRule(referencedTarget)
            .library(
                target,
                ImmutableList.of(FakeSourcePath.of("a/file"), FakeSourcePath.of("another/file")),
                "[\"%s\"]",
                LocationMacro.of(referencedTarget))
            .build();

    BuildRule referenced = scenario.graphBuilder.getRule(referencedTarget);

    assertThat(referenced, in(scenario.graphBuilder.getRule(target).getBuildDeps()));

    RichStream<JsFile> jsFileRules = findJsFileRules(scenario.graphBuilder);
    jsFileRules.collect(
        countAssertions(jsFile -> assertThat(referenced, in(jsFile.getBuildDeps()))));
  }

  private JsTestScenario buildScenario(String basePath, SourcePath source) {
    return scenarioBuilder.library(target, basePath, source).build();
  }

  private JsTestScenario buildScenario(String basePath, Pair<SourcePath, String>... sources) {
    return scenarioBuilder.library(target, basePath, sources).build();
  }

  private RichStream<JsFile> findJsFileRules(ActionGraphBuilder graphBuilder) {
    return RichStream.from(internalFileRule(graphBuilder).getBuildDeps()).filter(JsFile.class);
  }

  private JsFile.JsFileDev findFirstJsFileDevRule(ActionGraphBuilder graphBuilder) {
    return findJsFileRules(graphBuilder).filter(JsFileDev.class).findFirst().get();
  }

  private JsLibrary.Files internalFileRule(ActionGraphBuilder graphBuilder) {
    return (Files) graphBuilder.requireRule(target.withAppendedFlavors(JsFlavors.LIBRARY_FILES));
  }

  private static ImmutableList<BuildTarget> getBuildDepsAsTargets(BuildRule buildRule) {
    return buildRule
        .getBuildDeps()
        .stream()
        .map(BuildRule::getBuildTarget)
        .collect(ImmutableList.toImmutableList());
  }

  private static <T> Collector<T, AtomicLong, Long> countAssertions(Consumer<T> assertion) {
    /**
     * Collects a stream by running the passed-in assertion on all stream items. The collection also
     * asserts that the stream is non-empty, to avoid false positives when accidentally producing
     * empty streams, e.g. by filtering.
     */
    return Collector.of(
        AtomicLong::new,
        (count, t) -> {
          count.incrementAndGet();
          assertion.accept(t);
        },
        (a, b) -> new AtomicLong(a.get() + b.get()),
        count -> {
          long value = count.get();
          if (value == 0) {
            throw new IllegalStateException("Stream was empty, did not assert anything.");
          }
          return value;
        },
        Characteristics.UNORDERED);
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
