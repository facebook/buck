/*
 * Copyright 2015-present Facebook, Inc.
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

import static com.facebook.buck.parser.ParserConfig.DEFAULT_BUILD_FILE_NAME;
import static com.facebook.buck.testutil.WatchEventsForTests.createPathEvent;
import static com.google.common.base.Charsets.UTF_8;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.BuildTargetNodeToBuildRuleTransformer;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.FakeBuckEventListener;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.json.ParseBuckFileEvent;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphToActionGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.shell.GenruleDescription;
import com.facebook.buck.testutil.WatchEventsForTests;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.Subscribe;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.SortedMap;
import java.util.concurrent.Executors;

@RunWith(Parameterized.class)
public class ParserTest {

  @Rule
  public TemporaryPaths tempDir = new TemporaryPaths();
  @Rule
  public ExpectedException thrown = ExpectedException.none();

  private final int threads;
  private final boolean parallelParsing;
  private Path defaultIncludeFile;
  private Path includedByIncludeFile;
  private Path includedByBuildFile;
  private Path testBuildFile;
  private Parser parser;
  private ProjectFilesystem filesystem;
  private Path cellRoot;
  private BuckEventBus eventBus;
  private Cell cell;
  private ParseEventStartedCounter counter;
  private ListeningExecutorService executorService;

  public ParserTest(int threads, boolean parallelParsing) {
    this.threads = threads;
    this.parallelParsing = parallelParsing;
  }

  @Parameterized.Parameters
  public static Collection<Object[]> generateData() {
    return Arrays.asList(new Object[][] {
        { 1, false, },
        { 1, true, },
        { 2, true, },
    });
  }

  @Before
  public void setUp() throws IOException, InterruptedException {
    tempDir.newFolder("java", "com", "facebook");

    defaultIncludeFile = tempDir.newFile(
        "java/com/facebook/defaultIncludeFile").toRealPath();
    Files.write(defaultIncludeFile, "\n".getBytes(UTF_8));

    includedByIncludeFile = tempDir.newFile(
        "java/com/facebook/includedByIncludeFile").toRealPath();
    Files.write(includedByIncludeFile, "\n".getBytes(UTF_8));

    includedByBuildFile = tempDir.newFile(
        "java/com/facebook/includedByBuildFile").toRealPath();
    Files.write(
        includedByBuildFile,
        "include_defs('//java/com/facebook/includedByIncludeFile')\n".getBytes(UTF_8));

    testBuildFile = tempDir.newFile("java/com/facebook/BUCK").toRealPath();
    Files.write(
        testBuildFile,
        ("include_defs('//java/com/facebook/includedByBuildFile')\n" +
            "java_library(name = 'foo')\n" +
            "java_library(name = 'bar')\n" +
            "genrule(name = 'baz', out = '')\n").getBytes(UTF_8));

    tempDir.newFile("bar.py");

    // Create a temp directory with some build files.
    Path root = tempDir.getRoot().toRealPath();
    filesystem = new ProjectFilesystem(root);
    cellRoot = filesystem.getRootPath();
    eventBus = BuckEventBusFactory.newInstance();

    ImmutableMap.Builder<String, ImmutableMap<String, String>> configSectionsBuilder =
        ImmutableMap.builder();
    configSectionsBuilder
        .put("buildfile", ImmutableMap.of("includes", "//java/com/facebook/defaultIncludeFile"));
    if (parallelParsing) {
      configSectionsBuilder.put(
          "project",
          ImmutableMap.of(
              "temp_files", ".*\\.swp$",
              "parallel_parsing", "true",
              "parsing_threads", Integer.toString(threads)));
    } else {
      configSectionsBuilder.put("project", ImmutableMap.of("temp_files", ".*\\.swp$"));
    }
    BuckConfig config = FakeBuckConfig.builder()
        .setFilesystem(filesystem)
        .setSections(configSectionsBuilder.build())
        .build();

    cell = new TestCellBuilder()
        .setFilesystem(filesystem)
        .setBuckConfig(config)
        .build();

    DefaultTypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    parser = new Parser(
        new ParserConfig(cell.getBuckConfig()),
        typeCoercerFactory,
        new ConstructorArgMarshaller(typeCoercerFactory));

    counter = new ParseEventStartedCounter();
    eventBus.register(counter);

    executorService = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(threads));
  }

  @After
  public void tearDown() {
    executorService.shutdown();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testParseBuildFilesForTargetsWithOverlappingTargets() throws Exception {
    // Execute buildTargetGraphForBuildTargets() with multiple targets that require parsing the same
    // build file.
    BuildTarget fooTarget = BuildTarget.builder(cellRoot, "//java/com/facebook", "foo").build();
    BuildTarget barTarget = BuildTarget.builder(cellRoot, "//java/com/facebook", "bar").build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget, barTarget);

    // The EventBus should be updated with events indicating how parsing ran.
    FakeBuckEventListener listener = new FakeBuckEventListener();
    eventBus.register(listener);

    TargetGraph targetGraph = parser.buildTargetGraph(
        eventBus,
        cell,
        false,
        executorService,
        buildTargets);
    BuildRuleResolver resolver = buildActionGraph(eventBus, targetGraph);
    BuildRule fooRule = resolver.requireRule(fooTarget);
    assertNotNull(fooRule);
    BuildRule barRule = resolver.requireRule(barTarget);
    assertNotNull(barRule);

    Iterable<ParseEvent> events = Iterables.filter(listener.getEvents(), ParseEvent.class);
    assertThat(events, Matchers.contains(
            Matchers.hasProperty("buildTargets", equalTo(buildTargets)),
            Matchers.allOf(
                Matchers.hasProperty("buildTargets", equalTo(buildTargets)),
                Matchers.hasProperty("graph", equalTo(Optional.of(targetGraph)))
            )));
  }

  @Test
  public void testMissingBuildRuleInValidFile()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Execute buildTargetGraphForBuildTargets() with a target in a valid file but a bad rule name.
    BuildTarget fooTarget = BuildTarget.builder(cellRoot, "//java/com/facebook", "foo").build();
    BuildTarget razTarget = BuildTarget.builder(cellRoot, "//java/com/facebook", "raz").build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget, razTarget);

    thrown.expectMessage(
        "No rule found when resolving target //java/com/facebook:raz in build file " +
            "//java/com/facebook/BUCK");
    thrown.expectMessage(
        "Defined in file: " +
            filesystem.resolve(razTarget.getBasePath()).resolve(DEFAULT_BUILD_FILE_NAME));

    parser.buildTargetGraph(
        eventBus,
        cell,
        false,
        executorService,
        buildTargets);
  }

  @Test
  public void testMissingBuildFile()
      throws InterruptedException, BuildFileParseException, IOException, BuildTargetException {
    BuildTarget target = BuildTarget.builder(cellRoot, "//path/to/nowhere", "nowhere").build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(target);

    thrown.expect(Cell.MissingBuildFileException.class);
    thrown.expectMessage(
        "No build file at path/to/nowhere/BUCK when resolving target " +
            "//path/to/nowhere:nowhere");

    parser.buildTargetGraph(
        eventBus,
        cell,
        false,
        executorService,
        buildTargets);
  }

  @Test
  public void shouldThrowAnExceptionIfConstructorArgMashallingFails()
      throws IOException, BuildFileParseException, InterruptedException {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("found ////cake:walk");

    Path buckFile = cellRoot.resolve("BUCK");
    Files.write(
        buckFile,
        "genrule(name = 'cake', out = 'file.txt', cmd = '$(exe ////cake:walk) > $OUT')"
            .getBytes(UTF_8));

    parser.getAllTargetNodes(eventBus, cell, false, executorService, buckFile);
  }

  @Test
  public void shouldThrowAnExceptionIfADepIsInAFileThatCannotBeParsed()
      throws IOException, InterruptedException, BuildTargetException, BuildFileParseException {
    thrown.expectMessage("Parse error for build file");
    thrown.expectMessage(Paths.get("foo/BUCK").toString());

    Path buckFile = cellRoot.resolve("BUCK");
    Files.write(
        buckFile,
        "genrule(name = 'cake', out = 'foo.txt', cmd = '$(exe //foo:bar) > $OUT')".getBytes(UTF_8));

    buckFile = cellRoot.resolve("foo/BUCK");
    Files.createDirectories(buckFile.getParent());
    Files.write(
        buckFile,
        "I do not parse as python".getBytes(UTF_8));

    parser.buildTargetGraph(
        eventBus,
        cell,
        false,
        executorService,
        Collections.singleton(BuildTargetFactory.newInstance(cell.getFilesystem(), "//:cake")));
  }

  @Test
  @Ignore("Existing parser just says that's there's a problem parsing the file")
  public void shouldThrowAnExceptionIfMultipleTargetsAreDefinedWithTheSameName()
      throws IOException, BuildFileParseException, InterruptedException {
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("Duplicate rule definition found (cake).");

    Path buckFile = cellRoot.resolve("BUCK");
    Files.write(
        buckFile,
        ("export_file(name = 'cake', src = 'hello.txt')\n" +
        "genrule(name = 'cake', out = 'file.txt', cmd = 'touch $OUT')\n").getBytes(UTF_8));

    parser.getAllTargetNodes(eventBus, cell, false, executorService, buckFile);
  }

  @Test
  public void shouldThrowAnExceptionWhenAnUnknownFlavorIsSeen()
      throws BuildFileParseException, BuildTargetException, InterruptedException, IOException {
    BuildTarget flavored = BuildTarget.builder(cellRoot, "//java/com/facebook", "foo")
        .addFlavors(ImmutableFlavor.of("doesNotExist"))
        .build();

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "Unrecognized flavor in target //java/com/facebook:foo#doesNotExist while parsing " +
            "//java/com/facebook/BUCK.");
    parser.buildTargetGraph(
        eventBus,
        cell,
        false,
        executorService,
        ImmutableSortedSet.of(flavored));
  }

  @Test
  public void shouldThrowAnExceptionWhenAFlavorIsAskedOfATargetThatDoesntSupportFlavors()
      throws BuildFileParseException, BuildTargetException, InterruptedException, IOException {
    BuildTarget flavored = BuildTarget.builder(cellRoot, "//java/com/facebook", "baz")
        .addFlavors(JavaLibrary.SRC_JAR)
        .build();

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "Target //java/com/facebook:baz (type genrule) does not currently support flavors " +
            "(tried [src])");
    parser.buildTargetGraph(
        eventBus,
        cell,
        false,
        executorService,
        ImmutableSortedSet.of(flavored));
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

    Path testInvalidBuildFile = tempDir.newFile("java/com/facebook/invalid/BUCK");
    Files.write(
        testInvalidBuildFile,
        ("java_library(name = 'foo', deps = ['//java/com/facebook/invalid/lib:missing_rule'])\n" +
            "java_library(name = 'bar')\n").getBytes(UTF_8));

    tempDir.newFolder("java", "com", "facebook", "invalid", "lib");
    tempDir.newFile("java/com/facebook/invalid/lib/BUCK");

    BuildTarget fooTarget =
        BuildTarget.builder(cellRoot, "//java/com/facebook/invalid", "foo").build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget);

    parser.buildTargetGraph(
        eventBus,
        cell,
        false,
        executorService,
        buildTargets);
  }

  @Test
  public void whenAllRulesRequestedWithTrueFilterThenMultipleRulesReturned()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    ImmutableSet<BuildTarget> targets = filterAllTargetsInProject(
        parser,
        cell,
        Predicates.<TargetNode<?>>alwaysTrue(),
        BuckEventBusFactory.newInstance(),
        executorService);

    ImmutableSet<BuildTarget> expectedTargets = ImmutableSet.of(
        BuildTarget.builder(cellRoot, "//java/com/facebook", "foo").build(),
        BuildTarget.builder(cellRoot, "//java/com/facebook", "bar").build(),
        BuildTarget.builder(cellRoot, "//java/com/facebook", "baz").build());
    assertEquals("Should have returned all rules.", expectedTargets, targets);
  }

  @Test
  public void whenAllRulesAreRequestedMultipleTimesThenRulesAreOnlyParsedOnce()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    filterAllTargetsInProject(
        parser,
        cell,
        Predicates.<TargetNode<?>>alwaysTrue(),
        eventBus,
        executorService);
    filterAllTargetsInProject(
        parser,
        cell,
        Predicates.<TargetNode<?>>alwaysTrue(),
        eventBus,
        executorService);

    assertEquals("Should have cached build rules.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfNonPathEventThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call filterAllTargetsInProject to populate the cache.
    filterAllTargetsInProject(
        parser,
        cell,
        Predicates.<TargetNode<?>>alwaysTrue(),
        eventBus,
        executorService);

    // Process event.
    WatchEvent<Object> event = WatchEventsForTests.createOverflowEvent();
    parser.onFileSystemChange(event);

    // Call filterAllTargetsInProject to request cached rules.
    filterAllTargetsInProject(
        parser,
        cell,
        Predicates.<TargetNode<?>>alwaysTrue(),
        eventBus,
        executorService);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenEnvironmentChangesThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    BuckConfig config = FakeBuckConfig.builder()
        .setFilesystem(filesystem)
        .setEnvironment(ImmutableMap.of("Some Key", "Some Value", "PATH", System.getenv("PATH")))
        .build();

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    // Call filterAllTargetsInProject to populate the cache.
    filterAllTargetsInProject(
        parser,
        cell,
        Predicates.<TargetNode<?>>alwaysTrue(),
        eventBus,
        executorService);

    // Call filterAllTargetsInProject to request cached rules.
    config = FakeBuckConfig.builder()
        .setFilesystem(filesystem)
        .setEnvironment(
            ImmutableMap.of("Some Key", "Some Other Value", "PATH", System.getenv("PATH")))
        .build();

    cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    filterAllTargetsInProject(
        parser,
        cell,
        Predicates.<TargetNode<?>>alwaysTrue(),
        eventBus,
        executorService);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenEnvironmentNotChangedThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    BuckConfig config = FakeBuckConfig.builder()
        .setFilesystem(filesystem)
        .setEnvironment(ImmutableMap.of("Some Key", "Some Value", "PATH", System.getenv("PATH")))
        .build();

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    // Call filterAllTargetsInProject to populate the cache.
    filterAllTargetsInProject(
        parser,
        cell,
        Predicates.<TargetNode<?>>alwaysTrue(),
        eventBus,
        executorService);

    // Call filterAllTargetsInProject to request cached rules with identical environment.
    filterAllTargetsInProject(
        parser,
        cell,
        Predicates.<TargetNode<?>>alwaysTrue(),
        eventBus,
        executorService);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should not have invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfBuildFileAddThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Process event.
    WatchEvent<Path> event = createPathEvent(
        MorePaths.relativize(tempDir.getRoot().toRealPath(), testBuildFile),
        StandardWatchEventKinds.ENTRY_CREATE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfBuildFileChangeThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Process event.
    WatchEvent<Path> event = createPathEvent(
        MorePaths.relativize(tempDir.getRoot().toRealPath(), testBuildFile),
        StandardWatchEventKinds.ENTRY_MODIFY);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfBuildFileDeleteThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Process event.
    WatchEvent<Path> event = createPathEvent(
        MorePaths.relativize(tempDir.getRoot().toRealPath(), testBuildFile),
        StandardWatchEventKinds.ENTRY_DELETE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfIncludeFileAddThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Process event.
    WatchEvent<Path> event = createPathEvent(
        MorePaths.relativize(tempDir.getRoot().toRealPath(), includedByBuildFile),
        StandardWatchEventKinds.ENTRY_CREATE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfIncludeFileChangeThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Process event.
    WatchEvent<Path> event = createPathEvent(
        MorePaths.relativize(tempDir.getRoot().toRealPath(), includedByBuildFile),
        StandardWatchEventKinds.ENTRY_MODIFY);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfIncludeFileDeleteThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Process event.
    WatchEvent<Path> event = createPathEvent(
        MorePaths.relativize(tempDir.getRoot().toRealPath(), includedByBuildFile),
        StandardWatchEventKinds.ENTRY_DELETE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOf2ndOrderIncludeFileAddThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Process event.
    WatchEvent<Path> event = createPathEvent(
        MorePaths.relativize(tempDir.getRoot().toRealPath(), includedByIncludeFile),
        StandardWatchEventKinds.ENTRY_CREATE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOf2ndOrderIncludeFileChangeThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Process event.
    WatchEvent<Path> event = createPathEvent(
        MorePaths.relativize(tempDir.getRoot().toRealPath(), includedByIncludeFile),
        StandardWatchEventKinds.ENTRY_MODIFY);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOf2ndOrderIncludeFileDeleteThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Process event.
    WatchEvent<Path> event = createPathEvent(
        MorePaths.relativize(tempDir.getRoot().toRealPath(), includedByIncludeFile),
        StandardWatchEventKinds.ENTRY_DELETE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfDefaultIncludeFileAddThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Process event.
    WatchEvent<Path> event = createPathEvent(
        MorePaths.relativize(tempDir.getRoot().toRealPath(), defaultIncludeFile),
        StandardWatchEventKinds.ENTRY_CREATE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfDefaultIncludeFileChangeThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Process event.
    WatchEvent<Path> event = createPathEvent(
        MorePaths.relativize(tempDir.getRoot().toRealPath(), defaultIncludeFile),
        StandardWatchEventKinds.ENTRY_MODIFY);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfDefaultIncludeFileDeleteThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Process event.
    WatchEvent<Path> event = createPathEvent(
        MorePaths.relativize(tempDir.getRoot().toRealPath(), defaultIncludeFile),
        StandardWatchEventKinds.ENTRY_DELETE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  // TODO(shs96c): avoid invalidation when arbitrary contained (possibly backup) files are added.
  public void whenNotifiedOfContainedFileAddThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Process event.
    WatchEvent<Path> event = createPathEvent(
        Paths.get("java/com/facebook/SomeClass.java"),
        StandardWatchEventKinds.ENTRY_CREATE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfContainedFileAddCachedAncestorsAreInvalidatedWithoutBoundaryChecks()
      throws Exception {
    BuckConfig config = FakeBuckConfig.builder()
        .setFilesystem(filesystem)
        .setSections(
            "[buildfile]",
            "includes = //java/com/facebook/defaultIncludeFile",
            "[project]",
            "check_package_boundary = false",
            "temp_files = ''")
        .build();
    Cell cell = new TestCellBuilder()
        .setFilesystem(filesystem)
        .setBuckConfig(config)
        .build();

    Path testAncestorBuildFile = tempDir.newFile("java/BUCK").toRealPath();
    Files.write(testAncestorBuildFile, "java_library(name = 'root')\n".getBytes(UTF_8));

    // Call parseBuildFile to populate the cache.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testAncestorBuildFile);


    // Process event.
    WatchEvent<Path> event = createPathEvent(Paths.get("java/com/facebook/SomeClass.java"),
        StandardWatchEventKinds.ENTRY_CREATE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testAncestorBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfContainedFileChangeThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Process event.
    WatchEvent<Path> event = createPathEvent(Paths.get("java/com/facebook/SomeClass.java"),
        StandardWatchEventKinds.ENTRY_MODIFY);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Test that the second parseBuildFile call did not repopulate the cache.
    assertEquals("Should have not invalidated cache.", 1, counter.calls);
  }

  @Test
  // TODO(shs96c): avoid invalidation when arbitrary contained (possibly backup) files are deleted.
  public void whenNotifiedOfContainedFileDeleteThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Process event.
    WatchEvent<Path> event = createPathEvent(Paths.get("java/com/facebook/SomeClass.java"),
        StandardWatchEventKinds.ENTRY_DELETE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfContainedTempFileAddThenCachedRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Process event.
    WatchEvent<Path> event = createPathEvent(Paths.get("java/com/facebook/MumbleSwp.Java.swp"),
        StandardWatchEventKinds.ENTRY_CREATE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should not have invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfContainedTempFileChangeThenCachedRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Process event.
    WatchEvent<Path> event = createPathEvent(Paths.get("java/com/facebook/MumbleSwp.Java.swp"),
        StandardWatchEventKinds.ENTRY_MODIFY);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should not have invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfContainedTempFileDeleteThenCachedRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Process event.
    WatchEvent<Path> event = createPathEvent(Paths.get("java/com/facebook/MumbleSwp.Java.swp"),
        StandardWatchEventKinds.ENTRY_DELETE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should not have invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfUnrelatedFileAddThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Process event.
    WatchEvent<Path> event = createPathEvent(Paths.get("SomeClass.java__backup"),
        StandardWatchEventKinds.ENTRY_CREATE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Test that the second parseBuildFile call did not repopulate the cache.
    assertEquals("Should have not invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfUnrelatedFileChangeThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Process event.
    WatchEvent<Path> event = createPathEvent(Paths.get("SomeClass.java__backup"),
        StandardWatchEventKinds.ENTRY_MODIFY);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Test that the second parseBuildFile call did not repopulate the cache.
    assertEquals("Should have not invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfUnrelatedFileDeleteThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Process event.
    WatchEvent<Path> event = createPathEvent(
        Paths.get("SomeClass.java__backup"),
        StandardWatchEventKinds.ENTRY_DELETE);
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    parser.getRawTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testBuildFile);

    // Test that the second parseBuildFile call did not repopulate the cache.
    assertEquals("Should have not invalidated cache.", 1, counter.calls);
  }

  @Test
  public void testGeneratedDeps() throws Exception {
    // Execute buildTargetGraphForBuildTargets() with a target in a valid file but a bad rule name.
    tempDir.newFolder("java", "com", "facebook", "generateddeps");

    Path testGeneratedDepsBuckFile = tempDir.newFile("java/com/facebook/generateddeps/BUCK");
    Files.write(
        testGeneratedDepsBuckFile,
        ("java_library(name = 'foo')\n" +
            "java_library(name = 'bar')\n" +
            "add_deps(name = 'foo', deps = [':bar'])\n").getBytes(UTF_8));

    BuildTarget fooTarget = BuildTarget.builder(
        tempDir.getRoot().toRealPath(),
        "//java/com/facebook/generateddeps",
        "foo").build();

    BuildTarget barTarget = BuildTarget.builder(
        tempDir.getRoot().toRealPath(),
        "//java/com/facebook/generateddeps",
        "bar").build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget, barTarget);

    TargetGraph targetGraph = parser.buildTargetGraph(
        eventBus,
        cell,
        false,
        executorService,
        buildTargets);
    BuildRuleResolver resolver = buildActionGraph(eventBus, targetGraph);

    BuildRule fooRule = resolver.requireRule(fooTarget);
    assertNotNull(fooRule);
    BuildRule barRule = resolver.requireRule(barTarget);
    assertNotNull(barRule);

    assertEquals(ImmutableSet.of(barRule), fooRule.getDeps());
  }

  @Test
  public void whenAllRulesAreRequestedWithDifferingIncludesThenRulesAreParsedTwice()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    filterAllTargetsInProject(
        parser,
        cell,
        Predicates.<TargetNode<?>>alwaysTrue(),
        eventBus,
        executorService);

    BuckConfig config = FakeBuckConfig.builder()
        .setFilesystem(filesystem)
        .setSections(
            ImmutableMap.of(
                ParserConfig.BUILDFILE_SECTION_NAME,
                ImmutableMap.of(ParserConfig.INCLUDES_PROPERTY_NAME, "//bar.py")))
        .build();
    Cell cell = new TestCellBuilder()
        .setFilesystem(filesystem)
        .setBuckConfig(config)
        .build();

    filterAllTargetsInProject(
        parser,
        cell,
        Predicates.<TargetNode<?>>alwaysTrue(),
        eventBus,
        executorService);

    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenAllRulesThenSingleTargetRequestedThenRulesAreParsedOnce()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    filterAllTargetsInProject(
        parser,
        cell,
        Predicates.<TargetNode<?>>alwaysTrue(),
        eventBus,
        executorService);
    BuildTarget foo = BuildTarget.builder(cellRoot, "//java/com/facebook", "foo").build();
    parser.buildTargetGraph(
        eventBus,
        cell,
        false,
        executorService,
        ImmutableList.of(foo));

    assertEquals("Should have cached build rules.", 1, counter.calls);
  }

  @Test
  public void whenSingleTargetThenAllRulesRequestedThenRulesAreParsedOnce()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    BuildTarget foo = BuildTarget.builder(cellRoot, "//java/com/facebook", "foo").build();
    parser.buildTargetGraph(
        eventBus,
        cell,
        false,
        executorService,
        ImmutableList.of(foo));
    filterAllTargetsInProject(
        parser,
        cell,
        Predicates.<TargetNode<?>>alwaysTrue(),
        eventBus,
        executorService);

    assertEquals("Should have replaced build rules", 1, counter.calls);
  }

  @Test
  public void whenBuildFilePathChangedThenFlavorsOfTargetsInPathAreInvalidated() throws Exception {
    tempDir.newFolder("foo");
    tempDir.newFolder("bar");

    Path testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(
        testFooBuckFile,
        "java_library(name = 'foo', visibility=['PUBLIC'])\n".getBytes(UTF_8));

    Path testBarBuckFile = tempDir.newFile("bar/BUCK");
    Files.write(
        testBarBuckFile,
        ("java_library(name = 'bar',\n" +
            "  deps = ['//foo:foo'])\n").getBytes(UTF_8));

    // Fetch //bar:bar#src to put it in cache.
    BuildTarget barTarget = BuildTarget
        .builder(cellRoot, "//bar", "bar")
        .addFlavors(ImmutableFlavor.of("src"))
        .build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(barTarget);

    parser.buildTargetGraph(
        eventBus,
        cell,
        false,
        executorService,
        buildTargets);

    // Rewrite //bar:bar so it doesn't depend on //foo:foo any more.
    // Delete foo/BUCK and invalidate the cache, which should invalidate
    // the cache entry for //bar:bar#src.
    Files.delete(testFooBuckFile);
    Files.write(testBarBuckFile, "java_library(name = 'bar')\n".getBytes(UTF_8));
    WatchEvent<Path> deleteEvent = createPathEvent(
        Paths.get("foo").resolve("BUCK"),
        StandardWatchEventKinds.ENTRY_DELETE);
    parser.onFileSystemChange(deleteEvent);
    WatchEvent<Path> modifyEvent = createPathEvent(
        Paths.get("bar").resolve("BUCK"),
        StandardWatchEventKinds.ENTRY_MODIFY);
    parser.onFileSystemChange(modifyEvent);

    parser.buildTargetGraph(
        eventBus,
        cell,
        false,
        executorService,
        buildTargets);
  }

  @Test
  public void targetWithSourceFileChangesHash() throws Exception {
    tempDir.newFolder("foo");

    Path testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(
        testFooBuckFile,
        "java_library(name = 'lib', srcs=glob(['*.java']), visibility=['PUBLIC'])\n"
            .getBytes(UTF_8));
    BuildTarget fooLibTarget = BuildTarget.builder(cellRoot, "//foo", "lib").build();
    HashCode original = buildTargetGraphAndGetHashCodes(parser, fooLibTarget).get(fooLibTarget);

    DefaultTypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    parser = new Parser(
        new ParserConfig(cell.getBuckConfig()),
        typeCoercerFactory,
        new ConstructorArgMarshaller(typeCoercerFactory));
    Path testFooJavaFile = tempDir.newFile("foo/Foo.java");
    Files.write(testFooJavaFile, "// Ceci n'est pas une Javafile\n".getBytes(UTF_8));
    HashCode updated = buildTargetGraphAndGetHashCodes(parser, fooLibTarget).get(fooLibTarget);

    assertNotEquals(original, updated);
  }

  @Test
  public void deletingSourceFileChangesHash() throws Exception {
    tempDir.newFolder("foo");

    Path testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(
        testFooBuckFile,
        "java_library(name = 'lib', srcs=glob(['*.java']), visibility=['PUBLIC'])\n"
            .getBytes(UTF_8));

    Path testFooJavaFile = tempDir.newFile("foo/Foo.java");
    Files.write(testFooJavaFile, "// Ceci n'est pas une Javafile\n".getBytes(UTF_8));

    Path testBarJavaFile = tempDir.newFile("foo/Bar.java");
    Files.write(testBarJavaFile, "// Seriously, no Java here\n".getBytes(UTF_8));

    BuildTarget fooLibTarget = BuildTarget.builder(cellRoot, "//foo", "lib").build();
    HashCode originalHash = buildTargetGraphAndGetHashCodes(parser, fooLibTarget).get(fooLibTarget);

    Files.delete(testBarJavaFile);
    WatchEvent<Path> deleteEvent = createPathEvent(
        Paths.get("foo/Bar.java"),
        StandardWatchEventKinds.ENTRY_DELETE);
    parser.onFileSystemChange(deleteEvent);

    HashCode updatedHash = buildTargetGraphAndGetHashCodes(parser, fooLibTarget).get(fooLibTarget);

    assertNotEquals(originalHash, updatedHash);
  }

  @Test
  public void renamingSourceFileChangesHash() throws Exception {
    tempDir.newFolder("foo");

    Path testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(
        testFooBuckFile,
        "java_library(name = 'lib', srcs=glob(['*.java']), visibility=['PUBLIC'])\n"
            .getBytes(UTF_8));

    Path testFooJavaFile = tempDir.newFile("foo/Foo.java");
    Files.write(testFooJavaFile, "// Ceci n'est pas une Javafile\n".getBytes(UTF_8));

    BuildTarget fooLibTarget = BuildTarget.builder(cellRoot, "//foo", "lib").build();

    HashCode originalHash = buildTargetGraphAndGetHashCodes(parser, fooLibTarget).get(fooLibTarget);

    Files.move(testFooJavaFile, testFooJavaFile.resolveSibling("Bar.java"));
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
    tempDir.newFolder("foo");

    Path testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(
        testFooBuckFile,
        ("java_library(name = 'lib', visibility=['PUBLIC'])\n" +
            "java_library(name = 'lib2', visibility=['PUBLIC'])\n").getBytes(UTF_8));

    BuildTarget fooLibTarget = BuildTarget.builder(cellRoot, "//foo", "lib").build();
    BuildTarget fooLib2Target = BuildTarget.builder(cellRoot, "//foo", "lib2").build();

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
    tempDir.newFolder("foo");

    Path testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(
        testFooBuckFile,
        ("java_library(name = 'lib', deps = [], visibility=['PUBLIC'])\n" +
            "java_library(name = 'lib2', deps = [], visibility=['PUBLIC'])\n")
            .getBytes(UTF_8));

    BuildTarget fooLibTarget = BuildTarget.builder(cellRoot, "//foo", "lib").build();
    BuildTarget fooLib2Target = BuildTarget.builder(cellRoot, "//foo", "lib2").build();
    ImmutableMap<BuildTarget, HashCode> hashes = buildTargetGraphAndGetHashCodes(
        parser,
        fooLibTarget,
        fooLib2Target);
    HashCode libKey = hashes.get(fooLibTarget);
    HashCode lib2Key = hashes.get(fooLib2Target);

    DefaultTypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    parser = new Parser(
        new ParserConfig(cell.getBuckConfig()),
        typeCoercerFactory,
        new ConstructorArgMarshaller(typeCoercerFactory));
    Files.write(
        testFooBuckFile,
        ("java_library(name = 'lib', deps = [], visibility=['PUBLIC'])\n" +
         "java_library(name = 'lib2', deps = [':lib'], visibility=['PUBLIC'])\n").getBytes(UTF_8));

    hashes = buildTargetGraphAndGetHashCodes(
        parser,
        fooLibTarget,
        fooLib2Target);

    assertEquals(libKey, hashes.get(fooLibTarget));
    assertNotEquals(lib2Key, hashes.get(fooLib2Target));
  }

  @Test
  public void loadedBuildFileWithoutLoadedTargetNodesLoadsAdditionalTargetNodes()
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException {
    tempDir.newFolder("foo");

    Path testFooBuckFile = tempDir.newFile("foo/BUCK").toRealPath();
    Files.write(
        testFooBuckFile,
        "java_library(name = 'lib1')\njava_library(name = 'lib2')\n".getBytes(UTF_8));
    BuildTarget fooLib1Target = BuildTarget.builder(cellRoot, "//foo", "lib1").build();
    BuildTarget fooLib2Target = BuildTarget.builder(cellRoot, "//foo", "lib2").build();

    // First, only load one target from the build file so the file is parsed, but only one of the
    // TargetNodes will be cached.
    TargetNode<?> targetNode = parser.getTargetNode(
        eventBus,
        cell,
        false,
        executorService,
        fooLib1Target);
    assertThat(targetNode.getBuildTarget(), equalTo(fooLib1Target));

    // Now, try to load the entire build file and get all TargetNodes.
    ImmutableSet<TargetNode<?>> targetNodes = parser.getAllTargetNodes(
        eventBus,
        cell,
        false,
        executorService,
        testFooBuckFile);
    assertThat(targetNodes.size(), equalTo(2));
    assertThat(
        FluentIterable.from(targetNodes)
            .transform(
                new Function<TargetNode<?>, BuildTarget>() {
                  @Override
                  public BuildTarget apply(TargetNode<?> targetNode) {
                    return targetNode.getBuildTarget();
                  }
                })
            .toList(),
        Matchers.hasItems(fooLib1Target, fooLib2Target));
  }

  @Test
  public void getOrLoadTargetNodeRules()
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException {
    tempDir.newFolder("foo");

    Path testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(
        testFooBuckFile,
        "java_library(name = 'lib')\n".getBytes(UTF_8));
    BuildTarget fooLibTarget = BuildTarget.builder(cellRoot, "//foo", "lib").build();

    TargetNode<?> targetNode = parser.getTargetNode(
        eventBus,
        cell,
        false,
        executorService,
        fooLibTarget);
    assertThat(targetNode.getBuildTarget(), equalTo(fooLibTarget));

      SortedMap<String, Object> rules = parser.getRawTargetNode(
          eventBus,
          cell,
          false,
          executorService,
          targetNode);
    assertThat(rules, Matchers.hasKey("name"));
    assertThat(
        (String) rules.get("name"),
        equalTo(targetNode.getBuildTarget().getShortName()));
  }

  @Test
  public void whenBuildFileContainsSourcesUnderSymLinkNewSourcesNotAddedUntilCacheCleaned()
      throws Exception {
    // This test depends on creating symbolic links which we cannot do on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    tempDir.newFolder("bar");
    tempDir.newFile("bar/Bar.java");
    tempDir.newFolder("foo");
    Path rootPath = tempDir.getRoot().toRealPath();
    Files.createSymbolicLink(rootPath.resolve("foo/bar"), rootPath.resolve("bar"));

    Path testBuckFile = rootPath.resolve("foo").resolve("BUCK");
    Files.write(
        testBuckFile,
        "java_library(name = 'lib', srcs=glob(['bar/*.java']))\n".getBytes(UTF_8));

    // Fetch //:lib to put it in cache.
    BuildTarget libTarget = BuildTarget.builder(cellRoot, "//foo", "lib").build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(libTarget);

    {
      TargetGraph targetGraph = parser.buildTargetGraph(
          eventBus,
          cell,
          false,
          executorService,
          buildTargets);
      BuildRuleResolver resolver = buildActionGraph(eventBus, targetGraph);

      JavaLibrary libRule = (JavaLibrary) resolver.requireRule(libTarget);
      assertEquals(ImmutableSet.of(Paths.get("foo/bar/Bar.java")), libRule.getJavaSrcs());
    }

    tempDir.newFile("bar/Baz.java");
    WatchEvent<Path> createEvent = createPathEvent(
        Paths.get("bar/Baz.java"),
        StandardWatchEventKinds.ENTRY_CREATE);
    parser.onFileSystemChange(createEvent);

    {
      TargetGraph targetGraph = parser.buildTargetGraph(
          eventBus,
          cell,
          false,
          executorService,
          buildTargets);
      BuildRuleResolver resolver = buildActionGraph(eventBus, targetGraph);

      JavaLibrary libRule = (JavaLibrary) resolver.requireRule(libTarget);
      assertEquals(
          ImmutableSet.of(Paths.get("foo/bar/Bar.java"), Paths.get("foo/bar/Baz.java")),
          libRule.getJavaSrcs());
    }
  }

  @Test
  public void whenBuildFileContainsSourcesUnderSymLinkDeletedSourcesNotRemovedUntilCacheCleaned()
      throws Exception {
    // This test depends on creating symbolic links which we cannot do on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    tempDir.newFolder("bar");
    tempDir.newFile("bar/Bar.java");
    tempDir.newFolder("foo");
    Path bazSourceFile = tempDir.newFile("bar/Baz.java");
    Path rootPath = tempDir.getRoot().toRealPath();
    Files.createSymbolicLink(rootPath.resolve("foo/bar"), rootPath.resolve("bar"));

    Path testBuckFile = rootPath.resolve("foo").resolve("BUCK");
    Files.write(
        testBuckFile,
        "java_library(name = 'lib', srcs=glob(['bar/*.java']))\n".getBytes(UTF_8));

    // Fetch //:lib to put it in cache.
    BuildTarget libTarget = BuildTarget.builder(cellRoot, "//foo", "lib").build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(libTarget);

    {
      TargetGraph targetGraph = parser.buildTargetGraph(
          eventBus,
          cell,
          false,
          executorService,
          buildTargets);
      BuildRuleResolver resolver = buildActionGraph(eventBus, targetGraph);

      JavaLibrary libRule = (JavaLibrary) resolver.requireRule(libTarget);

      assertEquals(
          ImmutableSortedSet.of(Paths.get("foo/bar/Bar.java"), Paths.get("foo/bar/Baz.java")),
          libRule.getJavaSrcs());
    }

    Files.delete(bazSourceFile);
    WatchEvent<Path> deleteEvent = createPathEvent(
        Paths.get("bar/Baz.java"),
        StandardWatchEventKinds.ENTRY_DELETE);
    parser.onFileSystemChange(deleteEvent);

    {
      TargetGraph targetGraph = parser.buildTargetGraph(
          eventBus,
          cell,
          false,
          executorService,
          buildTargets);
      BuildRuleResolver resolver = buildActionGraph(eventBus, targetGraph);

      JavaLibrary libRule = (JavaLibrary) resolver.requireRule(libTarget);
      assertEquals(
          ImmutableSet.of(Paths.get("foo/bar/Bar.java")),
          libRule.getJavaSrcs());
    }
  }

  @Test
  public void whenSymlinksForbiddenThenParseFailsOnSymlinkInSources()
      throws Exception {
    // This test depends on creating symbolic links which we cannot do on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "Target //foo:lib contains input files under a path which contains a symbolic link (" +
        "{foo/bar=bar}). To resolve this, use separate rules and declare dependencies instead of " +
        "using symbolic links.");

    BuckConfig config = FakeBuckConfig.builder()
        .setFilesystem(filesystem)
        .setSections(
            "[project]",
            "allow_symlinks = forbid")
        .build();
    cell = new TestCellBuilder().setBuckConfig(config).setFilesystem(filesystem).build();

    tempDir.newFolder("bar");
    tempDir.newFile("bar/Bar.java");
    tempDir.newFolder("foo");
    Path rootPath = tempDir.getRoot().toRealPath();
    Files.createSymbolicLink(rootPath.resolve("foo/bar"), rootPath.resolve("bar"));

    Path testBuckFile = rootPath.resolve("foo").resolve("BUCK");
    Files.write(
        testBuckFile,
        "java_library(name = 'lib', srcs=glob(['bar/*.java']))\n".getBytes(UTF_8));

    BuildTarget libTarget = BuildTarget.builder(cellRoot, "//foo", "lib").build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(libTarget);

    parser.buildTargetGraph(
        eventBus,
        cell,
        false,
        executorService,
        buildTargets);
  }

  @Test
  public void buildTargetHashCodePopulatesCorrectly() throws Exception {
    tempDir.newFolder("foo");

    Path testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(
        testFooBuckFile,
        "java_library(name = 'lib', visibility=['PUBLIC'])\n".getBytes(UTF_8));

    BuildTarget fooLibTarget = BuildTarget.builder(cellRoot, "//foo", "lib").build();

    // We can't precalculate the hash, since it depends on the buck version. Check for the presence
    // of a hash for the right key.
    HashCode hashCode = buildTargetGraphAndGetHashCodes(parser, fooLibTarget).get(fooLibTarget);

    assertNotNull(hashCode);
  }

  @Test
  public void readConfigReadsConfig() throws Exception {
    Path buckFile = cellRoot.resolve("BUCK");
    BuildTarget buildTarget = BuildTarget.of(
        UnflavoredBuildTarget.of(
            filesystem.getRootPath(),
            Optional.<String>absent(),
            "//",
            "cake"));
    Files.write(
        buckFile,
        Joiner.on("").join(
            ImmutableList.of(
                "genrule(\n" +
                    "name = 'cake',\n" +
                    "out = read_config('foo', 'bar', 'default') + '.txt',\n" +
                    "cmd = 'touch $OUT'\n" +
                    ")\n"))
            .getBytes(UTF_8));

    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .build();

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();
    TargetNode<GenruleDescription.Arg> node = parser
        .getTargetNode(eventBus, cell, false, executorService, buildTarget)
        .castArg(GenruleDescription.Arg.class)
        .get();

    assertThat(node.getConstructorArg().out, is(equalTo("default.txt")));

    config =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("foo", ImmutableMap.of("bar", "value")))
            .setFilesystem(filesystem)
            .build();
    cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();
    node = parser
        .getTargetNode(eventBus, cell, false, executorService, buildTarget)
        .castArg(GenruleDescription.Arg.class)
        .get();

    assertThat(node.getConstructorArg().out, is(equalTo("value.txt")));

    config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(ImmutableMap.of("foo", ImmutableMap.of("bar", "other value")))
            .build();
    cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();
    node = parser
        .getTargetNode(eventBus, cell, false, executorService, buildTarget)
        .castArg(GenruleDescription.Arg.class)
        .get();

    assertThat(node.getConstructorArg().out, is(equalTo("other value.txt")));
  }

  @Test
  public void whenBuckConfigEntryChangesThenCachedRulesAreInvalidated() throws Exception {
    Path buckFile = cellRoot.resolve("BUCK");
    Files.write(
        buckFile,
        Joiner.on("").join(
            ImmutableList.of(
                "read_config('foo', 'bar')\n",
                "genrule(name = 'cake', out = 'file.txt', cmd = 'touch $OUT')\n"))
            .getBytes(UTF_8));

    BuckConfig config =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("foo", ImmutableMap.of("bar", "value")))
            .setFilesystem(filesystem)
            .build();

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    parser.getAllTargetNodes(eventBus, cell, false, executorService, buckFile);

    // Call filterAllTargetsInProject to request cached rules.
    config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(ImmutableMap.of("foo", ImmutableMap.of("bar", "other value")))
            .build();

    cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    parser.getAllTargetNodes(eventBus, cell, false, executorService, buckFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated.", 2, counter.calls);
  }

  @Test
  public void whenBuckConfigAddedThenCachedRulesAreInvalidated() throws Exception {
    Path buckFile = cellRoot.resolve("BUCK");
    Files.write(
        buckFile,
        Joiner.on("").join(
            ImmutableList.of(
                "read_config('foo', 'bar')\n",
                "genrule(name = 'cake', out = 'file.txt', cmd = 'touch $OUT')\n"))
            .getBytes(UTF_8));

    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .build();

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    parser.getAllTargetNodes(eventBus, cell, false, executorService, buckFile);

    // Call filterAllTargetsInProject to request cached rules.
    config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(ImmutableMap.of("foo", ImmutableMap.of("bar", "other value")))
            .build();

    cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    parser.getAllTargetNodes(eventBus, cell, false, executorService, buckFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated.", 2, counter.calls);
  }

  @Test
  public void whenBuckConfigEntryRemovedThenCachedRulesAreInvalidated() throws Exception {
    Path buckFile = cellRoot.resolve("BUCK");
    Files.write(
        buckFile,
        Joiner.on("").join(
            ImmutableList.of(
                "read_config('foo', 'bar')\n",
                "genrule(name = 'cake', out = 'file.txt', cmd = 'touch $OUT')\n"))
            .getBytes(UTF_8));

    BuckConfig config =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("foo", ImmutableMap.of("bar", "value")))
            .setFilesystem(filesystem)
            .build();

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    parser.getAllTargetNodes(eventBus, cell, false, executorService, buckFile);

    // Call filterAllTargetsInProject to request cached rules.
    config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .build();

    cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    parser.getAllTargetNodes(eventBus, cell, false, executorService, buckFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated.", 2, counter.calls);
  }

  @Test
  public void whenUnrelatedBuckConfigEntryChangesThenCachedRulesAreNotInvalidated()
      throws Exception {
    Path buckFile = cellRoot.resolve("BUCK");
    Files.write(
        buckFile,
        Joiner.on("").join(
            ImmutableList.of(
                "read_config('foo', 'bar')\n",
                "genrule(name = 'cake', out = 'file.txt', cmd = 'touch $OUT')\n"))
            .getBytes(UTF_8));

    BuckConfig config =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "foo",
                    ImmutableMap.of(
                        "bar", "value",
                        "dead", "beef")))
            .setFilesystem(filesystem)
            .build();

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    parser.getAllTargetNodes(eventBus, cell, false, executorService, buckFile);

    // Call filterAllTargetsInProject to request cached rules.
    config =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "foo",
                    ImmutableMap.of(
                        "bar", "value",
                        "dead", "beef different")))
            .setFilesystem(filesystem)
            .build();

    cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    parser.getAllTargetNodes(eventBus, cell, false, executorService, buckFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should not have invalidated.", 1, counter.calls);
  }

  @Test(timeout = 20000)
  public void resolveTargetSpecsDoesNotHangOnException() throws Exception {
    Path buckFile = cellRoot.resolve("foo/BUCK");
    Files.createDirectories(buckFile.getParent());
    Files.write(buckFile, "# empty".getBytes(UTF_8));

    buckFile = cellRoot.resolve("bar/BUCK");
    Files.createDirectories(buckFile.getParent());
    Files.write(
        buckFile,
        "I do not parse as python".getBytes(UTF_8));

    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("Parse error for build file");
    thrown.expectMessage(Paths.get("bar/BUCK").toString());

    parser.resolveTargetSpecs(
        eventBus,
        cell,
        false,
        executorService,
        ImmutableList.of(
            TargetNodePredicateSpec.of(
                Predicates.alwaysTrue(),
                BuildFileSpec.fromRecursivePath(Paths.get("bar"))),
            TargetNodePredicateSpec.of(
                Predicates.alwaysTrue(),
                BuildFileSpec.fromRecursivePath(Paths.get("foo")))));
  }

  private BuildRuleResolver buildActionGraph(BuckEventBus eventBus, TargetGraph targetGraph) {
    return Preconditions.checkNotNull(
        new TargetGraphToActionGraph(
            eventBus,
            new BuildTargetNodeToBuildRuleTransformer())
            .apply(targetGraph))
        .getSecond();
  }

  /**
   * Populates the collection of known build targets that this Parser will use to construct an
   * action graph using all build files inside the given project root and returns an optionally
   * filtered set of build targets.
   *
   * @param filter if specified, applied to each rule in rules. All matching rules will be included
   *     in the List returned by this method. If filter is null, then this method returns null.
   * @return The build targets in the project filtered by the given filter.
   */
  public static synchronized ImmutableSet<BuildTarget> filterAllTargetsInProject(
      Parser parser,
      Cell cell,
      Predicate<TargetNode<?>> filter,
      BuckEventBus buckEventBus,
      ListeningExecutorService executor)
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    return FluentIterable
        .from(
            parser.buildTargetGraphForTargetNodeSpecs(
                buckEventBus,
                cell,
                false,
                executor,
                ImmutableList.of(
                    TargetNodePredicateSpec.of(
                        filter,
                        BuildFileSpec.fromRecursivePath(Paths.get("")))))
                .getSecond().getNodes())
        .filter(filter)
        .transform(HasBuildTarget.TO_TARGET)
        .toSet();
  }

  private ImmutableMap<BuildTarget, HashCode> buildTargetGraphAndGetHashCodes(
      Parser parser,
      BuildTarget... buildTargets) throws Exception {
    // Build the target graph so we can access the hash code cache.

    ImmutableList<BuildTarget> buildTargetsList = ImmutableList.copyOf(buildTargets);
    TargetGraph targetGraph = parser.buildTargetGraph(
        eventBus,
        cell,
        false,
        executorService,
        buildTargetsList);

    ImmutableMap.Builder<BuildTarget, HashCode> toReturn = ImmutableMap.builder();
    for (TargetNode<?> node : targetGraph.getNodes()) {
      toReturn.put(node.getBuildTarget(), node.getRawInputsHashCode());
    }

    return toReturn.build();
  }

  private static class ParseEventStartedCounter {
    int calls = 0;

    // We know that the ProjectBuildFileParser emits a Started event when it parses a build file.
    @Subscribe
    @SuppressWarnings("unused")
    public void call(ParseBuckFileEvent.Started parseEvent) {
      calls++;
    }
  }
}
