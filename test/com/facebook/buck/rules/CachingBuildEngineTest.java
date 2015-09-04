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

package com.facebook.buck.rules;

import static com.facebook.buck.event.TestEventConfigerator.configureTestEvent;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.isA;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cli.CommandEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.FakeBuckEventListener;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.FakeJavaPackageFinder;
import com.facebook.buck.java.JavaPackageFinder;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.keys.AbiRuleKeyBuilderFactory;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.rules.keys.DependencyFileRuleKeyBuilderFactory;
import com.facebook.buck.rules.keys.SupportsDependencyFileRuleKey;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.RuleMap;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.NullFileHashCache;
import com.facebook.buck.util.concurrent.MoreFutures;
import com.facebook.buck.zip.CustomZipEntry;
import com.facebook.buck.zip.CustomZipOutputStream;
import com.facebook.buck.zip.ZipOutputStreams;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import org.easymock.EasyMockSupport;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

/**
 * Ensuring that build rule caching works correctly in Buck is imperative for both its performance
 * and correctness.
 */
public class CachingBuildEngineTest extends EasyMockSupport {

  private static final BuildTarget BUILD_TARGET =
      BuildTarget.builder("//src/com/facebook/orca", "orca").build();
  private static final RuleKeyBuilderFactory NOOP_RULE_KEY_FACTORY =
      new DefaultRuleKeyBuilderFactory(
          new NullFileHashCache(),
          new SourcePathResolver(new BuildRuleResolver()));

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  /**
   * Tests what should happen when a rule is built for the first time: it should have no cached
   * RuleKey, nor should it have any artifact in the ArtifactCache. The sequence of events should be
   * as follows:
   * <ol>
   *   <li>The build engine invokes the {@link CachingBuildEngine#build(BuildContext, BuildRule)}
   *   method on each of the transitive deps.
   *   <li>The rule computes its own {@link RuleKey}.
   *   <li>The engine compares its {@link RuleKey} to the one on disk, if present.
   *   <li>Because the rule has no {@link RuleKey} on disk, the engine tries to build the rule.
   *   <li>First, it checks the artifact cache, but there is a cache miss.
   *   <li>The rule generates its build steps and the build engine executes them.
   *   <li>Upon executing its steps successfully, the build engine  should write the rule's
   *   {@link RuleKey} to disk.
   *   <li>The build engine should persist a rule's output to the ArtifactCache.
   * </ol>
   */
  @Test
  public void testBuildRuleLocallyWithCacheMiss()
      throws IOException, InterruptedException, ExecutionException, StepFailedException {
    final ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver());
    InMemoryArtifactCache cache = new InMemoryArtifactCache();

    // Create a dep for the build rule.
    BuildTarget depTarget = BuildTargetFactory.newInstance("//src/com/facebook/orca:lib");
    FakeBuildRule dep = new FakeBuildRule(depTarget, resolver);
    dep.setRuleKey(new RuleKey("19d2558a6bd3a34fb3f95412de9da27ed32fe208"));

    // The EventBus should be updated with events indicating how the rule was built.
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    FakeBuckEventListener listener = new FakeBuckEventListener();
    buckEventBus.register(listener);

    // Replay the mocks to instantiate the AbstractCachingBuildRule.
    replayAll();
    String pathToOutputFile = "buck-out/gen/src/com/facebook/orca/some_file";
    List<Step> buildSteps = Lists.newArrayList();
    final BuildRule ruleToTest = createRule(
        filesystem,
        resolver,
        ImmutableSet.<BuildRule>of(dep),
        buildSteps,
        /* postBuildSteps */ ImmutableList.<Step>of(),
        pathToOutputFile);
    verifyAll();
    resetAll();

