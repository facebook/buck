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
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.json.DefaultProjectBuildFileParserFactory;
import com.facebook.buck.json.ProjectBuildFileParser;
import com.facebook.buck.json.ProjectBuildFileParserFactory;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.FilesystemBackedBuildFileTree;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.FakeRepositoryFactory;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.rules.Repository;
import com.facebook.buck.rules.RepositoryFactory;
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
  private RepositoryFactory repositoryFactory;

  @Rule
  public TemporaryFolder tempDir = new TemporaryFolder();
  private ImmutableSet<Pattern> tempFilePatterns = ImmutableSet.of(Pattern.compile(".*\\.swp$"));

  @Before
  public void setUp() throws IOException, InterruptedException  {
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
    repositoryFactory = new FakeRepositoryFactory(root.toPath());
    Repository repository = repositoryFactory.getRootRepository();
    filesystem = repository.getFilesystem();

    buildRuleTypes = repository.getKnownBuildRuleTypes();

    DefaultProjectBuildFileParserFactory testBuildFileParserFactory =
        new DefaultProjectBuildFileParserFactory(
            filesystem,
            BuckTestConstant.PYTHON_INTERPRETER,
            buildRuleTypes.getAllDescriptions());
    testParser = createParser(emptyBuildTargets(), testBuildFileParserFactory);
  }

  private Parser createParser(Iterable<Map<String, Object>> rules)
      throws IOException, InterruptedException {
    return createParser(
        ofInstance(new FilesystemBackedBuildFileTree(filesystem)),
        rules,
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes),
        new BuildTargetParser());
  }

  private Parser createParser(
      Iterable<Map<String, Object>> rules,
      ProjectBuildFileParserFactory buildFileParserFactory)
      throws IOException, InterruptedException {
    return createParser(
        ofInstance(new FilesystemBackedBuildFileTree(filesystem)),
        rules,
        buildFileParserFactory,
        new BuildTargetParser());
  }

  private Parser createParser(
          Supplier<BuildFileTree> buildFileTreeSupplier,
      Iterable<Map<String, Object>> rules,
      ProjectBuildFileParserFactory buildFileParserFactory,
      BuildTargetParser buildTargetParser)
      throws IOException, InterruptedException {
    return createParser(
        buildFileTreeSupplier,
        rules,
        buildFileParserFactory,
        buildTargetParser,
        repositoryFactory);
  }

    private Parser createParser(
        Supplier<BuildFileTree> buildFileTreeSupplier,
        Iterable<Map<String, Object>> rules,
        ProjectBuildFileParserFactory buildFileParserFactory,
        BuildTargetParser buildTargetParser,
        RepositoryFactory repositoryFactory)
        throws IOException, InterruptedException {
    Parser parser = new Parser(
        repositoryFactory,
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

  @Test
  public void testParseBuildFilesForTargetsWithOverlappingTargets()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Execute buildTargetGraph() with multiple targets that require parsing the same
    // build file.
    BuildTarget fooTarget = BuildTarget.builder("//java/com/facebook", "foo").build();
    BuildTarget barTarget = BuildTarget.builder("//java/com/facebook", "bar").build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget, barTarget);
    Iterable<String> defaultIncludes = ImmutableList.of();

    // The EventBus should be updated with events indicating how parsing ran.
    BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    FakeBuckEventListener listener = new FakeBuckEventListener();
    eventBus.register(listener);

    TargetGraph targetGraph = testParser.buildTargetGraph(
        buildTargets,
        defaultIncludes,
        eventBus,
        new TestConsole(),
        ImmutableMap.<String, String>of());
    ActionGraph actionGraph = targetGraph.buildActionGraph();
    BuildRule fooRule = actionGraph.findBuildRuleByTarget(fooTarget);
    assertNotNull(fooRule);
    BuildRule barRule = actionGraph.findBuildRuleByTarget(barTarget);
    assertNotNull(barRule);

    ImmutableList<ParseEvent> expected = ImmutableList.of(
        TestEventConfigerator.configureTestEvent(ParseEvent.started(buildTargets), eventBus),
        TestEventConfigerator.configureTestEvent(ParseEvent.finished(buildTargets,
            Optional.of(targetGraph)),
            eventBus));

    Iterable<ParseEvent> events = Iterables.filter(listener.getEvents(), ParseEvent.class);
    assertEquals(expected, ImmutableList.copyOf(events));
  }

  @Test
  public void testMissingBuildRuleInValidFile()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Execute buildTargetGraph() with a target in a valid file but a bad rule name.
    BuildTarget fooTarget = BuildTarget.builder("//java/com/facebook", "foo").build();
    BuildTarget razTarget = BuildTarget.builder("//java/com/facebook", "raz").build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget, razTarget);
    Iterable<String> defaultIncludes = ImmutableList.of();

    try {
      testParser.buildTargetGraph(
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
        .addFlavor("doesNotExist")
        .build();
    try {
      testParser.buildTargetGraph(
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
    // Execute buildTargetGraph() with a target in a valid file but a bad rule name.
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
      testParser.buildTargetGraph(
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
        ImmutableMap.<String, String>of(),
        BuckEventBusFactory.newInstance(),
        false /* enableProfiling */);

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
        ImmutableMap.<String, String>of(),
        BuckEventBusFactory.newInstance(),
        false /* enableProfiling */);
    parser.filterAllTargetsInProject(
        filesystem,
        Lists.<String>newArrayList(),
        alwaysTrue(),
        new TestConsole(),
        ImmutableMap.<String, String>of(),
        BuckEventBusFactory.newInstance(),
        false);

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
        ImmutableMap.<String, String>of(),
        BuckEventBusFactory.newInstance(),
        false /* enableProfiling */);

    // Process event.
    WatchEvent<Object> event = WatchEvents.createOverflowEvent();
    parser.onFileSystemChange(event);

    // Call filterAllTargetsInProject to request cached rules.
    parser.filterAllTargetsInProject(
        filesystem,
        Lists.<String>newArrayList(),
        alwaysTrue(),
        new TestConsole(),
        ImmutableMap.<String, String>of(),
        BuckEventBusFactory.newInstance(),
        false /* enableProfiling */);

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
        ImmutableMap.of("Some Key", "Some Value"),
        BuckEventBusFactory.newInstance(),
        false /* enableProfiling */);

    // Call filterAllTargetsInProject to request cached rules.
    parser.filterAllTargetsInProject(
        filesystem,
        Lists.<String>newArrayList(),
        alwaysTrue(),
        new TestConsole(),
        ImmutableMap.of("Some Key", "Some Other Value"),
        BuckEventBusFactory.newInstance(),
        false /* enableProfiling */);

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
        ImmutableMap.of("Some Key", "Some Value"),
        BuckEventBusFactory.newInstance(),
        false /* enableProfiling */);

    // Call filterAllTargetsInProject to request cached rules with identical environment.
    parser.filterAllTargetsInProject(
        filesystem,
        Lists.<String>newArrayList(), alwaysTrue(),
        new TestConsole(),
        ImmutableMap.of("Some Key", "Some Value"),
        BuckEventBusFactory.newInstance(),
        false /* enableProfiling */);

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
        ImmutableMap.<String, String>of(),
        BuckEventBusFactory.newInstance())) {
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
    WatchEvent<Path> event = createPathEvent(
        Paths.get("SomeClass.java__backup"),
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
    // Execute buildTargetGraph() with a target in a valid file but a bad rule name.
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

    ActionGraph graph = testParser.buildTargetGraph(
        buildTargets,
        defaultIncludes,
        BuckEventBusFactory.newInstance(),
        new TestConsole(),
        ImmutableMap.<String, String>of()).buildActionGraph();

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
        ImmutableMap.<String, String>of(),
        BuckEventBusFactory.newInstance(),
        false /* enableProfiling */);
    parser.filterAllTargetsInProject(
        filesystem,
        ImmutableList.of("//bar.py"),
        alwaysTrue(),
        new TestConsole(),
        ImmutableMap.<String, String>of(),
        BuckEventBusFactory.newInstance(),
        false /* enableProfiling */);

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
        ImmutableMap.<String, String>of(),
        BuckEventBusFactory.newInstance(),
        false /* enableProfiling */);
    BuildTarget foo = BuildTarget.builder("//java/com/facebook", "foo").build();
    parser.buildTargetGraph(
        ImmutableList.of(foo),
        Lists.<String>newArrayList(),
        BuckEventBusFactory.newInstance(),
        new TestConsole(),
        ImmutableMap.<String, String>of());

    assertEquals("Should have cached build rules.", 1, buildFileParserFactory.calls);
  }

  @Test
  public void whenSingleTargetThenAllRulesRequestedThenRulesAreParsedOnce()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets(), buildFileParserFactory);

    BuildTarget foo = BuildTarget.builder("//java/com/facebook", "foo").build();
    parser.buildTargetGraph(
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
        ImmutableMap.<String, String>of(),
        BuckEventBusFactory.newInstance(),
        false /* enableProfiling */);

    assertEquals("Should have replaced build rules", 1, buildFileParserFactory.calls);
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
        ImmutableMap<String, String> environment,
        BuckEventBus buckEventBus) {
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
            ImmutableMap.<String, String>of(),
            BuckEventBusFactory.newInstance());
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
