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

import static com.facebook.buck.testutil.WatchEvents.createPathEvent;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.BuildTargetNodeToBuildRuleTransformer;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.FakeBuckEventListener;
import com.facebook.buck.event.TestEventConfigerator;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.json.DefaultProjectBuildFileParserFactory;
import com.facebook.buck.json.ProjectBuildFileParser;
import com.facebook.buck.json.ProjectBuildFileParserFactory;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.FilesystemBackedBuildFileTree;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.python.PythonBuckConfig;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.FakeRepositoryFactory;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.rules.Repository;
import com.facebook.buck.rules.RepositoryFactory;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphToActionGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.WatchEvents;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import com.google.common.io.Files;

import org.easymock.EasyMockSupport;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.List;
import java.util.Map;

public class ParserTest extends EasyMockSupport {

  private Path testBuildFile;
  private Path includedByBuildFile;
  private Path includedByIncludeFile;
  private Path defaultIncludeFile;
  private Parser testParser;
  private KnownBuildRuleTypes buildRuleTypes;
  private ProjectFilesystem filesystem;
  private RepositoryFactory repositoryFactory;
  private BuckEventBus eventBus;

  @Rule
  public TemporaryFolder tempDir = new TemporaryFolder();
  @Rule
  public ExpectedException thrown = ExpectedException.none();

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

    testBuildFile = tempDir.newFile("java/com/facebook/BUCK").toPath();
    Files.write(
        "include_defs('//java/com/facebook/includedByBuildFile')\n" +
        "java_library(name = 'foo')\n" +
        "java_library(name = 'bar')\n" +
        "genrule(name = 'baz', out = '')\n",
        testBuildFile.toFile(),
        Charsets.UTF_8);

    tempDir.newFile("bar.py");

    // Create a temp directory with some build files.
    File root = tempDir.getRoot();
    repositoryFactory = new FakeRepositoryFactory(root.toPath());
    Repository repository = repositoryFactory.getRootRepository();
    filesystem = repository.getFilesystem();
    eventBus = BuckEventBusFactory.newInstance();

    buildRuleTypes = repository.getKnownBuildRuleTypes();