    // The BuildContext that will be used by the rule's build() method.
    BuildContext context =
        FakeBuildContext.newBuilder()
            .setEventBus(buckEventBus)
            .setArtifactCache(cache)
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();

    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            fileHashCache,
            CachingBuildEngine.BuildMode.SHALLOW,
            CachingBuildEngine.DepFiles.ENABLED,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY);

    // Add a build step so we can verify that the steps are executed.
    buildSteps.add(
        new AbstractExecutionStep("Some Short Name") {
          @Override
          public int execute(ExecutionContext context) throws IOException {
            filesystem.touch(ruleToTest.getPathToOutput());
            return 0;
          }
        });

    // Attempting to build the rule should force a rebuild due to a cache miss.
    replayAll();

    cachingBuildEngine.setBuildRuleResult(
        dep,
        BuildRuleSuccessType.FETCHED_FROM_CACHE,
        CacheResult.skip());

    BuildResult result = cachingBuildEngine.build(context, ruleToTest).get();
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());
    buckEventBus.post(
        CommandEvent.finished(CommandEvent.started("build", ImmutableList.<String>of(), false), 0));
    verifyAll();

    assertTrue(cache.hasArtifact(ruleToTest.getRuleKey()));

    // Verify the events logged to the BuckEventBus.
    List<BuckEvent> events = listener.getEvents();
    assertThat(events, Matchers.hasSize(11));
    Iterator<BuckEvent> eventIter = events.iterator();
    assertEquals(
        configureTestEvent(BuildRuleEvent.started(dep), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.suspended(dep), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.started(ruleToTest), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.finished(
                ruleToTest,
                BuildRuleStatus.SUCCESS,
                CacheResult.miss(),
                Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                Optional.<HashCode>absent(),
                Optional.<Long>absent()),
            buckEventBus),
        events.get(events.size() - 2));
  }

  @Test
  public void testAsyncJobsAreNotLeftInExecutor()
      throws IOException, ExecutionException, InterruptedException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    BuildRuleParams buildRuleParams = new FakeBuildRuleParamsBuilder(BUILD_TARGET)
        .setProjectFilesystem(filesystem)
        .build();
    FakeBuildRule buildRule = new FakeBuildRule(
        buildRuleParams,
        new SourcePathResolver(new BuildRuleResolver()));

    // The EventBus should be updated with events indicating how the rule was built.
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    FakeBuckEventListener listener = new FakeBuckEventListener();
    buckEventBus.register(listener);

    // The BuildContext that will be used by the rule's build() method.
    BuildContext buildContext =
        FakeBuildContext.newBuilder()
            .setArtifactCache(
                new NoopArtifactCache() {
                  @Override
                  public void store(
                      ImmutableSet<RuleKey> ruleKeys,
                      ImmutableMap<String, String> metadata,
                      Path output) {
                    try {
                      Thread.sleep(500);
                    } catch (InterruptedException e) {
                      throw Throwables.propagate(e);
                    }
                  }
                })
            .setEventBus(buckEventBus)
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();

    ListeningExecutorService service = listeningDecorator(Executors.newFixedThreadPool(2));

    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            service,
            fileHashCache,
            CachingBuildEngine.BuildMode.SHALLOW,
            CachingBuildEngine.DepFiles.ENABLED,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY);
    ListenableFuture<BuildResult> buildResult = cachingBuildEngine.build(buildContext, buildRule);

    BuildResult result = buildResult.get();
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());

    assertTrue(service.shutdownNow().isEmpty());

    List<BuckEvent> events = listener.getEvents();
    assertThat(events, Matchers.hasSize(6));
    Iterator<BuckEvent> eventIter = events.iterator();
    assertEquals(
        configureTestEvent(BuildRuleEvent.started(buildRule), buckEventBus).getEventName(),
        eventIter.next().getEventName());
    assertEquals(
        configureTestEvent(BuildRuleEvent.suspended(buildRule), buckEventBus).getEventName(),
        eventIter.next().getEventName());
    assertEquals(
        configureTestEvent(BuildRuleEvent.resumed(buildRule), buckEventBus).getEventName(),
        eventIter.next().getEventName());
    assertEquals(
        configureTestEvent(BuildRuleEvent.suspended(buildRule), buckEventBus).getEventName(),
        eventIter.next().getEventName());
    assertEquals(
        configureTestEvent(BuildRuleEvent.resumed(buildRule), buckEventBus).getEventName(),
        eventIter.next().getEventName());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.finished(
                buildRule,
                BuildRuleStatus.SUCCESS,
                CacheResult.miss(),
                Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                Optional.<HashCode>absent(),
                Optional.<Long>absent()),
            buckEventBus)
            .getEventName(),
        eventIter.next().getEventName());
  }

  @Test
  public void testArtifactFetchedFromCache()
      throws InterruptedException, ExecutionException, IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());

    Step step = new AbstractExecutionStep("exploding step") {
      @Override
      public int execute(ExecutionContext context) {
        throw new UnsupportedOperationException("build step should not be executed");
      }
    };
    BuildRule buildRule = createRule(
        filesystem,
        new SourcePathResolver(new BuildRuleResolver()),
        /* deps */ ImmutableSet.<BuildRule>of(),
        ImmutableList.of(step),
        /* postBuildSteps */ ImmutableList.<Step>of(),
        /* pathToOutputFile */ null);

    StepRunner stepRunner = createStepRunner(null);

    // Simulate successfully fetching the output file from the ArtifactCache.
    ArtifactCache artifactCache = createMock(ArtifactCache.class);
    Map<String, String> desiredZipEntries = ImmutableMap.of(
        "buck-out/gen/src/com/facebook/orca/orca.jar",
        "Imagine this is the contents of a valid JAR file.");
    expect(
        artifactCache.fetch(
            eq(buildRule.getRuleKey()),
            isA(Path.class)))
        .andDelegateTo(
            new FakeArtifactCacheThatWritesAZipFile(desiredZipEntries));

    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    BuildContext buildContext = ImmutableBuildContext.builder()
        .setActionGraph(RuleMap.createGraphFromSingleRule(buildRule))
        .setStepRunner(stepRunner)
        .setClock(new DefaultClock())
        .setBuildId(new BuildId())
        .setArtifactCache(artifactCache)
        .setJavaPackageFinder(createMock(JavaPackageFinder.class))
        .setEventBus(buckEventBus)
        .build();

    filesystem.writeContentsToPath(
        new ObjectMapper().writeValueAsString(ImmutableList.of()),
        BuildInfo.getPathToMetadataDirectory(buildRule.getBuildTarget())
            .resolve(BuildInfo.METADATA_KEY_FOR_RECORDED_PATHS));

    // Build the rule!
    replayAll();
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            fileHashCache,
            CachingBuildEngine.BuildMode.SHALLOW,
            CachingBuildEngine.DepFiles.ENABLED,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY);
    ListenableFuture<BuildResult> buildResult = cachingBuildEngine.build(buildContext, buildRule);
    buckEventBus.post(
        CommandEvent.finished(CommandEvent.started("build", ImmutableList.<String>of(), false), 0));
    verifyAll();

    assertTrue(
        "We expect build() to be synchronous in this case, " +
            "so the future should already be resolved.",
        MoreFutures.isSuccess(buildResult));
    BuildResult result = buildResult.get();
    assertEquals(BuildRuleSuccessType.FETCHED_FROM_CACHE, result.getSuccess());
    assertTrue(
        ((BuildableAbstractCachingBuildRule) buildRule).isInitializedFromDisk());
    assertTrue(
        "The entries in the zip should be extracted as a result of building the rule.",
        filesystem.exists(Paths.get("buck-out/gen/src/com/facebook/orca/orca.jar")));
  }

  @Test
  public void testArtifactFetchedFromCacheStillRunsPostBuildSteps()
      throws InterruptedException, ExecutionException, IOException {

    ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());

    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    StepRunner stepRunner = createStepRunner(buckEventBus);

    // Add a post build step so we can verify that it's steps are executed.
    Step buildStep = createMock(Step.class);
    expect(buildStep.getDescription(anyObject(ExecutionContext.class)))
        .andReturn("Some Description")
        .anyTimes();
    expect(buildStep.getShortName()).andReturn("Some Short Name").anyTimes();
    expect(buildStep.execute(anyObject(ExecutionContext.class))).andReturn(0);

    BuildRule buildRule = createRule(
        filesystem,
        new SourcePathResolver(new BuildRuleResolver()),
        /* deps */ ImmutableSet.<BuildRule>of(),
        /* buildSteps */ ImmutableList.<Step>of(),
        /* postBuildSteps */ ImmutableList.of(buildStep),
        /* pathToOutputFile */ null);

    // Simulate successfully fetching the output file from the ArtifactCache.
    ArtifactCache artifactCache = createMock(ArtifactCache.class);
    Map<String, String> desiredZipEntries = ImmutableMap.of(
        "buck-out/gen/src/com/facebook/orca/orca.jar",
        "Imagine this is the contents of a valid JAR file.");
    expect(
        artifactCache.fetch(
            eq(buildRule.getRuleKey()),
            isA(Path.class)))
        .andDelegateTo(
            new FakeArtifactCacheThatWritesAZipFile(desiredZipEntries));

    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    BuildContext buildContext = ImmutableBuildContext.builder()
        .setActionGraph(RuleMap.createGraphFromSingleRule(buildRule))
        .setStepRunner(stepRunner)
        .setClock(new DefaultClock())
        .setBuildId(new BuildId())
        .setArtifactCache(artifactCache)
        .setJavaPackageFinder(createMock(JavaPackageFinder.class))
        .setEventBus(buckEventBus)
        .build();

    filesystem.writeContentsToPath(
        new ObjectMapper().writeValueAsString(ImmutableList.of()),
        BuildInfo.getPathToMetadataDirectory(buildRule.getBuildTarget())
            .resolve(BuildInfo.METADATA_KEY_FOR_RECORDED_PATHS));

    // Build the rule!
    replayAll();
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            fileHashCache,
            CachingBuildEngine.BuildMode.SHALLOW,
            CachingBuildEngine.DepFiles.ENABLED,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY);
    ListenableFuture<BuildResult> buildResult = cachingBuildEngine.build(buildContext, buildRule);
    buckEventBus.post(
        CommandEvent.finished(CommandEvent.started("build", ImmutableList.<String>of(), false), 0));
    verifyAll();

    BuildResult result = buildResult.get();
    assertEquals(BuildRuleSuccessType.FETCHED_FROM_CACHE, result.getSuccess());
    assertTrue(
        ((BuildableAbstractCachingBuildRule) buildRule).isInitializedFromDisk());
    assertTrue(
        "The entries in the zip should be extracted as a result of building the rule.",
        filesystem.exists(Paths.get("buck-out/gen/src/com/facebook/orca/orca.jar")));
  }

  @Test
  public void testMatchingTopLevelRuleKeyAvoidsProcessingDepInShallowMode() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    // The EventBus should be updated with events indicating how the rule was built.
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    FakeBuckEventListener listener = new FakeBuckEventListener();
    buckEventBus.register(listener);

    // Create a dep for the build rule.
    BuildTarget depTarget = BuildTargetFactory.newInstance("//src/com/facebook/orca:lib");
    FakeBuildRule dep = new FakeBuildRule(depTarget, pathResolver);
    dep.setRuleKey(new RuleKey("aaaa"));
    FakeBuildRule ruleToTest = new FakeBuildRule(BUILD_TARGET, filesystem, pathResolver, dep);
    ruleToTest.setRuleKey(new RuleKey("bbbb"));
    filesystem.writeContentsToPath(
        ruleToTest.getRuleKey().toString(),
        BuildInfo.getPathToMetadataDirectory(BUILD_TARGET)
            .resolve(BuildInfo.METADATA_KEY_FOR_RULE_KEY));
    filesystem.writeContentsToPath(
        new ObjectMapper().writeValueAsString(ImmutableList.of()),
        BuildInfo.getPathToMetadataDirectory(BUILD_TARGET)
            .resolve(BuildInfo.METADATA_KEY_FOR_RECORDED_PATHS));

    // The BuildContext that will be used by the rule's build() method.
    BuildContext context =
        FakeBuildContext.newBuilder()
            .setEventBus(buckEventBus)
            .setArtifactCache(new NoopArtifactCache())
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();

    // Create the build engine.
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            fileHashCache,
            CachingBuildEngine.BuildMode.SHALLOW,
            CachingBuildEngine.DepFiles.ENABLED,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY);

    // Run the build.
    replayAll();
    BuildResult result = cachingBuildEngine.build(context, ruleToTest).get();
    assertEquals(BuildRuleSuccessType.MATCHING_RULE_KEY, result.getSuccess());
    verifyAll();

    // Verify the events logged to the BuckEventBus.
    List<BuckEvent> events = listener.getEvents();
    assertThat(events, Matchers.hasSize(6));
    Iterator<BuckEvent> eventIter = events.iterator();
    assertEquals(
        configureTestEvent(BuildRuleEvent.started(dep), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.suspended(dep), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.started(ruleToTest), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.suspended(ruleToTest), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.resumed(ruleToTest), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.finished(
                ruleToTest,
                BuildRuleStatus.SUCCESS,
                CacheResult.localKeyUnchangedHit(),
                Optional.of(BuildRuleSuccessType.MATCHING_RULE_KEY),
                Optional.<HashCode>absent(),
                Optional.<Long>absent()),
            buckEventBus),
        eventIter.next());
  }

  @Test
  public void testMatchingTopLevelRuleKeyStillProcessesDepInDeepMode() throws Exception {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ArtifactCache cache = new NoopArtifactCache();
    ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);

    // The EventBus should be updated with events indicating how the rule was built.
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    FakeBuckEventListener listener = new FakeBuckEventListener();
    buckEventBus.register(listener);

    // Create a dep for the build rule.
    BuildTarget depTarget = BuildTargetFactory.newInstance("//src/com/facebook/orca:lib");
    BuildRuleParams ruleParams = new FakeBuildRuleParamsBuilder(depTarget)
        .setProjectFilesystem(filesystem)
        .build();
    FakeBuildRule dep = new FakeBuildRule(ruleParams, pathResolver);
    dep.setRuleKey(new RuleKey("aaaa"));
    filesystem.writeContentsToPath(
        dep.getRuleKey().toString(),
        BuildInfo.getPathToMetadataDirectory(depTarget)
            .resolve(BuildInfo.METADATA_KEY_FOR_RULE_KEY));
    filesystem.writeContentsToPath(
        new ObjectMapper().writeValueAsString(ImmutableList.of()),
        BuildInfo.getPathToMetadataDirectory(depTarget)
            .resolve(BuildInfo.METADATA_KEY_FOR_RECORDED_PATHS));
    FakeBuildRule ruleToTest = new FakeBuildRule(BUILD_TARGET, filesystem, pathResolver, dep);
    ruleToTest.setRuleKey(new RuleKey("bbbb"));
    filesystem.writeContentsToPath(
        ruleToTest.getRuleKey().toString(),
        BuildInfo.getPathToMetadataDirectory(BUILD_TARGET)
            .resolve(BuildInfo.METADATA_KEY_FOR_RULE_KEY));
    filesystem.writeContentsToPath(
        new ObjectMapper().writeValueAsString(ImmutableList.of()),
        BuildInfo.getPathToMetadataDirectory(BUILD_TARGET)
            .resolve(BuildInfo.METADATA_KEY_FOR_RECORDED_PATHS));

    // The BuildContext that will be used by the rule's build() method.
    BuildContext context =
        FakeBuildContext.newBuilder()
            .setArtifactCache(cache)
            .setEventBus(buckEventBus)
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();

    // Create the build engine.
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            fileHashCache,
            CachingBuildEngine.BuildMode.DEEP,
            CachingBuildEngine.DepFiles.ENABLED,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY);

    // Run the build.
    BuildResult result = cachingBuildEngine.build(context, ruleToTest).get();
    assertEquals(BuildRuleSuccessType.MATCHING_RULE_KEY, result.getSuccess());

    // Verify the events logged to the BuckEventBus.
    List<BuckEvent> events = listener.getEvents();
    assertThat(events, Matchers.hasSize(8));
    Iterator<BuckEvent> eventIter = events.iterator();
    assertEquals(
        configureTestEvent(BuildRuleEvent.started(dep), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.suspended(dep), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.started(ruleToTest), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.suspended(ruleToTest), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.resumed(ruleToTest), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.resumed(dep), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.finished(
                dep,
                BuildRuleStatus.SUCCESS,
                CacheResult.localKeyUnchangedHit(),
                Optional.of(BuildRuleSuccessType.MATCHING_RULE_KEY),
                Optional.<HashCode>absent(),
                Optional.<Long>absent()),
            buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.finished(
                ruleToTest,
                BuildRuleStatus.SUCCESS,
                CacheResult.localKeyUnchangedHit(),
                Optional.of(BuildRuleSuccessType.MATCHING_RULE_KEY),
                Optional.<HashCode>absent(),
                Optional.<Long>absent()),
            buckEventBus),
        eventIter.next());
  }

  @Test
  public void testMatchingTopLevelRuleKeyStillProcessesRuntimeDeps() throws Exception {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    ArtifactCache cache = new NoopArtifactCache();

    // The EventBus should be updated with events indicating how the rule was built.
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    FakeBuckEventListener listener = new FakeBuckEventListener();
    buckEventBus.register(listener);

    // Setup a runtime dependency that is found transitively from the top-level rule.
    BuildRuleParams ruleParams = new FakeBuildRuleParamsBuilder("//:transitive_dep")
        .setProjectFilesystem(filesystem)
        .build();
    FakeBuildRule transitiveRuntimeDep =
        new FakeBuildRule(ruleParams, pathResolver);
    transitiveRuntimeDep.setRuleKey(new RuleKey("aaaa"));
    filesystem.writeContentsToPath(
        transitiveRuntimeDep.getRuleKey().toString(),
        BuildInfo.getPathToMetadataDirectory(transitiveRuntimeDep.getBuildTarget())
            .resolve(BuildInfo.METADATA_KEY_FOR_RULE_KEY));
    filesystem.writeContentsToPath(
        new ObjectMapper().writeValueAsString(ImmutableList.of()),
        BuildInfo.getPathToMetadataDirectory(transitiveRuntimeDep.getBuildTarget())
            .resolve(BuildInfo.METADATA_KEY_FOR_RECORDED_PATHS));

    // Setup a runtime dependency that is referenced directly by the top-level rule.
    FakeBuildRule runtimeDep =
        new FakeHasRuntimeDeps(
            BuildTargetFactory.newInstance("//:runtime_dep"),
            filesystem,
            pathResolver,
            transitiveRuntimeDep);
    runtimeDep.setRuleKey(new RuleKey("bbbb"));
    filesystem.writeContentsToPath(
        runtimeDep.getRuleKey().toString(),
        BuildInfo.getPathToMetadataDirectory(runtimeDep.getBuildTarget())
            .resolve(BuildInfo.METADATA_KEY_FOR_RULE_KEY));
    filesystem.writeContentsToPath(
        new ObjectMapper().writeValueAsString(ImmutableList.of()),
        BuildInfo.getPathToMetadataDirectory(runtimeDep.getBuildTarget())
            .resolve(BuildInfo.METADATA_KEY_FOR_RECORDED_PATHS));

    // Create a dep for the build rule.
    FakeBuildRule ruleToTest = new FakeHasRuntimeDeps(
        BUILD_TARGET,
        filesystem,
        pathResolver,
        runtimeDep);
    ruleToTest.setRuleKey(new RuleKey("cccc"));
    filesystem.writeContentsToPath(
        ruleToTest.getRuleKey().toString(),
        BuildInfo.getPathToMetadataDirectory(ruleToTest.getBuildTarget())
            .resolve(BuildInfo.METADATA_KEY_FOR_RULE_KEY));
    filesystem.writeContentsToPath(
        new ObjectMapper().writeValueAsString(ImmutableList.of()),
        BuildInfo.getPathToMetadataDirectory(ruleToTest.getBuildTarget())
            .resolve(BuildInfo.METADATA_KEY_FOR_RECORDED_PATHS));

    // The BuildContext that will be used by the rule's build() method.
    BuildContext context =
        FakeBuildContext.newBuilder()
            .setArtifactCache(cache)
            .setEventBus(buckEventBus)
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();

    // Create the build engine.
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            fileHashCache,
            CachingBuildEngine.BuildMode.SHALLOW,
            CachingBuildEngine.DepFiles.ENABLED,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY);

    // Run the build.
    BuildResult result = cachingBuildEngine.build(context, ruleToTest).get();
    assertEquals(BuildRuleSuccessType.MATCHING_RULE_KEY, result.getSuccess());

    // Verify the events logged to the BuckEventBus.
    List<BuckEvent> events = listener.getEvents();
    assertThat(events, Matchers.hasSize(12));
    Iterator<BuckEvent> eventIter = events.iterator();
    assertEquals(
        configureTestEvent(BuildRuleEvent.started(ruleToTest), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.suspended(ruleToTest), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.resumed(ruleToTest), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.finished(
                ruleToTest,
                BuildRuleStatus.SUCCESS,
                CacheResult.localKeyUnchangedHit(),
                Optional.of(BuildRuleSuccessType.MATCHING_RULE_KEY),
                Optional.<HashCode>absent(),
                Optional.<Long>absent()),
            buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.started(runtimeDep), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.suspended(runtimeDep), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.resumed(runtimeDep), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.finished(
                runtimeDep,
                BuildRuleStatus.SUCCESS,
                CacheResult.localKeyUnchangedHit(),
                Optional.of(BuildRuleSuccessType.MATCHING_RULE_KEY),
                Optional.<HashCode>absent(),
                Optional.<Long>absent()),
            buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.started(transitiveRuntimeDep), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.suspended(transitiveRuntimeDep), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.resumed(transitiveRuntimeDep), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.finished(
                transitiveRuntimeDep,
                BuildRuleStatus.SUCCESS,
                CacheResult.localKeyUnchangedHit(),
                Optional.of(BuildRuleSuccessType.MATCHING_RULE_KEY),
                Optional.<HashCode>absent(),
                Optional.<Long>absent()),
            buckEventBus),
        eventIter.next());
  }

  @Test
  public void matchingRuleKeyDoesNotRunPostBuildSteps() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    ArtifactCache cache = new NoopArtifactCache();

    // The EventBus should be updated with events indicating how the rule was built.
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    FakeBuckEventListener listener = new FakeBuckEventListener();
    buckEventBus.register(listener);

    // Add a post build step so we can verify that it's steps are executed.
    Step failingStep =
        new AbstractExecutionStep("test") {
          @Override
          public int execute(ExecutionContext context) throws IOException {
            return 1;
          }
        };
    BuildRule ruleToTest = createRule(
        filesystem,
        new SourcePathResolver(new BuildRuleResolver()),
        /* deps */ ImmutableSet.<BuildRule>of(),
        /* buildSteps */ ImmutableList.<Step>of(),
        /* postBuildSteps */ ImmutableList.of(failingStep),
        /* pathToOutputFile */ null);
    filesystem.writeContentsToPath(
        ruleToTest.getRuleKey().toString(),
        BuildInfo.getPathToMetadataDirectory(ruleToTest.getBuildTarget())
            .resolve(BuildInfo.METADATA_KEY_FOR_RULE_KEY));
    filesystem.writeContentsToPath(
        new ObjectMapper().writeValueAsString(ImmutableList.of()),
        BuildInfo.getPathToMetadataDirectory(ruleToTest.getBuildTarget())
            .resolve(BuildInfo.METADATA_KEY_FOR_RECORDED_PATHS));

    // The BuildContext that will be used by the rule's build() method.
    BuildContext context =
        FakeBuildContext.newBuilder()
            .setArtifactCache(cache)
            .setEventBus(buckEventBus)
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();

    // Create the build engine.
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            fileHashCache,
            CachingBuildEngine.BuildMode.SHALLOW,
            CachingBuildEngine.DepFiles.ENABLED,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY);

    // Run the build.
    BuildResult result = cachingBuildEngine.build(context, ruleToTest).get();
    assertEquals(BuildRuleSuccessType.MATCHING_RULE_KEY, result.getSuccess());
  }

  @Test
  public void testBuildRuleLocallyWithCacheError() throws Exception {
    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver());
    ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);

    // Create an artifact cache that always errors out.
    ArtifactCache cache =
        new NoopArtifactCache() {
          @Override
          public CacheResult fetch(RuleKey ruleKey, Path output) {
            return CacheResult.error("cache", "error");
          }
        };

    // Use the artifact cache when running a simple rule that will build locally.
    BuildContext buildContext =
        FakeBuildContext.newBuilder()
            .setArtifactCache(cache)
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();

    BuildRule rule =
        new NoopBuildRule(
            new FakeBuildRuleParamsBuilder("//:rule")
                .setProjectFilesystem(filesystem)
                .build(),
            resolver);
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            fileHashCache,
            CachingBuildEngine.BuildMode.SHALLOW,
            CachingBuildEngine.DepFiles.ENABLED,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY);

    BuildResult result = cachingBuildEngine.build(buildContext, rule).get();
    assertThat(result.getSuccess(), equalTo(BuildRuleSuccessType.BUILT_LOCALLY));
    assertThat(result.getCacheResult().getType(), equalTo(CacheResult.Type.ERROR));
  }

  @Test
  public void inputBasedRuleKeyAndArtifactAreWrittenForSupportedRules() throws Exception {
    final FakeProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    InMemoryArtifactCache cache = new InMemoryArtifactCache();
    BuildContext buildContext =
        FakeBuildContext.newBuilder()
            .setArtifactCache(cache)
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();

    // Create a simple rule which just writes a file.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(target)
            .setProjectFilesystem(filesystem)
            .build();
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    RuleKey inputRuleKey = new RuleKey("aaaa");
    final Path output = Paths.get("output");
    BuildRule rule =
        new InputRuleKeyBuildRule(params, pathResolver) {
          @Override
          public ImmutableList<Step> getBuildSteps(
              BuildContext context,
              BuildableContext buildableContext) {
            return ImmutableList.<Step>of(
                new WriteFileStep(filesystem, "", output, /* executable */ false));
          }
          @Override
          public Path getPathToOutput() {
            return output;
          }
        };

    // Create the build engine.
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            fileHashCache,
            CachingBuildEngine.BuildMode.SHALLOW,
            CachingBuildEngine.DepFiles.ENABLED,
            new FixedRuleKeyBuilderFactory(ImmutableMap.of(rule.getBuildTarget(), inputRuleKey)),
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY);

    // Run the build.
    BuildResult result = cachingBuildEngine.build(buildContext, rule).get();
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());

    // Verify that the artifact was indexed in the cache by the input rule key.
    assertTrue(cache.hasArtifact(inputRuleKey));

    // Verify the input rule key was written to disk.
    OnDiskBuildInfo onDiskBuildInfo = buildContext.createOnDiskBuildInfoFor(target, filesystem);
    assertThat(
        onDiskBuildInfo.getRuleKey(BuildInfo.METADATA_KEY_FOR_INPUT_BASED_RULE_KEY),
        equalTo(Optional.of(inputRuleKey)));
  }

  @Test
  public void inputBasedRuleKeyMatchAvoidsBuildingLocally() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    InMemoryArtifactCache cache = new InMemoryArtifactCache();
    BuildContext buildContext =
        FakeBuildContext.newBuilder()
            .setArtifactCache(cache)
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();

    // Create a simple rule which just writes a file.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(target)
            .setProjectFilesystem(filesystem)
            .build();
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    RuleKey inputRuleKey = new RuleKey("aaaa");
    final Path output = Paths.get("output");
    BuildRule rule =
        new InputRuleKeyBuildRule(params, pathResolver) {
          @Override
          public ImmutableList<Step> getBuildSteps(
              BuildContext context,
              BuildableContext buildableContext) {
            return ImmutableList.<Step>of(
                new AbstractExecutionStep("false") {
                  @Override
                  public int execute(ExecutionContext context) {
                    return 1;
                  }
                });
          }
          @Override
          public Path getPathToOutput() {
            return output;
          }
        };

    // Create the output file.
    filesystem.writeContentsToPath("stuff", output);

    // Prepopulate the recorded paths metadata.
    filesystem.writeContentsToPath(
        new ObjectMapper().writeValueAsString(ImmutableList.of(output.toString())),
        BuildInfo.getPathToMetadataDirectory(target)
            .resolve(BuildInfo.METADATA_KEY_FOR_RECORDED_PATHS));

    // Prepopulate the input rule key on disk, so that we avoid a rebuild.
    filesystem.writeContentsToPath(
        inputRuleKey.toString(),
        BuildInfo.getPathToMetadataDirectory(target)
            .resolve(BuildInfo.METADATA_KEY_FOR_INPUT_BASED_RULE_KEY));

    // Create the build engine.
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            fileHashCache,
            CachingBuildEngine.BuildMode.SHALLOW,
            CachingBuildEngine.DepFiles.ENABLED,
            new FixedRuleKeyBuilderFactory(ImmutableMap.of(rule.getBuildTarget(), inputRuleKey)),
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY);

    // Run the build.
    BuildResult result = cachingBuildEngine.build(buildContext, rule).get();
    assertEquals(BuildRuleSuccessType.MATCHING_INPUT_BASED_RULE_KEY, result.getSuccess());

    // Verify the actual rule key was updated on disk.
    OnDiskBuildInfo onDiskBuildInfo = buildContext.createOnDiskBuildInfoFor(target, filesystem);
    assertThat(
        onDiskBuildInfo.getRuleKey(BuildInfo.METADATA_KEY_FOR_RULE_KEY),
        equalTo(Optional.of(rule.getRuleKey())));

    // Verify that the artifact is re-cached correctly under the main rule key.
    Path fetchedArtifact = tmp.newFile("fetched_artifact.zip").toPath();
    assertThat(
        cache.fetch(rule.getRuleKey(), fetchedArtifact).getType(),
        equalTo(CacheResult.Type.HIT));
    new ZipInspector(fetchedArtifact).assertFileExists(output.toString());
  }

  @Test
  public void inputBasedRuleKeyCacheHitAvoidsBuildingLocally() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    InMemoryArtifactCache cache = new InMemoryArtifactCache();
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    BuildContext buildContext =
        FakeBuildContext.newBuilder()
            .setArtifactCache(cache)
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();

    // Create a simple rule which just writes a file.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    RuleKey inputRuleKey = new RuleKey("aaaa");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(target)
            .setProjectFilesystem(filesystem)
            .build();
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    final Path output = Paths.get("output");
    BuildRule rule =
        new InputRuleKeyBuildRule(params, pathResolver) {
          @Override
          public ImmutableList<Step> getBuildSteps(
              BuildContext context,
              BuildableContext buildableContext) {
            return ImmutableList.<Step>of(
                new AbstractExecutionStep("false") {
                  @Override
                  public int execute(ExecutionContext context) {
                    return 1;
                  }
                });
          }
          @Override
          public Path getPathToOutput() {
            return output;
          }
        };

    // Prepopulate the recorded paths metadata.
    filesystem.writeContentsToPath(
        new ObjectMapper().writeValueAsString(ImmutableList.of(output.toString())),
        BuildInfo.getPathToMetadataDirectory(target)
            .resolve(BuildInfo.METADATA_KEY_FOR_RECORDED_PATHS));

    // Prepopulate the cache with an artifact indexed by the input-based rule key.
    Path artifact = tmp.newFile("artifact.zip").toPath();
    writeEntriesToZip(
        artifact,
        ImmutableMap.of(
            BuildInfo.getPathToMetadataDirectory(target)
                .resolve(BuildInfo.METADATA_KEY_FOR_RECORDED_PATHS).toString(),
            new ObjectMapper().writeValueAsString(ImmutableList.of(output.toString())),
            output.toString(),
            "stuff"));
    cache.store(
        ImmutableSet.of(inputRuleKey),
        ImmutableMap.of(
            BuildInfo.METADATA_KEY_FOR_RULE_KEY,
            new RuleKey("bbbb").toString(),
            BuildInfo.METADATA_KEY_FOR_INPUT_BASED_RULE_KEY,
            inputRuleKey.toString()),
        artifact);

    // Create the build engine.
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            fileHashCache,
            CachingBuildEngine.BuildMode.SHALLOW,
            CachingBuildEngine.DepFiles.ENABLED,
            new FixedRuleKeyBuilderFactory(ImmutableMap.of(rule.getBuildTarget(), inputRuleKey)),
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY);

    // Run the build.
    BuildResult result = cachingBuildEngine.build(buildContext, rule).get();
    assertEquals(BuildRuleSuccessType.FETCHED_FROM_CACHE_INPUT_BASED, result.getSuccess());

    // Verify the input-based and actual rule keys were updated on disk.
    OnDiskBuildInfo onDiskBuildInfo = buildContext.createOnDiskBuildInfoFor(target, filesystem);
    assertThat(
        onDiskBuildInfo.getRuleKey(BuildInfo.METADATA_KEY_FOR_RULE_KEY),
        equalTo(Optional.of(rule.getRuleKey())));
    assertThat(
        onDiskBuildInfo.getRuleKey(BuildInfo.METADATA_KEY_FOR_INPUT_BASED_RULE_KEY),
        equalTo(Optional.of(inputRuleKey)));

    // Verify that the artifact is re-cached correctly under the main rule key.
    Path fetchedArtifact = tmp.newFile("fetched_artifact.zip").toPath();
    assertThat(
        cache.fetch(rule.getRuleKey(), fetchedArtifact).getType(),
        equalTo(CacheResult.Type.HIT));
    assertEquals(
        new ZipInspector(artifact).getZipFileEntries(),
        new ZipInspector(fetchedArtifact).getZipFileEntries());
    MoreAsserts.assertContentsEqual(artifact, fetchedArtifact);
  }

  @Test
  public void depFileRuleKeyAndDepFileAreWrittenForSupportedRules() throws Exception {
    final FakeProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    InMemoryArtifactCache cache = new InMemoryArtifactCache();
    BuildContext buildContext =
        FakeBuildContext.newBuilder()
            .setArtifactCache(cache)
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();

    // Prepare an input file that should appear in the dep file.
    final Path input = Paths.get("input_file");
    filesystem.writeContentsToPath("contents", input);

    // Create a simple rule which just writes a file.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(target)
            .setProjectFilesystem(filesystem)
            .build();
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    RuleKey depFileRuleKey = new RuleKey("aaaa");
    final Path output = Paths.get("output");
    BuildRule rule =
        new DepFileBuildRule(params, pathResolver) {
          @Override
          public ImmutableList<Step> getBuildSteps(
              BuildContext context,
              BuildableContext buildableContext) {
            return ImmutableList.<Step>of(
                new WriteFileStep(filesystem, "", output, /* executable */ false));
          }
          @Override
          public ImmutableList<Path> getInputsAfterBuildingLocally() {
            return ImmutableList.of(input);
          }
          @Override
          public Optional<ImmutableMultimap<String, String>> getSymlinkTreeInputMap() {
            return Optional.absent();
          }
          @Override
          public Path getPathToOutput() {
            return output;
          }
        };

    // Create the build engine.
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            fileHashCache,
            CachingBuildEngine.BuildMode.SHALLOW,
            CachingBuildEngine.DepFiles.ENABLED,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY,
            new FixedRuleKeyBuilderFactory(
                ImmutableMap.of(rule.getBuildTarget(), depFileRuleKey),
                fileHashCache));

    // Run the build.
    BuildResult result = cachingBuildEngine.build(buildContext, rule).get();
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());

    // Verify the dep file rule key and dep file contents were written to disk.
    OnDiskBuildInfo onDiskBuildInfo = buildContext.createOnDiskBuildInfoFor(target, filesystem);
    assertThat(
        onDiskBuildInfo.getRuleKey(BuildInfo.METADATA_KEY_FOR_DEP_FILE_RULE_KEY),
        equalTo(Optional.of(depFileRuleKey)));
    assertThat(
        onDiskBuildInfo.getValues(BuildInfo.METADATA_KEY_FOR_DEP_FILE),
        equalTo(Optional.of(ImmutableList.of(input.toString()))));

    // Verify that the dep file rule key and dep file were written to the cached artifact.
    Path fetchedArtifact = tmp.newFile("fetched_artifact.zip").toPath();
    CacheResult cacheResult = cache.fetch(rule.getRuleKey(), fetchedArtifact);
    assertThat(
        cacheResult.getType(),
        equalTo(CacheResult.Type.HIT));
    assertThat(
        cacheResult.getMetadata().get(BuildInfo.METADATA_KEY_FOR_DEP_FILE_RULE_KEY),
        equalTo(depFileRuleKey.toString()));
    ZipInspector inspector = new ZipInspector(fetchedArtifact);
    inspector.assertFileContents(
        BuildInfo.getPathToMetadataDirectory(target)
            .resolve(BuildInfo.METADATA_KEY_FOR_DEP_FILE).toString(),
        new ObjectMapper().writeValueAsString(ImmutableList.of(input.toString())));
  }

  @Test
  public void depFileRuleKeyMatchAvoidsBuilding() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    InMemoryArtifactCache cache = new InMemoryArtifactCache();
    BuildContext buildContext =
        FakeBuildContext.newBuilder()
            .setArtifactCache(cache)
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();

    // Prepare an input file that should appear in the dep file.
    final Path input = Paths.get("input_file");
    filesystem.touch(input);

    // Create a simple rule which just writes a file.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(target)
            .setProjectFilesystem(filesystem)
            .build();
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    RuleKey depFileRuleKey = new RuleKey("aaaa");
    final Path output = Paths.get("output");
    filesystem.touch(output);
    BuildRule rule =
        new DepFileBuildRule(params, pathResolver) {
          @Override
          public ImmutableList<Step> getBuildSteps(
              BuildContext context,
              BuildableContext buildableContext) {
            return ImmutableList.<Step>of(
                new AbstractExecutionStep("false") {
                  @Override
                  public int execute(ExecutionContext context) {
                    return 1;
                  }
                });
          }
          @Override
          public ImmutableList<Path> getInputsAfterBuildingLocally() {
            return ImmutableList.of(input);
          }
          @Override
          public Optional<ImmutableMultimap<String, String>> getSymlinkTreeInputMap() {
            return Optional.absent();
          }
          @Override
          public Path getPathToOutput() {
            return output;
          }
        };

    // Create the build engine.
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            fileHashCache,
            CachingBuildEngine.BuildMode.SHALLOW,
            CachingBuildEngine.DepFiles.ENABLED,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY,
            new FixedRuleKeyBuilderFactory(
                ImmutableMap.of(rule.getBuildTarget(), depFileRuleKey),
                fileHashCache));

    // Prepopulate the dep file rule key and dep file.
    filesystem.writeContentsToPath(
        depFileRuleKey.toString(),
        BuildInfo.getPathToMetadataDirectory(target)
            .resolve(BuildInfo.METADATA_KEY_FOR_DEP_FILE_RULE_KEY));
    filesystem.writeContentsToPath(
        new ObjectMapper().writeValueAsString(ImmutableList.of(input.toString())),
        BuildInfo.getPathToMetadataDirectory(target)
            .resolve(BuildInfo.METADATA_KEY_FOR_DEP_FILE));

    // Prepopulate the recorded paths metadata.
    filesystem.writeContentsToPath(
        new ObjectMapper().writeValueAsString(ImmutableList.of(output.toString())),
        BuildInfo.getPathToMetadataDirectory(target)
            .resolve(BuildInfo.METADATA_KEY_FOR_RECORDED_PATHS));

    // Run the build.
    BuildResult result = cachingBuildEngine.build(buildContext, rule).get();
    assertEquals(BuildRuleSuccessType.MATCHING_DEP_FILE_RULE_KEY, result.getSuccess());
  }

  @Test
  public void depFileInputChangeCausesRebuild() throws Exception {
    final FakeProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    InMemoryArtifactCache cache = new InMemoryArtifactCache();
    BuildContext buildContext =
        FakeBuildContext.newBuilder()
            .setArtifactCache(cache)
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();
    FileHashCache fileHashCache = new DefaultFileHashCache(filesystem);

    // Create a simple rule which just writes a file.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(target)
            .setProjectFilesystem(filesystem)
            .build();
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    final Path input = Paths.get("input_file");
    final Path output = Paths.get("output");
    BuildRule rule =
        new DepFileBuildRule(params, pathResolver) {
          @Override
          public ImmutableList<Step> getBuildSteps(
              BuildContext context,
              BuildableContext buildableContext) {
            buildableContext.addMetadata(
                BuildInfo.METADATA_KEY_FOR_DEP_FILE,
                ImmutableList.of(input.toString()));
            return ImmutableList.<Step>of(
                new WriteFileStep(filesystem, "", output, /* executable */ false));
          }
          @Override
          public ImmutableList<Path> getInputsAfterBuildingLocally() {
            return ImmutableList.of(input);
          }
          @Override
          public Optional<ImmutableMultimap<String, String>> getSymlinkTreeInputMap() {
            return Optional.absent();
          }
          @Override
          public Path getPathToOutput() {
            return output;
          }
        };

    // Prepare an input file that should appear in the dep file.
    filesystem.writeContentsToPath("something", input);
    DependencyFileRuleKeyBuilderFactory factory =
        new DependencyFileRuleKeyBuilderFactory(
            fileHashCache,
            new SourcePathResolver(new BuildRuleResolver()));
    RuleKey depFileRuleKey = factory.newInstance(rule).setPath(input).build();

    // Prepopulate the dep file rule key and dep file.
    filesystem.writeContentsToPath(
        depFileRuleKey.toString(),
        BuildInfo.getPathToMetadataDirectory(target)
            .resolve(BuildInfo.METADATA_KEY_FOR_DEP_FILE_RULE_KEY));
    filesystem.writeContentsToPath(
        new ObjectMapper().writeValueAsString(ImmutableList.of(input.toString())),
        BuildInfo.getPathToMetadataDirectory(target)
            .resolve(BuildInfo.METADATA_KEY_FOR_DEP_FILE));

    // Prepopulate the recorded paths metadata.
    filesystem.writeContentsToPath(
        new ObjectMapper().writeValueAsString(ImmutableList.of(output.toString())),
        BuildInfo.getPathToMetadataDirectory(target)
            .resolve(BuildInfo.METADATA_KEY_FOR_RECORDED_PATHS));

    // Now modify the input file and invalidate it in the cache.
    filesystem.writeContentsToPath("something else", input);
    fileHashCache.invalidate(input);

    // Run the build.
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            fileHashCache,
            CachingBuildEngine.BuildMode.SHALLOW,
            CachingBuildEngine.DepFiles.ENABLED,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY,
            new DependencyFileRuleKeyBuilderFactory(
                fileHashCache,
                pathResolver));
    BuildResult result = cachingBuildEngine.build(buildContext, rule).get();
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());
  }

  @Test
  public void depFileDeletedInputCausesRebuild() throws Exception {
    final FakeProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    InMemoryArtifactCache cache = new InMemoryArtifactCache();
    BuildContext buildContext =
        FakeBuildContext.newBuilder()
            .setArtifactCache(cache)
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();
    FileHashCache fileHashCache = new DefaultFileHashCache(filesystem);

    // Create a simple rule which just writes a file.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(target)
            .setProjectFilesystem(filesystem)
            .build();
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    final Path input = Paths.get("input_file");
    final Path output = Paths.get("output");
    BuildRule rule =
        new DepFileBuildRule(params, pathResolver) {
          @Override
          public ImmutableList<Step> getBuildSteps(
              BuildContext context,
              BuildableContext buildableContext) {
            buildableContext.addMetadata(
                BuildInfo.METADATA_KEY_FOR_DEP_FILE,
                ImmutableList.of(input.toString()));
            return ImmutableList.<Step>of(
                new WriteFileStep(filesystem, "", output, /* executable */ false));
          }
          @Override
          public ImmutableList<Path> getInputsAfterBuildingLocally() {
            return ImmutableList.of();
          }
          @Override
          public Optional<ImmutableMultimap<String, String>> getSymlinkTreeInputMap() {
            return Optional.absent();
          }
          @Override
          public Path getPathToOutput() {
            return output;
          }
        };

    // Prepare an input file that should appear in the dep file.
    filesystem.writeContentsToPath("something", input);
    DependencyFileRuleKeyBuilderFactory factory =
        new DependencyFileRuleKeyBuilderFactory(
            fileHashCache,
            new SourcePathResolver(new BuildRuleResolver()));
    RuleKey depFileRuleKey = factory.newInstance(rule).setPath(input).build();

    // Prepopulate the dep file rule key and dep file.
    filesystem.writeContentsToPath(
        depFileRuleKey.toString(),
        BuildInfo.getPathToMetadataDirectory(target)
            .resolve(BuildInfo.METADATA_KEY_FOR_DEP_FILE_RULE_KEY));
    filesystem.writeContentsToPath(
        new ObjectMapper().writeValueAsString(ImmutableList.of(input.toString())),
        BuildInfo.getPathToMetadataDirectory(target)
            .resolve(BuildInfo.METADATA_KEY_FOR_DEP_FILE));

    // Prepopulate the recorded paths metadata.
    filesystem.writeContentsToPath(
        new ObjectMapper().writeValueAsString(ImmutableList.of(output.toString())),
        BuildInfo.getPathToMetadataDirectory(target)
            .resolve(BuildInfo.METADATA_KEY_FOR_RECORDED_PATHS));

    // Now delete the input and invalidate it in the cache.
    filesystem.deleteFileAtPath(input);
    fileHashCache.invalidate(input);

    // Run the build.
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            fileHashCache,
            CachingBuildEngine.BuildMode.SHALLOW,
            CachingBuildEngine.DepFiles.ENABLED,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY,
            new DependencyFileRuleKeyBuilderFactory(
                fileHashCache,
                pathResolver));
    BuildResult result = cachingBuildEngine.build(buildContext, rule).get();
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());
  }

  @Test
  public void buildingRuleLocallyInvalidatesOutputs() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    InMemoryArtifactCache cache = new InMemoryArtifactCache();
    BuildContext buildContext =
        FakeBuildContext.newBuilder()
            .setArtifactCache(cache)
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();

    // First, write something to the output file and get it's hash.
    Path output = Paths.get("output/path");
    filesystem.mkdirs(output.getParent());
    filesystem.writeContentsToPath("something", output);
    HashCode originalHashCode = fileHashCache.get(output);
    assertTrue(fileHashCache.contains(output));

    // Create a simple rule which just writes something new to the output file.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(target)
            .setProjectFilesystem(filesystem)
            .build();
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    BuildRule rule =
        new WriteFile(params, pathResolver, "something else", output, /* executable */ false);

    // Create the build engine.
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            fileHashCache,
            CachingBuildEngine.BuildMode.SHALLOW,
            CachingBuildEngine.DepFiles.ENABLED,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY);

    // Run the build.
    BuildResult result = cachingBuildEngine.build(buildContext, rule).get();
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());

    // Verify that we have a new hash.
    HashCode newHashCode = fileHashCache.get(output);
    assertThat(newHashCode, Matchers.not(equalTo(originalHashCode)));
  }

  @Test
  public void dependencyFailuresDoesNotOrphanOtherDependencies() throws Exception {
    ListeningExecutorService service = listeningDecorator(Executors.newFixedThreadPool(2));
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    InMemoryArtifactCache cache = new InMemoryArtifactCache();
    BuildContext buildContext =
        FakeBuildContext.newBuilder()
            .setArtifactCache(cache)
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();

    // Create a dep chain comprising one side of the dep tree of the main rule, where the first-
    // running rule fails immediately, canceling the second rule, and ophaning at least one rule
    // in the other side of the dep tree.
    BuildRule dep1 =
        new RuleWithSteps(
            new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:dep1"))
                .setProjectFilesystem(filesystem)
                .build(),
            pathResolver,
            ImmutableList.<Step>of(new FailingStep()),
            /* output */ null);
    BuildRule dep2 =
        new RuleWithSteps(
            new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:dep2"))
                .setDeps(ImmutableSortedSet.of(dep1))
                .setProjectFilesystem(filesystem)
                .build(),
            pathResolver,
            ImmutableList.<Step>of(new SleepStep(0)),
            /* output */ null);

    // Create another dep chain, which is two deep with rules that just sleep.
    BuildRule dep3 =
        new RuleWithSteps(
            new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:dep3"))
                .setProjectFilesystem(filesystem)
                .build(),
            pathResolver,
            ImmutableList.<Step>of(new SleepStep(300)),
            /* output */ null);
    BuildRule dep4 =
        new RuleWithSteps(
            new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:dep4"))
                .setDeps(ImmutableSortedSet.of(dep3))
                .setProjectFilesystem(filesystem)
                .build(),
            pathResolver,
            ImmutableList.<Step>of(new SleepStep(300)),
            /* output */ null);

    // Create the top-level rule which pulls in the two sides of the dep tree.
    BuildRule rule =
        new RuleWithSteps(
            new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule"))
                .setDeps(ImmutableSortedSet.of(dep2, dep4))
                .setProjectFilesystem(filesystem)
                .build(),
            pathResolver,
            ImmutableList.<Step>of(new SleepStep(1000)),
            /* output */ null);

    // Create the build engine.
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            service,
            fileHashCache,
            CachingBuildEngine.BuildMode.DEEP,
            CachingBuildEngine.DepFiles.ENABLED,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY);

    // Run the build.
    BuildResult result = cachingBuildEngine.build(buildContext, rule).get();
    assertTrue(service.shutdownNow().isEmpty());
    assertThat(result.getStatus(), equalTo(BuildRuleStatus.CANCELED));
    assertThat(
        Preconditions.checkNotNull(
            cachingBuildEngine.getBuildRuleResult(
                dep1.getBuildTarget())).getStatus(),
        equalTo(BuildRuleStatus.FAIL));
    assertThat(
        Preconditions.checkNotNull(cachingBuildEngine.getBuildRuleResult(
                dep2.getBuildTarget())).getStatus(),
        equalTo(BuildRuleStatus.CANCELED));
    assertThat(
        Preconditions.checkNotNull(
            cachingBuildEngine.getBuildRuleResult(
                dep3.getBuildTarget())).getStatus(),
        Matchers.oneOf(BuildRuleStatus.SUCCESS, BuildRuleStatus.CANCELED));
    assertThat(
        Preconditions.checkNotNull(
            cachingBuildEngine.getBuildRuleResult(
                dep4.getBuildTarget())).getStatus(),
        Matchers.oneOf(BuildRuleStatus.SUCCESS, BuildRuleStatus.CANCELED));
  }


  @Test
  public void runningWithKeepGoingBuildsAsMuchAsPossible() throws Exception {
    ListeningExecutorService service = listeningDecorator(Executors.newFixedThreadPool(2));
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    InMemoryArtifactCache cache = new InMemoryArtifactCache();
    BuildContext buildContext =
        FakeBuildContext.newBuilder()
            .setArtifactCache(cache)
            .setKeepGoing(true)
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();

    // Create a dep chain comprising one side of the dep tree of the main rule, where the first-
    // running rule fails immediately, canceling the second rule, and ophaning at least one rule
    // in the other side of the dep tree.
    BuildRule dep1 =
        new RuleWithSteps(
            new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:dep1"))
                .setProjectFilesystem(filesystem)
                .build(),
            pathResolver,
            ImmutableList.<Step>of(new FailingStep()),
            /* output */ null);
    BuildRule dep2 =
        new RuleWithSteps(
            new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:dep2"))
                .setDeps(ImmutableSortedSet.of(dep1))
                .setProjectFilesystem(filesystem)
                .build(),
            pathResolver,
            ImmutableList.<Step>of(new SleepStep(0)),
            /* output */ null);

    // Create another dep chain, which is two deep with rules that just sleep.
    BuildRule dep3 =
        new RuleWithSteps(
            new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:dep3"))
                .setProjectFilesystem(filesystem)
                .build(),
            pathResolver,
            ImmutableList.<Step>of(new SleepStep(300)),
            /* output */ null);
    BuildRule dep4 =
        new RuleWithSteps(
            new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:dep4"))
                .setDeps(ImmutableSortedSet.of(dep3))
                .setProjectFilesystem(filesystem)
                .build(),
            pathResolver,
            ImmutableList.<Step>of(new SleepStep(300)),
            /* output */ null);

    // Create the top-level rule which pulls in the two sides of the dep tree.
    BuildRule rule =
        new RuleWithSteps(
            new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule"))
                .setDeps(ImmutableSortedSet.of(dep2, dep4))
                .setProjectFilesystem(filesystem)
                .build(),
            pathResolver,
            ImmutableList.<Step>of(new SleepStep(1000)),
            /* output */ null);

    // Create the build engine.
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            service,
            fileHashCache,
            CachingBuildEngine.BuildMode.SHALLOW,
            CachingBuildEngine.DepFiles.ENABLED,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY);

    // Run the build.
    BuildResult result = cachingBuildEngine.build(buildContext, rule).get();
    assertTrue(service.shutdownNow().isEmpty());
    assertThat(result.getStatus(), equalTo(BuildRuleStatus.CANCELED));
    assertThat(
        Preconditions.checkNotNull(
            cachingBuildEngine.getBuildRuleResult(
                dep1.getBuildTarget())).getStatus(),
        equalTo(BuildRuleStatus.FAIL));
    assertThat(
        Preconditions.checkNotNull(cachingBuildEngine.getBuildRuleResult(
                dep2.getBuildTarget())).getStatus(),
        equalTo(BuildRuleStatus.CANCELED));
    assertThat(
        Preconditions.checkNotNull(
            cachingBuildEngine.getBuildRuleResult(
                dep3.getBuildTarget())).getStatus(),
        equalTo(BuildRuleStatus.SUCCESS));
    assertThat(
        Preconditions.checkNotNull(
            cachingBuildEngine.getBuildRuleResult(
                dep4.getBuildTarget())).getStatus(),
        equalTo(BuildRuleStatus.SUCCESS));
  }

  @Test
  public void abiRuleKeyIsWrittenForSupportedRules() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    InMemoryArtifactCache cache = new InMemoryArtifactCache();
    BuildContext buildContext =
        FakeBuildContext.newBuilder()
            .setArtifactCache(cache)
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(target)
            .setProjectFilesystem(filesystem)
            .build();
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    FakeAbiRuleBuildRule rule = new FakeAbiRuleBuildRule(params, pathResolver);
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            fileHashCache,
            CachingBuildEngine.BuildMode.SHALLOW,
            CachingBuildEngine.DepFiles.ENABLED,
            NOOP_RULE_KEY_FACTORY,
            new AbiRuleKeyBuilderFactory(fileHashCache, pathResolver),
            NOOP_RULE_KEY_FACTORY);

    BuildResult result = cachingBuildEngine.build(buildContext, rule).get();
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());

    OnDiskBuildInfo onDiskBuildInfo = buildContext.createOnDiskBuildInfoFor(target, filesystem);
    Optional<RuleKey> abiRuleKey =
        onDiskBuildInfo.getRuleKey(BuildInfo.METADATA_KEY_FOR_ABI_RULE_KEY);
    assertThat(abiRuleKey.isPresent(), is(true));

    // Verify that the dep file rule key and dep file were written to the cached artifact.
    Path fetchedArtifact = tmp.newFile("fetched_artifact.zip").toPath();
    CacheResult cacheResult = cache.fetch(rule.getRuleKey(), fetchedArtifact);
    assertThat(
        cacheResult.getType(),
        equalTo(CacheResult.Type.HIT));
    assertThat(
        cacheResult.getMetadata().get(BuildInfo.METADATA_KEY_FOR_ABI_RULE_KEY),
        equalTo(abiRuleKey.get().toString()));
  }

  @Test
  public void abiRuleKeyMatchAvoidsBuilding() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    InMemoryArtifactCache cache = new InMemoryArtifactCache();
    BuildContext buildContext =
        FakeBuildContext.newBuilder()
            .setArtifactCache(cache)
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(target)
            .setProjectFilesystem(filesystem)
            .build();
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    FakeAbiRuleBuildRule rule = new FakeAbiRuleBuildRule(params, pathResolver);
    AbiRuleKeyBuilderFactory abiRuleKeyBuilderFactory = new AbiRuleKeyBuilderFactory(
        fileHashCache,
        pathResolver);
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            fileHashCache,
            CachingBuildEngine.BuildMode.SHALLOW,
            CachingBuildEngine.DepFiles.ENABLED,
            NOOP_RULE_KEY_FACTORY,
            abiRuleKeyBuilderFactory,
            NOOP_RULE_KEY_FACTORY);

    // Prepopulate the dep file rule key and dep file.
    filesystem.writeContentsToPath(
        abiRuleKeyBuilderFactory.newInstance(rule).build().toString(),
        BuildInfo.getPathToMetadataDirectory(target)
            .resolve(BuildInfo.METADATA_KEY_FOR_ABI_RULE_KEY));

    // Prepopulate the recorded paths metadata.
    filesystem.writeContentsToPath(
        new ObjectMapper().writeValueAsString(ImmutableList.of()),
        BuildInfo.getPathToMetadataDirectory(target)
            .resolve(BuildInfo.METADATA_KEY_FOR_RECORDED_PATHS));

    // Run the build.
    BuildResult result = cachingBuildEngine.build(buildContext, rule).get();
    assertEquals(BuildRuleSuccessType.MATCHING_ABI_RULE_KEY, result.getSuccess());
  }

  @Test
  public void abiRuleKeyChangeCausesRebuild() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    InMemoryArtifactCache cache = new InMemoryArtifactCache();
    BuildContext buildContext =
        FakeBuildContext.newBuilder()
            .setArtifactCache(cache)
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(target)
            .setProjectFilesystem(filesystem)
            .build();
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    FakeAbiRuleBuildRule rule = new FakeAbiRuleBuildRule(params, pathResolver);
    AbiRuleKeyBuilderFactory abiRuleKeyBuilderFactory = new AbiRuleKeyBuilderFactory(
        fileHashCache,
        pathResolver);
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            fileHashCache,
            CachingBuildEngine.BuildMode.SHALLOW,
            CachingBuildEngine.DepFiles.ENABLED,
            NOOP_RULE_KEY_FACTORY,
            abiRuleKeyBuilderFactory,
            NOOP_RULE_KEY_FACTORY);

    // Prepopulate the dep file rule key and dep file.
    filesystem.writeContentsToPath(
        new StringBuilder(abiRuleKeyBuilderFactory.newInstance(rule).build().toString())
            .reverse()
            .toString(),
        BuildInfo.getPathToMetadataDirectory(target)
            .resolve(BuildInfo.METADATA_KEY_FOR_ABI_RULE_KEY));

    // Prepopulate the recorded paths metadata.
    filesystem.writeContentsToPath(
        new ObjectMapper().writeValueAsString(ImmutableList.of()),
        BuildInfo.getPathToMetadataDirectory(target)
            .resolve(BuildInfo.METADATA_KEY_FOR_RECORDED_PATHS));

    // Run the build.
    BuildResult result = cachingBuildEngine.build(buildContext, rule).get();
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());
  }

  @Test
  public void getNumRulesToBuild() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    BuildRule rule3 =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule3"))
            .setOut("out3")
            .build(resolver);
    BuildRule rule2 =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule2"))
            .setOut("out2")
            .setDeps(ImmutableSortedSet.of(rule3.getBuildTarget()))
            .build(resolver);
    BuildRule rule1 =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule1"))
            .setOut("out1")
            .setDeps(ImmutableSortedSet.of(rule2.getBuildTarget()))
            .build(resolver);

    // Create the build engine.
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            new NullFileHashCache(),
            CachingBuildEngine.BuildMode.SHALLOW,
            CachingBuildEngine.DepFiles.ENABLED,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY,
            NOOP_RULE_KEY_FACTORY);

    assertThat(
        cachingBuildEngine.getNumRulesToBuild(ImmutableList.of(rule1)),
        equalTo(3));
  }


  // TODO(mbolin): Test that when the success files match, nothing is built and nothing is written
  // back to the cache.

  // TODO(mbolin): Test that when the value in the success file does not agree with the current
  // value, the rule is rebuilt and the result is written back to the cache.

  // TODO(mbolin): Test that a failure when executing the build steps is propagated appropriately.

  // TODO(mbolin): Test what happens when the cache's methods throw an exception.

  private BuildRule createRule(
      ProjectFilesystem filesystem,
      SourcePathResolver resolver,
      ImmutableSet<BuildRule> deps,
      List<Step> buildSteps,
      ImmutableList<Step> postBuildSteps,
      @Nullable String pathToOutputFile) {
    Comparator<BuildRule> comparator = RetainOrderComparator.createComparator(deps);
    ImmutableSortedSet<BuildRule> sortedDeps = ImmutableSortedSet.copyOf(comparator, deps);

    final FileHashCache fileHashCache = FakeFileHashCache.createFromStrings(ImmutableMap.of(
            "/dev/null", "ae8c0f860a0ecad94ecede79b69460434eddbfbc"));

    BuildRuleParams buildRuleParams = new FakeBuildRuleParamsBuilder(BUILD_TARGET)
        .setProjectFilesystem(filesystem)
        .setDeps(sortedDeps)
        .setFileHashCache(fileHashCache)
        .build();

    return new BuildableAbstractCachingBuildRule(
        buildRuleParams,
        resolver,
        pathToOutputFile,
        buildSteps,
        postBuildSteps);
  }

  private static class BuildableAbstractCachingBuildRule extends AbstractBuildRule
      implements HasPostBuildSteps, InitializableFromDisk<Object> {

    private final Path pathToOutputFile;
    private final List<Step> buildSteps;
    private final ImmutableList<Step> postBuildSteps;
    private final BuildOutputInitializer<Object> buildOutputInitializer;

    private boolean isInitializedFromDisk = false;

    private BuildableAbstractCachingBuildRule(
        BuildRuleParams params,
        SourcePathResolver resolver,
        @Nullable String pathToOutputFile,
        List<Step> buildSteps,
        ImmutableList<Step> postBuildSteps) {
      super(params, resolver);
      this.pathToOutputFile = pathToOutputFile == null ? null : Paths.get(pathToOutputFile);
      this.buildSteps = buildSteps;
      this.postBuildSteps = postBuildSteps;
      this.buildOutputInitializer =
          new BuildOutputInitializer<>(params.getBuildTarget(), this);
    }

    @Override
    @Nullable
    public Path getPathToOutput() {
      return pathToOutputFile;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context,
        BuildableContext buildableContext) {
      if (pathToOutputFile != null) {
        buildableContext.recordArtifact(pathToOutputFile);
      }
      return ImmutableList.copyOf(buildSteps);
    }

    @Override
    public ImmutableList<Step> getPostBuildSteps(
        BuildContext context,
        BuildableContext buildableContext) {
      return postBuildSteps;
    }

    @Override
    public Object initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
      isInitializedFromDisk = true;
      return new Object();
    }

    @Override
    public BuildOutputInitializer<Object> getBuildOutputInitializer() {
      return buildOutputInitializer;
    }

    public boolean isInitializedFromDisk() {
      return isInitializedFromDisk;
    }
  }

  /**
   * Implementation of {@link ArtifactCache} that, when its fetch method is called, takes the
   * location of requested {@link File} and writes a zip file there with the entries specified to
   * its constructor.
   * <p>
   * This makes it possible to react to a call to
   * {@link ArtifactCache#store(ImmutableSet, ImmutableMap, Path)} and ensure that there will be a
   * zip file in place immediately after the captured method has been invoked.
   */
  private static class FakeArtifactCacheThatWritesAZipFile implements ArtifactCache {

    private final Map<String, String> desiredEntries;

    public FakeArtifactCacheThatWritesAZipFile(Map<String, String> desiredEntries) {
      this.desiredEntries = desiredEntries;
    }

    @Override
    public CacheResult fetch(RuleKey ruleKey, Path file) throws InterruptedException {
      try {
        writeEntriesToZip(file, ImmutableMap.copyOf(desiredEntries));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      return CacheResult.hit("dir");
    }

    @Override
    public void store(
        ImmutableSet<RuleKey> ruleKeys,
        ImmutableMap<String, String> metadata,
        Path output) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isStoreSupported() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() throws IOException {
      throw new UnsupportedOperationException();
    }
  }

  private static class FakeHasRuntimeDeps extends FakeBuildRule implements HasRuntimeDeps {

    private final ImmutableSortedSet<BuildRule> runtimeDeps;

    public FakeHasRuntimeDeps(
        BuildTarget target,
        ProjectFilesystem filesystem,
        SourcePathResolver resolver,
        BuildRule... runtimeDeps) {
      super(target, filesystem, resolver);
      this.runtimeDeps = ImmutableSortedSet.copyOf(runtimeDeps);
    }

    @Override
    public ImmutableSortedSet<BuildRule> getRuntimeDeps() {
      return runtimeDeps;
    }

  }

  private static class FixedRuleKeyBuilderFactory implements RuleKeyBuilderFactory {

    private final ImmutableMap<BuildTarget, RuleKey> ruleKeys;
    private final FileHashCache fileHashCache;

    public FixedRuleKeyBuilderFactory(
        ImmutableMap<BuildTarget, RuleKey> ruleKeys,
        FileHashCache fileHashCache) {
      this.ruleKeys = ruleKeys;
      this.fileHashCache = fileHashCache;
    }

    public FixedRuleKeyBuilderFactory(ImmutableMap<BuildTarget, RuleKey> ruleKeys) {
      this(ruleKeys, new NullFileHashCache());
    }

    @Override
    public RuleKeyBuilder newInstance(final BuildRule buildRule) {
      SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver());
      return new RuleKeyBuilder(resolver, fileHashCache) {

        @Override
        public RuleKeyBuilder setReflectively(String key, @Nullable Object val) {
          return this;
        }

        @Override
        public RuleKey build() {
          return ruleKeys.get(buildRule.getBuildTarget());
        }

      };
    }
  }

  private abstract static class InputRuleKeyBuildRule
      extends AbstractBuildRule
      implements SupportsInputBasedRuleKey {
    public InputRuleKeyBuildRule(
        BuildRuleParams buildRuleParams,
        SourcePathResolver resolver) {
      super(buildRuleParams, resolver);
    }
  }

  private abstract static class DepFileBuildRule
      extends AbstractBuildRule
      implements SupportsDependencyFileRuleKey {
    public DepFileBuildRule(
        BuildRuleParams buildRuleParams,
        SourcePathResolver resolver) {
      super(buildRuleParams, resolver);
    }
    @Override
    public boolean useDependencyFileRuleKeys() {
      return true;
    }
  }

  private static class RuleWithSteps extends AbstractBuildRule {

    private final ImmutableList<Step> steps;
    @Nullable private final Path output;

    public RuleWithSteps(
        BuildRuleParams buildRuleParams,
        SourcePathResolver resolver,
        ImmutableList<Step> steps,
        @Nullable Path output) {
      super(buildRuleParams, resolver);
      this.steps = steps;
      this.output = output;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context,
        BuildableContext buildableContext) {
      return steps;
    }

    @Nullable
    @Override
    public Path getPathToOutput() {
      return output;
    }

  }

  private static class SleepStep extends AbstractExecutionStep {

    private final long millis;

    public SleepStep(long millis) {
      super(String.format("sleep %sms", millis));
      this.millis = millis;
    }

    @Override
    public int execute(ExecutionContext context) throws IOException {
      try {
        Thread.sleep(millis);
      } catch (InterruptedException e) {
        throw Throwables.propagate(e);
      }
      return 0;
    }
  }

  private static class FailingStep extends AbstractExecutionStep {

    public FailingStep() {
      super("failing step");
    }

    @Override
    public int execute(ExecutionContext context) throws IOException {
      return 1;
    }

  }

  private static void writeEntriesToZip(Path file, ImmutableMap<String, String> entries)
      throws IOException {
    try (CustomZipOutputStream zip = ZipOutputStreams.newOutputStream(file)) {
      for (Map.Entry<String, String> mapEntry : entries.entrySet()) {
        CustomZipEntry entry = new CustomZipEntry(mapEntry.getKey());
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(mapEntry.getValue().getBytes());
        zip.closeEntry();
      }
    }
  }

  private StepRunner createStepRunner(@Nullable BuckEventBus eventBus) {
    ExecutionContext executionContext = TestExecutionContext.newBuilder()
        .setEventBus(eventBus == null ? BuckEventBusFactory.newInstance() : eventBus)
        .build();

    return new DefaultStepRunner(executionContext);
  }
}
