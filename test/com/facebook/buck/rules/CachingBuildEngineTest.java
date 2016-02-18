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

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.artifact_cache.InMemoryArtifactCache;
import com.facebook.buck.io.LazyPath;
import com.facebook.buck.artifact_cache.NoopArtifactCache;
import com.facebook.buck.cli.BuildTargetNodeToBuildRuleTransformer;
import com.facebook.buck.cli.CommandEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.FakeBuckEventListener;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.FakeJavaPackageFinder;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.keys.AbiRuleKeyBuilderFactory;
import com.facebook.buck.rules.keys.DefaultDependencyFileRuleKeyBuilderFactory;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.rules.keys.DependencyFileRuleKeyBuilderFactory;
import com.facebook.buck.rules.keys.SupportsDependencyFileRuleKey;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.NullFileHashCache;
import com.facebook.buck.util.concurrent.MoreFutures;
import com.facebook.buck.zip.CustomZipEntry;
import com.facebook.buck.zip.CustomZipOutputStream;
import com.facebook.buck.zip.ZipConstants;
import com.facebook.buck.zip.ZipOutputStreams;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import org.easymock.EasyMockSupport;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.annotation.Nullable;

/**
 * Ensuring that build rule caching works correctly in Buck is imperative for both its performance
 * and correctness.
 */
public class CachingBuildEngineTest extends EasyMockSupport {

