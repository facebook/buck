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
import static com.google.common.base.Charsets.UTF_8;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.config.ConfigBuilder;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.FakeBuckEventListener;
import com.facebook.buck.event.listener.BroadcastEventListener;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.json.ParseBuckFileEvent;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.ActionGraphCache;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.shell.GenruleDescriptionArg;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.WatchmanOverflowEvent;
import com.facebook.buck.util.WatchmanPathEvent;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.concurrent.Executors;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class ParserTest {

  @Rule public TemporaryPaths tempDir = new TemporaryPaths();
  @Rule public ExpectedException thrown = ExpectedException.none();

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
    return Arrays.asList(
        new Object[][] {
          {
            1, false,
          },
          {
            1, true,
          },
          {
            2, true,
          },
        });
  }

  /** Helper to construct a PerBuildState and use it to get nodes. */
  @VisibleForTesting
  private static ImmutableSet<Map<String, Object>> getRawTargetNodes(
      Parser parser,
      BuckEventBus eventBus,
      Cell cell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      Path buildFile)
      throws InterruptedException, BuildFileParseException {
    try (PerBuildState state =
        new PerBuildState(
            parser, eventBus, executor, cell, enableProfiling, SpeculativeParsing.of(false))) {
      return Parser.getRawTargetNodes(state, cell, buildFile);
    }
  }

  @Before
  public void setUp() throws IOException, InterruptedException {
    tempDir.newFolder("java", "com", "facebook");

    defaultIncludeFile = tempDir.newFile("java/com/facebook/defaultIncludeFile").toRealPath();
    Files.write(defaultIncludeFile, "\n".getBytes(UTF_8));

    includedByIncludeFile = tempDir.newFile("java/com/facebook/includedByIncludeFile").toRealPath();
    Files.write(includedByIncludeFile, "\n".getBytes(UTF_8));

    includedByBuildFile = tempDir.newFile("java/com/facebook/includedByBuildFile").toRealPath();
    Files.write(
        includedByBuildFile,
        "include_defs('//java/com/facebook/includedByIncludeFile')\n".getBytes(UTF_8));

    testBuildFile = tempDir.newFile("java/com/facebook/BUCK").toRealPath();
    Files.write(
        testBuildFile,
        ("include_defs('//java/com/facebook/includedByBuildFile')\n"
                + "java_library(name = 'foo')\n"
                + "java_library(name = 'bar')\n"
                + "genrule(name = 'baz', out = '')\n")
            .getBytes(UTF_8));

    tempDir.newFile("bar.py");

    // Create a temp directory with some build files.
    Path root = tempDir.getRoot().toRealPath();
    filesystem =
        new ProjectFilesystem(root, ConfigBuilder.createFromText("[project]", "ignore = **/*.swp"));
    cellRoot = filesystem.getRootPath();
    eventBus = BuckEventBusFactory.newInstance();

    ImmutableMap.Builder<String, ImmutableMap<String, String>> configSectionsBuilder =
        ImmutableMap.builder();
    configSectionsBuilder.put(
        "buildfile", ImmutableMap.of("includes", "//java/com/facebook/defaultIncludeFile"));
    if (parallelParsing) {
      configSectionsBuilder.put(
          "project",
          ImmutableMap.of(
              "parallel_parsing", "true", "parsing_threads", Integer.toString(threads)));
    }

    configSectionsBuilder.put(
        "unknown_flavors_messages",
        ImmutableMap.of("macosx*", "This is an error message read by the .buckconfig"));

    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(configSectionsBuilder.build())
            .build();

    cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    BroadcastEventListener broadcastEventListener = new BroadcastEventListener();
    broadcastEventListener.addEventBus(eventBus);
    parser =
        new Parser(
            broadcastEventListener,
            cell.getBuckConfig().getView(ParserConfig.class),
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

    TargetGraph targetGraph =
        parser.buildTargetGraph(eventBus, cell, false, executorService, buildTargets);
    BuildRuleResolver resolver = buildActionGraph(eventBus, targetGraph);
    BuildRule fooRule = resolver.requireRule(fooTarget);
    assertNotNull(fooRule);
    BuildRule barRule = resolver.requireRule(barTarget);
    assertNotNull(barRule);

    Iterable<ParseEvent> events = Iterables.filter(listener.getEvents(), ParseEvent.class);
    assertThat(
        events,
        Matchers.contains(
            Matchers.hasProperty("buildTargets", equalTo(buildTargets)),
            Matchers.allOf(
                Matchers.hasProperty("buildTargets", equalTo(buildTargets)),
                Matchers.hasProperty("graph", equalTo(Optional.of(targetGraph))))));
  }

  @Test
  public void testMissingBuildRuleInValidFile()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Execute buildTargetGraphForBuildTargets() with a target in a valid file but a bad rule name.
    BuildTarget fooTarget = BuildTarget.builder(cellRoot, "//java/com/facebook", "foo").build();
    BuildTarget razTarget = BuildTarget.builder(cellRoot, "//java/com/facebook", "raz").build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget, razTarget);

    thrown.expectMessage(
        "No rule found when resolving target //java/com/facebook:raz in build file "
            + "//java/com/facebook/BUCK");
    thrown.expectMessage(
        "Defined in file: "
            + filesystem.resolve(razTarget.getBasePath()).resolve(DEFAULT_BUILD_FILE_NAME));

    parser.buildTargetGraph(eventBus, cell, false, executorService, buildTargets);
  }

  @Test
  public void testMissingBuildFile()
      throws InterruptedException, BuildFileParseException, IOException, BuildTargetException {
    BuildTarget target = BuildTarget.builder(cellRoot, "//path/to/nowhere", "nowhere").build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(target);

    thrown.expect(Cell.MissingBuildFileException.class);
    thrown.expectMessage(
        String.format(
            "No build file at %s when resolving target //path/to/nowhere:nowhere",
            Paths.get("path", "to", "nowhere", "BUCK").toString()));

    parser.buildTargetGraph(eventBus, cell, false, executorService, buildTargets);
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
    Files.write(buckFile, "I do not parse as python".getBytes(UTF_8));

    parser.buildTargetGraph(
        eventBus,
        cell,
        false,
        executorService,
        Collections.singleton(
            BuildTargetFactory.newInstance(cell.getFilesystem().getRootPath(), "//:cake")));
  }

  @Test
  public void shouldThrowAnExceptionIfMultipleTargetsAreDefinedWithTheSameName()
      throws IOException, BuildFileParseException, InterruptedException {
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("Duplicate rule definition found.");

    Path buckFile = cellRoot.resolve("BUCK");
    Files.write(
        buckFile,
        ("export_file(name = 'cake', src = 'hello.txt')\n"
                + "genrule(name = 'cake', out = 'file.txt', cmd = 'touch $OUT')\n")
            .getBytes(UTF_8));

    parser.getAllTargetNodes(eventBus, cell, false, executorService, buckFile);
  }

  @Test
  public void shouldThrowAnExceptionIfNameIsNone()
      throws IOException, BuildFileParseException, InterruptedException {
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("rules 'name' field must be a string.  Found None.");

    Path buckFile = cellRoot.resolve("BUCK");
    Files.write(
        buckFile, ("genrule(name = None, out = 'file.txt', cmd = 'touch $OUT')\n").getBytes(UTF_8));

    parser.getAllTargetNodes(eventBus, cell, false, executorService, buckFile);
  }

  @Test
  public void shouldThrowAnExceptionWhenAnUnknownFlavorIsSeen()
      throws BuildFileParseException, BuildTargetException, InterruptedException, IOException {
    BuildTarget flavored =
        BuildTarget.builder(cellRoot, "//java/com/facebook", "foo")
            .addFlavors(InternalFlavor.of("doesNotExist"))
            .build();

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "Unrecognized flavor in target //java/com/facebook:foo#doesNotExist while parsing "
            + "//java/com/facebook/BUCK");
    parser.buildTargetGraph(
        eventBus, cell, false, executorService, ImmutableSortedSet.of(flavored));
  }

  @Test
  public void shouldThrowAnExceptionWhenAnUnknownFlavorIsSeenAndShowSuggestionsDefault()
      throws BuildFileParseException, BuildTargetException, InterruptedException, IOException {
    BuildTarget flavored =
        BuildTarget.builder(cellRoot, "//java/com/facebook", "foo")
            .addFlavors(InternalFlavor.of("android-unknown"))
            .build();

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "Unrecognized flavor in target //java/com/facebook:foo#android-unknown while parsing "
            + "//java/com/facebook/BUCK\nHere are some things you can try to get the following "
            + "flavors to work::\nandroid-unknown : Make sure you have the Android SDK/NDK "
            + "installed and set up. "
            + "See https://buckbuild.com/setup/install.html#locate-android-sdk\n");
    parser.buildTargetGraph(
        eventBus, cell, false, executorService, ImmutableSortedSet.of(flavored));
  }

  @Test
  public void shouldThrowAnExceptionWhenAnUnknownFlavorIsSeenAndShowSuggestionsFromConfig()
      throws BuildFileParseException, BuildTargetException, InterruptedException, IOException {
    BuildTarget flavored =
        BuildTarget.builder(cellRoot, "//java/com/facebook", "foo")
            .addFlavors(InternalFlavor.of("macosx109sdk"))
            .build();

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "Unrecognized flavor in target //java/com/facebook:foo#macosx109sdk while parsing "
            + "//java/com/facebook/BUCK\nHere are some things you can try to get the following "
            + "flavors to work::\nmacosx109sdk : This is an error message read by the .buckconfig");

    parser.buildTargetGraph(
        eventBus, cell, false, executorService, ImmutableSortedSet.of(flavored));
  }

  @Test
  public void shouldThrowAnExceptionWhenAFlavorIsAskedOfATargetThatDoesntSupportFlavors()
      throws BuildFileParseException, BuildTargetException, InterruptedException, IOException {
    BuildTarget flavored =
        BuildTarget.builder(cellRoot, "//java/com/facebook", "baz")
            .addFlavors(JavaLibrary.SRC_JAR)
            .build();

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "Target //java/com/facebook:baz (type genrule) does not currently support flavors "
            + "(tried [src])");
    parser.buildTargetGraph(
        eventBus, cell, false, executorService, ImmutableSortedSet.of(flavored));
  }

  @Test
  public void testInvalidDepFromValidFile()
      throws IOException, BuildFileParseException, BuildTargetException, InterruptedException {
    // Ensure an exception with a specific message is thrown.
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "Couldn't get dependency '//java/com/facebook/invalid/lib:missing_rule' of target "
            + "'//java/com/facebook/invalid:foo'");

    // Execute buildTargetGraphForBuildTargets() with a target in a valid file but a bad rule name.
    tempDir.newFolder("java", "com", "facebook", "invalid");

    Path testInvalidBuildFile = tempDir.newFile("java/com/facebook/invalid/BUCK");
    Files.write(
        testInvalidBuildFile,
        ("java_library(name = 'foo', deps = ['//java/com/facebook/invalid/lib:missing_rule'])\n"
                + "java_library(name = 'bar')\n")
            .getBytes(UTF_8));

    tempDir.newFolder("java", "com", "facebook", "invalid", "lib");
    tempDir.newFile("java/com/facebook/invalid/lib/BUCK");

    BuildTarget fooTarget =
        BuildTarget.builder(cellRoot, "//java/com/facebook/invalid", "foo").build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget);

    parser.buildTargetGraph(eventBus, cell, false, executorService, buildTargets);
  }

  @Test
  public void whenAllRulesRequestedWithTrueFilterThenMultipleRulesReturned()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    ImmutableSet<BuildTarget> targets =
        filterAllTargetsInProject(
            parser, cell, x -> true, BuckEventBusFactory.newInstance(), executorService);

    ImmutableSet<BuildTarget> expectedTargets =
        ImmutableSet.of(
            BuildTarget.builder(cellRoot, "//java/com/facebook", "foo").build(),
            BuildTarget.builder(cellRoot, "//java/com/facebook", "bar").build(),
            BuildTarget.builder(cellRoot, "//java/com/facebook", "baz").build());
    assertEquals("Should have returned all rules.", expectedTargets, targets);
  }

  @Test
  public void whenAllRulesAreRequestedMultipleTimesThenRulesAreOnlyParsedOnce()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    filterAllTargetsInProject(parser, cell, x -> true, eventBus, executorService);
    filterAllTargetsInProject(parser, cell, x -> true, eventBus, executorService);

    assertEquals("Should have cached build rules.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfNonPathEventThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call filterAllTargetsInProject to populate the cache.
    filterAllTargetsInProject(parser, cell, x -> true, eventBus, executorService);

    // Process event.
    parser.onFileSystemChange(WatchmanOverflowEvent.of(filesystem.getRootPath(), ""));

    // Call filterAllTargetsInProject to request cached rules.
    filterAllTargetsInProject(parser, cell, x -> true, eventBus, executorService);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void pathInvalidationWorksAfterOverflow() throws Exception {
    // Call filterAllTargetsInProject to populate the cache.
    filterAllTargetsInProject(parser, cell, x -> true, eventBus, executorService);

    // Send overflow event.
    parser.onFileSystemChange(WatchmanOverflowEvent.of(filesystem.getRootPath(), ""));

    // Call filterAllTargetsInProject to request cached rules.
    filterAllTargetsInProject(parser, cell, x -> true, eventBus, executorService);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);

    // Send a "file added" event.
    parser.onFileSystemChange(
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.CREATE,
            Paths.get("java/com/facebook/Something.java")));

    // Call filterAllTargetsInProject to request cached rules.
    filterAllTargetsInProject(parser, cell, x -> true, eventBus, executorService);

    // Test that the third parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 3, counter.calls);
  }

  @Test
  public void whenEnvironmentNotChangedThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setEnvironment(
                ImmutableMap.of("Some Key", "Some Value", "PATH", System.getenv("PATH")))
            .build();

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    // Call filterAllTargetsInProject to populate the cache.
    filterAllTargetsInProject(parser, cell, x -> true, eventBus, executorService);

    // Call filterAllTargetsInProject to request cached rules with identical environment.
    filterAllTargetsInProject(parser, cell, x -> true, eventBus, executorService);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should not have invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfBuildFileAddThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Process event.
    parser.onFileSystemChange(
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.CREATE,
            MorePaths.relativize(tempDir.getRoot().toRealPath(), testBuildFile)));

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfBuildFileChangeThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.MODIFY,
            MorePaths.relativize(tempDir.getRoot().toRealPath(), testBuildFile));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfBuildFileDeleteThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.DELETE,
            MorePaths.relativize(tempDir.getRoot().toRealPath(), testBuildFile));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfIncludeFileAddThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.CREATE,
            MorePaths.relativize(tempDir.getRoot().toRealPath(), includedByBuildFile));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfIncludeFileChangeThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    assertEquals("Should have parsed at all.", 1, counter.calls);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.MODIFY,
            MorePaths.relativize(tempDir.getRoot().toRealPath(), includedByBuildFile));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfIncludeFileDeleteThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.DELETE,
            MorePaths.relativize(tempDir.getRoot().toRealPath(), includedByBuildFile));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOf2ndOrderIncludeFileAddThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.CREATE,
            MorePaths.relativize(tempDir.getRoot().toRealPath(), includedByIncludeFile));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOf2ndOrderIncludeFileChangeThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.MODIFY,
            MorePaths.relativize(tempDir.getRoot().toRealPath(), includedByIncludeFile));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOf2ndOrderIncludeFileDeleteThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.DELETE,
            MorePaths.relativize(tempDir.getRoot().toRealPath(), includedByIncludeFile));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfDefaultIncludeFileAddThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.CREATE,
            MorePaths.relativize(tempDir.getRoot().toRealPath(), defaultIncludeFile));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfDefaultIncludeFileChangeThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.MODIFY,
            MorePaths.relativize(tempDir.getRoot().toRealPath(), defaultIncludeFile));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfDefaultIncludeFileDeleteThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.DELETE,
            MorePaths.relativize(tempDir.getRoot().toRealPath(), defaultIncludeFile));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  // TODO(simons): avoid invalidation when arbitrary contained (possibly backup) files are added.
  public void whenNotifiedOfContainedFileAddThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.CREATE,
            Paths.get("java/com/facebook/SomeClass.java"));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfContainedFileAddCachedAncestorsAreInvalidatedWithoutBoundaryChecks()
      throws Exception {
    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(
                "[buildfile]",
                "includes = //java/com/facebook/defaultIncludeFile",
                "[project]",
                "check_package_boundary = false",
                "temp_files = ''")
            .build();
    Cell cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    Path testAncestorBuildFile = tempDir.newFile("java/BUCK").toRealPath();
    Files.write(testAncestorBuildFile, "java_library(name = 'root')\n".getBytes(UTF_8));

    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testAncestorBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.CREATE,
            Paths.get("java/com/facebook/SomeClass.java"));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testAncestorBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfContainedFileChangeThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.MODIFY,
            Paths.get("java/com/facebook/SomeClass.java"));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Test that the second parseBuildFile call did not repopulate the cache.
    assertEquals("Should have not invalidated cache.", 1, counter.calls);
  }

  @Test
  // TODO(simons): avoid invalidation when arbitrary contained (possibly backup) files are deleted.
  public void whenNotifiedOfContainedFileDeleteThenCacheRulesAreInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.DELETE,
            Paths.get("java/com/facebook/SomeClass.java"));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfContainedTempFileAddThenCachedRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.CREATE,
            Paths.get("java/com/facebook/MumbleSwp.Java.swp"));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should not have invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfContainedTempFileChangeThenCachedRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.MODIFY,
            Paths.get("java/com/facebook/MumbleSwp.Java.swp"));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should not have invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfContainedTempFileDeleteThenCachedRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.DELETE,
            Paths.get("java/com/facebook/MumbleSwp.Java.swp"));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should not have invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfUnrelatedFileAddThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.CREATE,
            Paths.get("SomeClass.java__backup"));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Test that the second parseBuildFile call did not repopulate the cache.
    assertEquals("Should have not invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfUnrelatedFileChangeThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.MODIFY,
            Paths.get("SomeClass.java__backup"));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Test that the second parseBuildFile call did not repopulate the cache.
    assertEquals("Should have not invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfUnrelatedFileDeleteThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.DELETE,
            Paths.get("SomeClass.java__backup"));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(parser, eventBus, cell, false, executorService, testBuildFile);

    // Test that the second parseBuildFile call did not repopulate the cache.
    assertEquals("Should have not invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenAllRulesAreRequestedWithDifferingIncludesThenRulesAreParsedTwice()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    filterAllTargetsInProject(parser, cell, x -> true, eventBus, executorService);

    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(
                ImmutableMap.of(
                    ParserConfig.BUILDFILE_SECTION_NAME,
                    ImmutableMap.of(ParserConfig.INCLUDES_PROPERTY_NAME, "//bar.py")))
            .build();
    Cell cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    filterAllTargetsInProject(parser, cell, x -> true, eventBus, executorService);

    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenAllRulesAreRequestedWithDifferingCellsThenRulesAreParsedOnce()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    filterAllTargetsInProject(parser, cell, x -> true, eventBus, executorService);

    assertEquals("Should have parsed once.", 1, counter.calls);

    Path newTempDir = Files.createTempDirectory("junit-temp-path").toRealPath();
    Files.createFile(newTempDir.resolve("bar.py"));
    ProjectFilesystem newFilesystem = new ProjectFilesystem(newTempDir);
    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(newFilesystem)
            .setSections(
                ImmutableMap.of(
                    ParserConfig.BUILDFILE_SECTION_NAME,
                    ImmutableMap.of(ParserConfig.INCLUDES_PROPERTY_NAME, "//bar.py")))
            .build();
    Cell cell = new TestCellBuilder().setFilesystem(newFilesystem).setBuckConfig(config).build();

    filterAllTargetsInProject(parser, cell, x -> true, eventBus, executorService);

    assertEquals("Should not have invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenAllRulesThenSingleTargetRequestedThenRulesAreParsedOnce()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    filterAllTargetsInProject(parser, cell, x -> true, eventBus, executorService);
    BuildTarget foo = BuildTarget.builder(cellRoot, "//java/com/facebook", "foo").build();
    parser.buildTargetGraph(eventBus, cell, false, executorService, ImmutableList.of(foo));

    assertEquals("Should have cached build rules.", 1, counter.calls);
  }

  @Test
  public void whenSingleTargetThenAllRulesRequestedThenRulesAreParsedOnce()
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    BuildTarget foo = BuildTarget.builder(cellRoot, "//java/com/facebook", "foo").build();
    parser.buildTargetGraph(eventBus, cell, false, executorService, ImmutableList.of(foo));
    filterAllTargetsInProject(parser, cell, x -> true, eventBus, executorService);

    assertEquals("Should have replaced build rules", 1, counter.calls);
  }

  @Test
  public void whenBuildFilePathChangedThenFlavorsOfTargetsInPathAreInvalidated() throws Exception {
    tempDir.newFolder("foo");
    tempDir.newFolder("bar");

    Path testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(
        testFooBuckFile, "java_library(name = 'foo', visibility=['PUBLIC'])\n".getBytes(UTF_8));

    Path testBarBuckFile = tempDir.newFile("bar/BUCK");
    Files.write(
        testBarBuckFile,
        ("java_library(name = 'bar',\n" + "  deps = ['//foo:foo'])\n").getBytes(UTF_8));

    // Fetch //bar:bar#src to put it in cache.
    BuildTarget barTarget =
        BuildTarget.builder(cellRoot, "//bar", "bar").addFlavors(InternalFlavor.of("src")).build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(barTarget);

    parser.buildTargetGraph(eventBus, cell, false, executorService, buildTargets);

    // Rewrite //bar:bar so it doesn't depend on //foo:foo any more.
    // Delete foo/BUCK and invalidate the cache, which should invalidate
    // the cache entry for //bar:bar#src.
    Files.delete(testFooBuckFile);
    Files.write(testBarBuckFile, "java_library(name = 'bar')\n".getBytes(UTF_8));
    WatchmanPathEvent deleteEvent =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.DELETE,
            Paths.get("foo").resolve("BUCK"));
    parser.onFileSystemChange(deleteEvent);
    WatchmanPathEvent modifyEvent =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.MODIFY,
            Paths.get("bar").resolve("BUCK"));
    parser.onFileSystemChange(modifyEvent);

    parser.buildTargetGraph(eventBus, cell, false, executorService, buildTargets);
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
    parser =
        new Parser(
            new BroadcastEventListener(),
            cell.getBuckConfig().getView(ParserConfig.class),
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
    WatchmanPathEvent deleteEvent =
        WatchmanPathEvent.of(
            filesystem.getRootPath(), WatchmanPathEvent.Kind.DELETE, Paths.get("foo/Bar.java"));
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
    WatchmanPathEvent deleteEvent =
        WatchmanPathEvent.of(
            filesystem.getRootPath(), WatchmanPathEvent.Kind.DELETE, Paths.get("foo/Foo.java"));
    WatchmanPathEvent createEvent =
        WatchmanPathEvent.of(
            filesystem.getRootPath(), WatchmanPathEvent.Kind.CREATE, Paths.get("foo/Bar.java"));
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
        ("java_library(name = 'lib', visibility=['PUBLIC'])\n"
                + "java_library(name = 'lib2', visibility=['PUBLIC'])\n")
            .getBytes(UTF_8));

    BuildTarget fooLibTarget = BuildTarget.builder(cellRoot, "//foo", "lib").build();
    BuildTarget fooLib2Target = BuildTarget.builder(cellRoot, "//foo", "lib2").build();

    ImmutableMap<BuildTarget, HashCode> hashes =
        buildTargetGraphAndGetHashCodes(parser, fooLibTarget, fooLib2Target);

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
        ("java_library(name = 'lib', deps = [], visibility=['PUBLIC'])\n"
                + "java_library(name = 'lib2', deps = [], visibility=['PUBLIC'])\n")
            .getBytes(UTF_8));

    BuildTarget fooLibTarget = BuildTarget.builder(cellRoot, "//foo", "lib").build();
    BuildTarget fooLib2Target = BuildTarget.builder(cellRoot, "//foo", "lib2").build();
    ImmutableMap<BuildTarget, HashCode> hashes =
        buildTargetGraphAndGetHashCodes(parser, fooLibTarget, fooLib2Target);
    HashCode libKey = hashes.get(fooLibTarget);
    HashCode lib2Key = hashes.get(fooLib2Target);

    DefaultTypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    parser =
        new Parser(
            new BroadcastEventListener(),
            cell.getBuckConfig().getView(ParserConfig.class),
            typeCoercerFactory,
            new ConstructorArgMarshaller(typeCoercerFactory));
    Files.write(
        testFooBuckFile,
        ("java_library(name = 'lib', deps = [], visibility=['PUBLIC'])\njava_library("
                + "name = 'lib2', deps = [':lib'], visibility=['PUBLIC'])\n")
            .getBytes(UTF_8));

    hashes = buildTargetGraphAndGetHashCodes(parser, fooLibTarget, fooLib2Target);

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
    TargetNode<?, ?> targetNode =
        parser.getTargetNode(eventBus, cell, false, executorService, fooLib1Target);
    assertThat(targetNode.getBuildTarget(), equalTo(fooLib1Target));

    // Now, try to load the entire build file and get all TargetNodes.
    ImmutableSet<TargetNode<?, ?>> targetNodes =
        parser.getAllTargetNodes(eventBus, cell, false, executorService, testFooBuckFile);
    assertThat(targetNodes.size(), equalTo(2));
    assertThat(
        targetNodes
            .stream()
            .map(TargetNode::getBuildTarget)
            .collect(MoreCollectors.toImmutableList()),
        hasItems(fooLib1Target, fooLib2Target));
  }

  @Test
  public void getOrLoadTargetNodeRules()
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException {
    tempDir.newFolder("foo");

    Path testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(testFooBuckFile, "java_library(name = 'lib')\n".getBytes(UTF_8));
    BuildTarget fooLibTarget = BuildTarget.builder(cellRoot, "//foo", "lib").build();

    TargetNode<?, ?> targetNode =
        parser.getTargetNode(eventBus, cell, false, executorService, fooLibTarget);
    assertThat(targetNode.getBuildTarget(), equalTo(fooLibTarget));

    SortedMap<String, Object> rules =
        parser.getRawTargetNode(eventBus, cell, false, executorService, targetNode);
    assertThat(rules, Matchers.hasKey("name"));
    assertThat((String) rules.get("name"), equalTo(targetNode.getBuildTarget().getShortName()));
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
        testBuckFile, "java_library(name = 'lib', srcs=glob(['bar/*.java']))\n".getBytes(UTF_8));

    // Fetch //:lib to put it in cache.
    BuildTarget libTarget = BuildTarget.builder(cellRoot, "//foo", "lib").build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(libTarget);

    {
      TargetGraph targetGraph =
          parser.buildTargetGraph(eventBus, cell, false, executorService, buildTargets);
      BuildRuleResolver resolver = buildActionGraph(eventBus, targetGraph);

      JavaLibrary libRule = (JavaLibrary) resolver.requireRule(libTarget);
      assertEquals(
          ImmutableSortedSet.of(new PathSourcePath(filesystem, Paths.get("foo/bar/Bar.java"))),
          libRule.getJavaSrcs());
    }

    tempDir.newFile("bar/Baz.java");
    WatchmanPathEvent createEvent =
        WatchmanPathEvent.of(
            filesystem.getRootPath(), WatchmanPathEvent.Kind.CREATE, Paths.get("bar/Baz.java"));
    parser.onFileSystemChange(createEvent);

    {
      TargetGraph targetGraph =
          parser.buildTargetGraph(eventBus, cell, false, executorService, buildTargets);
      BuildRuleResolver resolver = buildActionGraph(eventBus, targetGraph);

      JavaLibrary libRule = (JavaLibrary) resolver.requireRule(libTarget);
      assertEquals(
          ImmutableSet.of(
              new PathSourcePath(filesystem, Paths.get("foo/bar/Bar.java")),
              new PathSourcePath(filesystem, Paths.get("foo/bar/Baz.java"))),
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
        testBuckFile, "java_library(name = 'lib', srcs=glob(['bar/*.java']))\n".getBytes(UTF_8));

    // Fetch //:lib to put it in cache.
    BuildTarget libTarget = BuildTarget.builder(cellRoot, "//foo", "lib").build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(libTarget);

    {
      TargetGraph targetGraph =
          parser.buildTargetGraph(eventBus, cell, false, executorService, buildTargets);
      BuildRuleResolver resolver = buildActionGraph(eventBus, targetGraph);

      JavaLibrary libRule = (JavaLibrary) resolver.requireRule(libTarget);

      assertEquals(
          ImmutableSortedSet.of(
              new PathSourcePath(filesystem, Paths.get("foo/bar/Bar.java")),
              new PathSourcePath(filesystem, Paths.get("foo/bar/Baz.java"))),
          libRule.getJavaSrcs());
    }

    Files.delete(bazSourceFile);
    WatchmanPathEvent deleteEvent =
        WatchmanPathEvent.of(
            filesystem.getRootPath(), WatchmanPathEvent.Kind.DELETE, Paths.get("bar/Baz.java"));
    parser.onFileSystemChange(deleteEvent);

    {
      TargetGraph targetGraph =
          parser.buildTargetGraph(eventBus, cell, false, executorService, buildTargets);
      BuildRuleResolver resolver = buildActionGraph(eventBus, targetGraph);

      JavaLibrary libRule = (JavaLibrary) resolver.requireRule(libTarget);
      assertEquals(
          ImmutableSortedSet.of(new PathSourcePath(filesystem, Paths.get("foo/bar/Bar.java"))),
          libRule.getJavaSrcs());
    }
  }

  @Test
  public void whenSymlinksForbiddenThenParseFailsOnSymlinkInSources() throws Exception {
    // This test depends on creating symbolic links which we cannot do on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "Target //foo:lib contains input files under a path which contains a symbolic link ("
            + "{foo/bar=bar}). To resolve this, use separate rules and declare dependencies "
            + "instead of using symbolic links.");

    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections("[project]", "allow_symlinks = forbid")
            .build();
    cell = new TestCellBuilder().setBuckConfig(config).setFilesystem(filesystem).build();

    tempDir.newFolder("bar");
    tempDir.newFile("bar/Bar.java");
    tempDir.newFolder("foo");
    Path rootPath = tempDir.getRoot().toRealPath();
    Files.createSymbolicLink(rootPath.resolve("foo/bar"), rootPath.resolve("bar"));

    Path testBuckFile = rootPath.resolve("foo").resolve("BUCK");
    Files.write(
        testBuckFile, "java_library(name = 'lib', srcs=glob(['bar/*.java']))\n".getBytes(UTF_8));

    BuildTarget libTarget = BuildTarget.builder(cellRoot, "//foo", "lib").build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(libTarget);

    parser.buildTargetGraph(eventBus, cell, false, executorService, buildTargets);
  }

  @Test
  public void whenSymlinksAreInReadOnlyPathsCachingIsNotDisabled() throws Exception {
    // This test depends on creating symbolic links which we cannot do on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    Path rootPath = tempDir.getRoot().toRealPath();
    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections("[project]", "read_only_paths = " + rootPath.resolve("foo"))
            .build();
    cell = new TestCellBuilder().setBuckConfig(config).setFilesystem(filesystem).build();

    tempDir.newFolder("bar");
    tempDir.newFile("bar/Bar.java");
    tempDir.newFolder("foo");

    Files.createSymbolicLink(rootPath.resolve("foo/bar"), rootPath.resolve("bar"));

    Path testBuckFile = rootPath.resolve("foo").resolve("BUCK");
    Files.write(
        testBuckFile, "java_library(name = 'lib', srcs=glob(['bar/*.java']))\n".getBytes(UTF_8));

    BuildTarget libTarget = BuildTarget.builder(cellRoot, "//foo", "lib").build();
    Iterable<BuildTarget> buildTargets = ImmutableList.of(libTarget);

    parser.buildTargetGraph(eventBus, cell, false, executorService, buildTargets);

    DaemonicParserState permState = parser.getPermState();
    for (BuildTarget target : buildTargets) {
      assertTrue(
          permState
              .getOrCreateNodeCache(TargetNode.class)
              .lookupComputedNode(cell, target)
              .isPresent());
    }
  }

  @Test
  public void buildTargetHashCodePopulatesCorrectly() throws Exception {
    tempDir.newFolder("foo");

    Path testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(
        testFooBuckFile, "java_library(name = 'lib', visibility=['PUBLIC'])\n".getBytes(UTF_8));

    BuildTarget fooLibTarget = BuildTarget.builder(cellRoot, "//foo", "lib").build();

    // We can't precalculate the hash, since it depends on the buck version. Check for the presence
    // of a hash for the right key.
    HashCode hashCode = buildTargetGraphAndGetHashCodes(parser, fooLibTarget).get(fooLibTarget);

    assertNotNull(hashCode);
  }

  @Test
  public void readConfigReadsConfig() throws Exception {
    Path buckFile = cellRoot.resolve("BUCK");
    BuildTarget buildTarget =
        BuildTarget.of(
            UnflavoredBuildTarget.of(filesystem.getRootPath(), Optional.empty(), "//", "cake"));
    Files.write(
        buckFile,
        Joiner.on("")
            .join(
                ImmutableList.of(
                    "genrule(\n"
                        + "name = 'cake',\n"
                        + "out = read_config('foo', 'bar', 'default') + '.txt',\n"
                        + "cmd = 'touch $OUT'\n"
                        + ")\n"))
            .getBytes(UTF_8));

    BuckConfig config = FakeBuckConfig.builder().setFilesystem(filesystem).build();

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();
    TargetNode<GenruleDescriptionArg, ?> node =
        parser
            .getTargetNode(eventBus, cell, false, executorService, buildTarget)
            .castArg(GenruleDescriptionArg.class)
            .get();

    assertThat(node.getConstructorArg().getOut(), is(equalTo("default.txt")));
  }

  @Test
  public void emptyStringBuckConfigEntryDoesNotCauseInvalidation() throws Exception {
    Path buckFile = cellRoot.resolve("BUCK");
    Files.write(
        buckFile,
        Joiner.on("")
            .join(
                ImmutableList.of(
                    "read_config('foo', 'bar')\n",
                    "genrule(name = 'cake', out = 'file.txt', cmd = 'touch $OUT')\n"))
            .getBytes(UTF_8));

    BuckConfig config =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("foo", ImmutableMap.of("bar", "")))
            .setFilesystem(filesystem)
            .build();

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    parser.getAllTargetNodes(eventBus, cell, false, executorService, buckFile);

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
    Files.write(buckFile, "I do not parse as python".getBytes(UTF_8));

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
                x -> true, BuildFileSpec.fromRecursivePath(Paths.get("bar"), cell.getRoot())),
            TargetNodePredicateSpec.of(
                x -> true, BuildFileSpec.fromRecursivePath(Paths.get("foo"), cell.getRoot()))),
        SpeculativeParsing.of(true),
        ParserConfig.ApplyDefaultFlavorsMode.ENABLED);
  }

  @Test
  public void resolveTargetSpecsPreservesOrder() throws Exception {
    BuildTarget foo = BuildTargetFactory.newInstance(filesystem.getRootPath(), "//foo:foo");
    Path buckFile = cellRoot.resolve("foo/BUCK");
    Files.createDirectories(buckFile.getParent());
    Files.write(buckFile, "genrule(name='foo', out='foo', cmd='foo')".getBytes(UTF_8));

    BuildTarget bar = BuildTargetFactory.newInstance(filesystem.getRootPath(), "//bar:bar");
    buckFile = cellRoot.resolve("bar/BUCK");
    Files.createDirectories(buckFile.getParent());
    Files.write(buckFile, "genrule(name='bar', out='bar', cmd='bar')".getBytes(UTF_8));

    ImmutableList<ImmutableSet<BuildTarget>> targets =
        parser.resolveTargetSpecs(
            eventBus,
            cell,
            false,
            executorService,
            ImmutableList.of(
                TargetNodePredicateSpec.of(
                    x -> true, BuildFileSpec.fromRecursivePath(Paths.get("bar"), cell.getRoot())),
                TargetNodePredicateSpec.of(
                    x -> true, BuildFileSpec.fromRecursivePath(Paths.get("foo"), cell.getRoot()))),
            SpeculativeParsing.of(true),
            ParserConfig.ApplyDefaultFlavorsMode.ENABLED);
    assertThat(targets, equalTo(ImmutableList.of(ImmutableSet.of(bar), ImmutableSet.of(foo))));

    targets =
        parser.resolveTargetSpecs(
            eventBus,
            cell,
            false,
            executorService,
            ImmutableList.of(
                TargetNodePredicateSpec.of(
                    x -> true, BuildFileSpec.fromRecursivePath(Paths.get("foo"), cell.getRoot())),
                TargetNodePredicateSpec.of(
                    x -> true, BuildFileSpec.fromRecursivePath(Paths.get("bar"), cell.getRoot()))),
            SpeculativeParsing.of(true),
            ParserConfig.ApplyDefaultFlavorsMode.ENABLED);
    assertThat(targets, equalTo(ImmutableList.of(ImmutableSet.of(foo), ImmutableSet.of(bar))));
  }

  @Test
  public void defaultFlavorsInRuleArgsAppliedToTarget() throws Exception {
    // We depend on Xcode platforms for this test.
    assumeTrue(Platform.detect() == Platform.MACOS);

    Path buckFile = cellRoot.resolve("lib/BUCK");
    Files.createDirectories(buckFile.getParent());
    Files.write(
        buckFile,
        ("cxx_library("
                + "  name = 'lib', "
                + "  srcs=glob(['*.c']), "
                + "  defaults={'platform':'iphonesimulator-x86_64'}"
                + ")")
            .getBytes(UTF_8));

    ImmutableSet<BuildTarget> result =
        parser
            .buildTargetGraphForTargetNodeSpecs(
                eventBus,
                cell,
                false,
                executorService,
                ImmutableList.of(
                    AbstractBuildTargetSpec.from(
                        BuildTarget.builder(cellRoot, "//lib", "lib").build())),
                ParserConfig.ApplyDefaultFlavorsMode.ENABLED)
            .getBuildTargets();

    assertThat(
        result,
        hasItems(
            BuildTarget.builder(cellRoot, "//lib", "lib")
                .addFlavors(
                    InternalFlavor.of("iphonesimulator-x86_64"), InternalFlavor.of("static"))
                .build()));
  }

  @Test
  public void defaultFlavorsInConfigAppliedToTarget() throws Exception {
    // We depend on Xcode platforms for this test.
    assumeTrue(Platform.detect() == Platform.MACOS);

    Path buckFile = cellRoot.resolve("lib/BUCK");
    Files.createDirectories(buckFile.getParent());
    Files.write(
        buckFile,
        ("cxx_library(" + "  name = 'lib', " + "  srcs=glob(['*.c']) " + ")").getBytes(UTF_8));

    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(
                ImmutableMap.of(
                    "defaults.cxx_library",
                    ImmutableMap.of("platform", "iphoneos-arm64", "type", "shared")))
            .build();

    cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    ImmutableSet<BuildTarget> result =
        parser
            .buildTargetGraphForTargetNodeSpecs(
                eventBus,
                cell,
                false,
                executorService,
                ImmutableList.of(
                    AbstractBuildTargetSpec.from(
                        BuildTarget.builder(cellRoot, "//lib", "lib").build())),
                ParserConfig.ApplyDefaultFlavorsMode.ENABLED)
            .getBuildTargets();

    assertThat(
        result,
        hasItems(
            BuildTarget.builder(cellRoot, "//lib", "lib")
                .addFlavors(InternalFlavor.of("iphoneos-arm64"), InternalFlavor.of("shared"))
                .build()));
  }

  @Test
  public void defaultFlavorsInArgsOverrideDefaultsFromConfig() throws Exception {
    // We depend on Xcode platforms for this test.
    assumeTrue(Platform.detect() == Platform.MACOS);

    Path buckFile = cellRoot.resolve("lib/BUCK");
    Files.createDirectories(buckFile.getParent());
    Files.write(
        buckFile,
        ("cxx_library("
                + "  name = 'lib', "
                + "  srcs=glob(['*.c']), "
                + "  defaults={'platform':'macosx-x86_64'}"
                + ")")
            .getBytes(UTF_8));

    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(
                ImmutableMap.of(
                    "defaults.cxx_library",
                    ImmutableMap.of("platform", "iphoneos-arm64", "type", "shared")))
            .build();

    cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    ImmutableSet<BuildTarget> result =
        parser
            .buildTargetGraphForTargetNodeSpecs(
                eventBus,
                cell,
                false,
                executorService,
                ImmutableList.of(
                    AbstractBuildTargetSpec.from(
                        BuildTarget.builder(cellRoot, "//lib", "lib").build())),
                ParserConfig.ApplyDefaultFlavorsMode.ENABLED)
            .getBuildTargets();

    assertThat(
        result,
        hasItems(
            BuildTarget.builder(cellRoot, "//lib", "lib")
                .addFlavors(InternalFlavor.of("macosx-x86_64"), InternalFlavor.of("shared"))
                .build()));
  }

  @Test
  public void countsParsedBytes() throws Exception {
    Path buckFile = cellRoot.resolve("lib/BUCK");
    Files.createDirectories(buckFile.getParent());
    byte[] bytes =
        ("genrule(" + "name='gen'," + "out='generated', " + "cmd='touch ${OUT}')").getBytes(UTF_8);
    Files.write(buckFile, bytes);

    cell = new TestCellBuilder().setFilesystem(filesystem).build();

    final List<ParseEvent.Finished> events = new ArrayList<>();
    class EventListener {
      @Subscribe
      public void onParseFinished(ParseEvent.Finished event) {
        events.add(event);
      }
    }
    EventListener eventListener = new EventListener();
    eventBus.register(eventListener);

    parser.buildTargetGraphForTargetNodeSpecs(
        eventBus,
        cell,
        false,
        executorService,
        ImmutableList.of(
            AbstractBuildTargetSpec.from(BuildTarget.builder(cellRoot, "//lib", "gen").build())),
        ParserConfig.ApplyDefaultFlavorsMode.DISABLED);

    // The read bytes are dependent on the serialization format of the parser, and the absolute path
    // of the temporary BUCK file we wrote, so let's just assert that there are a reasonable
    // minimum.
    assertThat(
        Iterables.getOnlyElement(events).getProcessedBytes(),
        greaterThanOrEqualTo((long) bytes.length));

    // The value should be cached, so no bytes are read when re-computing.
    events.clear();
    parser.buildTargetGraphForTargetNodeSpecs(
        eventBus,
        cell,
        false,
        executorService,
        ImmutableList.of(
            AbstractBuildTargetSpec.from(BuildTarget.builder(cellRoot, "//lib", "gen").build())),
        ParserConfig.ApplyDefaultFlavorsMode.DISABLED);
    assertEquals(0L, Iterables.getOnlyElement(events).getProcessedBytes());
  }

  @Test
  public void testGetCacheReturnsSame() throws Exception {
    assertEquals(
        parser.getPermState().getOrCreateNodeCache(TargetNode.class),
        parser.getPermState().getOrCreateNodeCache(TargetNode.class));
    assertNotEquals(
        parser.getPermState().getOrCreateNodeCache(TargetNode.class),
        parser.getPermState().getOrCreateNodeCache(Map.class));
  }

  @Test
  public void testVisibilityGetsChecked() throws Exception {
    Path visibilityData = TestDataHelper.getTestDataScenario(this, "visibility");
    Path visibilityBuckFile = cellRoot.resolve("BUCK");
    Path visibilitySubBuckFile = cellRoot.resolve("sub/BUCK");
    Files.createDirectories(visibilityBuckFile.getParent());
    Files.createDirectories(visibilitySubBuckFile.getParent());
    Files.copy(visibilityData.resolve("BUCK.fixture"), visibilityBuckFile);
    Files.copy(visibilityData.resolve("sub/BUCK.fixture"), visibilitySubBuckFile);

    parser.buildTargetGraph(
        eventBus,
        cell,
        false,
        executorService,
        ImmutableSet.of(BuildTargetFactory.newInstance(cellRoot, "//:should_pass")));
    parser.buildTargetGraph(
        eventBus,
        cell,
        false,
        executorService,
        ImmutableSet.of(BuildTargetFactory.newInstance(cellRoot, "//:should_pass2")));
    try {
      parser.buildTargetGraph(
          eventBus,
          cell,
          false,
          executorService,
          ImmutableSet.of(BuildTargetFactory.newInstance(cellRoot, "//:should_fail")));
      Assert.fail("did not expect to succeed parsing");
    } catch (Exception e) {
      assertThat(e, instanceOf(HumanReadableException.class));
      assertThat(
          e.getMessage(),
          containsString("//:should_fail depends on //sub:sub, which is not visible"));
    }
  }

  @Test
  public void whenEnvChangesThenCachedRulesAreInvalidated() throws Exception {
    Path buckFile = cellRoot.resolve("BUCK");
    Files.write(
        buckFile,
        Joiner.on("")
            .join(
                ImmutableList.of(
                    "import os\n",
                    "os.getenv('FOO')\n",
                    "genrule(name = 'cake', out = 'file.txt', cmd = 'touch $OUT')\n"))
            .getBytes(UTF_8));

    BuckConfig config =
        FakeBuckConfig.builder()
            .setEnvironment(
                ImmutableMap.<String, String>builder()
                    .putAll(System.getenv())
                    .put("FOO", "value")
                    .build())
            .setFilesystem(filesystem)
            .build();

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    parser.getAllTargetNodes(eventBus, cell, false, executorService, buckFile);

    // Call filterAllTargetsInProject to request cached rules.
    config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setEnvironment(
                ImmutableMap.<String, String>builder()
                    .putAll(System.getenv())
                    .put("FOO", "other value")
                    .build())
            .build();

    cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    parser.getAllTargetNodes(eventBus, cell, false, executorService, buckFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated.", 2, counter.calls);
  }

  @Test
  public void whenEnvAddedThenCachedRulesAreInvalidated() throws Exception {
    Path buckFile = cellRoot.resolve("BUCK");
    Files.write(
        buckFile,
        Joiner.on("")
            .join(
                ImmutableList.of(
                    "import os\n",
                    "os.getenv('FOO')\n",
                    "genrule(name = 'cake', out = 'file.txt', cmd = 'touch $OUT')\n"))
            .getBytes(UTF_8));

    BuckConfig config = FakeBuckConfig.builder().setFilesystem(filesystem).build();

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    parser.getAllTargetNodes(eventBus, cell, false, executorService, buckFile);

    // Call filterAllTargetsInProject to request cached rules.
    config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setEnvironment(
                ImmutableMap.<String, String>builder()
                    .putAll(System.getenv())
                    .put("FOO", "other value")
                    .build())
            .build();

    cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    parser.getAllTargetNodes(eventBus, cell, false, executorService, buckFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated.", 2, counter.calls);
  }

  @Test
  public void whenEnvRemovedThenCachedRulesAreInvalidated() throws Exception {
    Path buckFile = cellRoot.resolve("BUCK");
    Files.write(
        buckFile,
        Joiner.on("")
            .join(
                ImmutableList.of(
                    "import os\n",
                    "os.getenv('FOO')\n",
                    "genrule(name = 'cake', out = 'file.txt', cmd = 'touch $OUT')\n"))
            .getBytes(UTF_8));

    BuckConfig config =
        FakeBuckConfig.builder()
            .setEnvironment(
                ImmutableMap.<String, String>builder()
                    .putAll(System.getenv())
                    .put("FOO", "value")
                    .build())
            .setFilesystem(filesystem)
            .build();

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    parser.getAllTargetNodes(eventBus, cell, false, executorService, buckFile);

    // Call filterAllTargetsInProject to request cached rules.
    config = FakeBuckConfig.builder().setFilesystem(filesystem).build();

    cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    parser.getAllTargetNodes(eventBus, cell, false, executorService, buckFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated.", 2, counter.calls);
  }

  @Test
  public void whenUnrelatedEnvChangesThenCachedRulesAreNotInvalidated() throws Exception {
    Path buckFile = cellRoot.resolve("BUCK");
    Files.write(
        buckFile,
        Joiner.on("")
            .join(
                ImmutableList.of(
                    "import os\n",
                    "os.getenv('FOO')\n",
                    "genrule(name = 'cake', out = 'file.txt', cmd = 'touch $OUT')\n"))
            .getBytes(UTF_8));

    BuckConfig config =
        FakeBuckConfig.builder()
            .setEnvironment(
                ImmutableMap.<String, String>builder()
                    .putAll(System.getenv())
                    .put("FOO", "value")
                    .put("BAR", "something")
                    .build())
            .setFilesystem(filesystem)
            .build();

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    parser.getAllTargetNodes(eventBus, cell, false, executorService, buckFile);

    // Call filterAllTargetsInProject to request cached rules.
    config =
        FakeBuckConfig.builder()
            .setEnvironment(
                ImmutableMap.<String, String>builder()
                    .putAll(System.getenv())
                    .put("FOO", "value")
                    .put("BAR", "something else")
                    .build())
            .setFilesystem(filesystem)
            .build();

    cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    parser.getAllTargetNodes(eventBus, cell, false, executorService, buckFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should not have invalidated.", 1, counter.calls);
  }

  private BuildRuleResolver buildActionGraph(BuckEventBus eventBus, TargetGraph targetGraph) {
    return Preconditions.checkNotNull(ActionGraphCache.getFreshActionGraph(eventBus, targetGraph))
        .getResolver();
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
      Predicate<TargetNode<?, ?>> filter,
      BuckEventBus buckEventBus,
      ListeningExecutorService executor)
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    return FluentIterable.from(
            parser
                .buildTargetGraphForTargetNodeSpecs(
                    buckEventBus,
                    cell,
                    false,
                    executor,
                    ImmutableList.of(
                        TargetNodePredicateSpec.of(
                            filter,
                            BuildFileSpec.fromRecursivePath(Paths.get(""), cell.getRoot()))))
                .getTargetGraph()
                .getNodes())
        .filter(filter)
        .transform(TargetNode::getBuildTarget)
        .toSet();
  }

  private ImmutableMap<BuildTarget, HashCode> buildTargetGraphAndGetHashCodes(
      Parser parser, BuildTarget... buildTargets) throws Exception {
    // Build the target graph so we can access the hash code cache.

    ImmutableList<BuildTarget> buildTargetsList = ImmutableList.copyOf(buildTargets);
    TargetGraph targetGraph =
        parser.buildTargetGraph(eventBus, cell, false, executorService, buildTargetsList);

    ImmutableMap.Builder<BuildTarget, HashCode> toReturn = ImmutableMap.builder();
    for (TargetNode<?, ?> node : targetGraph.getNodes()) {
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
