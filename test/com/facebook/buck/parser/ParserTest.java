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
import static com.facebook.buck.testutil.WatchEvents.createPathEvent;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.FakeBuckEventListener;
import com.facebook.buck.event.TestEventConfigerator;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.json.DefaultProjectBuildFileParserFactory;
import com.facebook.buck.json.ProjectBuildFileParser;
import com.facebook.buck.json.ProjectBuildFileParserFactory;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleBuilder;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.testutil.BuckTestConstant;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.WatchEvents;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.google.common.io.InputSupplier;

import org.easymock.EasyMockSupport;
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
import java.util.regex.Pattern;

public class ParserTest extends EasyMockSupport {

  private File testBuildFile;
  private File includedByBuildFile;
  private File includedByIncludeFile;
  private File defaultIncludeFile;
  private Parser testParser;
  private KnownBuildRuleTypes buildRuleTypes;
  private ProjectFilesystem filesystem;

  @Rule
  public TemporaryFolder tempDir = new TemporaryFolder();
  private ImmutableSet<Pattern> tempFilePatterns = ImmutableSet.of(Pattern.compile(".*\\.swp$"));

  @Before
  public void setUp() throws IOException {
    tempDir.newFolder("java", "com", "facebook");

    defaultIncludeFile = tempDir.newFile(
        "java/com/facebook/defaultIncludeFile");
    Files.write(
        "\n",
        defaultIncludeFile,
        Charsets.UTF_8);

    includedByIncludeFile = tempDir.newFile(
        "java/com/facebook/includedByIncludeFile");
    Files.write(
        "\n",
        includedByIncludeFile,
        Charsets.UTF_8);

    includedByBuildFile = tempDir.newFile(
        "java/com/facebook/includedByBuildFile");
    Files.write(
        "include_defs('//java/com/facebook/includedByIncludeFile')\n",
        includedByBuildFile,
        Charsets.UTF_8);

    testBuildFile = tempDir.newFile(
        "java/com/facebook/" + BuckConstant.BUILD_RULES_FILE_NAME);
    Files.write(
        "include_defs('//java/com/facebook/includedByBuildFile')\n" +
        "java_library(name = 'foo')\n" +
        "java_library(name = 'bar')\n",
        testBuildFile,
        Charsets.UTF_8);

    tempDir.newFile("bar.py");

    // Create a temp directory with some build files.
    File root = tempDir.getRoot();
    filesystem = new ProjectFilesystem(root);

    buildRuleTypes = new KnownBuildRuleTypes();
    DefaultProjectBuildFileParserFactory testBuildFileParserFactory =
        new DefaultProjectBuildFileParserFactory(
            filesystem,
            BuckTestConstant.PYTHON_INTERPRETER,
            buildRuleTypes.getAllDescriptions());
    testParser = createParser(emptyBuildTargets(), testBuildFileParserFactory);
  }

  private ProjectBuildFileParserFactory createDoNothingBuildFileParserFactory()
      throws BuildFileParseException {
    final ProjectBuildFileParser mockBuildFileParser = createMock(ProjectBuildFileParser.class);
    mockBuildFileParser.close();
    expectLastCall().anyTimes();

    return new ProjectBuildFileParserFactory() {
      @Override
      public ProjectBuildFileParser createParser(Iterable<String> commonIncludes) {
        return mockBuildFileParser;
      }
    };
  }

