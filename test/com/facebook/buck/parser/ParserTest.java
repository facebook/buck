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

import static com.facebook.buck.parser.RuleJsonPredicates.alwaysTrue;
import static com.facebook.buck.testutil.WatchEvents.createPathEvent;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.FakeBuckEventListener;
import com.facebook.buck.event.TestEventConfigerator;
import com.facebook.buck.java.PrebuiltJarDescription;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.json.DefaultProjectBuildFileParserFactory;
import com.facebook.buck.json.ProjectBuildFileParser;
import com.facebook.buck.json.ProjectBuildFileParserFactory;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.FilesystemBackedBuildFileTree;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.DefaultKnownBuildRuleTypes;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.rules.Repository;
import com.facebook.buck.rules.TestRepositoryBuilder;
import com.facebook.buck.testutil.BuckTestConstant;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.WatchEvents;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Files;

import org.easymock.EasyMockSupport;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class ParserTest extends EasyMockSupport {

  private Path testBuildFile;
  private Path includedByBuildFile;
  private Path includedByIncludeFile;
  private Path defaultIncludeFile;
  private Parser testParser;
  private KnownBuildRuleTypes buildRuleTypes;
  private ProjectFilesystem filesystem;
  private Repository repository;

  @Rule
  public TemporaryFolder tempDir = new TemporaryFolder();
  private ImmutableSet<Pattern> tempFilePatterns = ImmutableSet.of(Pattern.compile(".*\\.swp$"));

  @Before
  public void setUp() throws IOException {
    tempDir.newFolder("java", "com", "facebook");

    defaultIncludeFile = tempDir.newFile(
        "java/com/facebook/defaultIncludeFile").toPath();
    Files.write(
        "\n",
        defaultIncludeFile.toFile(),
        Charsets.UTF_8);

    includedByIncludeFile = tempDir.newFile(
        "java/com/facebook/includedByIncludeFile").toPath();
    Files.write(
        "\n",
        includedByIncludeFile.toFile(),
        Charsets.UTF_8);

    includedByBuildFile = tempDir.newFile(
        "java/com/facebook/includedByBuildFile").toPath();
    Files.write(
        "include_defs('//java/com/facebook/includedByIncludeFile')\n",
        includedByBuildFile.toFile(),
        Charsets.UTF_8);

    testBuildFile = tempDir.newFile(
        "java/com/facebook/" + BuckConstant.BUILD_RULES_FILE_NAME).toPath();
    Files.write(
        "include_defs('//java/com/facebook/includedByBuildFile')\n" +
        "java_library(name = 'foo')\n" +
        "java_library(name = 'bar')\n",
        testBuildFile.toFile(),
        Charsets.UTF_8);

    tempDir.newFile("bar.py");

    // Create a temp directory with some build files.
    File root = tempDir.getRoot();
    filesystem = new ProjectFilesystem(root);

    buildRuleTypes = DefaultKnownBuildRuleTypes.getDefaultKnownBuildRuleTypes(filesystem);

    repository = new TestRepositoryBuilder()
        .setFilesystem(filesystem)
        .setBuildRuleTypes(buildRuleTypes)
        .build();

    DefaultProjectBuildFileParserFactory testBuildFileParserFactory =
        new DefaultProjectBuildFileParserFactory(
            filesystem,
            BuckTestConstant.PYTHON_INTERPRETER,
            buildRuleTypes.getAllDescriptions());
    testParser = createParser(emptyBuildTargets(), testBuildFileParserFactory);
  }

  private ProjectBuildFileParserFactory createDoNothingBuildFileParserFactory()
      throws BuildFileParseException, InterruptedException {
    final ProjectBuildFileParser mockBuildFileParser = createMock(ProjectBuildFileParser.class);
    mockBuildFileParser.close();
    expectLastCall().anyTimes();

    return new ProjectBuildFileParserFactory() {
      @Override
      public ProjectBuildFileParser createParser(
          Iterable<String> commonIncludes,
          Console console, ImmutableMap<String, String> environment) {
        return mockBuildFileParser;
      }
    };
  }

  private Parser createParser(Iterable<Map<String, Object>> rules)
      throws IOException {
    return createParser(
        ofInstance(new FilesystemBackedBuildFileTree(filesystem)),
        rules,
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes),
        new BuildTargetParser(filesystem));
  }

  private Parser createParser(
      Iterable<Map<String, Object>> rules,
      ProjectBuildFileParserFactory buildFileParserFactory) throws IOException {
    return createParser(
        ofInstance(new FilesystemBackedBuildFileTree(filesystem)),
        rules,
        buildFileParserFactory,
        new BuildTargetParser(filesystem));
  }

  private Parser createParser(Supplier<BuildFileTree> buildFileTreeSupplier,
    Iterable<Map<String, Object>> rules,
    ProjectBuildFileParserFactory buildFileParserFactory,
    BuildTargetParser buildTargetParser) {
    Parser parser = new Parser(
        repository,
        buildFileTreeSupplier,
        buildTargetParser,
        buildFileParserFactory,
        tempFilePatterns,
        new FakeRuleKeyBuilderFactory());

    try {
      parser.parseRawRulesInternal(rules);
    } catch (BuildTargetException|IOException e) {
      throw new RuntimeException(e);
    }

    return parser;
  }

  /**
   * If a rule contains an erroneous dep to a non-existent rule, then it should throw an
   * appropriate message to help the user find the source of his error.
   */
  @Test
  public void testParseRawRulesWithBadDependency()
      throws BuildTargetException, BuildFileParseException, IOException, InterruptedException {
    String nonExistentBuildTarget = "//testdata/com/facebook/feed:util";
    Map<String, Object> rawRule = ImmutableMap.<String, Object>of(
        "type", "java_library",
        "name", "feed",
        // A non-existent dependency: this is a user error that should be reported.
        "deps", ImmutableList.of(nonExistentBuildTarget),
        "buck.base_path", "testdata/com/facebook/feed/model");
    List<Map<String, Object>> ruleObjects = ImmutableList.of(rawRule);

    ProjectFilesystem filesystem = new ProjectFilesystem(Paths.get("."));
    Parser parser = new Parser(
        new TestRepositoryBuilder().setFilesystem(filesystem).build(),
        BuckTestConstant.PYTHON_INTERPRETER,
        tempFilePatterns,
        new FakeRuleKeyBuilderFactory());

    parser.parseRawRulesInternal(ruleObjects);
    RuleJsonPredicate predicate = alwaysTrue();
    ImmutableSet<BuildTarget> targets = parser.filterTargets(predicate);
    BuildTarget expectedBuildTarget = BuildTarget.builder(
        "//testdata/com/facebook/feed/model",
        "feed").build();
    assertEquals(ImmutableSet.of(expectedBuildTarget), targets);

    try {
      parser.onlyUseThisWhenTestingToFindAllTransitiveDependencies(
          targets,
          ImmutableList.<String>of(),
          new TestConsole(),
          ImmutableMap.<String, String>of());
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
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
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
      parser.onlyUseThisWhenTestingToFindAllTransitiveDependencies(
          buildTargets, defaultIncludes, new TestConsole(), ImmutableMap.<String, String>of());
      fail("Should have thrown a HumanReadableException.");
    } catch (HumanReadableException e) {
      assertEquals("Cycle found: //:F -> //:C -> //:E -> //:F", e.getMessage());
    }

    verifyAll();
  }

  @Test
  public void testParseBuildFilesForTargetsWithOverlappingTargets()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Execute parseBuildFilesForTargets() with multiple targets that require parsing the same
    // build file.
    BuildTarget fooTarget = BuildTarget.builder("//java/com/facebook", "foo").build();
    BuildTarget barTarget = BuildTarget.builder("//java/com/facebook", "bar").build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget, barTarget);
    Iterable<String> defaultIncludes = ImmutableList.of();

    // The EventBus should be updated with events indicating how parsing ran.
    BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    FakeBuckEventListener listener = new FakeBuckEventListener();
    eventBus.register(listener);

    ActionGraph graph = testParser.parseBuildFilesForTargets(
        buildTargets,
        defaultIncludes,
        eventBus,
        new TestConsole(),
        ImmutableMap.<String, String>of());
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
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Execute parseBuildFilesForTargets() with a target in a valid file but a bad rule name.
    BuildTarget fooTarget = BuildTarget.builder("//java/com/facebook", "foo").build();
    BuildTarget razTarget = BuildTarget.builder("//java/com/facebook", "raz").build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget, razTarget);
    Iterable<String> defaultIncludes = ImmutableList.of();

    try {
      testParser.parseBuildFilesForTargets(
          buildTargets,
          defaultIncludes,
          BuckEventBusFactory.newInstance(),
          new TestConsole(),
          ImmutableMap.<String, String>of());
      fail("HumanReadableException should be thrown");
    } catch (HumanReadableException e) {
      assertEquals("No rule found when resolving target //java/com/facebook:raz in build file " +
                   "//java/com/facebook/BUCK",
          e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void shouldThrowAnExceptionWhenAnUnknownFlavorIsSeen()
      throws BuildFileParseException, BuildTargetException, InterruptedException, IOException {
    BuildTarget flavored = BuildTarget.builder("//java/com/facebook", "foo")
        .setFlavor("doesNotExist")
        .build();
    try {
      testParser.parseBuildFilesForTargets(
          ImmutableSortedSet.of(flavored),
          ImmutableList.<String>of(),
          BuckEventBusFactory.newInstance(),
          new TestConsole(),
          ImmutableMap.<String, String>of());
    } catch (HumanReadableException e) {
      assertEquals(
          "Unrecognized flavor in target //java/com/facebook:foo#doesNotExist while parsing " +
              "//java/com/facebook/BUCK.",
          e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void testInvalidDepFromValidFile()
      throws IOException, BuildFileParseException, BuildTargetException, InterruptedException {
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

    BuildTarget fooTarget = BuildTarget.builder("//java/com/facebook/invalid", "foo").build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget);
    Iterable<String> defaultIncludes = ImmutableList.of();

    try {
      testParser.parseBuildFilesForTargets(
          buildTargets,
          defaultIncludes,
          BuckEventBusFactory.newInstance(),
          new TestConsole(),
          ImmutableMap.<String, String>of());
      fail("HumanReadableException should be thrown");
    } catch (HumanReadableException e) {
      assertEquals(
          "No rule found when resolving target " +
              "//java/com/facebook/invalid/lib:missing_rule in build file " +
              "//java/com/facebook/invalid/lib/BUCK",
          e.getHumanReadableErrorMessage()
      );
    }
  }

  @Test
  public void whenAllRulesRequestedWithTrueFilterThenMultipleRulesReturned()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    ImmutableSet<BuildTarget> targets = testParser.filterAllTargetsInProject(
        filesystem,
        Lists.<String>newArrayList(),
        alwaysTrue(),
        new TestConsole(),
        ImmutableMap.<String, String>of());

    ImmutableSet<BuildTarget> expectedTargets = ImmutableSet.of(
        BuildTarget.builder("//java/com/facebook", "foo").build(),
        BuildTarget.builder("//java/com/facebook", "bar").build());
    assertEquals("Should have returned all rules.", expectedTargets, targets);
  }

  @Test
  public void whenAllRulesAreRequestedMultipleTimesThenRulesAreOnlyParsedOnce()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets(), buildFileParserFactory);

    parser.filterAllTargetsInProject(
        filesystem, Lists.<String>newArrayList(),
        alwaysTrue(),
        new TestConsole(),
        ImmutableMap.<String, String>of());
    parser.filterAllTargetsInProject(
        filesystem,
        Lists.<String>newArrayList(),
        alwaysTrue(),
        new TestConsole(),
        ImmutableMap.<String, String>of());

    assertEquals("Should have cached build rules.", 1, buildFileParserFactory.calls);
  }

  @Test
  public void whenNotifiedOfNonPathEventThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets(), buildFileParserFactory);

    // Call filterAllTargetsInProject to populate the cache.
    parser.filterAllTargetsInProject(
        filesystem,
        Lists.<String>newArrayList(),
        alwaysTrue(),
        new TestConsole(),
        ImmutableMap.<String, String>of());

    // Process event.
    WatchEvent<Object> event = WatchEvents.createOverflowEvent();
    parser.onFileSystemChange(event);

    // Call filterAllTargetsInProject to request cached rules.
    parser.filterAllTargetsInProject(
        filesystem,
        Lists.<String>newArrayList(),
        alwaysTrue(),
        new TestConsole(),
        ImmutableMap.<String, String>of());

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, buildFileParserFactory.calls);
  }

  @Test
  public void whenEnvironmentChangesThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets(), buildFileParserFactory);

    // Call filterAllTargetsInProject to populate the cache.
    parser.filterAllTargetsInProject(
        filesystem,
        Lists.<String>newArrayList(),
        alwaysTrue(),
        new TestConsole(),
        ImmutableMap.of("Some Key", "Some Value"));

    // Call filterAllTargetsInProject to request cached rules.
    parser.filterAllTargetsInProject(
        filesystem,
        Lists.<String>newArrayList(),
        alwaysTrue(),
        new TestConsole(),
        ImmutableMap.of("Some Key", "Some Other Value"));

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, buildFileParserFactory.calls);
  }


  @Test
  public void whenEnvironmentNotChangedThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets(), buildFileParserFactory);

    // Call filterAllTargetsInProject to populate the cache.
    parser.filterAllTargetsInProject(
        filesystem,
        Lists.<String>newArrayList(),
        alwaysTrue(),
        new TestConsole(),
        ImmutableMap.of("Some Key", "Some Value"));

    // Call filterAllTargetsInProject to request cached rules with identical environment.
    parser.filterAllTargetsInProject(
        filesystem,
        Lists.<String>newArrayList(), alwaysTrue(),
        new TestConsole(),
        ImmutableMap.of("Some Key", "Some Value"));

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should not have invalidated cache.", 1, buildFileParserFactory.calls);
  }


  // TODO(jimp/devjasta): clean up the horrible ProjectBuildFileParserFactory mess.
  private void parseBuildFile(Path buildFile, Parser parser,
                              ProjectBuildFileParserFactory buildFileParserFactory)
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    try (ProjectBuildFileParser projectBuildFileParser = buildFileParserFactory.createParser(
        /* commonIncludes */ Lists.<String>newArrayList(),
        new TestConsole(),
        ImmutableMap.<String, String>of())) {
      parser.parseBuildFile(
          buildFile,
          /* defaultIncludes */ ImmutableList.<String>of(),
          projectBuildFileParser,
          /* environment */ ImmutableMap.<String, String>of());
    }
  }

  @Test
  public void whenNotifiedOfBuildFileAddThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
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
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
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
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
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
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(includedByBuildFile,
        StandardWatchEventKinds.ENTRY_CREATE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, buildFileParserFactory.calls);
  }

  @Test
  public void whenNotifiedOfIncludeFileChangeThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(includedByBuildFile,
        StandardWatchEventKinds.ENTRY_MODIFY);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, buildFileParserFactory.calls);
  }

  @Test
  public void whenNotifiedOfIncludeFileDeleteThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(includedByBuildFile,
        StandardWatchEventKinds.ENTRY_DELETE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, buildFileParserFactory.calls);
  }

  @Test
  public void whenNotifiedOf2ndOrderIncludeFileAddThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
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
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
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
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
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
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
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
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
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
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
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
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(Paths.get("java/com/facebook/SomeClass.java"),
        StandardWatchEventKinds.ENTRY_CREATE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, buildFileParserFactory.calls);
  }

  @Test
  public void whenNotifiedOfContainedFileChangeThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(Paths.get("java/com/facebook/SomeClass.java"),
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
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(Paths.get("java/com/facebook/SomeClass.java"),
        StandardWatchEventKinds.ENTRY_DELETE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, buildFileParserFactory.calls);
  }


  @Test
  public void whenNotifiedOfContainedTempFileAddThenCachedRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(Paths.get("java/com/facebook/MumbleSwp.Java.swp"),
        StandardWatchEventKinds.ENTRY_CREATE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should not have invalidated cache.", 1, buildFileParserFactory.calls);
  }

  @Test
  public void whenNotifiedOfContainedTempFileChangeThenCachedRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(Paths.get("java/com/facebook/MumbleSwp.Java.swp"),
        StandardWatchEventKinds.ENTRY_MODIFY);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should not have invalidated cache.", 1, buildFileParserFactory.calls);
  }

  @Test
  public void whenNotifiedOfContainedTempFileDeleteThenCachedRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(Paths.get("java/com/facebook/MumbleSwp.Java.swp"),
        StandardWatchEventKinds.ENTRY_DELETE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should not have invalidated cache.", 1, buildFileParserFactory.calls);
  }

  @Test
  public void whenNotifiedOfUnrelatedFileAddThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(Paths.get("SomeClass.java__backup"),
        StandardWatchEventKinds.ENTRY_CREATE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call did not repopulate the cache.
    assertEquals("Should have not invalidated cache.", 1, buildFileParserFactory.calls);
  }

  @Test
  public void whenNotifiedOfUnrelatedFileChangeThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(Paths.get("SomeClass.java__backup"),
        StandardWatchEventKinds.ENTRY_MODIFY);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call did not repopulate the cache.
    assertEquals("Should have not invalidated cache.", 1, buildFileParserFactory.calls);
  }

  @Test
  public void whenNotifiedOfUnrelatedFileDeleteThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets());

    // Call parseBuildFile to populate the cache.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Process event.
    WatchEvent<Path> event = createPathEvent(Paths.get("SomeClass.java__backup"),
        StandardWatchEventKinds.ENTRY_DELETE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parseBuildFile(testBuildFile, parser, buildFileParserFactory);

    // Test that the second parseBuildFile call did not repopulate the cache.
    assertEquals("Should have not invalidated cache.", 1, buildFileParserFactory.calls);
  }

  @Test
  public void testGeneratedDeps()
      throws IOException, BuildFileParseException, BuildTargetException, InterruptedException {
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

    BuildTarget fooTarget = BuildTarget.builder("//java/com/facebook/generateddeps", "foo").build();

    BuildTarget barTarget = BuildTarget.builder("//java/com/facebook/generateddeps", "bar").build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget, barTarget);
    Iterable<String> defaultIncludes = ImmutableList.of();

    ActionGraph graph = testParser.parseBuildFilesForTargets(
        buildTargets,
        defaultIncludes,
        BuckEventBusFactory.newInstance(),
        new TestConsole(),
        ImmutableMap.<String, String>of());

    BuildRule fooRule = graph.findBuildRuleByTarget(fooTarget);
    assertNotNull(fooRule);
    BuildRule barRule = graph.findBuildRuleByTarget(barTarget);
    assertNotNull(barRule);

    assertEquals(ImmutableSet.of(barRule), fooRule.getDeps());
  }

  @Test
  public void whenAllRulesAreRequestedWithDifferingIncludesThenRulesAreParsedTwice()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets(), buildFileParserFactory);

    parser.filterAllTargetsInProject(
        filesystem,
        Lists.<String>newArrayList(),
        alwaysTrue(),
        new TestConsole(),
        ImmutableMap.<String, String>of());
    parser.filterAllTargetsInProject(
        filesystem,
        ImmutableList.of("//bar.py"),
        alwaysTrue(),
        new TestConsole(),
        ImmutableMap.<String, String>of());

    assertEquals("Should have invalidated cache.", 2, buildFileParserFactory.calls);
  }

  @Test
  public void whenAllRulesThenSingleTargetRequestedThenRulesAreParsedOnce()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets(), buildFileParserFactory);

    parser.filterAllTargetsInProject(
        filesystem,
        Lists.<String>newArrayList(),
        alwaysTrue(),
        new TestConsole(),
        ImmutableMap.<String, String>of());
    BuildTarget foo = BuildTarget.builder("//java/com/facebook", "foo").build();
    parser.parseBuildFilesForTargets(
        ImmutableList.of(foo),
        Lists.<String>newArrayList(),
        BuckEventBusFactory.newInstance(),
        new TestConsole(),
        ImmutableMap.<String, String>of());

    assertEquals("Should have cached build rules.", 1, buildFileParserFactory.calls);
  }

  @Test
  public void whenSingleTargetThenAllRulesRequestedThenRulesAreParsedTwice()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets(), buildFileParserFactory);

    BuildTarget foo = BuildTarget.builder("//java/com/facebook", "foo").build();
    parser.parseBuildFilesForTargets(
        ImmutableList.of(foo),
        Lists.<String>newArrayList(),
        BuckEventBusFactory.newInstance(),
        new TestConsole(),
        ImmutableMap.<String, String>of());
    parser.filterAllTargetsInProject(
        filesystem,
        Lists.<String>newArrayList(),
        alwaysTrue(),
        new TestConsole(),
        ImmutableMap.<String, String>of());

    assertEquals("Should have replaced build rules", 2, buildFileParserFactory.calls);
  }

  @Test
  @SuppressWarnings("unchecked") // Needed to mock generic class.
  public void whenBuildFileTreeCacheInvalidatedTwiceDuringASingleBuildThenBuildFileTreeBuiltOnce()
      throws IOException {
    BuckEvent mockEvent = createMock(BuckEvent.class);
    expect(mockEvent.getEventName()).andReturn("CommandStarted").anyTimes();
    expect(mockEvent.getBuildId()).andReturn(new BuildId("BUILD1"));
    BuildFileTree mockBuildFileTree = createMock(BuildFileTree.class);
    Supplier<BuildFileTree> mockSupplier = createMock(Supplier.class);
    expect(mockSupplier.get()).andReturn(mockBuildFileTree).once();
    replay(mockEvent, mockSupplier, mockBuildFileTree);
    Parser.BuildFileTreeCache cache = new Parser.BuildFileTreeCache(mockSupplier);
    cache.onCommandStartedEvent(mockEvent);
    cache.invalidateIfStale();
    cache.get();
    cache.invalidateIfStale();
    cache.get();
    verify(mockEvent, mockSupplier);
  }

  @Test
  @SuppressWarnings("unchecked") // Needed to mock generic class.
  public void whenBuildFileTreeCacheInvalidatedDuringTwoBuildsThenBuildFileTreeBuiltTwice()
      throws IOException {
    BuckEvent mockEvent = createMock(BuckEvent.class);
    expect(mockEvent.getEventName()).andReturn("CommandStarted").anyTimes();
    expect(mockEvent.getBuildId()).andReturn(new BuildId("BUILD1"));
    expect(mockEvent.getBuildId()).andReturn(new BuildId("BUILD2"));
    BuildFileTree mockBuildFileTree = createMock(BuildFileTree.class);
    Supplier<BuildFileTree> mockSupplier = createMock(Supplier.class);
    expect(mockSupplier.get()).andReturn(mockBuildFileTree).times(2);
    replay(mockEvent, mockSupplier, mockBuildFileTree);
    Parser.BuildFileTreeCache cache = new Parser.BuildFileTreeCache(mockSupplier);
    cache.onCommandStartedEvent(mockEvent);
    cache.invalidateIfStale();
    cache.get();
    cache.onCommandStartedEvent(mockEvent);
    cache.invalidateIfStale();
    cache.get();
    verify(mockEvent, mockSupplier);
  }

  @Test(expected = BuildFileParseException.class)
  public void whenSubprocessReturnsFailureThenProjectBuildFileParserThrowsOnClose()
      throws IOException, BuildFileParseException, InterruptedException {
    // This test depends on unix utilities that don't exist on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    try (ProjectBuildFileParser buildFileParser =
        buildFileParserFactory.createNoopParserThatAlwaysReturnsError()) {
      buildFileParser.initIfNeeded();
      // close() is called implicitly at the end of this block. It must throw.
    }
  }

  @Test
  public void whenSubprocessReturnsSuccessThenProjectBuildFileParserClosesCleanly()
      throws IOException, BuildFileParseException, InterruptedException {
    // This test depends on unix utilities that don't exist on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    try (ProjectBuildFileParser buildFileParser =
        buildFileParserFactory.createNoopParserThatAlwaysReturnsSuccess()) {
      buildFileParser.initIfNeeded();
      // close() is called implicitly at the end of this block. It must not throw.
    }
  }

  private Iterable<Map<String, Object>> emptyBuildTargets() {
    return Sets.newHashSet();
  }

  private Iterable<Map<String, Object>> circularBuildTargets() {
    return ImmutableSet.<Map<String, Object>>builder()
        .add(createRule("//:A", "B", "C"))
        .add(createRule("//:B", "D", "E"))
        .add(createRule("//:C", "E"))
        .add(createRule("//:D", "F"))
        .add(createRule("//:E", "F"))
        .add(createRule("//:F", "C"))
        .build();
  }

  private ImmutableMap<String, Object> createRule(String name, String... deps) {
    BuildTarget target = BuildTargetFactory.newInstance(name);

    ImmutableMap.Builder<String, Object> rule = ImmutableMap.<String, Object>builder()
        .put("name", target.getShortName())
        .put("type", PrebuiltJarDescription.TYPE.toString())
        .put("binary_jar", name + ".jar")
        .put("buck.base_path", target.getBasePath().toString());

    ImmutableList.Builder<String> allDeps = ImmutableList.builder();
    for (String dep : deps) {
      allDeps.add(String.format("%s:%s", BuildTarget.BUILD_TARGET_PREFIX, dep));
    }
    rule.put("deps", allDeps.build());

    return rule.build();
  }


  /**
   * ProjectBuildFileParser test double which counts the number of times rules are parsed to test
   * caching logic in Parser.
   */
  private static class TestProjectBuildFileParserFactory implements ProjectBuildFileParserFactory {
    private final ProjectFilesystem projectFilesystem;
    private final KnownBuildRuleTypes buildRuleTypes;
    public int calls = 0;

    public TestProjectBuildFileParserFactory(
        ProjectFilesystem projectFilesystem,
        KnownBuildRuleTypes buildRuleTypes) {
      this.projectFilesystem = projectFilesystem;
      this.buildRuleTypes = buildRuleTypes;
    }

    @Override
    public ProjectBuildFileParser createParser(
        Iterable<String> commonIncludes,
        Console console,
        ImmutableMap<String, String> environment) {
      return new TestProjectBuildFileParser("python" /* pythonInterpreter */);
    }

    public ProjectBuildFileParser createNoopParserThatAlwaysReturnsError() {
      // "false" is a unix utility that always returns error code 1 (failure).
      return new TestProjectBuildFileParser("false" /* pythonInterpreter */);
    }

    public ProjectBuildFileParser createNoopParserThatAlwaysReturnsSuccess() {
      // "true" is a unix utility that always returns error code 0 (success).
      return new TestProjectBuildFileParser("true" /* pythonInterpreter */);
    }

    private class TestProjectBuildFileParser extends ProjectBuildFileParser {
      public TestProjectBuildFileParser(String pythonInterpreter) {
        super(
            projectFilesystem,
            ImmutableList.of("//java/com/facebook/defaultIncludeFile"),
            pythonInterpreter,
            buildRuleTypes.getAllDescriptions(),
            new TestConsole(),
            ImmutableMap.<String, String>of());
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
  private static Supplier<BuildFileTree> ofInstance(final BuildFileTree buildFileTree) {
    return new Supplier<BuildFileTree>() {
      @Override
      public BuildFileTree get() {
        return buildFileTree;
      }
    };
  }
}
