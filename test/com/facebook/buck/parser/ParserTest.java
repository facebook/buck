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

import com.facebook.buck.apple.AppleNativeIntegrationTestUtils;
import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.apple.toolchain.AppleDeveloperDirectoryProvider;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.AppleSdkLocation;
import com.facebook.buck.apple.toolchain.AppleToolchainProvider;
import com.facebook.buck.apple.toolchain.impl.AppleCxxPlatformsProviderFactory;
import com.facebook.buck.apple.toolchain.impl.AppleDeveloperDirectoryProviderFactory;
import com.facebook.buck.apple.toolchain.impl.AppleSdkLocationFactory;
import com.facebook.buck.apple.toolchain.impl.AppleToolchainProviderFactory;
import com.facebook.buck.config.ActionGraphParallelizationMode;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.config.IncrementalActionGraphMode;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.FakeBuckEventListener;
import com.facebook.buck.event.listener.BroadcastEventListener;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.WatchmanOverflowEvent;
import com.facebook.buck.io.WatchmanPathEvent;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.parser.events.ParseBuckFileEvent;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.MissingBuildFileException;
import com.facebook.buck.parser.thrift.RemoteDaemonicCellState;
import com.facebook.buck.parser.thrift.RemoteDaemonicParserState;
import com.facebook.buck.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.rules.ActionGraphCache;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.DefaultKnownBuildRuleTypesFactory;
import com.facebook.buck.rules.KnownBuildRuleTypesProvider;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.sandbox.TestSandboxExecutionStrategyFactory;
import com.facebook.buck.shell.GenruleDescriptionArg;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.toolchain.ToolchainCreationContext;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.util.CloseableMemoizedSupplier;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.config.ConfigBuilder;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
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
import java.nio.file.StandardOpenOption;
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
  private TypeCoercerFactory typeCoercerFactory;
  private ProjectFilesystem filesystem;
  private Path cellRoot;
  private BuckEventBus eventBus;
  private Cell cell;
  private KnownBuildRuleTypesProvider knownBuildRuleTypesProvider;
  private ParseEventStartedCounter counter;
  private ListeningExecutorService executorService;
  private ExecutableFinder executableFinder;

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
      TypeCoercerFactory typeCoercerFactory,
      BuckEventBus eventBus,
      Cell cell,
      KnownBuildRuleTypesProvider knownBuildRuleTypesProvider,
      boolean enableProfiling,
      ListeningExecutorService executor,
      ExecutableFinder executableFinder,
      Path buildFile)
      throws BuildFileParseException {
    try (PerBuildState state =
        new PerBuildState(
            typeCoercerFactory,
            new ConstructorArgMarshaller(typeCoercerFactory),
            parser.getPermState(),
            eventBus,
            executableFinder,
            executor,
            cell,
            knownBuildRuleTypesProvider,
            enableProfiling,
            PerBuildState.SpeculativeParsing.DISABLED)) {
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
        TestProjectFilesystems.createProjectFilesystem(
            root, ConfigBuilder.createFromText("[project]", "ignore = **/*.swp"));
    cellRoot = filesystem.getRootPath();
    eventBus = BuckEventBusForTests.newInstance();

    ImmutableMap.Builder<String, String> projectSectionBuilder = ImmutableMap.builder();
    projectSectionBuilder.put("allow_symlinks", "warn");
    if (parallelParsing) {
      projectSectionBuilder.put("parallel_parsing", "true");
      projectSectionBuilder.put("parsing_threads", Integer.toString(threads));
    }

    ImmutableMap.Builder<String, ImmutableMap<String, String>> configSectionsBuilder =
        ImmutableMap.builder();
    configSectionsBuilder.put(
        "buildfile", ImmutableMap.of("includes", "//java/com/facebook/defaultIncludeFile"));
    configSectionsBuilder.put(
        "unknown_flavors_messages",
        ImmutableMap.of("macosx*", "This is an error message read by the .buckconfig"));
    configSectionsBuilder.put("project", projectSectionBuilder.build());

    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(configSectionsBuilder.build())
            .build();

    ProcessExecutor processExecutor = new DefaultProcessExecutor(new TestConsole());

    executableFinder = new ExecutableFinder();

    ToolchainCreationContext toolchainCreationContext =
        ToolchainCreationContext.of(
            ImmutableMap.of(),
            config,
            filesystem,
            processExecutor,
            executableFinder,
            TestRuleKeyConfigurationFactory.create());

    ToolchainProviderBuilder toolchainProviderBuilder = new ToolchainProviderBuilder();
    Optional<AppleDeveloperDirectoryProvider> appleDeveloperDirectoryProvider =
        new AppleDeveloperDirectoryProviderFactory()
            .createToolchain(toolchainProviderBuilder.build(), toolchainCreationContext);
    appleDeveloperDirectoryProvider.ifPresent(
        provider ->
            toolchainProviderBuilder.withToolchain(
                AppleDeveloperDirectoryProvider.DEFAULT_NAME, provider));
    Optional<AppleToolchainProvider> appleToolchainProvider =
        new AppleToolchainProviderFactory()
            .createToolchain(toolchainProviderBuilder.build(), toolchainCreationContext);
    appleToolchainProvider.ifPresent(
        provider ->
            toolchainProviderBuilder.withToolchain(AppleToolchainProvider.DEFAULT_NAME, provider));
    Optional<AppleSdkLocation> appleSdkLocation =
        new AppleSdkLocationFactory()
            .createToolchain(toolchainProviderBuilder.build(), toolchainCreationContext);
    appleSdkLocation.ifPresent(
        provider ->
            toolchainProviderBuilder.withToolchain(AppleSdkLocation.DEFAULT_NAME, provider));
    Optional<AppleCxxPlatformsProvider> appleCxxPlatformsProvider =
        new AppleCxxPlatformsProviderFactory()
            .createToolchain(toolchainProviderBuilder.build(), toolchainCreationContext);
    appleCxxPlatformsProvider.ifPresent(
        provider ->
            toolchainProviderBuilder.withToolchain(
                AppleCxxPlatformsProvider.DEFAULT_NAME, provider));

    cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();
    knownBuildRuleTypesProvider =
        KnownBuildRuleTypesProvider.of(
            DefaultKnownBuildRuleTypesFactory.of(
                processExecutor,
                BuckPluginManagerFactory.createPluginManager(),
                new TestSandboxExecutionStrategyFactory()));

    BroadcastEventListener broadcastEventListener = new BroadcastEventListener();
    broadcastEventListener.addEventBus(eventBus);
    typeCoercerFactory = new DefaultTypeCoercerFactory();
    parser =
        new Parser(
            broadcastEventListener,
            cell.getBuckConfig().getView(ParserConfig.class),
            typeCoercerFactory,
            new ConstructorArgMarshaller(typeCoercerFactory),
            knownBuildRuleTypesProvider,
            executableFinder);

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
    BuildTarget fooTarget = BuildTargetFactory.newInstance(cellRoot, "//java/com/facebook", "foo");
    BuildTarget barTarget = BuildTargetFactory.newInstance(cellRoot, "//java/com/facebook", "bar");
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget, barTarget);

    // The EventBus should be updated with events indicating how parsing ran.
    FakeBuckEventListener listener = new FakeBuckEventListener();
    eventBus.register(listener);

    TargetGraph targetGraph =
        parser.buildTargetGraph(eventBus, cell, false, executorService, buildTargets);
    BuildRuleResolver resolver = buildActionGraph(eventBus, targetGraph, cell);
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
      throws BuildFileParseException, IOException, InterruptedException {
    // Execute buildTargetGraphForBuildTargets() with a target in a valid file but a bad rule name.
    BuildTarget fooTarget = BuildTargetFactory.newInstance(cellRoot, "//java/com/facebook", "foo");
    BuildTarget razTarget = BuildTargetFactory.newInstance(cellRoot, "//java/com/facebook", "raz");
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget, razTarget);

    thrown.expectMessage(
        "The rule //java/com/facebook:raz could not be found.\nPlease check the spelling and whether it exists in "
            + filesystem.resolve(razTarget.getBasePath()).resolve(DEFAULT_BUILD_FILE_NAME));

    parser.buildTargetGraph(eventBus, cell, false, executorService, buildTargets);
  }

  @Test
  public void testMissingBuildFile()
      throws InterruptedException, BuildFileParseException, IOException {
    BuildTarget target = BuildTargetFactory.newInstance(cellRoot, "//path/to/nowhere", "nowhere");
    Iterable<BuildTarget> buildTargets = ImmutableList.of(target);

    thrown.expect(MissingBuildFileException.class);
    thrown.expectMessage(
        String.format(
            "No build file at %s when resolving target //path/to/nowhere:nowhere",
            Paths.get("path", "to", "nowhere", "BUCK").toString()));

    parser.buildTargetGraph(eventBus, cell, false, executorService, buildTargets);
  }

  @Test
  public void shouldThrowAnExceptionIfConstructorArgMashallingFails()
      throws IOException, BuildFileParseException {
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
      throws IOException, InterruptedException, BuildFileParseException {
    thrown.expectMessage("Buck wasn't able to parse");
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
      throws IOException, BuildFileParseException {
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("Duplicate rule definition 'cake' found.");

    Path buckFile = cellRoot.resolve("BUCK");
    Files.write(
        buckFile,
        ("export_file(name = 'cake', src = 'hello.txt')\n"
                + "genrule(name = 'cake', out = 'file.txt', cmd = 'touch $OUT')\n")
            .getBytes(UTF_8));

    parser.getAllTargetNodes(eventBus, cell, false, executorService, buckFile);
  }

  @Test
  public void shouldAllowAccessingBuiltInRulesViaNative() throws Exception {
    Files.write(
        includedByBuildFile,
        "def foo(name): native.export_file(name=name)\n".getBytes(UTF_8),
        StandardOpenOption.APPEND);
    Files.write(testBuildFile, "foo(name='BUCK')\n".getBytes(UTF_8), StandardOpenOption.APPEND);
    parser.getAllTargetNodes(eventBus, cell, false, executorService, testBuildFile);
  }

  @Test
  public void shouldThrowAnExceptionIfNameIsNone() throws IOException, BuildFileParseException {
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("rules 'name' field must be a string.  Found None.");

    Path buckFile = cellRoot.resolve("BUCK");
    Files.write(
        buckFile, ("genrule(name = None, out = 'file.txt', cmd = 'touch $OUT')\n").getBytes(UTF_8));

    parser.getAllTargetNodes(eventBus, cell, false, executorService, buckFile);
  }

  @Test
  public void shouldThrowAnExceptionWhenAnUnknownFlavorIsSeen()
      throws BuildFileParseException, InterruptedException, IOException {
    BuildTarget flavored =
        BuildTargetFactory.newInstance(
            cellRoot, "//java/com/facebook", "foo", InternalFlavor.of("doesNotExist"));

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        containsString(
            "The following flavor(s) are not supported on target "
                + "//java/com/facebook:foo#doesNotExist"));
    parser.buildTargetGraph(
        eventBus, cell, false, executorService, ImmutableSortedSet.of(flavored));
  }

  @Test
  public void shouldThrowAnExceptionWhenAnUnknownFlavorIsSeenAndShowSuggestionsDefault()
      throws BuildFileParseException, InterruptedException, IOException {
    BuildTarget flavored =
        BuildTargetFactory.newInstance(
            cellRoot, "//java/com/facebook", "foo", InternalFlavor.of("android-unknown"));

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        containsString(
            "The following flavor(s) are not supported on target "
                + "//java/com/facebook:foo#android-unknown"));
    thrown.expectMessage(
        containsString(
            "android-unknown: Please make sure you have the Android SDK/NDK "
                + "installed and set up. "
                + "See https://buckbuild.com/setup/install.html#locate-android-sdk"));
    parser.buildTargetGraph(
        eventBus, cell, false, executorService, ImmutableSortedSet.of(flavored));
  }

  @Test
  public void shouldThrowAnExceptionWhenAnUnknownFlavorIsSeenAndShowSuggestionsFromConfig()
      throws BuildFileParseException, InterruptedException, IOException {
    BuildTarget flavored =
        BuildTargetFactory.newInstance(
            cellRoot, "//java/com/facebook", "foo", InternalFlavor.of("macosx109sdk"));

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        containsString(
            "The following flavor(s) are not supported on target "
                + "//java/com/facebook:foo#macosx109sdk"));
    thrown.expectMessage(
        containsString("macosx109sdk: This is an error message read by the .buckconfig"));

    parser.buildTargetGraph(
        eventBus, cell, false, executorService, ImmutableSortedSet.of(flavored));
  }

  @Test
  public void shouldThrowAnExceptionWhenAFlavorIsAskedOfATargetThatDoesntSupportFlavors()
      throws BuildFileParseException, InterruptedException, IOException {
    BuildTarget flavored =
        BuildTargetFactory.newInstance(cellRoot, "//java/com/facebook", "baz", JavaLibrary.SRC_JAR);

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "The following flavor(s) are not supported on target //java/com/facebook:baz:\n" + "src.");
    parser.buildTargetGraph(
        eventBus, cell, false, executorService, ImmutableSortedSet.of(flavored));
  }

  @Test
  public void testInvalidDepFromValidFile()
      throws IOException, BuildFileParseException, InterruptedException {
    // Ensure an exception with a specific message is thrown.
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "This error happened while trying to get dependency '//java/com/facebook/invalid/lib:missing_rule' of target '//java/com/facebook/invalid:foo'");

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
        BuildTargetFactory.newInstance(cellRoot, "//java/com/facebook/invalid", "foo");
    Iterable<BuildTarget> buildTargets = ImmutableList.of(fooTarget);

    parser.buildTargetGraph(eventBus, cell, false, executorService, buildTargets);
  }

  @Test
  public void whenAllRulesRequestedWithTrueFilterThenMultipleRulesReturned()
      throws BuildFileParseException, IOException, InterruptedException {
    ImmutableSet<BuildTarget> targets =
        filterAllTargetsInProject(
            parser, cell, BuckEventBusForTests.newInstance(), executorService);

    ImmutableSet<BuildTarget> expectedTargets =
        ImmutableSet.of(
            BuildTargetFactory.newInstance(cellRoot, "//java/com/facebook", "foo"),
            BuildTargetFactory.newInstance(cellRoot, "//java/com/facebook", "bar"),
            BuildTargetFactory.newInstance(cellRoot, "//java/com/facebook", "baz"));
    assertEquals("Should have returned all rules.", expectedTargets, targets);
  }

  @Test
  public void whenAllRulesAreRequestedMultipleTimesThenRulesAreOnlyParsedOnce()
      throws BuildFileParseException, IOException, InterruptedException {
    filterAllTargetsInProject(parser, cell, eventBus, executorService);
    filterAllTargetsInProject(parser, cell, eventBus, executorService);

    assertEquals("Should have cached build rules.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfNonPathEventThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException, InterruptedException {
    // Call filterAllTargetsInProject to populate the cache.
    filterAllTargetsInProject(parser, cell, eventBus, executorService);

    // Process event.
    parser.onFileSystemChange(WatchmanOverflowEvent.of(filesystem.getRootPath(), ""));

    // Call filterAllTargetsInProject to request cached rules.
    filterAllTargetsInProject(parser, cell, eventBus, executorService);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void pathInvalidationWorksAfterOverflow() throws Exception {
    // Call filterAllTargetsInProject to populate the cache.
    filterAllTargetsInProject(parser, cell, eventBus, executorService);

    // Send overflow event.
    parser.onFileSystemChange(WatchmanOverflowEvent.of(filesystem.getRootPath(), ""));

    // Call filterAllTargetsInProject to request cached rules.
    filterAllTargetsInProject(parser, cell, eventBus, executorService);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);

    // Send a "file added" event.
    parser.onFileSystemChange(
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.CREATE,
            Paths.get("java/com/facebook/Something.java")));

    // Call filterAllTargetsInProject to request cached rules.
    filterAllTargetsInProject(parser, cell, eventBus, executorService);

    // Test that the third parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 3, counter.calls);
  }

  @Test
  public void whenEnvironmentNotChangedThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException, IOException, InterruptedException {
    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setEnvironment(
                ImmutableMap.of("Some Key", "Some Value", "PATH", System.getenv("PATH")))
            .build();

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    // Call filterAllTargetsInProject to populate the cache.
    filterAllTargetsInProject(parser, cell, eventBus, executorService);

    // Call filterAllTargetsInProject to request cached rules with identical environment.
    filterAllTargetsInProject(parser, cell, eventBus, executorService);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should not have invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfBuildFileAddThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    parser.onFileSystemChange(
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.CREATE,
            MorePaths.relativize(tempDir.getRoot().toRealPath(), testBuildFile)));

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfBuildFileChangeThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.MODIFY,
            MorePaths.relativize(tempDir.getRoot().toRealPath(), testBuildFile));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfBuildFileDeleteThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.DELETE,
            MorePaths.relativize(tempDir.getRoot().toRealPath(), testBuildFile));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfIncludeFileAddThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.CREATE,
            MorePaths.relativize(tempDir.getRoot().toRealPath(), includedByBuildFile));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfIncludeFileChangeThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    assertEquals("Should have parsed at all.", 1, counter.calls);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.MODIFY,
            MorePaths.relativize(tempDir.getRoot().toRealPath(), includedByBuildFile));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfIncludeFileDeleteThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.DELETE,
            MorePaths.relativize(tempDir.getRoot().toRealPath(), includedByBuildFile));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOf2ndOrderIncludeFileAddThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.CREATE,
            MorePaths.relativize(tempDir.getRoot().toRealPath(), includedByIncludeFile));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOf2ndOrderIncludeFileChangeThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.MODIFY,
            MorePaths.relativize(tempDir.getRoot().toRealPath(), includedByIncludeFile));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOf2ndOrderIncludeFileDeleteThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.DELETE,
            MorePaths.relativize(tempDir.getRoot().toRealPath(), includedByIncludeFile));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfDefaultIncludeFileAddThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.CREATE,
            MorePaths.relativize(tempDir.getRoot().toRealPath(), defaultIncludeFile));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfDefaultIncludeFileChangeThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.MODIFY,
            MorePaths.relativize(tempDir.getRoot().toRealPath(), defaultIncludeFile));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfDefaultIncludeFileDeleteThenCacheRulesAreInvalidated()
      throws BuildFileParseException, IOException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.DELETE,
            MorePaths.relativize(tempDir.getRoot().toRealPath(), defaultIncludeFile));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  // TODO(simons): avoid invalidation when arbitrary contained (possibly backup) files are added.
  public void whenNotifiedOfContainedFileAddThenCacheRulesAreInvalidated()
      throws BuildFileParseException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.CREATE,
            Paths.get("java/com/facebook/SomeClass.java"));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

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
                "check_package_boundary = false")
            .build();
    Cell cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    Path testAncestorBuildFile = tempDir.newFile("java/BUCK").toRealPath();
    Files.write(testAncestorBuildFile, "java_library(name = 'root')\n".getBytes(UTF_8));

    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testAncestorBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.CREATE,
            Paths.get("java/com/facebook/SomeClass.java"));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testAncestorBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfContainedFileChangeThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.MODIFY,
            Paths.get("java/com/facebook/SomeClass.java"));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call did not repopulate the cache.
    assertEquals("Should have not invalidated cache.", 1, counter.calls);
  }

  @Test
  // TODO(simons): avoid invalidation when arbitrary contained (possibly backup) files are deleted.
  public void whenNotifiedOfContainedFileDeleteThenCacheRulesAreInvalidated()
      throws BuildFileParseException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.DELETE,
            Paths.get("java/com/facebook/SomeClass.java"));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenNotifiedOfContainedTempFileAddThenCachedRulesAreNotInvalidated()
      throws BuildFileParseException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.CREATE,
            Paths.get("java/com/facebook/MumbleSwp.Java.swp"));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should not have invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfContainedTempFileChangeThenCachedRulesAreNotInvalidated()
      throws BuildFileParseException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.MODIFY,
            Paths.get("java/com/facebook/MumbleSwp.Java.swp"));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should not have invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfContainedTempFileDeleteThenCachedRulesAreNotInvalidated()
      throws BuildFileParseException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.DELETE,
            Paths.get("java/com/facebook/MumbleSwp.Java.swp"));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call repopulated the cache.
    assertEquals("Should not have invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfUnrelatedFileAddThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.CREATE,
            Paths.get("SomeClass.java__backup"));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call did not repopulate the cache.
    assertEquals("Should have not invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfUnrelatedFileChangeThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.MODIFY,
            Paths.get("SomeClass.java__backup"));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call did not repopulate the cache.
    assertEquals("Should have not invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenNotifiedOfUnrelatedFileDeleteThenCacheRulesAreNotInvalidated()
      throws BuildFileParseException, InterruptedException {
    // Call parseBuildFile to populate the cache.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Process event.
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            filesystem.getRootPath(),
            WatchmanPathEvent.Kind.DELETE,
            Paths.get("SomeClass.java__backup"));
    parser.onFileSystemChange(event);

    // Call parseBuildFile to request cached rules.
    getRawTargetNodes(
        parser,
        typeCoercerFactory,
        eventBus,
        cell,
        knownBuildRuleTypesProvider,
        false,
        executorService,
        executableFinder,
        testBuildFile);

    // Test that the second parseBuildFile call did not repopulate the cache.
    assertEquals("Should have not invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenAllRulesAreRequestedWithDifferingIncludesThenRulesAreParsedTwice()
      throws BuildFileParseException, IOException, InterruptedException {
    filterAllTargetsInProject(parser, cell, eventBus, executorService);

    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(
                ImmutableMap.of(
                    ParserConfig.BUILDFILE_SECTION_NAME,
                    ImmutableMap.of(ParserConfig.INCLUDES_PROPERTY_NAME, "//bar.py")))
            .build();
    Cell cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    filterAllTargetsInProject(parser, cell, eventBus, executorService);

    assertEquals("Should have invalidated cache.", 2, counter.calls);
  }

  @Test
  public void whenAllRulesAreRequestedWithDifferingCellsThenRulesAreParsedOnce()
      throws BuildFileParseException, IOException, InterruptedException {
    filterAllTargetsInProject(parser, cell, eventBus, executorService);

    assertEquals("Should have parsed once.", 1, counter.calls);

    Path newTempDir = Files.createTempDirectory("junit-temp-path").toRealPath();
    Files.createFile(newTempDir.resolve("bar.py"));
    ProjectFilesystem newFilesystem = TestProjectFilesystems.createProjectFilesystem(newTempDir);
    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(newFilesystem)
            .setSections(
                ImmutableMap.of(
                    ParserConfig.BUILDFILE_SECTION_NAME,
                    ImmutableMap.of(ParserConfig.INCLUDES_PROPERTY_NAME, "//bar.py")))
            .build();
    Cell cell = new TestCellBuilder().setFilesystem(newFilesystem).setBuckConfig(config).build();

    filterAllTargetsInProject(parser, cell, eventBus, executorService);

    assertEquals("Should not have invalidated cache.", 1, counter.calls);
  }

  @Test
  public void whenAllRulesThenSingleTargetRequestedThenRulesAreParsedOnce()
      throws BuildFileParseException, IOException, InterruptedException {
    filterAllTargetsInProject(parser, cell, eventBus, executorService);
    BuildTarget foo = BuildTargetFactory.newInstance(cellRoot, "//java/com/facebook", "foo");
    parser.buildTargetGraph(eventBus, cell, false, executorService, ImmutableList.of(foo));

    assertEquals("Should have cached build rules.", 1, counter.calls);
  }

  @Test
  public void whenSingleTargetThenAllRulesRequestedThenRulesAreParsedOnce()
      throws BuildFileParseException, IOException, InterruptedException {
    BuildTarget foo = BuildTargetFactory.newInstance(cellRoot, "//java/com/facebook", "foo");
    parser.buildTargetGraph(eventBus, cell, false, executorService, ImmutableList.of(foo));
    filterAllTargetsInProject(parser, cell, eventBus, executorService);

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
        BuildTargetFactory.newInstance(cellRoot, "//bar", "bar", InternalFlavor.of("src"));
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
  public void depsetCanBeUsedForSpecifyingDeps() throws Exception {
    tempDir.newFolder("foo");
    tempDir.newFolder("bar");

    Path testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(
        testFooBuckFile, "java_library(name = 'foo', visibility=['PUBLIC'])\n".getBytes(UTF_8));

    Path testBarBuckFile = tempDir.newFile("bar/BUCK");
    Files.write(
        testBarBuckFile,
        ("java_library(name = 'bar',\n" + "  deps = depset(['//foo:foo']))\n").getBytes(UTF_8));

    // Fetch //bar:bar#src to put it in cache.
    BuildTarget barTarget =
        BuildTargetFactory.newInstance(cellRoot, "//bar", "bar", InternalFlavor.of("src"));
    Iterable<BuildTarget> buildTargets = ImmutableList.of(barTarget);

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
    BuildTarget fooLibTarget = BuildTargetFactory.newInstance(cellRoot, "//foo", "lib");
    HashCode original = buildTargetGraphAndGetHashCodes(parser, fooLibTarget).get(fooLibTarget);

    DefaultTypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    parser =
        new Parser(
            new BroadcastEventListener(),
            cell.getBuckConfig().getView(ParserConfig.class),
            typeCoercerFactory,
            new ConstructorArgMarshaller(typeCoercerFactory),
            knownBuildRuleTypesProvider,
            executableFinder);
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

    BuildTarget fooLibTarget = BuildTargetFactory.newInstance(cellRoot, "//foo", "lib");
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

    BuildTarget fooLibTarget = BuildTargetFactory.newInstance(cellRoot, "//foo", "lib");

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

    BuildTarget fooLibTarget = BuildTargetFactory.newInstance(cellRoot, "//foo", "lib");
    BuildTarget fooLib2Target = BuildTargetFactory.newInstance(cellRoot, "//foo", "lib2");

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

    BuildTarget fooLibTarget = BuildTargetFactory.newInstance(cellRoot, "//foo", "lib");
    BuildTarget fooLib2Target = BuildTargetFactory.newInstance(cellRoot, "//foo", "lib2");
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
            new ConstructorArgMarshaller(typeCoercerFactory),
            knownBuildRuleTypesProvider,
            executableFinder);
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
      throws IOException, BuildFileParseException {
    tempDir.newFolder("foo");

    Path testFooBuckFile = tempDir.newFile("foo/BUCK").toRealPath();
    Files.write(
        testFooBuckFile,
        "java_library(name = 'lib1')\njava_library(name = 'lib2')\n".getBytes(UTF_8));
    BuildTarget fooLib1Target = BuildTargetFactory.newInstance(cellRoot, "//foo", "lib1");
    BuildTarget fooLib2Target = BuildTargetFactory.newInstance(cellRoot, "//foo", "lib2");

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
            .collect(ImmutableList.toImmutableList()),
        hasItems(fooLib1Target, fooLib2Target));
  }

  @Test
  public void getOrLoadTargetNodeRules() throws IOException, BuildFileParseException {
    tempDir.newFolder("foo");

    Path testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(testFooBuckFile, "java_library(name = 'lib')\n".getBytes(UTF_8));
    BuildTarget fooLibTarget = BuildTargetFactory.newInstance(cellRoot, "//foo", "lib");

    TargetNode<?, ?> targetNode =
        parser.getTargetNode(eventBus, cell, false, executorService, fooLibTarget);
    assertThat(targetNode.getBuildTarget(), equalTo(fooLibTarget));

    SortedMap<String, Object> rules =
        parser.getRawTargetNode(eventBus, cell, false, executorService, targetNode);
    assertThat(rules, Matchers.hasKey("name"));
    assertThat(rules.get("name"), equalTo(targetNode.getBuildTarget().getShortName()));
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
    BuildTarget libTarget = BuildTargetFactory.newInstance(cellRoot, "//foo", "lib");
    Iterable<BuildTarget> buildTargets = ImmutableList.of(libTarget);

    {
      TargetGraph targetGraph =
          parser.buildTargetGraph(eventBus, cell, false, executorService, buildTargets);
      BuildRuleResolver resolver = buildActionGraph(eventBus, targetGraph, cell);

      JavaLibrary libRule = (JavaLibrary) resolver.requireRule(libTarget);
      assertEquals(
          ImmutableSortedSet.of(PathSourcePath.of(filesystem, Paths.get("foo/bar/Bar.java"))),
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
      BuildRuleResolver resolver = buildActionGraph(eventBus, targetGraph, cell);

      JavaLibrary libRule = (JavaLibrary) resolver.requireRule(libTarget);
      assertEquals(
          ImmutableSet.of(
              PathSourcePath.of(filesystem, Paths.get("foo/bar/Bar.java")),
              PathSourcePath.of(filesystem, Paths.get("foo/bar/Baz.java"))),
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
    BuildTarget libTarget = BuildTargetFactory.newInstance(cellRoot, "//foo", "lib");
    Iterable<BuildTarget> buildTargets = ImmutableList.of(libTarget);

    {
      TargetGraph targetGraph =
          parser.buildTargetGraph(eventBus, cell, false, executorService, buildTargets);
      BuildRuleResolver resolver = buildActionGraph(eventBus, targetGraph, cell);

      JavaLibrary libRule = (JavaLibrary) resolver.requireRule(libTarget);

      assertEquals(
          ImmutableSortedSet.of(
              PathSourcePath.of(filesystem, Paths.get("foo/bar/Bar.java")),
              PathSourcePath.of(filesystem, Paths.get("foo/bar/Baz.java"))),
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
      BuildRuleResolver resolver = buildActionGraph(eventBus, targetGraph, cell);

      JavaLibrary libRule = (JavaLibrary) resolver.requireRule(libTarget);
      assertEquals(
          ImmutableSortedSet.of(PathSourcePath.of(filesystem, Paths.get("foo/bar/Bar.java"))),
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

    BuildTarget libTarget = BuildTargetFactory.newInstance(cellRoot, "//foo", "lib");
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
            .setSections("[project]", "read_only_paths = foo/bar")
            .build();
    cell = new TestCellBuilder().setBuckConfig(config).setFilesystem(filesystem).build();

    tempDir.newFolder("bar");
    tempDir.newFile("bar/Bar.java");
    tempDir.newFolder("foo");

    Files.createSymbolicLink(rootPath.resolve("foo/bar"), rootPath.resolve("bar"));

    Path testBuckFile = rootPath.resolve("foo").resolve("BUCK");
    Files.write(
        testBuckFile, "java_library(name = 'lib', srcs=glob(['bar/*.java']))\n".getBytes(UTF_8));

    BuildTarget libTarget = BuildTargetFactory.newInstance(cellRoot, "//foo", "lib");
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
  public void daemonicParserStateSerializesAndDeserializesCorrectly() throws Exception {
    tempDir.newFolder("foo");

    Path testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(
        testFooBuckFile,
        "java_library(name = 'lib', srcs=glob(['*.java']), visibility=['PUBLIC'])\n"
            .getBytes(UTF_8));

    Path testFooJavaFile = tempDir.newFile("foo/Foo.java");
    Files.write(testFooJavaFile, "// Nothing to see here, move along!\n".getBytes(UTF_8));

    Path testBarJavaFile = tempDir.newFile("foo/Bar.java");
    Files.write(testBarJavaFile, "// Plz, leave me alone!\n".getBytes(UTF_8));

    BuildTarget libTarget = BuildTargetFactory.newInstance(cellRoot, "//foo", "lib");
    Iterable<BuildTarget> buildTargets = ImmutableList.of(libTarget);

    TargetGraph oldGraph =
        parser.buildTargetGraph(eventBus, cell, false, executorService, buildTargets);

    // Serialize target graph information.
    RemoteDaemonicParserState remote = parser.storeParserState(cell);

    assertTrue(remote.isSetCachedIncludes());
    assertEquals(remote.cachedIncludes.size(), 1);
    assertTrue(remote.isSetCellPathToDaemonicState());
    assertEquals(remote.cellPathToDaemonicState.size(), 1);
    RemoteDaemonicCellState cellState = remote.cellPathToDaemonicState.get("");
    assertTrue(cellState.isSetAllRawNodesJsons());
    assertEquals(cellState.allRawNodesJsons.size(), 1);
    assertTrue(cellState.isSetBuildFileDependents());
    assertEquals(cellState.buildFileDependents.size(), 2);
    assertTrue(cellState.isSetBuildFileEnv());
    assertEquals(cellState.buildFileEnv.size(), 1);
    assertTrue(remote.isSetCellPaths());
    assertEquals(remote.cellPaths.size(), 1);

    TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    BroadcastEventListener broadcastEventListener = new BroadcastEventListener();
    // Reset parser to square one.
    parser =
        new Parser(
            broadcastEventListener,
            cell.getBuckConfig().getView(ParserConfig.class),
            typeCoercerFactory,
            new ConstructorArgMarshaller(typeCoercerFactory),
            knownBuildRuleTypesProvider,
            executableFinder);
    // Restore state.
    parser.restoreParserState(remote, cell);
    // Try to use the restored target graph.
    TargetGraph newGraph =
        parser
            .buildTargetGraphForTargetNodeSpecs(
                eventBus,
                cell,
                false,
                executorService,
                ImmutableList.of(
                    BuildTargetSpec.of(
                        BuildTarget.of(
                            UnflavoredBuildTarget.of(
                                cell.getRoot(), cell.getCanonicalName(), "//foo", "lib")),
                        BuildFileSpec.of(Paths.get("foo"), false, cell.getRoot()))),
                ParserConfig.ApplyDefaultFlavorsMode.ENABLED)
            .getTargetGraph();
    assertEquals(oldGraph, newGraph);
  }

  @Test
  public void buildTargetHashCodePopulatesCorrectly() throws Exception {
    tempDir.newFolder("foo");

    Path testFooBuckFile = tempDir.newFile("foo/BUCK");
    Files.write(
        testFooBuckFile, "java_library(name = 'lib', visibility=['PUBLIC'])\n".getBytes(UTF_8));

    BuildTarget fooLibTarget = BuildTargetFactory.newInstance(cellRoot, "//foo", "lib");

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
    thrown.expectMessage("Buck wasn't able to parse");
    thrown.expectMessage(Paths.get("bar/BUCK").toString());

    parser.resolveTargetSpecs(
        eventBus,
        cell,
        false,
        executorService,
        ImmutableList.of(
            TargetNodePredicateSpec.of(
                BuildFileSpec.fromRecursivePath(Paths.get("bar"), cell.getRoot())),
            TargetNodePredicateSpec.of(
                BuildFileSpec.fromRecursivePath(Paths.get("foo"), cell.getRoot()))),
        PerBuildState.SpeculativeParsing.ENABLED,
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
                    BuildFileSpec.fromRecursivePath(Paths.get("bar"), cell.getRoot())),
                TargetNodePredicateSpec.of(
                    BuildFileSpec.fromRecursivePath(Paths.get("foo"), cell.getRoot()))),
            PerBuildState.SpeculativeParsing.ENABLED,
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
                    BuildFileSpec.fromRecursivePath(Paths.get("foo"), cell.getRoot())),
                TargetNodePredicateSpec.of(
                    BuildFileSpec.fromRecursivePath(Paths.get("bar"), cell.getRoot()))),
            PerBuildState.SpeculativeParsing.ENABLED,
            ParserConfig.ApplyDefaultFlavorsMode.ENABLED);
    assertThat(targets, equalTo(ImmutableList.of(ImmutableSet.of(foo), ImmutableSet.of(bar))));
  }

  @Test
  public void defaultFlavorsInRuleArgsAppliedToTarget() throws Exception {
    // We depend on Xcode platforms for this test.
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

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
                        BuildTargetFactory.newInstance(cellRoot, "//lib", "lib"))),
                ParserConfig.ApplyDefaultFlavorsMode.ENABLED)
            .getBuildTargets();

    assertThat(
        result,
        hasItems(
            BuildTargetFactory.newInstance(
                cellRoot,
                "//lib",
                "lib",
                InternalFlavor.of("iphonesimulator-x86_64"),
                InternalFlavor.of("static"))));
  }

  @Test
  public void defaultFlavorsInConfigAppliedToTarget() throws Exception {
    // We depend on Xcode platforms for this test.
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

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
                        BuildTargetFactory.newInstance(cellRoot, "//lib", "lib"))),
                ParserConfig.ApplyDefaultFlavorsMode.ENABLED)
            .getBuildTargets();

    assertThat(
        result,
        hasItems(
            BuildTargetFactory.newInstance(
                cellRoot,
                "//lib",
                "lib",
                InternalFlavor.of("iphoneos-arm64"),
                InternalFlavor.of("shared"))));
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
                        BuildTargetFactory.newInstance(cellRoot, "//lib", "lib"))),
                ParserConfig.ApplyDefaultFlavorsMode.ENABLED)
            .getBuildTargets();

    assertThat(
        result,
        hasItems(
            BuildTargetFactory.newInstance(
                cellRoot,
                "//lib",
                "lib",
                InternalFlavor.of("macosx-x86_64"),
                InternalFlavor.of("shared"))));
  }

  @Test
  public void countsParsedBytes() throws Exception {
    Path buckFile = cellRoot.resolve("lib/BUCK");
    Files.createDirectories(buckFile.getParent());
    byte[] bytes =
        ("genrule(" + "name='gen'," + "out='generated', " + "cmd='touch ${OUT}')").getBytes(UTF_8);
    Files.write(buckFile, bytes);

    cell = new TestCellBuilder().setFilesystem(filesystem).build();

    List<ParseEvent.Finished> events = new ArrayList<>();
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
            AbstractBuildTargetSpec.from(BuildTargetFactory.newInstance(cellRoot, "//lib", "gen"))),
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
            AbstractBuildTargetSpec.from(BuildTargetFactory.newInstance(cellRoot, "//lib", "gen"))),
        ParserConfig.ApplyDefaultFlavorsMode.DISABLED);
    assertEquals(0L, Iterables.getOnlyElement(events).getProcessedBytes());
  }

  @Test
  public void testGetCacheReturnsSame() {
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

  @Test
  public void testSkylarkSyntaxParsing() throws Exception {
    Path buckFile = cellRoot.resolve("BUCK");
    Files.write(
        buckFile,
        Joiner.on("\n")
            .join(
                ImmutableList.of(
                    "# BUILD FILE SYNTAX: SKYLARK",
                    "genrule(name = 'cake', out = 'file.txt', cmd = 'touch $OUT')",
                    "glob(['*.txt'])"))
            .getBytes(UTF_8));

    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections("[parser]", "polyglot_parsing_enabled=true")
            .build();

    Cell cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();

    parser.getAllTargetNodes(eventBus, cell, false, executorService, buckFile);
  }

  private BuildRuleResolver buildActionGraph(
      BuckEventBus eventBus, TargetGraph targetGraph, Cell cell) {
    return Preconditions.checkNotNull(
            new ActionGraphCache(1, 1)
                .getFreshActionGraph(
                    eventBus,
                    targetGraph,
                    cell.getCellProvider(),
                    ActionGraphParallelizationMode.DISABLED,
                    false,
                    IncrementalActionGraphMode.DISABLED,
                    CloseableMemoizedSupplier.of(
                        () -> {
                          throw new IllegalStateException(
                              "should not use parallel executor for action graph construction in test");
                        },
                        ignored -> {})))
        .getResolver();
  }

  /**
   * Populates the collection of known build targets that this Parser will use to construct an
   * action graph using all build files inside the given project root and returns an optionally
   * filtered set of build targets.
   *
   * @return The build targets in the project filtered by the given filter.
   */
  public static synchronized ImmutableSet<BuildTarget> filterAllTargetsInProject(
      Parser parser, Cell cell, BuckEventBus buckEventBus, ListeningExecutorService executor)
      throws BuildFileParseException, IOException, InterruptedException {
    return FluentIterable.from(
            parser
                .buildTargetGraphForTargetNodeSpecs(
                    buckEventBus,
                    cell,
                    false,
                    executor,
                    ImmutableList.of(
                        TargetNodePredicateSpec.of(
                            BuildFileSpec.fromRecursivePath(Paths.get(""), cell.getRoot()))))
                .getTargetGraph()
                .getNodes())
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