  private Parser createParser(Map<BuildTarget, BuildRuleBuilder<?>> knownBuildTargets)
      throws IOException {
    return createParser(
        ofInstance(BuildFileTree.constructBuildFileTree(filesystem)),
        knownBuildTargets,
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes),
        new BuildTargetParser(filesystem));
  }

  private Parser createParser(Map<BuildTarget,
      BuildRuleBuilder<?>> knownBuildTargets,
      ProjectBuildFileParserFactory buildFileParserFactory) throws IOException {
    return createParser(
        ofInstance(BuildFileTree.constructBuildFileTree(filesystem)),
        knownBuildTargets,
        buildFileParserFactory,
        new BuildTargetParser(filesystem));
  }

  private Parser createParser(InputSupplier<BuildFileTree> buildFileTreeSupplier,
    Map<BuildTarget, BuildRuleBuilder<?>> knownBuildTargets,
    ProjectBuildFileParserFactory buildFileParserFactory,
    BuildTargetParser buildTargetParser) {
    return new Parser(
        filesystem,
        buildRuleTypes,
        new TestConsole(),
        buildFileTreeSupplier,
        buildTargetParser,
        knownBuildTargets,
        buildFileParserFactory,
        tempFilePatterns,
        new FakeRuleKeyBuilderFactory());
  }

  /**
   * If a rule contains an erroneous dep to a non-existent rule, then it should throw an
   * appropriate message to help the user find the source of his error.
   */
  @Test
  public void testParseRawRulesWithBadDependency()
      throws BuildTargetException, BuildFileParseException, IOException {
    String nonExistentBuildTarget = "//testdata/com/facebook/feed:util";
    Map<String, Object> rawRule = ImmutableMap.<String, Object>of(
        "type", "java_library",
        "name", "feed",
        // A non-existent dependency: this is a user error that should be reported.
        "deps", ImmutableList.of(nonExistentBuildTarget),
        "buck.base_path", "testdata/com/facebook/feed/model");
    List<Map<String, Object>> ruleObjects = ImmutableList.of(rawRule);

    Parser parser = new Parser(
        new ProjectFilesystem(new File(".")),
        new KnownBuildRuleTypes(),
        new TestConsole(),
        BuckTestConstant.PYTHON_INTERPRETER,
        tempFilePatterns,
        new FakeRuleKeyBuilderFactory());

    parser.parseRawRulesInternal(ruleObjects);
    RawRulePredicate predicate = alwaysTrue();
    List<BuildTarget> targets = parser.filterTargets(predicate);
    BuildTarget expectedBuildTarget = new BuildTarget(
        "//testdata/com/facebook/feed/model",
        "feed");
    assertEquals(ImmutableList.of(expectedBuildTarget), targets);

    try {
      parser.onlyUseThisWhenTestingToFindAllTransitiveDependencies(
          targets, ImmutableList.<String>of());
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
  public void testCircularDependencyDetection()
      throws BuildFileParseException, BuildTargetException, IOException {
    // Mock out objects that are not critical to parsing.
    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);
    BuildTargetParser buildTargetParser = new BuildTargetParser(projectFilesystem) {
      @Override
      public BuildTarget parse(String buildTargetName, ParseContext parseContext)
          throws NoSuchBuildTargetException {
        return BuildTargetFactory.newInstance(buildTargetName);
      }
    };
    final BuildFileTree buildFiles = createMock(BuildFileTree.class);

    Parser parser = createParser(
        ofInstance(buildFiles),
        circularBuildTargets(),
        createDoNothingBuildFileParserFactory(),
        buildTargetParser);

    replayAll();

    BuildTarget rootNode = BuildTargetFactory.newInstance("//:A");
    Iterable<BuildTarget> buildTargets = ImmutableSet.of(rootNode);
    Iterable<String> defaultIncludes = ImmutableList.of();
    try {
      parser.onlyUseThisWhenTestingToFindAllTransitiveDependencies(buildTargets, defaultIncludes);
      fail("Should have thrown a HumanReadableException.");
    } catch (HumanReadableException e) {
      assertEquals("Cycle found: //:F -> //:C -> //:E -> //:F", e.getMessage());
    }

    verifyAll();
  }

  @Test
  public void testParseBuildFilesForTargetsWithOverlappingTargets()
      throws BuildFileParseException, BuildTargetException, IOException {
    // Execute parseBuildFilesForTargets() with multiple targets that require parsing the same
    // build file.
    BuildTarget fooTarget = new BuildTarget("//java/com/facebook", "foo");
    BuildTarget barTarget = new BuildTarget("//java/com/facebook", "bar");
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget, barTarget);
    Iterable<String> defaultIncludes = ImmutableList.of();

    // The EventBus should be updated with events indicating how parsing ran.
    BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    FakeBuckEventListener listener = new FakeBuckEventListener();
    eventBus.register(listener);

    DependencyGraph graph = testParser.parseBuildFilesForTargets(buildTargets,
        defaultIncludes,
        eventBus);
    BuildRule fooRule = graph.findBuildRuleByTarget(fooTarget);
    assertNotNull(fooRule);
    BuildRule barRule = graph.findBuildRuleByTarget(barTarget);
    assertNotNull(barRule);

    ImmutableList<ParseEvent> expected = ImmutableList.of(
        TestEventConfigerator.configureTestEvent(ParseEvent.started(buildTargets), eventBus),
        TestEventConfigerator.configureTestEvent(ParseEvent.finished(buildTargets,
            Optional.of(graph)),
            eventBus));

    Iterable<ParseEvent> events = Iterables.filter(listener.getEvents(), ParseEvent.class);
    assertEquals(expected, ImmutableList.copyOf(events));
  }

  @Test
  public void testMissingBuildRuleInValidFile()
      throws BuildFileParseException, BuildTargetException, IOException {
    // Execute parseBuildFilesForTargets() with a target in a valid file but a bad rule name.
    BuildTarget fooTarget = new BuildTarget("//java/com/facebook", "foo");
    BuildTarget razTarget = new BuildTarget("//java/com/facebook", "raz");
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget, razTarget);
    Iterable<String> defaultIncludes = ImmutableList.of();

    try {
      testParser.parseBuildFilesForTargets(buildTargets,
          defaultIncludes,
          BuckEventBusFactory.newInstance());
      fail("HumanReadableException should be thrown");
    } catch (HumanReadableException e) {
      assertEquals("No rule found when resolving target //java/com/facebook:raz in build file " +
                   "//java/com/facebook/BUCK",
          e.getHumanReadableErrorMessage());
    }
  }


  @Test
  public void testInvalidDepFromValidFile()
      throws IOException, BuildFileParseException, BuildTargetException {
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

    BuildTarget fooTarget = new BuildTarget("//java/com/facebook/invalid", "foo");
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget);
    Iterable<String> defaultIncludes = ImmutableList.of();

    try {
      testParser.parseBuildFilesForTargets(buildTargets, defaultIncludes, BuckEventBusFactory.newInstance());
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
      throws BuildFileParseException, BuildTargetException, IOException {
    List<BuildTarget> targets = testParser.filterAllTargetsInProject(filesystem,
        Lists.<String>newArrayList(),
        alwaysTrue());

    List<BuildTarget> expectedTargets = ImmutableList.of(
        new BuildTarget("//java/com/facebook", "foo"),
        new BuildTarget("//java/com/facebook", "bar"));
    assertEquals("Should have returned all rules.", expectedTargets, targets);
  }

  @Test
  public void whenAllRulesRequestedWithFalseFilterThenNoRulesReturned()
      throws BuildFileParseException, BuildTargetException, IOException {
    List<BuildTarget> targets = testParser.filterAllTargetsInProject(filesystem,
        Lists.<String>newArrayList(), alwaysFalse());

    assertEquals("Should have returned no rules.", 0, targets.size());
  }

  @Test
  public void whenAllRulesAreRequestedMultipleTimesThenRulesAreOnlyParsedOnce()
      throws BuildFileParseException, BuildTargetException, IOException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets(), buildFileParserFactory);

    parser.filterAllTargetsInProject(filesystem, Lists.<String>newArrayList(), alwaysTrue());
    parser.filterAllTargetsInProject(filesystem, Lists.<String>newArrayList(), alwaysTrue());

    assertEquals("Should have cached build rules.", 1, buildFileParserFactory.calls);
  }

  @Test
  public void whenNotifiedOfNonPathEventThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets(), buildFileParserFactory);

    // Call filterAllTargetsInProject to populate the cache.
    parser.filterAllTargetsInProject(filesystem, Lists.<String>newArrayList(), alwaysTrue());

    // Process event.
    WatchEvent<Object> event = WatchEvents.createOverflowEvent();
    parser.onFileSystemChange(event);

    // Call filterAllTargetsInProject to request cached rules.
    parser.filterAllTargetsInProject(filesystem, Lists.<String>newArrayList(), alwaysTrue());

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, buildFileParserFactory.calls);
  }

  // TODO(jimp/devjasta): clean up the horrible ProjectBuildFileParserFactory mess.
  private void parseBuildFile(File buildFile, Parser parser,
                              ProjectBuildFileParserFactory buildFileParserFactory)
      throws BuildFileParseException, BuildTargetException, IOException {
    parser.parseBuildFile(buildFile,
        /* defaultIncludes */ ImmutableList.<String>of(),
        buildFileParserFactory.createParser(/* commonIncludes */ Lists.<String>newArrayList()));
  }

  @Test
  public void whenNotifiedOfBuildFileAddThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(testBuildFile, StandardWatchEventKinds.ENTRY_CREATE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, buildFileParserFactory.calls);
  }

  @Test
  public void whenNotifiedOfBuildFileChangeThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(testBuildFile, StandardWatchEventKinds.ENTRY_MODIFY);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, buildFileParserFactory.calls);
  }

  @Test
  public void whenNotifiedOfBuildFileDeleteThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(testBuildFile, StandardWatchEventKinds.ENTRY_DELETE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, buildFileParserFactory.calls);
  }

  @Test
  public void whenNotifiedOfIncludeFileAddThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(includedByBuildFile, StandardWatchEventKinds.ENTRY_CREATE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, buildFileParserFactory.calls);
  }

  @Test
  public void whenNotifiedOfIncludeFileChangeThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(includedByBuildFile, StandardWatchEventKinds.ENTRY_MODIFY);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, buildFileParserFactory.calls);
  }

  @Test
  public void whenNotifiedOfIncludeFileDeleteThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(includedByBuildFile, StandardWatchEventKinds.ENTRY_DELETE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, buildFileParserFactory.calls);
  }

  @Test
  public void whenNotifiedOf2ndOrderIncludeFileAddThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(includedByIncludeFile,
        StandardWatchEventKinds.ENTRY_CREATE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, buildFileParserFactory.calls);
  }

  @Test
  public void whenNotifiedOf2ndOrderIncludeFileChangeThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(includedByIncludeFile,
        StandardWatchEventKinds.ENTRY_MODIFY);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, buildFileParserFactory.calls);
  }

  @Test
  public void whenNotifiedOf2ndOrderIncludeFileDeleteThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(includedByIncludeFile,
        StandardWatchEventKinds.ENTRY_DELETE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, buildFileParserFactory.calls);
  }

  @Test
  public void whenNotifiedOfDefaultIncludeFileAddThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(defaultIncludeFile,
        StandardWatchEventKinds.ENTRY_CREATE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, buildFileParserFactory.calls);
  }

  @Test
  public void whenNotifiedOfDefaultIncludeFileChangeThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(defaultIncludeFile,
        StandardWatchEventKinds.ENTRY_MODIFY);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, buildFileParserFactory.calls);

  }

  @Test
  public void whenNotifiedOfDefaultIncludeFileDeleteThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(defaultIncludeFile,
        StandardWatchEventKinds.ENTRY_DELETE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, buildFileParserFactory.calls);
  }

  @Test
  // TODO(user): avoid invalidation when arbitrary contained (possibly backup) files are added.
  public void whenNotifiedOfContainedFileAddThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(tempDir.newFile("java/com/facebook/SomeClass.java"),
        StandardWatchEventKinds.ENTRY_CREATE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, buildFileParserFactory.calls);
  }

  @Test
  public void whenNotifiedOfContainedFileChangeThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(tempDir.newFile("java/com/facebook/SomeClass.java"),
        StandardWatchEventKinds.ENTRY_MODIFY);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call did not repopulate the cache.
    assertEquals("Should have not invalidated cache.", 1, buildFileParserFactory.calls);
  }

  @Test
  // TODO(user): avoid invalidation when arbitrary contained (possibly backup) files are deleted.
  public void whenNotifiedOfContainedFileDeleteThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(tempDir.newFile("java/com/facebook/SomeClass.java"),
        StandardWatchEventKinds.ENTRY_DELETE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, buildFileParserFactory.calls);
  }


  @Test
  public void whenNotifiedOfContainedTempFileAddThenCachedRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(tempDir.newFile("java/com/facebook/MumbleSwp.Java.swp"),
        StandardWatchEventKinds.ENTRY_CREATE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should not have invalidated cache.", 1, buildFileParserFactory.calls);
  }

  @Test
  public void whenNotifiedOfContainedTempFileChangeThenCachedRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(tempDir.newFile("java/com/facebook/MumbleSwp.Java.swp"),
        StandardWatchEventKinds.ENTRY_MODIFY);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should not have invalidated cache.", 1, buildFileParserFactory.calls);
  }

  @Test
  public void whenNotifiedOfContainedTempFileDeleteThenCachedRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(tempDir.newFile("java/com/facebook/MumbleSwp.Java.swp"),
        StandardWatchEventKinds.ENTRY_DELETE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should not have invalidated cache.", 1, buildFileParserFactory.calls);
  }

  @Test
  public void whenNotifiedOfUnrelatedFileAddThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(tempDir.newFile("SomeClass.java__backup"),
        StandardWatchEventKinds.ENTRY_CREATE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call did not repopulate the cache.
    assertEquals("Should have not invalidated cache.", 1, buildFileParserFactory.calls);
  }

  @Test
  public void whenNotifiedOfUnrelatedFileChangeThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(tempDir.newFile("SomeClass.java__backup"),
        StandardWatchEventKinds.ENTRY_MODIFY);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call did not repopulate the cache.
    assertEquals("Should have not invalidated cache.", 1, buildFileParserFactory.calls);
  }

  @Test
  public void whenNotifiedOfUnrelatedFileDeleteThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(tempDir.newFile("SomeClass.java__backup"),
        StandardWatchEventKinds.ENTRY_DELETE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call did not repopulate the cache.
    assertEquals("Should have not invalidated cache.", 1, buildFileParserFactory.calls);
  }

  @Test
  public void testGeneratedDeps()
      throws IOException, BuildFileParseException, BuildTargetException {
    // Execute parseBuildFilesForTargets() with a target in a valid file but a bad rule name.
    tempDir.newFolder("java", "com", "facebook", "generateddeps");

    File testGeneratedDepsBuckFile = tempDir.newFile(
        "java/com/facebook/generateddeps/" + BuckConstant.BUILD_RULES_FILE_NAME);
    Files.write(
        "java_library(name = 'foo')\n" +
            "java_library(name = 'bar')\n" +
            "add_deps(name = 'foo', deps = [':bar'])\n",
        testGeneratedDepsBuckFile,
        Charsets.UTF_8);

    BuildTarget fooTarget = new BuildTarget("//java/com/facebook/generateddeps", "foo");

    BuildTarget barTarget = new BuildTarget("//java/com/facebook/generateddeps", "bar");
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget, barTarget);
    Iterable<String> defaultIncludes = ImmutableList.of();

    DependencyGraph graph = testParser.parseBuildFilesForTargets(buildTargets,
        defaultIncludes,
        BuckEventBusFactory.newInstance());

    BuildRule fooRule = graph.findBuildRuleByTarget(fooTarget);
    assertNotNull(fooRule);
    BuildRule barRule = graph.findBuildRuleByTarget(barTarget);
    assertNotNull(barRule);

    assertEquals(ImmutableSet.of(barRule), fooRule.getDeps());
  }

  @Test
  public void whenAllRulesAreRequestedWithDifferingIncludesThenRulesAreParsedTwice()
      throws BuildFileParseException, BuildTargetException, IOException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets(), buildFileParserFactory);

    parser.filterAllTargetsInProject(filesystem, Lists.<String>newArrayList(), alwaysTrue());
    parser.filterAllTargetsInProject(filesystem, ImmutableList.of("//bar.py"), alwaysTrue());

    assertEquals("Should have invalidated cache.", 2, buildFileParserFactory.calls);
  }

  @Test
  public void whenAllRulesThenSingleTargetRequestedThenRulesAreParsedOnce()
      throws BuildFileParseException, BuildTargetException, IOException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets(), buildFileParserFactory);

    parser.filterAllTargetsInProject(filesystem, Lists.<String>newArrayList(), alwaysTrue());
    BuildTarget foo = new BuildTarget("//java/com/facebook", "foo");
    parser.parseBuildFilesForTargets(ImmutableList.of(foo),
        Lists.<String>newArrayList(),
        BuckEventBusFactory.newInstance());

    assertEquals("Should have cached build rules.", 1, buildFileParserFactory.calls);
  }

  @Test
  public void whenSingleTargetThenAllRulesRequestedThenRulesAreParsedTwice()
      throws BuildFileParseException, BuildTargetException, IOException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets(), buildFileParserFactory);

    BuildTarget foo = new BuildTarget("//java/com/facebook", "foo");
    parser.parseBuildFilesForTargets(ImmutableList.of(foo),
        Lists.<String>newArrayList(),
        BuckEventBusFactory.newInstance());
    parser.filterAllTargetsInProject(filesystem, Lists.<String>newArrayList(), alwaysTrue());

    assertEquals("Should have replaced build rules", 2, buildFileParserFactory.calls);
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
  private static class TestProjectBuildFileParserFactory implements ProjectBuildFileParserFactory {
    private final ProjectFilesystem projectFilesystem;
    private final KnownBuildRuleTypes buildRuleTypes;
    public int calls = 0;

    public TestProjectBuildFileParserFactory(ProjectFilesystem projectFilesystem, KnownBuildRuleTypes buildRuleTypes) {
      this.projectFilesystem = projectFilesystem;
      this.buildRuleTypes = buildRuleTypes;
    }

    @Override
    public ProjectBuildFileParser createParser(Iterable<String> commonIncludes) {
      return new TestProjectBuildFileParser();
    }

    private class TestProjectBuildFileParser extends ProjectBuildFileParser {
      public TestProjectBuildFileParser() {
        super(
            projectFilesystem,
            ImmutableList.of("//java/com/facebook/defaultIncludeFile"),
            "python",
            buildRuleTypes.getAllDescriptions());
      }

      @Override
      protected List<Map<String, Object>> getAllRulesInternal(Optional<Path> buildFile)
          throws IOException {
        calls += 1;
        return super.getAllRulesInternal(buildFile);
      }
    }
  }

  /**
   * Analogue to {@link Suppliers#ofInstance(Object)}.
   */
  private static InputSupplier<BuildFileTree> ofInstance(final BuildFileTree buildFileTree) {
    return new InputSupplier<BuildFileTree>() {
      @Override
      public BuildFileTree getInput() throws IOException {
        return buildFileTree;
      }
    };
  }
}
