/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.parser;

import static com.facebook.buck.parser.RawRulePredicates.alwaysFalse;
import static com.facebook.buck.parser.RawRulePredicates.alwaysTrue;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.json.ProjectBuildFileParser;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleBuilder;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Files;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ParserTest {

  private File testBuildFile;
  private Parser testParser;
  private KnownBuildRuleTypes buildRuleTypes;
  private ProjectBuildFileParser buildFileParser;
  private ProjectFilesystem filesystem;

  @Rule
  public TemporaryFolder tempDir = new TemporaryFolder();

  @Before
  public void setUp() throws IOException {
    tempDir.newFolder("java", "com", "facebook");

    testBuildFile = tempDir.newFile(
        "java/com/facebook/" + BuckConstant.BUILD_RULES_FILE_NAME);
    Files.write(
        "java_library(name = 'foo')\n" +
        "java_library(name = 'bar')\n",
        testBuildFile,
        Charsets.UTF_8);
    tempDir.newFile("bar.py");

    // Create a temp directory with some build files.
    File root = tempDir.getRoot();
    filesystem = new ProjectFilesystem(root);

    buildRuleTypes = new KnownBuildRuleTypes();
    buildFileParser = new ProjectBuildFileParser(filesystem.getIgnorePaths());
    testParser = createParser(emptyBuildTargets(), buildFileParser);
  }

  private Parser createParser(Map<BuildTarget, BuildRuleBuilder<?>> knownBuildTargets,
      ProjectBuildFileParser buildFileParser) {
    return new Parser(
        filesystem,
        buildRuleTypes,
        new TestConsole(),
        new BuildFileTree(ImmutableSet.<BuildTarget>of()),
        new BuildTargetParser(filesystem),
        knownBuildTargets,
        buildFileParser);
  }

  /**
   * If a rule contains an erroneous dep to a non-existent rule, then it should throw an
   * appropriate message to help the user find the source of his error.
   */
  @Test
  public void testParseRawRulesWithBadDependency() throws NoSuchBuildTargetException, IOException {
    String nonExistentBuildTarget = "//testdata/com/facebook/feed:util";
    Map<String, Object> rawRule = ImmutableMap.<String, Object>of(
        "type", "java_library",
        "name", "feed",
        // A non-existent dependency: this is a user error that should be reported.
        "deps", ImmutableList.of(nonExistentBuildTarget),
        "buck_base_path", "testdata/com/facebook/feed/model");
    List<Map<String, Object>> ruleObjects = ImmutableList.of(rawRule);

    Parser parser = new Parser(
        new ProjectFilesystem(new File(".")),
        new KnownBuildRuleTypes(),
        new TestConsole());

    parser.parseRawRulesInternal(ruleObjects, null);
    RawRulePredicate predicate = alwaysTrue();
    List<BuildTarget> targets = parser.filterTargets(predicate);
    BuildTarget expectedBuildTarget = new BuildTarget(
        new File("./testdata/com/facebook/feed/model/" + BuckConstant.BUILD_RULES_FILE_NAME),
        "//testdata/com/facebook/feed/model",
        "feed");
    assertEquals(ImmutableList.of(expectedBuildTarget), targets);

    try {
      parser.findAllTransitiveDependencies(targets, ImmutableList.<String>of());
      fail("Should have thrown a HumanReadableException.");
    } catch (HumanReadableException e) {
      assertEquals(
          String.format("No rule found when resolving target %s in build file " +
              "//testdata/com/facebook/feed/BUCK", nonExistentBuildTarget),
          e.getHumanReadableErrorMessage());
    }
  }

  /**
   * Creates the following graph (assume all / and \ indicate downward pointing arrows):
   * <pre>
   *         A
   *       /   \
   *     B       C <----|
   *   /   \   /        |
   * D       E          |
   *   \   /            |
   *     F --------------
   * </pre>
   * Note that there is a circular dependency from C -> E -> F -> C that should be caught by the
   * parser.
   */
  @Test
  public void testCircularDependencyDetection() throws IOException, NoSuchBuildTargetException {
    // Mock out objects that are not critical to parsing.
    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);
    expect(projectFilesystem.getProjectRoot()).andReturn(new File("."));

    BuildTargetParser buildTargetParser = new BuildTargetParser(projectFilesystem) {
      @Override
      public BuildTarget parse(String buildTargetName, ParseContext parseContext)
          throws NoSuchBuildTargetException {
        return BuildTargetFactory.newInstance(buildTargetName);
      }
    };

    BuildFileTree buildFiles = createMock(BuildFileTree.class);
    replay(projectFilesystem, buildFiles);

    // Create the set of known build targets so the Parser does not have to exercise its parsing
    // logic, only its graph traversal algorithms.
    Parser parser = new Parser(projectFilesystem,
        buildRuleTypes,
        new TestConsole(),
        buildFiles,
        buildTargetParser,
        circularBuildTargets(),
        buildFileParser);

    BuildTarget rootNode = BuildTargetFactory.newInstance("//:A");
    Iterable<BuildTarget> buildTargets = ImmutableSet.of(rootNode);
    Iterable<String> defaultIncludes = ImmutableList.of();
    try {
      parser.findAllTransitiveDependencies(buildTargets, defaultIncludes);
      fail("Should have thrown a HumanReadableException.");
    } catch (HumanReadableException e) {
      assertEquals("Cycle found: //:C -> //:E -> //:F -> //:C", e.getMessage());
    }

    verify(projectFilesystem, buildFiles);
  }

  @Test
  public void testParseBuildFilesForTargetsWithOverlappingTargets()
      throws IOException, NoSuchBuildTargetException {
    // Execute parseBuildFilesForTargets() with multiple targets that require parsing the same
    // build file.
    BuildTarget fooTarget = BuildTargetFactory.newInstance("//java/com/facebook",
        "foo",
        testBuildFile);
    BuildTarget barTarget = BuildTargetFactory.newInstance("//java/com/facebook",
        "bar",
        testBuildFile);
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget, barTarget);
    Iterable<String> defaultIncludes = ImmutableList.of();

    // The EventBus should be updated with events indicating how parsing ran.
    BuckEventBus eventBus = new BuckEventBus();
    BuckEventListener listener = new BuckEventListener();
    eventBus.getEventBus().register(listener);

    DependencyGraph graph = testParser.parseBuildFilesForTargets(buildTargets,
        defaultIncludes,
        eventBus);
    BuildRule fooRule = graph.findBuildRuleByTarget(fooTarget);
    assertNotNull(fooRule);
    BuildRule barRule = graph.findBuildRuleByTarget(barTarget);
    assertNotNull(barRule);

    ImmutableList<ParseEvent> expected = ImmutableList.of(
        ParseEvent.started(buildTargets),
        ParseEvent.finished(buildTargets));

    Iterable<ParseEvent> events = Iterables.filter(listener.getEvents(), ParseEvent.class);
    assertEquals(expected, ImmutableList.copyOf(events));
  }

  @Test
  public void testMissingBuildRuleInValidFile() throws IOException, NoSuchBuildTargetException {
    // Execute parseBuildFilesForTargets() with a target in a valid file but a bad rule name.
    BuildTarget fooTarget = BuildTargetFactory.newInstance("//java/com/facebook",
        "foo",
        testBuildFile);
    BuildTarget razTarget = BuildTargetFactory.newInstance("//java/com/facebook",
        "raz",
        testBuildFile);
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget, razTarget);
    Iterable<String> defaultIncludes = ImmutableList.of();

    try {
      testParser.parseBuildFilesForTargets(buildTargets, defaultIncludes, new BuckEventBus());
      fail("HumanReadableException should be thrown");
    } catch (HumanReadableException e) {
      assertEquals("No rule found when resolving target //java/com/facebook:raz in build file " +
                   "//java/com/facebook/BUCK",
          e.getHumanReadableErrorMessage());
    }
  }


  @Test
  public void testInvalidDepFromValidFile() throws IOException, NoSuchBuildTargetException {
    // Execute parseBuildFilesForTargets() with a target in a valid file but a bad rule name.
    tempDir.newFolder("java", "com", "facebook", "invalid");

    File testInvalidBuildFile = tempDir.newFile(
        "java/com/facebook/invalid/" + BuckConstant.BUILD_RULES_FILE_NAME);
    Files.write(
        "java_library(name = 'foo', deps = ['//java/com/facebook/invalid/lib:missing_rule'])\n" +
        "java_library(name = 'bar')\n",
        testInvalidBuildFile,
        Charsets.UTF_8);

    tempDir.newFolder("java", "com", "facebook", "invalid", "lib");
    tempDir.newFile(
        "java/com/facebook/invalid/lib/" + BuckConstant.BUILD_RULES_FILE_NAME);

    BuildTarget fooTarget = BuildTargetFactory.newInstance("//java/com/facebook/invalid",
        "foo",
        testInvalidBuildFile);
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget);
    Iterable<String> defaultIncludes = ImmutableList.of();

    try {
      testParser.parseBuildFilesForTargets(buildTargets, defaultIncludes, new BuckEventBus());
      fail("HumanReadableException should be thrown");
    } catch (HumanReadableException e) {
      assertEquals("No rule found when resolving target " +
          "//java/com/facebook/invalid/lib:missing_rule in build file " +
          "//java/com/facebook/invalid/lib/BUCK",
          e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void whenAllRulesRequestedWithTrueFilterThenMultipleRulesReturned()
      throws IOException, NoSuchBuildTargetException {
    List<BuildTarget> targets = testParser.filterAllTargetsInProject(filesystem,
        Lists.<String>newArrayList(),
        alwaysTrue());

    List<BuildTarget> expectedTargets = ImmutableList.of(
        BuildTargetFactory.newInstance("//java/com/facebook", "foo", testBuildFile),
        BuildTargetFactory.newInstance("//java/com/facebook", "bar", testBuildFile));
    assertEquals("Should have returned all rules.", expectedTargets, targets);
  }

  @Test
  public void whenAllRulesRequestedWithFalseFilterThenNoRulesReturned()
      throws IOException, NoSuchBuildTargetException {
    List<BuildTarget> targets = testParser.filterAllTargetsInProject(filesystem,
        Lists.<String>newArrayList(), alwaysFalse());

    assertEquals("Should have returned no rules.", 0, targets.size());
  }

  @Test
  public void whenAllRulesAreRequestedMultipleTimesThenRulesAreOnlyParsedOnce()
      throws IOException, NoSuchBuildTargetException {
    TestProjectBuildFileParser buildFileParser = new TestProjectBuildFileParser();
    Parser parser = createParser(emptyBuildTargets(), buildFileParser);

    parser.filterAllTargetsInProject(filesystem, Lists.<String>newArrayList(), alwaysTrue());
    parser.filterAllTargetsInProject(filesystem, Lists.<String>newArrayList(), alwaysTrue());

    assertEquals("Should have cached build rules.", 1, buildFileParser.calls);
  }

  @Test
  @SuppressWarnings("unchecked") // Needed to mock generic WatchEvent class.
  public void whenNotifiedOfNonPathEventThenCacheRulesAreInvalidated()
      throws IOException, NoSuchBuildTargetException {
    TestProjectBuildFileParser buildFileParser = new TestProjectBuildFileParser();
    Parser parser = createParser(emptyBuildTargets(), buildFileParser);

    parser.filterAllTargetsInProject(filesystem, Lists.<String>newArrayList(), alwaysTrue());
    WatchEvent<Object> event = createMock(WatchEvent.class);
    expect(event.kind()).andReturn(StandardWatchEventKinds.OVERFLOW).anyTimes();
    replay(event);
    parser.onFileSystemChange(event);
    parser.filterAllTargetsInProject(filesystem, Lists.<String>newArrayList(), alwaysTrue());

    verify(event);
    assertEquals("Should have invalidated cache.", 2, buildFileParser.calls);
  }

  @Test
  @SuppressWarnings("unchecked") // Needed to mock generic WatchEvent class.
  public void whenNotifiedOfNonSourcePathEventThenCacheRulesAreInvalidated()
      throws IOException, NoSuchBuildTargetException {
    TestProjectBuildFileParser buildFileParser = new TestProjectBuildFileParser();
    Parser parser = createParser(emptyBuildTargets(), buildFileParser);

    parser.filterAllTargetsInProject(filesystem, Lists.<String>newArrayList(), alwaysTrue());
    WatchEvent<Path> event = createMock(WatchEvent.class);
    expect(event.kind()).andReturn(StandardWatchEventKinds.ENTRY_MODIFY).anyTimes();
    expect(event.context()).andReturn(new File("./BUCK").toPath());
    replay(event);
    parser.onFileSystemChange(event);
    parser.filterAllTargetsInProject(filesystem, Lists.<String>newArrayList(), alwaysTrue());

    verify(event);
    assertEquals("Should have invalidated cache.", 2, buildFileParser.calls);
  }

  @Test
  @SuppressWarnings("unchecked") // Needed to mock generic WatchEvent class.
  public void whenNotifiedOfSourcePathEventThenCacheRulesAreNotInvalidated()
      throws IOException, NoSuchBuildTargetException {
    TestProjectBuildFileParser buildFileParser = new TestProjectBuildFileParser();
    Parser parser = createParser(emptyBuildTargets(), buildFileParser);

    parser.filterAllTargetsInProject(filesystem, Lists.<String>newArrayList(), alwaysTrue());
    WatchEvent<Path> event = createMock(WatchEvent.class);
    expect(event.kind()).andReturn(StandardWatchEventKinds.ENTRY_MODIFY).anyTimes();
    expect(event.context()).andReturn(new File("./SomeClass.java").toPath());
    replay(event);
    parser.onFileSystemChange(event);
    parser.filterAllTargetsInProject(filesystem, Lists.<String>newArrayList(), alwaysTrue());

    verify(event);
    assertEquals("Should have cached build rules.", 1, buildFileParser.calls);
  }

  @Test
  public void whenAllRulesAreRequestedWithDifferingIncludesThenRulesAreParsedTwice()
      throws IOException, NoSuchBuildTargetException {
    TestProjectBuildFileParser buildFileParser = new TestProjectBuildFileParser();
    Parser parser = createParser(emptyBuildTargets(), buildFileParser);

    parser.filterAllTargetsInProject(filesystem, Lists.<String>newArrayList(), alwaysTrue());
    parser.filterAllTargetsInProject(filesystem, ImmutableList.of("//bar.py"), alwaysTrue());

    assertEquals("Should have invalidated cache.", 2, buildFileParser.calls);
  }

  @Test
  public void whenAllRulesThenSingleTargetRequestedThenRulesAreParsedOnce()
      throws IOException, NoSuchBuildTargetException {
    TestProjectBuildFileParser buildFileParser = new TestProjectBuildFileParser();
    Parser parser = createParser(emptyBuildTargets(), buildFileParser);

    parser.filterAllTargetsInProject(filesystem, Lists.<String>newArrayList(), alwaysTrue());
    BuildTarget foo = BuildTargetFactory.newInstance("//java/com/facebook", "foo", testBuildFile);
    parser.parseBuildFilesForTargets(ImmutableList.of(foo),
        Lists.<String>newArrayList(),
        new BuckEventBus());

    assertEquals("Should have cached build rules.", 1, buildFileParser.calls);
  }

  @Test
  public void whenSingleTargetThenAllRulesRequestedThenRulesAreParsedTwice()
      throws IOException, NoSuchBuildTargetException {
    TestProjectBuildFileParser buildFileParser = new TestProjectBuildFileParser();
    Parser parser = createParser(emptyBuildTargets(), buildFileParser);

    BuildTarget foo = BuildTargetFactory.newInstance("//java/com/facebook", "foo", testBuildFile);
    parser.parseBuildFilesForTargets(ImmutableList.of(foo),
        Lists.<String>newArrayList(),
        new BuckEventBus());
    parser.filterAllTargetsInProject(filesystem, Lists.<String>newArrayList(), alwaysTrue());

    assertEquals("Should have replaced build rules", 2, buildFileParser.calls);
  }

  private Map<BuildTarget, BuildRuleBuilder<?>> emptyBuildTargets() {
    return Maps.newHashMap();
  }

  private Map<BuildTarget, BuildRuleBuilder<?>> circularBuildTargets() {
    return ImmutableMap.<BuildTarget, BuildRuleBuilder<?>>builder()
        .put(BuildTargetFactory.newInstance("//:A"), createBuildRuleBuilder("A", "B", "C"))
        .put(BuildTargetFactory.newInstance("//:B"), createBuildRuleBuilder("B", "D", "E"))
        .put(BuildTargetFactory.newInstance("//:C"), createBuildRuleBuilder("C", "E"))
        .put(BuildTargetFactory.newInstance("//:D"), createBuildRuleBuilder("D", "F"))
        .put(BuildTargetFactory.newInstance("//:E"), createBuildRuleBuilder("E", "F"))
        .put(BuildTargetFactory.newInstance("//:F"), createBuildRuleBuilder("F", "C"))
        .build();
  }

  private static BuildRuleBuilder<?> createBuildRuleBuilder(String name, String... qualifiedDeps) {
    final BuildTarget buildTarget = BuildTargetFactory.newInstance("//:" + name);
    ImmutableSortedSet.Builder<BuildTarget> depsBuilder = ImmutableSortedSet.naturalOrder();
    for (String dep : qualifiedDeps) {
      depsBuilder.add(BuildTargetFactory.newInstance("//:" + dep));
    }
    final ImmutableSortedSet<BuildTarget> deps = depsBuilder.build();

    return new BuildRuleBuilder<BuildRule>() {

      @Override
      public BuildTarget getBuildTarget() {
        return buildTarget;
      }

      @Override
      public Set<BuildTarget> getDeps() {
        return deps;
      }

      @Override
      public Set<BuildTargetPattern> getVisibilityPatterns() {
        return ImmutableSet.of();
      }

      @Override
      public BuildRule build(final BuildRuleResolver ruleResolver) {
        return new FakeBuildRule(
            BuildRuleType.JAVA_LIBRARY,
            buildTarget,
            ImmutableSortedSet.<BuildRule>naturalOrder()
              .addAll(Iterables.transform(deps, new Function<BuildTarget, BuildRule>() {
                @Override
                public BuildRule apply(BuildTarget target) {
                  return ruleResolver.get(target);
                }
              }))
              .build(),
              ImmutableSet.<BuildTargetPattern>of());
      }
    };
  }

  /**
   * ProjectBuildFileParser test double which counts the number of times rules are parsed to test
   * caching logic in Parser.
   */
  private class TestProjectBuildFileParser extends ProjectBuildFileParser {

    public int calls = 0;

    public TestProjectBuildFileParser() {
      super(ImmutableSet.<String>of());
    }

    @Override
    public List<Map<String, Object>> getAllRules(String rootPath, Optional<String> buildFile,
                                                 Iterable<String> includes) throws IOException {
      calls += 1;
      return super.getAllRules(rootPath, buildFile, includes);
    }
  }
}