  private static final BuildTarget BUILD_TARGET =
      BuildTargetFactory.newInstance("//src/com/facebook/orca:orca");
  private static final SourcePathResolver DEFAULT_SOURCE_PATH_RESOLVER =
      new SourcePathResolver(
          new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer()));
  private static final RuleKeyBuilderFactory NOOP_RULE_KEY_FACTORY =
      new DefaultRuleKeyBuilderFactory(
          new NullFileHashCache(),
          DEFAULT_SOURCE_PATH_RESOLVER);
  private static final DependencyFileRuleKeyBuilderFactory NOOP_DEP_FILE_RULE_KEY_FACTORY =
      new DefaultDependencyFileRuleKeyBuilderFactory(
          new NullFileHashCache(),
          DEFAULT_SOURCE_PATH_RESOLVER,
          NOOP_RULE_KEY_FACTORY);

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
    SourcePathResolver resolver =
        new SourcePathResolver(
            new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer()));
    DefaultRuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(fileHashCache, resolver);
    InMemoryArtifactCache cache = new InMemoryArtifactCache();

    // Create a dep for the build rule.
    BuildTarget depTarget = BuildTargetFactory.newInstance("//src/com/facebook/orca:lib");
    FakeBuildRule dep = new FakeBuildRule(depTarget, resolver);

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
            CachingBuildEngine.DependencySchedulingOrder.RANDOM,
            CachingBuildEngine.DepFiles.ENABLED,
            256L,
            new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer()));

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
        CacheResult.miss());

    BuildResult result = cachingBuildEngine.build(context, ruleToTest).get();
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());
    buckEventBus.post(
        CommandEvent.finished(CommandEvent.started("build", ImmutableList.<String>of(), false), 0));
    verifyAll();

    RuleKey ruleToTestKey = ruleKeyBuilderFactory.build(ruleToTest);
    assertTrue(cache.hasArtifact(ruleToTestKey));

    // Verify the events logged to the BuckEventBus.
    List<BuckEvent> events = listener.getEvents();
    assertThat(events, Matchers.hasSize(11));
    Iterator<BuckEvent> eventIter = events.iterator();
    assertEquals(
        configureTestEvent(BuildRuleEvent.started(dep), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.suspended(dep, ruleKeyBuilderFactory), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.started(ruleToTest),
            buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.finished(
                ruleToTest,
                BuildRuleKeys.of(ruleToTestKey),
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
    SourcePathResolver resolver =
        new SourcePathResolver(
            new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer()));
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(fileHashCache, resolver);
    BuildRuleParams buildRuleParams = new FakeBuildRuleParamsBuilder(BUILD_TARGET)
        .setProjectFilesystem(filesystem)
        .build();
    FakeBuildRule buildRule = new FakeBuildRule(
        buildRuleParams,
        resolver);

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
                  public ListenableFuture<Void> store(
                      ImmutableSet<RuleKey> ruleKeys,
                      ImmutableMap<String, String> metadata,
                      Path output) {
                    try {
                      Thread.sleep(500);
                    } catch (InterruptedException e) {
                      throw Throwables.propagate(e);
                    }
                    return Futures.immediateFuture(null);
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
            CachingBuildEngine.DependencySchedulingOrder.RANDOM,
            CachingBuildEngine.DepFiles.ENABLED,
            256L,
            new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer()));
    ListenableFuture<BuildResult> buildResult = cachingBuildEngine.build(buildContext, buildRule);

    BuildResult result = buildResult.get();
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());

    assertTrue(service.shutdownNow().isEmpty());

    List<BuckEvent> events = listener.getEvents();
    assertThat(events, Matchers.hasSize(6));
    Iterator<BuckEvent> eventIter = events.iterator();
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.started(buildRule),
            buckEventBus)
            .getEventName(),
        eventIter.next().getEventName());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.suspended(buildRule, ruleKeyBuilderFactory),
            buckEventBus)
            .getEventName(),
        eventIter.next().getEventName());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.resumed(buildRule, ruleKeyBuilderFactory),
            buckEventBus)
            .getEventName(),
        eventIter.next().getEventName());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.suspended(buildRule, ruleKeyBuilderFactory),
            buckEventBus)
            .getEventName(),
        eventIter.next().getEventName());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.resumed(buildRule, ruleKeyBuilderFactory),
            buckEventBus)
            .getEventName(),
        eventIter.next().getEventName());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.finished(
                buildRule,
                BuildRuleKeys.of(ruleKeyBuilderFactory.build(buildRule)),
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
    FileHashCache hashCache = new DefaultFileHashCache(filesystem);
    SourcePathResolver resolver =
        new SourcePathResolver(
            new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer()));
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(hashCache, resolver);

    Step step = new AbstractExecutionStep("exploding step") {
      @Override
      public int execute(ExecutionContext context) {
        throw new UnsupportedOperationException("build step should not be executed");
      }
    };
    BuildRule buildRule = createRule(
        filesystem,
        resolver,
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
            eq(ruleKeyBuilderFactory.build(buildRule)),
            isA(LazyPath.class)))
        .andDelegateTo(
            new FakeArtifactCacheThatWritesAZipFile(desiredZipEntries));

    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    BuildContext buildContext = ImmutableBuildContext.builder()
        .setActionGraph(new ActionGraph(ImmutableList.of(buildRule)))
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
            CachingBuildEngine.DependencySchedulingOrder.RANDOM,
            CachingBuildEngine.DepFiles.ENABLED,
            256L,
            new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer()));
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
    FileHashCache hashCache = new DefaultFileHashCache(filesystem);
    SourcePathResolver resolver =
        new SourcePathResolver(
            new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer()));
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(hashCache, resolver);

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
        resolver,
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
            eq(ruleKeyBuilderFactory.build(buildRule)),
            isA(LazyPath.class)))
        .andDelegateTo(
            new FakeArtifactCacheThatWritesAZipFile(desiredZipEntries));

    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    BuildContext buildContext = ImmutableBuildContext.builder()
        .setActionGraph(new ActionGraph(ImmutableList.of(buildRule)))
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
            CachingBuildEngine.DependencySchedulingOrder.RANDOM,
            CachingBuildEngine.DepFiles.ENABLED,
            256L,
            new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer()));
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
    ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(fileHashCache, pathResolver);

    // The EventBus should be updated with events indicating how the rule was built.
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    FakeBuckEventListener listener = new FakeBuckEventListener();
    buckEventBus.register(listener);

    // Create a dep for the build rule.
    BuildTarget depTarget = BuildTargetFactory.newInstance("//src/com/facebook/orca:lib");
    FakeBuildRule dep = new FakeBuildRule(depTarget, pathResolver);
    FakeBuildRule ruleToTest = new FakeBuildRule(BUILD_TARGET, filesystem, pathResolver, dep);
    RuleKey ruleToTestKey = ruleKeyBuilderFactory.build(ruleToTest);
    filesystem.writeContentsToPath(
        ruleToTestKey.toString(),
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
            CachingBuildEngine.DependencySchedulingOrder.RANDOM,
            CachingBuildEngine.DepFiles.ENABLED,
            256L,
            new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer()));

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
        configureTestEvent(BuildRuleEvent.suspended(dep, ruleKeyBuilderFactory), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.started(ruleToTest),
            buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.suspended(ruleToTest, ruleKeyBuilderFactory),
            buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.resumed(ruleToTest, ruleKeyBuilderFactory),
            buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.finished(
                ruleToTest,
                BuildRuleKeys.of(ruleToTestKey),
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
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ArtifactCache cache = new NoopArtifactCache();
    ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(fileHashCache, pathResolver);

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
    RuleKey depKey = ruleKeyBuilderFactory.build(dep);
    filesystem.writeContentsToPath(
        depKey.toString(),
        BuildInfo.getPathToMetadataDirectory(depTarget)
            .resolve(BuildInfo.METADATA_KEY_FOR_RULE_KEY));
    filesystem.writeContentsToPath(
        new ObjectMapper().writeValueAsString(ImmutableList.of()),
        BuildInfo.getPathToMetadataDirectory(depTarget)
            .resolve(BuildInfo.METADATA_KEY_FOR_RECORDED_PATHS));
    FakeBuildRule ruleToTest = new FakeBuildRule(BUILD_TARGET, filesystem, pathResolver, dep);
    RuleKey ruleToTestKey = ruleKeyBuilderFactory.build(ruleToTest);
    filesystem.writeContentsToPath(
        ruleToTestKey.toString(),
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
            CachingBuildEngine.DependencySchedulingOrder.RANDOM,
            CachingBuildEngine.DepFiles.ENABLED,
            256L,
            new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer()));

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
        configureTestEvent(BuildRuleEvent.suspended(dep, ruleKeyBuilderFactory), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.started(ruleToTest),
            buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.suspended(ruleToTest, ruleKeyBuilderFactory),
            buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.resumed(ruleToTest, ruleKeyBuilderFactory),
            buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.resumed(dep, ruleKeyBuilderFactory),
            buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.finished(
                dep,
                BuildRuleKeys.of(depKey),
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
                BuildRuleKeys.of(ruleToTestKey),
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
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    ArtifactCache cache = new NoopArtifactCache();
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(fileHashCache, pathResolver);

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
    RuleKey transitiveRuntimeDepKey = ruleKeyBuilderFactory.build(transitiveRuntimeDep);
    filesystem.writeContentsToPath(
        transitiveRuntimeDepKey.toString(),
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
    RuleKey runtimeDepKey = ruleKeyBuilderFactory.build(runtimeDep);
    filesystem.writeContentsToPath(
        runtimeDepKey.toString(),
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
    RuleKey ruleToTestKey = ruleKeyBuilderFactory.build(ruleToTest);
    filesystem.writeContentsToPath(
        ruleToTestKey.toString(),
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
            CachingBuildEngine.DependencySchedulingOrder.RANDOM,
            CachingBuildEngine.DepFiles.ENABLED,
            256L,
            new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer()));

    // Run the build.
    BuildResult result = cachingBuildEngine.build(context, ruleToTest).get();
    assertEquals(BuildRuleSuccessType.MATCHING_RULE_KEY, result.getSuccess());

    // Verify the events logged to the BuckEventBus.
    List<BuckEvent> events = listener.getEvents();
    assertThat(events, Matchers.hasSize(12));
    Iterator<BuckEvent> eventIter = events.iterator();
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.started(transitiveRuntimeDep),
            buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.suspended(transitiveRuntimeDep, ruleKeyBuilderFactory),
            buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.started(runtimeDep),
            buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.suspended(runtimeDep, ruleKeyBuilderFactory),
            buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.started(ruleToTest),
            buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.suspended(ruleToTest, ruleKeyBuilderFactory),
            buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.resumed(ruleToTest, ruleKeyBuilderFactory),
            buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.finished(
                ruleToTest,
                BuildRuleKeys.of(ruleToTestKey),
                BuildRuleStatus.SUCCESS,
                CacheResult.localKeyUnchangedHit(),
                Optional.of(BuildRuleSuccessType.MATCHING_RULE_KEY),
                Optional.<HashCode>absent(),
                Optional.<Long>absent()),
            buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.resumed(runtimeDep, ruleKeyBuilderFactory),
            buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.finished(
                runtimeDep,
                BuildRuleKeys.of(runtimeDepKey),
                BuildRuleStatus.SUCCESS,
                CacheResult.localKeyUnchangedHit(),
                Optional.of(BuildRuleSuccessType.MATCHING_RULE_KEY),
                Optional.<HashCode>absent(),
                Optional.<Long>absent()),
            buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.resumed(transitiveRuntimeDep, ruleKeyBuilderFactory),
            buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.finished(
                transitiveRuntimeDep,
                BuildRuleKeys.of(transitiveRuntimeDepKey),
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
    SourcePathResolver resolver =
        new SourcePathResolver(
            new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer()));
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(fileHashCache, resolver);

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
        resolver,
        /* deps */ ImmutableSet.<BuildRule>of(),
        /* buildSteps */ ImmutableList.<Step>of(),
        /* postBuildSteps */ ImmutableList.of(failingStep),
        /* pathToOutputFile */ null);
    filesystem.writeContentsToPath(
        ruleKeyBuilderFactory.build(ruleToTest).toString(),
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
            CachingBuildEngine.DependencySchedulingOrder.RANDOM,
            CachingBuildEngine.DepFiles.ENABLED,
            256L,
            new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer()));

    // Run the build.
    BuildResult result = cachingBuildEngine.build(context, ruleToTest).get();
    assertEquals(BuildRuleSuccessType.MATCHING_RULE_KEY, result.getSuccess());
  }

  @Test
  public void testBuildRuleLocallyWithCacheError() throws Exception {
    SourcePathResolver resolver =
        new SourcePathResolver(
            new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer()));
    ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);

    // Create an artifact cache that always errors out.
    ArtifactCache cache =
        new NoopArtifactCache() {
          @Override
          public CacheResult fetch(RuleKey ruleKey, LazyPath output) {
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
            CachingBuildEngine.DependencySchedulingOrder.RANDOM,
            CachingBuildEngine.DepFiles.ENABLED,
            256L,
            new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer()));

    BuildResult result = cachingBuildEngine.build(buildContext, rule).get();
    assertThat(result.getSuccess(), equalTo(BuildRuleSuccessType.BUILT_LOCALLY));
    assertThat(result.getCacheResult().getType(), equalTo(CacheResultType.ERROR));
  }

  @Test
  public void inputBasedRuleKeyAndArtifactAreWrittenForSupportedRules() throws Exception {
    final ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
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
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    final RuleKey inputRuleKey = new RuleKey("aaaa");
    final Path output = Paths.get("output");
    final BuildRule rule =
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
            CachingBuildEngine.DependencySchedulingOrder.RANDOM,
            CachingBuildEngine.DepFiles.ENABLED,
            256L,
            pathResolver,
            Functions.constant(
                new CachingBuildEngine.RuleKeyFactories(
                    NOOP_RULE_KEY_FACTORY,
                    new FakeRuleKeyBuilderFactory(ImmutableMap.of(
                        rule.getBuildTarget(),
                        inputRuleKey)),
                    NOOP_RULE_KEY_FACTORY,
                    NOOP_DEP_FILE_RULE_KEY_FACTORY)));

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
    ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    InMemoryArtifactCache cache = new InMemoryArtifactCache();
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    final RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(fileHashCache, pathResolver);
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
    final RuleKey inputRuleKey = new RuleKey("aaaa");
    final Path output = Paths.get("output");
    final BuildRule rule =
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
            CachingBuildEngine.DependencySchedulingOrder.RANDOM,
            CachingBuildEngine.DepFiles.ENABLED,
            256L,
            pathResolver,
            Functions.constant(
                new CachingBuildEngine.RuleKeyFactories(
                    ruleKeyBuilderFactory,
                    new FakeRuleKeyBuilderFactory(
                        ImmutableMap.of(rule.getBuildTarget(), inputRuleKey)),
                    NOOP_RULE_KEY_FACTORY,
                    NOOP_DEP_FILE_RULE_KEY_FACTORY)));

    // Run the build.
    BuildResult result = cachingBuildEngine.build(buildContext, rule).get();
    assertEquals(BuildRuleSuccessType.MATCHING_INPUT_BASED_RULE_KEY, result.getSuccess());

    // Verify the actual rule key was updated on disk.
    OnDiskBuildInfo onDiskBuildInfo = buildContext.createOnDiskBuildInfoFor(target, filesystem);
    assertThat(
        onDiskBuildInfo.getRuleKey(BuildInfo.METADATA_KEY_FOR_RULE_KEY),
        equalTo(Optional.of(ruleKeyBuilderFactory.build(rule))));

    // Verify that the artifact is *not* re-cached under the main rule key.
    LazyPath fetchedArtifact = LazyPath.ofInstance(
        tmp.newFile("fetched_artifact.zip").toPath());
    assertThat(
        cache.fetch(ruleKeyBuilderFactory.build(rule), fetchedArtifact).getType(),
        equalTo(CacheResultType.MISS));
  }

  @Test
  public void inputBasedRuleKeyCacheHitAvoidsBuildingLocally() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    InMemoryArtifactCache cache = new InMemoryArtifactCache();
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    final RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(fileHashCache, pathResolver);
    BuildContext buildContext =
        FakeBuildContext.newBuilder()
            .setArtifactCache(cache)
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();

    // Create a simple rule which just writes a file.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    final RuleKey inputRuleKey = new RuleKey("aaaa");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(target)
            .setProjectFilesystem(filesystem)
            .build();
    final Path output = Paths.get("output");
    final BuildRule rule =
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
            CachingBuildEngine.DependencySchedulingOrder.RANDOM,
            CachingBuildEngine.DepFiles.ENABLED,
            256L,
            pathResolver,
            Functions.constant(
                new CachingBuildEngine.RuleKeyFactories(
                    ruleKeyBuilderFactory,
                    new FakeRuleKeyBuilderFactory(
                        ImmutableMap.of(rule.getBuildTarget(), inputRuleKey)),
                    NOOP_RULE_KEY_FACTORY,
                    NOOP_DEP_FILE_RULE_KEY_FACTORY)));

    // Run the build.
    BuildResult result = cachingBuildEngine.build(buildContext, rule).get();
    assertEquals(BuildRuleSuccessType.FETCHED_FROM_CACHE_INPUT_BASED, result.getSuccess());

    // Verify the input-based and actual rule keys were updated on disk.
    OnDiskBuildInfo onDiskBuildInfo = buildContext.createOnDiskBuildInfoFor(target, filesystem);
    assertThat(
        onDiskBuildInfo.getRuleKey(BuildInfo.METADATA_KEY_FOR_RULE_KEY),
        equalTo(Optional.of(ruleKeyBuilderFactory.build(rule))));
    assertThat(
        onDiskBuildInfo.getRuleKey(BuildInfo.METADATA_KEY_FOR_INPUT_BASED_RULE_KEY),
        equalTo(Optional.of(inputRuleKey)));

    // Verify that the artifact is re-cached correctly under the main rule key.
    Path fetchedArtifact = tmp.newFile("fetched_artifact.zip").toPath();
    assertThat(
        cache.fetch(ruleKeyBuilderFactory.build(rule), LazyPath.ofInstance(fetchedArtifact))
            .getType(),
        equalTo(CacheResultType.HIT));
    assertEquals(
        new ZipInspector(artifact).getZipFileEntries(),
        new ZipInspector(fetchedArtifact).getZipFileEntries());
    MoreAsserts.assertContentsEqual(artifact, fetchedArtifact);
  }

  @Test
  public void depFileRuleKeyAndDepFileAreWrittenForSupportedRules() throws Exception {
    final ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    final DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    InMemoryArtifactCache cache = new InMemoryArtifactCache();
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    BuildContext buildContext =
        FakeBuildContext.newBuilder()
            .setArtifactCache(cache)
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();
    RuleKeyBuilderFactory factory = new DefaultRuleKeyBuilderFactory(fileHashCache, pathResolver);
    DefaultDependencyFileRuleKeyBuilderFactory depFilefactory =
        new DefaultDependencyFileRuleKeyBuilderFactory(
            fileHashCache,
            pathResolver,
            factory);

    // Use a genrule to produce the input file.
    final Genrule genrule =
        (Genrule) GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("input")
            .build(resolver, filesystem);
    final Path input = Preconditions.checkNotNull(genrule.getPathToOutput());
    filesystem.writeContentsToPath("contents", input);

    // Create a simple rule which just writes a file.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(target)
            .setProjectFilesystem(filesystem)
            .build();
    final Path output = Paths.get("output");
    final BuildRule rule =
        new DepFileBuildRule(params, pathResolver) {
          @AddToRuleKey
          private final SourcePath path = new BuildTargetSourcePath(genrule.getBuildTarget());
          @Override
          public ImmutableList<Step> getBuildSteps(
              BuildContext context,
              BuildableContext buildableContext) {
            return ImmutableList.<Step>of(
                new WriteFileStep(filesystem, "", output, /* executable */ false));
          }
          @Override
          public ImmutableList<SourcePath> getInputsAfterBuildingLocally() {
            return ImmutableList.<SourcePath>of(new PathSourcePath(filesystem, input));
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
            CachingBuildEngine.DependencySchedulingOrder.RANDOM,
            CachingBuildEngine.DepFiles.ENABLED,
            256L,
            pathResolver,
            Functions.constant(
                new CachingBuildEngine.RuleKeyFactories(
                    factory,
                    factory,
                    factory,
                    depFilefactory)));

    // Run the build.
    RuleKey depFileRuleKey = depFilefactory.build(rule, ImmutableList.of(input));
    BuildResult result = cachingBuildEngine.build(buildContext, rule).get();
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, getSuccess(result));

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
    CacheResult cacheResult =
        cache.fetch(factory.build(rule), LazyPath.ofInstance(fetchedArtifact));
    assertThat(
        cacheResult.getType(),
        equalTo(CacheResultType.HIT));
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
    final ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    final DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    InMemoryArtifactCache cache = new InMemoryArtifactCache();
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    final RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(fileHashCache, pathResolver);
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
    final RuleKey depFileRuleKey = new RuleKey("aaaa");
    final Path output = Paths.get("output");
    filesystem.touch(output);
    final BuildRule rule =
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
          public ImmutableList<SourcePath> getInputsAfterBuildingLocally() {
            return ImmutableList.<SourcePath>of(new PathSourcePath(filesystem, input));
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
            CachingBuildEngine.DependencySchedulingOrder.RANDOM,
            CachingBuildEngine.DepFiles.ENABLED,
            256L,
            pathResolver,
            Functions.constant(
                new CachingBuildEngine.RuleKeyFactories(
                    ruleKeyBuilderFactory,
                    NOOP_RULE_KEY_FACTORY,
                    NOOP_RULE_KEY_FACTORY,
                    new FakeRuleKeyBuilderFactory(
                        ImmutableMap.of(rule.getBuildTarget(), depFileRuleKey),
                        fileHashCache))));

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
    final ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    InMemoryArtifactCache cache = new InMemoryArtifactCache();
    BuildContext buildContext =
        FakeBuildContext.newBuilder()
            .setArtifactCache(cache)
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();
    FileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    RuleKeyBuilderFactory factory = new DefaultRuleKeyBuilderFactory(fileHashCache, pathResolver);
    DefaultDependencyFileRuleKeyBuilderFactory depFilefactory =
        new DefaultDependencyFileRuleKeyBuilderFactory(
            fileHashCache,
            pathResolver,
            factory);

    // Use a genrule to produce the input file.
    final Genrule genrule =
        (Genrule) GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("input")
            .build(resolver, filesystem);
    final Path input = Preconditions.checkNotNull(genrule.getPathToOutput());

    // Create a simple rule which just writes a file.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(target)
            .setProjectFilesystem(filesystem)
            .build();
    final Path output = Paths.get("output");
    BuildRule rule =
        new DepFileBuildRule(params, pathResolver) {
          @AddToRuleKey
          private final SourcePath path = new BuildTargetSourcePath(genrule.getBuildTarget());
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
          public ImmutableList<SourcePath> getInputsAfterBuildingLocally() {
            return ImmutableList.<SourcePath>of(new PathSourcePath(filesystem, input));
          }
          @Override
          public Path getPathToOutput() {
            return output;
          }
        };

    // Prepare an input file that should appear in the dep file.
    filesystem.writeContentsToPath("something", input);
    RuleKey depFileRuleKey = depFilefactory.build(rule, ImmutableList.of(input));

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
            CachingBuildEngine.DependencySchedulingOrder.RANDOM,
            CachingBuildEngine.DepFiles.ENABLED,
            256L,
            pathResolver,
            Functions.constant(
                new CachingBuildEngine.RuleKeyFactories(
                    factory,
                    factory,
                    factory,
                    depFilefactory)));
    BuildResult result = cachingBuildEngine.build(buildContext, rule).get();
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, getSuccess(result));
  }

  @Test
  public void depFileDeletedInputCausesRebuild() throws Exception {
    final ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    InMemoryArtifactCache cache = new InMemoryArtifactCache();
    BuildContext buildContext =
        FakeBuildContext.newBuilder()
            .setArtifactCache(cache)
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();
    FileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    RuleKeyBuilderFactory factory = new DefaultRuleKeyBuilderFactory(fileHashCache, pathResolver);
    DefaultDependencyFileRuleKeyBuilderFactory depFilefactory =
        new DefaultDependencyFileRuleKeyBuilderFactory(
            fileHashCache,
            pathResolver,
            NOOP_RULE_KEY_FACTORY);

    // Use a genrule to produce the input file.
    final Genrule genrule =
        (Genrule) GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("input")
            .build(resolver, filesystem);
    final Path input = Preconditions.checkNotNull(genrule.getPathToOutput());

    // Create a simple rule which just writes a file.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(target)
            .setProjectFilesystem(filesystem)
            .build();
    final Path output = Paths.get("output");
    BuildRule rule =
        new DepFileBuildRule(params, pathResolver) {
          @AddToRuleKey
          private final SourcePath path = new BuildTargetSourcePath(genrule.getBuildTarget());
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
          public ImmutableList<SourcePath> getInputsAfterBuildingLocally() {
            return ImmutableList.of();
          }
          @Override
          public Path getPathToOutput() {
            return output;
          }
        };

    // Prepare an input file that should appear in the dep file.
    filesystem.writeContentsToPath("something", input);
    RuleKey depFileRuleKey = depFilefactory.build(rule, ImmutableList.of(input));

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
            CachingBuildEngine.DependencySchedulingOrder.RANDOM,
            CachingBuildEngine.DepFiles.ENABLED,
            256L,
            pathResolver,
            Functions.constant(
                new CachingBuildEngine.RuleKeyFactories(
                    factory,
                    factory,
                    factory,
                    depFilefactory)));
    BuildResult result = cachingBuildEngine.build(buildContext, rule).get();
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, getSuccess(result));
  }

  @Test
  public void manifestIsWrittenWhenBuiltLocally() throws Exception {
    final ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    InMemoryArtifactCache cache = new InMemoryArtifactCache();
    BuildContext buildContext =
        FakeBuildContext.newBuilder()
            .setArtifactCache(cache)
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    RuleKeyBuilderFactory factory = new DefaultRuleKeyBuilderFactory(fileHashCache, pathResolver);
    DefaultDependencyFileRuleKeyBuilderFactory depFilefactory =
        new DefaultDependencyFileRuleKeyBuilderFactory(
            fileHashCache,
            pathResolver,
            NOOP_RULE_KEY_FACTORY);

    // Use a genrule to produce the input file.
    final Genrule genrule =
        (Genrule) GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("input")
            .build(resolver, filesystem);
    final Path input = Preconditions.checkNotNull(genrule.getPathToOutput());
    filesystem.writeContentsToPath("contents", input);

    // Create a simple rule which just writes a file.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(target)
            .setProjectFilesystem(filesystem)
            .build();
    final Path output = Paths.get("output");
    BuildRule rule =
        new DepFileBuildRule(params, pathResolver) {
          @AddToRuleKey
          private final SourcePath path = new BuildTargetSourcePath(genrule.getBuildTarget());
          @Override
          public ImmutableList<Step> getBuildSteps(
              BuildContext context,
              BuildableContext buildableContext) {
            return ImmutableList.<Step>of(
                new WriteFileStep(filesystem, "", output, /* executable */ false));
          }
          @Override
          public ImmutableList<SourcePath> getInputsAfterBuildingLocally() {
            return ImmutableList.<SourcePath>of(new PathSourcePath(filesystem, input));
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
            CachingBuildEngine.DependencySchedulingOrder.RANDOM,
            CachingBuildEngine.DepFiles.CACHE,
            256L,
            pathResolver,
            Functions.constant(
                new CachingBuildEngine.RuleKeyFactories(
                    factory,
                    factory,
                    factory,
                    depFilefactory)));

    // Run the build.
    BuildResult result = cachingBuildEngine.build(buildContext, rule).get();
    assertThat(
        getSuccess(result),
        equalTo(BuildRuleSuccessType.BUILT_LOCALLY));

    OnDiskBuildInfo onDiskBuildInfo = buildContext.createOnDiskBuildInfoFor(target, filesystem);
    RuleKey depFileRuleKey =
        onDiskBuildInfo.getRuleKey(BuildInfo.METADATA_KEY_FOR_DEP_FILE_RULE_KEY).get();

    // Verify that the manifest written to the cache is correct.
    Path fetchedManifest = tmp.newFile("manifest").toPath();
    CacheResult cacheResult =
        cache.fetch(
            cachingBuildEngine.getManifestRuleKey(rule),
            LazyPath.ofInstance(fetchedManifest));
    assertThat(
        cacheResult.getType(),
        equalTo(CacheResultType.HIT));
    Manifest manifest = loadManifest(fetchedManifest);
    assertThat(
        manifest.toMap(),
        equalTo(
            ImmutableMap.of(
                depFileRuleKey,
                ImmutableMap.of(input.toString(), fileHashCache.get(input)))));

    // Verify that the artifact is also cached via the dep file rule key.
    Path fetchedArtifact = tmp.newFile("artifact").toPath();
    cacheResult =
        cache.fetch(
            depFileRuleKey,
            LazyPath.ofInstance(fetchedArtifact));
    assertThat(
        cacheResult.getType(),
        equalTo(CacheResultType.HIT));
  }

  @Test
  public void manifestIsUpdatedWhenBuiltLocally() throws Exception {
    final ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    InMemoryArtifactCache cache = new InMemoryArtifactCache();
    BuildContext buildContext =
        FakeBuildContext.newBuilder()
            .setArtifactCache(cache)
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    RuleKeyBuilderFactory factory = new DefaultRuleKeyBuilderFactory(fileHashCache, pathResolver);
    DefaultDependencyFileRuleKeyBuilderFactory depFilefactory =
        new DefaultDependencyFileRuleKeyBuilderFactory(
            fileHashCache,
            pathResolver,
            NOOP_RULE_KEY_FACTORY);

    // Use a genrule to produce the input file.
    final Genrule genrule =
        (Genrule) GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("input")
            .build(resolver, filesystem);
    final Path input = Preconditions.checkNotNull(genrule.getPathToOutput());
    filesystem.writeContentsToPath("contents", input);

    // Create a simple rule which just writes a file.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(target)
            .setProjectFilesystem(filesystem)
            .build();
    final Path output = Paths.get("output");
    BuildRule rule =
        new DepFileBuildRule(params, pathResolver) {
          @AddToRuleKey
          private final SourcePath path = new BuildTargetSourcePath(genrule.getBuildTarget());
          @Override
          public ImmutableList<Step> getBuildSteps(
              BuildContext context,
              BuildableContext buildableContext) {
            return ImmutableList.<Step>of(
                new WriteFileStep(filesystem, "", output, /* executable */ false));
          }
          @Override
          public ImmutableList<SourcePath> getInputsAfterBuildingLocally() {
            return ImmutableList.<SourcePath>of(new PathSourcePath(filesystem, input));
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
            CachingBuildEngine.DependencySchedulingOrder.RANDOM,
            CachingBuildEngine.DepFiles.CACHE,
            256L,
            pathResolver,
            Functions.constant(
                new CachingBuildEngine.RuleKeyFactories(
                    factory,
                    factory,
                    factory,
                    depFilefactory)));

    // Seed the cache with an existing manifest with a dummy entry.
    Manifest manifest = Manifest.fromMap(
        ImmutableMap.of(new RuleKey("abcd"), ImmutableMap.of("some/path.h", HashCode.fromInt(12))));
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    try (GZIPOutputStream outputStream = new GZIPOutputStream(byteArrayOutputStream)) {
      manifest.serialize(outputStream);
    }
    cache.store(
        ImmutableSet.of(cachingBuildEngine.getManifestRuleKey(rule)),
        ImmutableMap.<String, String>of(),
        byteArrayOutputStream.toByteArray());

    // Run the build.
    BuildResult result = cachingBuildEngine.build(buildContext, rule).get();
    assertThat(
        getSuccess(result),
        equalTo(BuildRuleSuccessType.BUILT_LOCALLY));

    OnDiskBuildInfo onDiskBuildInfo = buildContext.createOnDiskBuildInfoFor(target, filesystem);
    RuleKey depFileRuleKey =
        onDiskBuildInfo.getRuleKey(BuildInfo.METADATA_KEY_FOR_DEP_FILE_RULE_KEY).get();

    // Verify that the manifest written to the cache is correct.
    Path fetchedManifest = tmp.newFile("manifest").toPath();
    CacheResult cacheResult =
        cache.fetch(
            cachingBuildEngine.getManifestRuleKey(rule),
            LazyPath.ofInstance(fetchedManifest));
    assertThat(
        cacheResult.getType(),
        equalTo(CacheResultType.HIT));
    manifest = loadManifest(fetchedManifest);
    assertThat(
        manifest.toMap(),
        equalTo(
            ImmutableMap.of(
                depFileRuleKey,
                ImmutableMap.of(input.toString(), fileHashCache.get(input)),
                new RuleKey("abcd"),
                ImmutableMap.of("some/path.h", HashCode.fromInt(12)))));

    // Verify that the artifact is also cached via the dep file rule key.
    Path fetchedArtifact = tmp.newFile("artifact").toPath();
    cacheResult =
        cache.fetch(
            depFileRuleKey,
            LazyPath.ofInstance(fetchedArtifact));
    assertThat(
        cacheResult.getType(),
        equalTo(CacheResultType.HIT));
  }

  @Test
  public void manifestIsTruncatedWhenGrowingPastSizeLimit() throws Exception {
    final ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    InMemoryArtifactCache cache = new InMemoryArtifactCache();
    BuildContext buildContext =
        FakeBuildContext.newBuilder()
            .setArtifactCache(cache)
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    RuleKeyBuilderFactory factory = new DefaultRuleKeyBuilderFactory(fileHashCache, pathResolver);
    DefaultDependencyFileRuleKeyBuilderFactory depFilefactory =
        new DefaultDependencyFileRuleKeyBuilderFactory(
            fileHashCache,
            pathResolver,
            NOOP_RULE_KEY_FACTORY);

    // Use a genrule to produce the input file.
    final Genrule genrule =
        (Genrule) GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("input")
            .build(resolver, filesystem);
    final Path input = Preconditions.checkNotNull(genrule.getPathToOutput());
    filesystem.writeContentsToPath("contents", input);

    // Create a simple rule which just writes a file.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(target)
            .setProjectFilesystem(filesystem)
            .build();
    final Path output = Paths.get("output");
    BuildRule rule =
        new DepFileBuildRule(params, pathResolver) {
          @AddToRuleKey
          private final SourcePath path = new BuildTargetSourcePath(genrule.getBuildTarget());
          @Override
          public ImmutableList<Step> getBuildSteps(
              BuildContext context,
              BuildableContext buildableContext) {
            return ImmutableList.<Step>of(
                new WriteFileStep(filesystem, "", output, /* executable */ false));
          }
          @Override
          public ImmutableList<SourcePath> getInputsAfterBuildingLocally() {
            return ImmutableList.<SourcePath>of(new PathSourcePath(filesystem, input));
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
            CachingBuildEngine.DependencySchedulingOrder.RANDOM,
            CachingBuildEngine.DepFiles.CACHE,
            1L,
            pathResolver,
            Functions.constant(
                new CachingBuildEngine.RuleKeyFactories(
                    factory,
                    factory,
                    factory,
                    depFilefactory)));

    // Seed the cache with an existing manifest with a dummy entry so that it's already at the max
    // size.
    Manifest manifest = Manifest.fromMap(
        ImmutableMap.of(new RuleKey("abcd"), ImmutableMap.of("some/path.h", HashCode.fromInt(12))));
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    try (GZIPOutputStream outputStream = new GZIPOutputStream(byteArrayOutputStream)) {
      manifest.serialize(outputStream);
    }
    cache.store(
        ImmutableSet.of(cachingBuildEngine.getManifestRuleKey(rule)),
        ImmutableMap.<String, String>of(),
        byteArrayOutputStream.toByteArray());

    // Run the build.
    BuildResult result = cachingBuildEngine.build(buildContext, rule).get();
    assertThat(
        getSuccess(result),
        equalTo(BuildRuleSuccessType.BUILT_LOCALLY));

    OnDiskBuildInfo onDiskBuildInfo = buildContext.createOnDiskBuildInfoFor(target, filesystem);
    RuleKey depFileRuleKey =
        onDiskBuildInfo.getRuleKey(BuildInfo.METADATA_KEY_FOR_DEP_FILE_RULE_KEY).get();

    // Verify that the manifest is truncated and now only contains the newly written entry.
    Path fetchedManifest = tmp.newFile("manifest").toPath();
    CacheResult cacheResult =
        cache.fetch(
            cachingBuildEngine.getManifestRuleKey(rule),
            LazyPath.ofInstance(fetchedManifest));
    assertThat(
        cacheResult.getType(),
        equalTo(CacheResultType.HIT));
    manifest = loadManifest(fetchedManifest);
    assertThat(
        manifest.toMap(),
        equalTo(
            ImmutableMap.of(
                depFileRuleKey,
                ImmutableMap.of(input.toString(), fileHashCache.get(input)))));
  }

  @Test
  public void manifestBasedCacheHit() throws Exception {
    final ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    InMemoryArtifactCache cache = new InMemoryArtifactCache();
    BuildContext buildContext =
        FakeBuildContext.newBuilder()
            .setArtifactCache(cache)
            .setJavaPackageFinder(new FakeJavaPackageFinder())
            .setActionGraph(new ActionGraph(ImmutableList.<BuildRule>of()))
            .build();
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    RuleKeyBuilderFactory factory = new DefaultRuleKeyBuilderFactory(fileHashCache, pathResolver);
    DefaultDependencyFileRuleKeyBuilderFactory depFilefactory =
        new DefaultDependencyFileRuleKeyBuilderFactory(
            fileHashCache,
            pathResolver,
            NOOP_RULE_KEY_FACTORY);

    // Prepare an input file that should appear in the dep file.
    final Genrule genrule =
        (Genrule) GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("input")
            .build(resolver, filesystem);
    final Path input = Preconditions.checkNotNull(genrule.getPathToOutput());
    filesystem.writeContentsToPath("contents", input);

    // Create a simple rule which just writes a file.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(target)
            .setProjectFilesystem(filesystem)
            .build();
    final Path output = Paths.get("output");
    BuildRule rule =
        new DepFileBuildRule(params, pathResolver) {
          @AddToRuleKey
          private final SourcePath path = new BuildTargetSourcePath(genrule.getBuildTarget());
          @Override
          public ImmutableList<Step> getBuildSteps(
              BuildContext context,
              BuildableContext buildableContext) {
            return ImmutableList.<Step>of(
                new WriteFileStep(filesystem, "", output, /* executable */ false));
          }
          @Override
          public ImmutableList<SourcePath> getInputsAfterBuildingLocally() {
            return ImmutableList.<SourcePath>of(new PathSourcePath(filesystem, input));
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
            CachingBuildEngine.DependencySchedulingOrder.RANDOM,
            CachingBuildEngine.DepFiles.CACHE,
            256L,
            pathResolver,
            Functions.constant(
                new CachingBuildEngine.RuleKeyFactories(
                    factory,
                    factory,
                    factory,
                    depFilefactory)));

    // Seed the cache with the manifest and a referenced artifact.
    RuleKey artifactKey = new RuleKey("bbbb");
    Manifest manifest = new Manifest();
    manifest.addEntry(
        fileHashCache,
        artifactKey,
        pathResolver,
        ImmutableSet.<SourcePath>of(new PathSourcePath(filesystem, input)),
        ImmutableSet.<SourcePath>of(new PathSourcePath(filesystem, input)));
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    try (GZIPOutputStream outputStream = new GZIPOutputStream(byteArrayOutputStream)) {
      manifest.serialize(outputStream);
    }
    cache.store(
        ImmutableSet.of(cachingBuildEngine.getManifestRuleKey(rule)),
        ImmutableMap.<String, String>of(),
        byteArrayOutputStream.toByteArray());
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
        ImmutableSet.of(artifactKey),
        ImmutableMap.<String, String>of(),
        artifact);

    // Run the build.
    BuildResult result = cachingBuildEngine.build(buildContext, rule).get();
    assertThat(
        getSuccess(result),
        equalTo(BuildRuleSuccessType.FETCHED_FROM_CACHE_MANIFEST_BASED));
  }

  @Test
  public void buildingRuleLocallyInvalidatesOutputs() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
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
    assertTrue(fileHashCache.willGet(output));

    // Create a simple rule which just writes something new to the output file.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(target)
            .setProjectFilesystem(filesystem)
            .build();
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    BuildRule rule =
        new WriteFile(params, pathResolver, "something else", output, /* executable */ false);

    // Create the build engine.
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            fileHashCache,
            CachingBuildEngine.BuildMode.SHALLOW,
            CachingBuildEngine.DependencySchedulingOrder.RANDOM,
            CachingBuildEngine.DepFiles.ENABLED,
            256L,
            new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer()));

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
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
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
                .setDeclaredDeps(ImmutableSortedSet.of(dep1))
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
                .setDeclaredDeps(ImmutableSortedSet.of(dep3))
                .setProjectFilesystem(filesystem)
                .build(),
            pathResolver,
            ImmutableList.<Step>of(new SleepStep(300)),
            /* output */ null);

    // Create the top-level rule which pulls in the two sides of the dep tree.
    BuildRule rule =
        new RuleWithSteps(
            new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule"))
                .setDeclaredDeps(ImmutableSortedSet.of(dep2, dep4))
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
            CachingBuildEngine.DependencySchedulingOrder.RANDOM,
            CachingBuildEngine.DepFiles.ENABLED,
            256L,
            new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer()));

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
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
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
                .setDeclaredDeps(ImmutableSortedSet.of(dep1))
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
                .setDeclaredDeps(ImmutableSortedSet.of(dep3))
                .setProjectFilesystem(filesystem)
                .build(),
            pathResolver,
            ImmutableList.<Step>of(new SleepStep(300)),
            /* output */ null);

    // Create the top-level rule which pulls in the two sides of the dep tree.
    BuildRule rule =
        new RuleWithSteps(
            new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule"))
                .setDeclaredDeps(ImmutableSortedSet.of(dep2, dep4))
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
            CachingBuildEngine.DependencySchedulingOrder.RANDOM,
            CachingBuildEngine.DepFiles.ENABLED,
            256L,
            new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer()));

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
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    final DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    InMemoryArtifactCache cache = new InMemoryArtifactCache();
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(fileHashCache, pathResolver);
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
    FakeAbiRuleBuildRule rule = new FakeAbiRuleBuildRule(params, pathResolver);
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            fileHashCache,
            CachingBuildEngine.BuildMode.SHALLOW,
            CachingBuildEngine.DependencySchedulingOrder.RANDOM,
            CachingBuildEngine.DepFiles.ENABLED,
            256L,
            pathResolver,
            Functions.constant(
                new CachingBuildEngine.RuleKeyFactories(
                    ruleKeyBuilderFactory,
                    NOOP_RULE_KEY_FACTORY,
                    new AbiRuleKeyBuilderFactory(
                        fileHashCache,
                        pathResolver,
                        ruleKeyBuilderFactory),
                    NOOP_DEP_FILE_RULE_KEY_FACTORY)));

    BuildResult result = cachingBuildEngine.build(buildContext, rule).get();
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());

    OnDiskBuildInfo onDiskBuildInfo = buildContext.createOnDiskBuildInfoFor(target, filesystem);
    Optional<RuleKey> abiRuleKey =
        onDiskBuildInfo.getRuleKey(BuildInfo.METADATA_KEY_FOR_ABI_RULE_KEY);
    assertThat(abiRuleKey.isPresent(), is(true));

    // Verify that the dep file rule key and dep file were written to the cached artifact.
    Path fetchedArtifact = tmp.newFile("fetched_artifact.zip").toPath();
    CacheResult cacheResult =
        cache.fetch(ruleKeyBuilderFactory.build(rule), LazyPath.ofInstance(fetchedArtifact));
    assertThat(
        cacheResult.getType(),
        equalTo(CacheResultType.HIT));
    assertThat(
        cacheResult.getMetadata().get(BuildInfo.METADATA_KEY_FOR_ABI_RULE_KEY),
        equalTo(abiRuleKey.get().toString()));
  }

  @Test
  public void abiRuleKeyMatchAvoidsBuilding() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    DefaultFileHashCache fileHashCache = new DefaultFileHashCache(filesystem);
    InMemoryArtifactCache cache = new InMemoryArtifactCache();
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    final RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(fileHashCache, pathResolver);
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
    FakeAbiRuleBuildRule rule = new FakeAbiRuleBuildRule(params, pathResolver);
    final AbiRuleKeyBuilderFactory abiRuleKeyBuilderFactory = new AbiRuleKeyBuilderFactory(
        fileHashCache,
        pathResolver,
        ruleKeyBuilderFactory);
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            fileHashCache,
            CachingBuildEngine.BuildMode.SHALLOW,
            CachingBuildEngine.DependencySchedulingOrder.RANDOM,
            CachingBuildEngine.DepFiles.ENABLED,
            256L,
            pathResolver,
            Functions.constant(
                new CachingBuildEngine.RuleKeyFactories(
                    ruleKeyBuilderFactory,
                    NOOP_RULE_KEY_FACTORY,
                    abiRuleKeyBuilderFactory,
                    NOOP_DEP_FILE_RULE_KEY_FACTORY)));

    // Prepopulate the dep file rule key and dep file.
    filesystem.writeContentsToPath(
        abiRuleKeyBuilderFactory.build(rule).toString(),
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
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
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
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    FakeAbiRuleBuildRule rule = new FakeAbiRuleBuildRule(params, pathResolver);
    final AbiRuleKeyBuilderFactory abiRuleKeyBuilderFactory = new AbiRuleKeyBuilderFactory(
        fileHashCache,
        pathResolver,
        NOOP_RULE_KEY_FACTORY);
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            fileHashCache,
            CachingBuildEngine.BuildMode.SHALLOW,
            CachingBuildEngine.DependencySchedulingOrder.RANDOM,
            CachingBuildEngine.DepFiles.ENABLED,
            256L,
            pathResolver,
            Functions.constant(
                new CachingBuildEngine.RuleKeyFactories(
                    NOOP_RULE_KEY_FACTORY,
                    NOOP_RULE_KEY_FACTORY,
                    abiRuleKeyBuilderFactory,
                    NOOP_DEP_FILE_RULE_KEY_FACTORY)));

    // Prepopulate the dep file rule key and dep file.
    filesystem.writeContentsToPath(
        new StringBuilder(abiRuleKeyBuilderFactory.build(rule).toString())
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
  public void getNumRulesToBuild() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    BuildRule rule3 =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule3"))
            .setOut("out3")
            .build(resolver);
    BuildRule rule2 =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule2"))
            .setOut("out2")
            .setSrcs(
                ImmutableList.<SourcePath>of(
                    new BuildTargetSourcePath(rule3.getBuildTarget())))
            .build(resolver);
    BuildRule rule1 =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule1"))
            .setOut("out1")
            .setSrcs(
                ImmutableList.<SourcePath>of(
                    new BuildTargetSourcePath(rule2.getBuildTarget())))
            .build(resolver);

    // Create the build engine.
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            new NullFileHashCache(),
            CachingBuildEngine.BuildMode.SHALLOW,
            CachingBuildEngine.DependencySchedulingOrder.RANDOM,
            CachingBuildEngine.DepFiles.ENABLED,
            256L,
            new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer()));

    assertThat(
        cachingBuildEngine.getNumRulesToBuild(ImmutableList.of(rule1)),
        equalTo(3));
  }


  // TODO(bolinfest): Test that when the success files match, nothing is built and nothing is
  // written back to the cache.

  // TODO(bolinfest): Test that when the value in the success file does not agree with the current
  // value, the rule is rebuilt and the result is written back to the cache.

  // TODO(bolinfest): Test that a failure when executing the build steps is propagated
  // appropriately.

  // TODO(bolinfest): Test what happens when the cache's methods throw an exception.

  private BuildRule createRule(
      ProjectFilesystem filesystem,
      SourcePathResolver resolver,
      ImmutableSet<BuildRule> deps,
      List<Step> buildSteps,
      ImmutableList<Step> postBuildSteps,
      @Nullable String pathToOutputFile) {
    Comparator<BuildRule> comparator = RetainOrderComparator.createComparator(deps);
    ImmutableSortedSet<BuildRule> sortedDeps = ImmutableSortedSet.copyOf(comparator, deps);

    BuildRuleParams buildRuleParams = new FakeBuildRuleParamsBuilder(BUILD_TARGET)
        .setProjectFilesystem(filesystem)
        .setDeclaredDeps(sortedDeps)
        .build();

    return new BuildableAbstractCachingBuildRule(
        buildRuleParams,
        resolver,
        pathToOutputFile,
        buildSteps,
        postBuildSteps);
  }

  private Manifest loadManifest(Path path) throws IOException {
    try (InputStream inputStream =
             new GZIPInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
      return new Manifest(inputStream);
    }
  }

  private BuildRuleSuccessType getSuccess(BuildResult result) {
    switch (result.getStatus()) {
      case FAIL:
        throw Throwables.propagate(Preconditions.checkNotNull(result.getFailure()));
      case CANCELED:
        throw new RuntimeException("result is canceled");
      case SUCCESS:
        return result.getSuccess();
      default:
        throw new IllegalStateException();
    }
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
    public CacheResult fetch(RuleKey ruleKey, LazyPath file) throws InterruptedException {
      try {
        writeEntriesToZip(file.get(), ImmutableMap.copyOf(desiredEntries));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      return CacheResult.hit("dir");
    }

    @Override
    public ListenableFuture<Void> store(
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
    public void close() {
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
        // We want deterministic ZIPs, so avoid mtimes. -1 is timzeone independent, 0 is not.
        entry.setTime(ZipConstants.getFakeTime());
        // We set the external attributes to this magic value which seems to match the attributes
        // of entries created by {@link InMemoryArtifactCache}.
        entry.setExternalAttributes(420 << 16);
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