    DefaultProjectBuildFileParserFactory testBuildFileParserFactory =
        new DefaultProjectBuildFileParserFactory(
            filesystem,
            new ParserConfig(new FakeBuckConfig()),
            buildRuleTypes.getAllDescriptions());
    testParser = createParser(emptyBuildTargets(), testBuildFileParserFactory);
  }

  private Parser createParser(Iterable<Map<String, Object>> rules)
      throws IOException, InterruptedException {
    return createParser(
        ofInstance(
            new FilesystemBackedBuildFileTree(filesystem, "BUCK")),
        rules,
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes),
        new BuildTargetParser());
  }

  private Parser createParser(
      Iterable<Map<String, Object>> rules,
      ProjectBuildFileParserFactory buildFileParserFactory)
      throws IOException, InterruptedException {
    return createParser(
        ofInstance(
            new FilesystemBackedBuildFileTree(filesystem, "BUCK")),
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
        new ParserConfig(
            new FakeBuckConfig(
                ImmutableMap.<String, Map<String, String>>of(
                    "project", ImmutableMap.of("temp_files", ".*\\.swp$")))),
        buildFileTreeSupplier,
        buildTargetParser,
        buildFileParserFactory,
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
    // Execute buildTargetGraphForBuildTargets() with multiple targets that require parsing the same
    // build file.
    BuildTarget fooTarget = BuildTarget.builder("//java/com/facebook", "foo").build();
    BuildTarget barTarget = BuildTarget.builder("//java/com/facebook", "bar").build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget, barTarget);

    // The EventBus should be updated with events indicating how parsing ran.
    FakeBuckEventListener listener = new FakeBuckEventListener();
    eventBus.register(listener);

    TargetGraph targetGraph = testParser.buildTargetGraphForBuildTargets(
        buildTargets,
        new ParserConfig(new FakeBuckConfig()),
        eventBus,
        new TestConsole(),
        ImmutableMap.<String, String>of(),
        /* enableProfiling */ false);
    ActionGraph actionGraph = buildActionGraph(eventBus, targetGraph);
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
    // Execute buildTargetGraphForBuildTargets() with a target in a valid file but a bad rule name.
    BuildTarget fooTarget = BuildTarget.builder("//java/com/facebook", "foo").build();
    BuildTarget razTarget = BuildTarget.builder("//java/com/facebook", "raz").build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget, razTarget);

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "No rule found when resolving target //java/com/facebook:raz in build file " +
            "//java/com/facebook/BUCK");

    testParser.buildTargetGraphForBuildTargets(
        buildTargets,
        new ParserConfig(new FakeBuckConfig()),
        BuckEventBusFactory.newInstance(),
        new TestConsole(),
        ImmutableMap.<String, String>of(),
        /* enableProfiling */ false);
  }

  @Test
  public void shouldThrowAnExceptionWhenAnUnknownFlavorIsSeen()
      throws BuildFileParseException, BuildTargetException, InterruptedException, IOException {
    BuildTarget flavored = BuildTarget.builder("//java/com/facebook", "foo")
        .addFlavors(ImmutableFlavor.of("doesNotExist"))
        .build();

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "Unrecognized flavor in target //java/com/facebook:foo#doesNotExist while parsing " +
            "//java/com/facebook/BUCK.");
    testParser.buildTargetGraphForBuildTargets(
        ImmutableSortedSet.of(flavored),
        new ParserConfig(new FakeBuckConfig()),
        BuckEventBusFactory.newInstance(),
        new TestConsole(),
        ImmutableMap.<String, String>of(),
        /* enableProfiling */ false);
  }

  @Test
  public void shouldThrowAnExceptionWhenAFlavorIsAskedOfATargetThatDoesntSupportFlavors()
    throws BuildFileParseException, BuildTargetException, InterruptedException, IOException {
    BuildTarget flavored = BuildTarget.builder("//java/com/facebook", "baz")
        .addFlavors(JavaLibrary.SRC_JAR)
        .build();

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "Target //java/com/facebook:baz (type genrule) does not currently support flavors " +
            "(tried [src])");
    testParser.buildTargetGraphForBuildTargets(
        ImmutableSortedSet.of(flavored),
        new ParserConfig(new FakeBuckConfig()),
        BuckEventBusFactory.newInstance(),
        new TestConsole(),
        ImmutableMap.<String, String>of(),
        /* enableProfiling */ false);
  }

  @Test
  public void testInvalidDepFromValidFile()
      throws IOException, BuildFileParseException, BuildTargetException, InterruptedException {
    // Ensure an exception with a specific message is thrown.
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "Couldn't get dependency '//java/com/facebook/invalid/lib:missing_rule' of target " +
        "'//java/com/facebook/invalid:foo'");

    // Execute buildTargetGraphForBuildTargets() with a target in a valid file but a bad rule name.
    tempDir.newFolder("java", "com", "facebook", "invalid");

    File testInvalidBuildFile = tempDir.newFile("java/com/facebook/invalid/BUCK");
    Files.write(
        "java_library(name = 'foo', deps = ['//java/com/facebook/invalid/lib:missing_rule'])\n" +
        "java_library(name = 'bar')\n",
        testInvalidBuildFile,
        Charsets.UTF_8);

    tempDir.newFolder("java", "com", "facebook", "invalid", "lib");
    tempDir.newFile("java/com/facebook/invalid/lib/BUCK");

    BuildTarget fooTarget = BuildTarget.builder("//java/com/facebook/invalid", "foo").build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget);

    testParser.buildTargetGraphForBuildTargets(
        buildTargets,
        new ParserConfig(new FakeBuckConfig()),
        BuckEventBusFactory.newInstance(),
        new TestConsole(),
        ImmutableMap.<String, String>of(),
        /* enableProfiling */ false);
  }

  @Test
  public void whenAllRulesRequestedWithTrueFilterThenMultipleRulesReturned()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    ImmutableSet<BuildTarget> targets = testParser.filterAllTargetsInProject(
        filesystem,
        new ParserConfig(new FakeBuckConfig()),
        Predicates.<TargetNode<?>>alwaysTrue(),
        new TestConsole(),
        ImmutableMap.<String, String>of(),
        BuckEventBusFactory.newInstance(),
        false /* enableProfiling */);

    ImmutableSet<BuildTarget> expectedTargets = ImmutableSet.of(
        (BuildTarget) BuildTarget.builder("//java/com/facebook", "foo").build(),
        BuildTarget.builder("//java/com/facebook", "bar").build(),
        BuildTarget.builder("//java/com/facebook", "baz").build());
    assertEquals("Should have returned all rules.", expectedTargets, targets);
  }

  @Test
  public void whenAllRulesAreRequestedMultipleTimesThenRulesAreOnlyParsedOnce()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets(), buildFileParserFactory);

    BuckConfig config = new FakeBuckConfig();
    parser.filterAllTargetsInProject(
        filesystem,
        new ParserConfig(config),
        Predicates.<TargetNode<?>>alwaysTrue(),
        new TestConsole(),
        config.getEnvironment(),
        BuckEventBusFactory.newInstance(),
        false /* enableProfiling */);
    parser.filterAllTargetsInProject(
        filesystem,
        new ParserConfig(config),
        Predicates.<TargetNode<?>>alwaysTrue(),
        new TestConsole(),
        config.getEnvironment(),
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
    BuckConfig config = new FakeBuckConfig();
    parser.filterAllTargetsInProject(
        filesystem,
        new ParserConfig(config),
        Predicates.<TargetNode<?>>alwaysTrue(),
        new TestConsole(),
        config.getEnvironment(),
        BuckEventBusFactory.newInstance(),
        false /* enableProfiling */);

    // Process event.
    WatchEvent<Object> event = WatchEvents.createOverflowEvent();
    parser.onFileSystemChange(event);

    // Call filterAllTargetsInProject to request cached rules.
    parser.filterAllTargetsInProject(
        filesystem,
        new ParserConfig(config),
        Predicates.<TargetNode<?>>alwaysTrue(),
        new TestConsole(),
        config.getEnvironment(),
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

    BuckConfig config = new FakeBuckConfig();

    // Call filterAllTargetsInProject to populate the cache.
    parser.filterAllTargetsInProject(
        filesystem,
        new ParserConfig(config),
        Predicates.<TargetNode<?>>alwaysTrue(),
        new TestConsole(),
        ImmutableMap.<String, String>builder()
            .putAll(config.getEnvironment())
            .put("Some Key", "Some Value")
            .build(),
        BuckEventBusFactory.newInstance(),
        false /* enableProfiling */);

    // Call filterAllTargetsInProject to request cached rules.
    parser.filterAllTargetsInProject(
        filesystem,
        new ParserConfig(config),
        Predicates.<TargetNode<?>>alwaysTrue(),
        new TestConsole(),
        ImmutableMap.<String, String>builder()
            .putAll(config.getEnvironment())
            .put("Some Key", "Some Other Value")
            .build(),
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

    BuckConfig config = new FakeBuckConfig();

    // Call filterAllTargetsInProject to populate the cache.
    parser.filterAllTargetsInProject(
        filesystem,
        new ParserConfig(config),
        Predicates.<TargetNode<?>>alwaysTrue(),
        new TestConsole(),
        config.getEnvironment(),
        BuckEventBusFactory.newInstance(),
        false /* enableProfiling */);

    // Call filterAllTargetsInProject to request cached rules with identical environment.
    parser.filterAllTargetsInProject(
        filesystem,
        new ParserConfig(new FakeBuckConfig()),
        Predicates.<TargetNode<?>>alwaysTrue(),
        new TestConsole(),
        config.getEnvironment(),
        BuckEventBusFactory.newInstance(),
        false /* enableProfiling */);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should not have invalidated cache.", 1, buildFileParserFactory.calls);
  }


  // TODO(jimp/devjasta): clean up the horrible ProjectBuildFileParserFactory mess.
  private void parseBuildFile(
      Path buildFile,
      Parser parser,
      ProjectBuildFileParserFactory buildFileParserFactory)
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    BuckConfig config = new FakeBuckConfig();
    try (ProjectBuildFileParser projectBuildFileParser = buildFileParserFactory.createParser(
        new TestConsole(),
        config.getEnvironment(),
        BuckEventBusFactory.newInstance())) {
      parser.parseBuildFile(
          buildFile,
          new ParserConfig(config),
          projectBuildFileParser,
          config.getEnvironment());
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
    WatchEvent<Path> event = createPathEvent(
        MorePaths.relativize(tempDir.getRoot().toPath(), testBuildFile),
        StandardWatchEventKinds.ENTRY_CREATE);
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
    WatchEvent<Path> event = createPathEvent(
        MorePaths.relativize(tempDir.getRoot().toPath(), testBuildFile),
        StandardWatchEventKinds.ENTRY_MODIFY);
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
    WatchEvent<Path> event = createPathEvent(
        MorePaths.relativize(tempDir.getRoot().toPath(), testBuildFile),
        StandardWatchEventKinds.ENTRY_DELETE);
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
    WatchEvent<Path> event = createPathEvent(
        MorePaths.relativize(tempDir.getRoot().toPath(), includedByBuildFile),
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
    WatchEvent<Path> event = createPathEvent(
        MorePaths.relativize(tempDir.getRoot().toPath(), includedByBuildFile),
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
    WatchEvent<Path> event = createPathEvent(
        MorePaths.relativize(tempDir.getRoot().toPath(), includedByBuildFile),
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
    WatchEvent<Path> event = createPathEvent(
        MorePaths.relativize(tempDir.getRoot().toPath(), includedByIncludeFile),
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
    WatchEvent<Path> event = createPathEvent(
        MorePaths.relativize(tempDir.getRoot().toPath(), includedByIncludeFile),
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
    WatchEvent<Path> event = createPathEvent(
        MorePaths.relativize(tempDir.getRoot().toPath(), includedByIncludeFile),
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
    WatchEvent<Path> event = createPathEvent(
        MorePaths.relativize(tempDir.getRoot().toPath(), defaultIncludeFile),
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
    WatchEvent<Path> event = createPathEvent(
        MorePaths.relativize(tempDir.getRoot().toPath(), defaultIncludeFile),
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
    WatchEvent<Path> event = createPathEvent(
        MorePaths.relativize(tempDir.getRoot().toPath(), defaultIncludeFile),
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
    // Execute buildTargetGraphForBuildTargets() with a target in a valid file but a bad rule name.
    tempDir.newFolder("java", "com", "facebook", "generateddeps");

    File testGeneratedDepsBuckFile = tempDir.newFile("java/com/facebook/generateddeps/BUCK");
    Files.write(
        "java_library(name = 'foo')\n" +
            "java_library(name = 'bar')\n" +
            "add_deps(name = 'foo', deps = [':bar'])\n",
        testGeneratedDepsBuckFile,
        Charsets.UTF_8);

    BuildTarget fooTarget = BuildTarget.builder("//java/com/facebook/generateddeps", "foo").build();

    BuildTarget barTarget = BuildTarget.builder("//java/com/facebook/generateddeps", "bar").build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget, barTarget);

    BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    TargetGraph targetGraph = testParser.buildTargetGraphForBuildTargets(
        buildTargets,
        new ParserConfig(new FakeBuckConfig()),
        eventBus,
        new TestConsole(),
        ImmutableMap.<String, String>of(),
        /* enableProfiling */ false);
    ActionGraph graph = buildActionGraph(eventBus, targetGraph);

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

    BuckConfig config = new FakeBuckConfig();
    parser.filterAllTargetsInProject(
        filesystem,
        new ParserConfig(config),
        Predicates.<TargetNode<?>>alwaysTrue(),
        new TestConsole(),
        config.getEnvironment(),
        BuckEventBusFactory.newInstance(),
        false /* enableProfiling */);
    parser.filterAllTargetsInProject(
        filesystem,
        new ParserConfig(
            new FakeBuckConfig(
                ImmutableMap.<String, Map<String, String>>of(
                    "buildfile", ImmutableMap.of("includes", "//bar.py")))),
        Predicates.<TargetNode<?>>alwaysTrue(),
        new TestConsole(),
        config.getEnvironment(),
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

    BuckConfig config = new FakeBuckConfig();
    parser.filterAllTargetsInProject(
        filesystem,
        new ParserConfig(config),
        Predicates.<TargetNode<?>>alwaysTrue(),
        new TestConsole(),
        config.getEnvironment(),
        BuckEventBusFactory.newInstance(),
        false /* enableProfiling */);
    BuildTarget foo = BuildTarget.builder("//java/com/facebook", "foo").build();
    parser.buildTargetGraphForBuildTargets(
        ImmutableList.of(foo),
        new ParserConfig(config),
        BuckEventBusFactory.newInstance(),
        new TestConsole(),
        config.getEnvironment(),
        /* enableProfiling */ false);

    assertEquals("Should have cached build rules.", 1, buildFileParserFactory.calls);
  }

  @Test
  public void whenSingleTargetThenAllRulesRequestedThenRulesAreParsedOnce()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(filesystem, buildRuleTypes);
    Parser parser = createParser(emptyBuildTargets(), buildFileParserFactory);

    BuildTarget foo = BuildTarget.builder("//java/com/facebook", "foo").build();
    BuckConfig config = new FakeBuckConfig();
    parser.buildTargetGraphForBuildTargets(
        ImmutableList.of(foo),
        new ParserConfig(config),
        BuckEventBusFactory.newInstance(),
        new TestConsole(),
        config.getEnvironment(),
        /* enableProfiling */ false);
    parser.filterAllTargetsInProject(
        filesystem,
        new ParserConfig(config),
        Predicates.<TargetNode<?>>alwaysTrue(),
        new TestConsole(),
        config.getEnvironment(),
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

  @Test
  public void whenBuildFilePathChangedThenFlavorsOfTargetsInPathAreInvalidated() throws Exception {
    Parser parser = createParser(emptyBuildTargets());

    tempDir.newFolder("foo");
    tempDir.newFolder("bar");

    File testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(
        "java_library(name = 'foo', visibility=['PUBLIC'])\n",
        testFooBuckFile,
        Charsets.UTF_8);

    File testBarBuckFile = tempDir.newFile("bar/BUCK");
    Files.write(
            "java_library(name = 'bar',\n" +
            "  deps = ['//foo:foo'])\n",
        testBarBuckFile,
        Charsets.UTF_8);

    // Fetch //bar:bar#src to put it in cache.
    BuildTarget barTarget = BuildTarget
        .builder("//bar", "bar")
        .addFlavors(ImmutableFlavor.of("src"))
        .build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(barTarget);

    BuckConfig config = new FakeBuckConfig();

    parser.buildTargetGraphForBuildTargets(
        buildTargets,
        new ParserConfig(config),
        BuckEventBusFactory.newInstance(),
        new TestConsole(),
        config.getEnvironment(),
        /* enableProfiling */ false);

    // Rewrite //bar:bar so it doesn't depend on //foo:foo any more.
    // Delete foo/BUCK and invalidate the cache, which should invalidate
    // the cache entry for //bar:bar#src.
    testFooBuckFile.delete();
    Files.write(
            "java_library(name = 'bar')\n",
        testBarBuckFile,
        Charsets.UTF_8);
    WatchEvent<Path> deleteEvent = createPathEvent(
        Paths.get("foo").resolve("BUCK"),
        StandardWatchEventKinds.ENTRY_DELETE);
    parser.onFileSystemChange(deleteEvent);
    WatchEvent<Path> modifyEvent = createPathEvent(
        Paths.get("bar").resolve("BUCK"),
        StandardWatchEventKinds.ENTRY_MODIFY);
    parser.onFileSystemChange(modifyEvent);

    parser.buildTargetGraphForBuildTargets(
        buildTargets,
        new ParserConfig(config),
        BuckEventBusFactory.newInstance(),
        new TestConsole(),
        config.getEnvironment(),
        /* enableProfiling */ false);
  }

  @Test
  public void whenBuildFileContainsSourcesUnderSymLinkNewSourcesNotAddedUntilCacheCleaned()
      throws Exception {
    // This test depends on creating symbolic links which we cannot do on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    Parser parser = createParser(emptyBuildTargets());

    tempDir.newFolder("bar");
    tempDir.newFile("bar/Bar.java");
    tempDir.newFolder("foo");
    Path rootPath = tempDir.getRoot().toPath();
    java.nio.file.Files.createSymbolicLink(rootPath.resolve("foo/bar"), rootPath.resolve("bar"));

    Path testBuckFile = rootPath.resolve("foo").resolve("BUCK");
    Files.write(
        "java_library(name = 'lib', srcs=glob(['bar/*.java']))\n",
        testBuckFile.toFile(),
        Charsets.UTF_8);

    // Fetch //:lib to put it in cache.
    BuildTarget libTarget = BuildTarget.builder("//foo", "lib").build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(libTarget);

    BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    BuckConfig config = new FakeBuckConfig();

    {
      TargetGraph targetGraph = parser.buildTargetGraphForBuildTargets(
          buildTargets,
          new ParserConfig(config),
          eventBus,
          new TestConsole(),
          config.getEnvironment(),
          /* enableProfiling */ false);
      ActionGraph graph = buildActionGraph(eventBus, targetGraph);

      BuildRule libRule = graph.findBuildRuleByTarget(libTarget);
      assertEquals(ImmutableList.of(Paths.get("foo/bar/Bar.java")), libRule.getInputs());
    }

    tempDir.newFile("bar/Baz.java");
    WatchEvent<Path> createEvent = createPathEvent(
        Paths.get("bar/Baz.java"),
        StandardWatchEventKinds.ENTRY_CREATE);
    parser.onFileSystemChange(createEvent);

    {
      // Even though we've created this new file, the parser can't know it
      // has anything to do with our lib (which looks in foo/*.java)
      // until we clean the parser cache.
      TargetGraph targetGraph = parser.buildTargetGraphForBuildTargets(
          buildTargets,
          new ParserConfig(config),
          eventBus,
          new TestConsole(),
          config.getEnvironment(),
          /* enableProfiling */ false);
      ActionGraph graph = buildActionGraph(eventBus, targetGraph);

      BuildRule libRule = graph.findBuildRuleByTarget(libTarget);
      assertEquals(ImmutableList.of(Paths.get("foo/bar/Bar.java")), libRule.getInputs());
    }

    // Now tell the parser to forget about build files with inputs under symlinks.
    parser.cleanCache();

    {
      TargetGraph targetGraph = parser.buildTargetGraphForBuildTargets(
          buildTargets,
          new ParserConfig(config),
          eventBus,
          new TestConsole(),
          config.getEnvironment(),
          /* enableProfiling */ false);
      ActionGraph graph = buildActionGraph(eventBus, targetGraph);

      BuildRule libRule = graph.findBuildRuleByTarget(libTarget);
      assertEquals(
          ImmutableList.of(Paths.get("foo/bar/Bar.java"), Paths.get("foo/bar/Baz.java")),
          libRule.getInputs());
    }
  }

  @Test
  public void whenBuildFileContainsSourcesUnderSymLinkDeletedSourcesNotRemovedUntilCacheCleaned()
      throws Exception {
    // This test depends on creating symbolic links which we cannot do on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    Parser parser = createParser(emptyBuildTargets());

    tempDir.newFolder("bar");
    tempDir.newFile("bar/Bar.java");
    tempDir.newFolder("foo");
    File bazSourceFile = tempDir.newFile("bar/Baz.java");
    Path rootPath = tempDir.getRoot().toPath();
    java.nio.file.Files.createSymbolicLink(rootPath.resolve("foo/bar"), rootPath.resolve("bar"));

    Path testBuckFile = rootPath.resolve("foo").resolve("BUCK");
    Files.write(
        "java_library(name = 'lib', srcs=glob(['bar/*.java']))\n",
        testBuckFile.toFile(),
        Charsets.UTF_8);

    // Fetch //:lib to put it in cache.
    BuildTarget libTarget = BuildTarget.builder("//foo", "lib").build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(libTarget);

    BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    BuckConfig config = new FakeBuckConfig();

    {
      TargetGraph targetGraph = parser.buildTargetGraphForBuildTargets(
          buildTargets,
          new ParserConfig(config),
          eventBus,
          new TestConsole(),
          config.getEnvironment(),
          /* enableProfiling */ false);
      ActionGraph graph = buildActionGraph(eventBus, targetGraph);

      BuildRule libRule = graph.findBuildRuleByTarget(libTarget);
      assertEquals(
          ImmutableList.of(Paths.get("foo/bar/Bar.java"), Paths.get("foo/bar/Baz.java")),
          libRule.getInputs());
    }

    bazSourceFile.delete();
    WatchEvent<Path> deleteEvent = createPathEvent(
        Paths.get("bar/Baz.java"),
        StandardWatchEventKinds.ENTRY_DELETE);
    parser.onFileSystemChange(deleteEvent);

    {
      // Even though we've deleted a source file, the parser can't know it
      // has anything to do with our lib (which looks in foo/*.java)
      // until we clean the parser cache.
      TargetGraph targetGraph = parser.buildTargetGraphForBuildTargets(
          buildTargets,
          new ParserConfig(config),
          eventBus,
          new TestConsole(),
          config.getEnvironment(),
          /* enableProfiling */ false);
      ActionGraph graph = buildActionGraph(eventBus, targetGraph);

      BuildRule libRule = graph.findBuildRuleByTarget(libTarget);
      assertEquals(
          ImmutableList.of(Paths.get("foo/bar/Bar.java"), Paths.get("foo/bar/Baz.java")),
          libRule.getInputs());
    }

    // Now tell the parser to forget about build files with inputs under symlinks.
    parser.cleanCache();

    {
      TargetGraph targetGraph = parser.buildTargetGraphForBuildTargets(
          buildTargets,
          new ParserConfig(config),
          eventBus,
          new TestConsole(),
          config.getEnvironment(),
          /* enableProfiling */ false);
      ActionGraph graph = buildActionGraph(eventBus, targetGraph);

      BuildRule libRule = graph.findBuildRuleByTarget(libTarget);
      assertEquals(
          ImmutableList.of(Paths.get("foo/bar/Bar.java")),
          libRule.getInputs());
    }
  }

  @Test
  public void buildTargetHashCodePopulatesCorrectly() throws Exception {
    Parser parser = createParser(emptyBuildTargets());

    tempDir.newFolder("foo");

    File testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(
        "java_library(name = 'lib', visibility=['PUBLIC'])\n",
        testFooBuckFile,
        Charsets.UTF_8);

    BuildTarget fooLibTarget = BuildTarget.builder("//foo", "lib").build();

    // We can't precalculate the hash, since it depends on the buck version. Check for the presence
    // of a hash for the right key.
    HashCode hashCode = buildTargetGraphAndGetHashCodes(parser, fooLibTarget).get(fooLibTarget);

    assertNotNull(hashCode);
  }

  @Test
  public void targetWithSourceFileChangesHash() throws Exception {
    Parser parser = createParser(emptyBuildTargets());

    tempDir.newFolder("foo");

    File testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(
        "java_library(name = 'lib', srcs=glob(['*.java']), visibility=['PUBLIC'])\n",
        testFooBuckFile,
        Charsets.UTF_8);
    BuildTarget fooLibTarget = BuildTarget.builder("//foo", "lib").build();
    HashCode original = buildTargetGraphAndGetHashCodes(parser, fooLibTarget).get(fooLibTarget);

    parser = createParser(emptyBuildTargets());
    File testFooJavaFile = tempDir.newFile("foo/Foo.java");
    Files.write(
        "// Ceci n'est pas une Javafile\n",
        testFooJavaFile,
        Charsets.UTF_8);
    HashCode updated = buildTargetGraphAndGetHashCodes(parser, fooLibTarget).get(fooLibTarget);

    assertNotEquals(original, updated);
  }

  @Test
  public void deletingSourceFileChangesHash() throws Exception {
    Parser parser = createParser(emptyBuildTargets());

    tempDir.newFolder("foo");

    File testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(
        "java_library(name = 'lib', srcs=glob(['*.java']), visibility=['PUBLIC'])\n",
        testFooBuckFile,
        Charsets.UTF_8);

    File testFooJavaFile = tempDir.newFile("foo/Foo.java");
    Files.write(
        "// Ceci n'est pas une Javafile\n",
        testFooJavaFile,
        Charsets.UTF_8);

    File testBarJavaFile = tempDir.newFile("foo/Bar.java");
    Files.write(
        "// Seriously, no Java here\n",
        testBarJavaFile,
        Charsets.UTF_8);

    BuildTarget fooLibTarget = BuildTarget.builder("//foo", "lib").build();
    HashCode originalHash = buildTargetGraphAndGetHashCodes(parser, fooLibTarget).get(fooLibTarget);

    assertTrue(testBarJavaFile.delete());
    WatchEvent<Path> deleteEvent = createPathEvent(
        Paths.get("foo/Bar.java"),
        StandardWatchEventKinds.ENTRY_DELETE);
    parser.onFileSystemChange(deleteEvent);

    HashCode updatedHash = buildTargetGraphAndGetHashCodes(parser, fooLibTarget).get(fooLibTarget);

    assertNotEquals(originalHash, updatedHash);
  }

  @Test
  public void renamingSourceFileChangesHash() throws Exception {
    Parser parser = createParser(emptyBuildTargets());

    tempDir.newFolder("foo");

    File testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(
        "java_library(name = 'lib', srcs=glob(['*.java']), visibility=['PUBLIC'])\n",
        testFooBuckFile,
        Charsets.UTF_8);

    File testFooJavaFile = tempDir.newFile("foo/Foo.java");
    Files.write(
        "// Ceci n'est pas une Javafile\n",
        testFooJavaFile,
        Charsets.UTF_8);

    BuildTarget fooLibTarget = BuildTarget.builder("//foo", "lib").build();

    HashCode originalHash = buildTargetGraphAndGetHashCodes(parser, fooLibTarget).get(fooLibTarget);

    Path testFooJavaFilePath = testFooJavaFile.toPath();
    java.nio.file.Files.move(testFooJavaFilePath, testFooJavaFilePath.resolveSibling("Bar.java"));
    WatchEvent<Path> deleteEvent = createPathEvent(
        Paths.get("foo/Foo.java"),
        StandardWatchEventKinds.ENTRY_DELETE);
    WatchEvent<Path> createEvent = createPathEvent(
        Paths.get("foo/Bar.java"),
        StandardWatchEventKinds.ENTRY_CREATE);
    parser.onFileSystemChange(deleteEvent);
    parser.onFileSystemChange(createEvent);

    HashCode updatedHash = buildTargetGraphAndGetHashCodes(parser, fooLibTarget).get(fooLibTarget);

    assertNotEquals(originalHash, updatedHash);
  }

  @Test
  public void twoBuildTargetHashCodesPopulatesCorrectly() throws Exception {
    Parser parser = createParser(emptyBuildTargets());

    tempDir.newFolder("foo");

    File testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(
        "java_library(name = 'lib', visibility=['PUBLIC'])\n" +
        "java_library(name = 'lib2', visibility=['PUBLIC'])\n",
        testFooBuckFile,
        Charsets.UTF_8);

    BuildTarget fooLibTarget = BuildTarget.builder("//foo", "lib").build();
    BuildTarget fooLib2Target = BuildTarget.builder("//foo", "lib2").build();

    ImmutableMap<BuildTarget, HashCode> hashes = buildTargetGraphAndGetHashCodes(
        parser,
        fooLibTarget,
        fooLib2Target);

    assertNotNull(hashes.get(fooLibTarget));
    assertNotNull(hashes.get(fooLib2Target));

    assertNotEquals(hashes.get(fooLibTarget), hashes.get(fooLib2Target));
  }

  @Test
  public void addingDepToTargetChangesHashOfDependingTargetOnly() throws Exception {
    Parser parser = createParser(emptyBuildTargets());

    tempDir.newFolder("foo");

    File testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(
        "java_library(name = 'lib', deps = [], visibility=['PUBLIC'])\n" +
        "java_library(name = 'lib2', deps = [], visibility=['PUBLIC'])\n",
        testFooBuckFile,
        Charsets.UTF_8);

    BuildTarget fooLibTarget = BuildTarget.builder("//foo", "lib").build();
    BuildTarget fooLib2Target = BuildTarget.builder("//foo", "lib2").build();
    ImmutableMap<BuildTarget, HashCode> hashes = buildTargetGraphAndGetHashCodes(
        parser,
        fooLibTarget,
        fooLib2Target);
    HashCode libKey = hashes.get(fooLibTarget);
    HashCode lib2Key = hashes.get(fooLib2Target);

    parser = createParser(emptyBuildTargets());
    Files.write(
        "java_library(name = 'lib', deps = [], visibility=['PUBLIC'])\n" +
        "java_library(name = 'lib2', deps = [':lib'], visibility=['PUBLIC'])\n",
        testFooBuckFile,
        Charsets.UTF_8);

    hashes = buildTargetGraphAndGetHashCodes(
        parser,
        fooLibTarget,
        fooLib2Target);

    assertEquals(libKey, hashes.get(fooLibTarget));
    assertNotEquals(lib2Key, hashes.get(fooLib2Target));
  }

  private ImmutableMap<BuildTarget, HashCode> buildTargetGraphAndGetHashCodes(
      Parser parser,
      BuildTarget... buildTargets) throws Exception {
    // Build the target graph so we can access the hash code cache.
    //
    // TODO(user): It'd be really nice if parser.getBuildTargetHashCodeCache()
    // knew how to run the parser for targets that weren't yet parsed, but
    // then we'd need to pass in the BuckEventBusFactory, Console, etc.
    // to every call to get()..
    ImmutableList<BuildTarget> buildTargetsList = ImmutableList.copyOf(buildTargets);
    BuckConfig config = new FakeBuckConfig();
    parser.buildTargetGraphForBuildTargets(
        buildTargetsList,
        new ParserConfig(config),
        BuckEventBusFactory.newInstance(),
        new TestConsole(),
        config.getEnvironment(),
        /* enableProfiling */ false);

    return parser.getBuildTargetHashCodeCache().getAll(buildTargetsList);
  }

  private ActionGraph buildActionGraph(BuckEventBus eventBus, TargetGraph targetGraph) {
    return new TargetGraphToActionGraph(eventBus, new BuildTargetNodeToBuildRuleTransformer())
        .apply(targetGraph);
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
        Console console,
        ImmutableMap<String, String> environment,
        BuckEventBus buckEventBus) {
      PythonBuckConfig config = new PythonBuckConfig(new FakeBuckConfig(environment));
      return new TestProjectBuildFileParser(config.getPythonInterpreter());
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
            new ParserConfig(
                new FakeBuckConfig(
                    ImmutableMap.<String, Map<String, String>>of(
                        "buildfile",
                        ImmutableMap.of("includes", "//java/com/facebook/defaultIncludeFile"),
                        "python",
                        ImmutableMap.of("interpreter", pythonInterpreter)))),
            buildRuleTypes.getAllDescriptions(),
            new TestConsole(),
            ImmutableMap.<String, String>of(),
            BuckEventBusFactory.newInstance());
      }

      @Override
      protected List<Map<String, Object>> getAllRulesInternal(Path buildFile)
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
