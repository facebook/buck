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
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.isA;
import static org.easymock.EasyMock.newCapture;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cli.CommandEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.FakeBuckEventListener;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.JavaPackageFinder;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.RuleMap;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.FileHashCache;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.concurrent.MoreFutures;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.easymock.IAnswer;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.annotation.Nullable;

/**
 * Ensuring that build rule caching works correctly in Buck is imperative for both its performance
 * and correctness.
 */
public class CachingBuildEngineTest extends EasyMockSupport {

  private static final BuildTarget buildTarget =
      BuildTarget.builder("//src/com/facebook/orca", "orca").build();

  @Rule
  public TemporaryFolder tmp = new DebuggableTemporaryFolder();

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
    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver());

    // Create a dep for the build rule.
    BuildTarget depTarget = BuildTargetFactory.newInstance("//src/com/facebook/orca:lib");
    FakeBuildRule dep = new FakeBuildRule(depTarget, resolver);
    dep.setRuleKey(new RuleKey("19d2558a6bd3a34fb3f95412de9da27ed32fe208"));

    // The EventBus should be updated with events indicating how the rule was built.
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();

    FakeBuckEventListener listener = new FakeBuckEventListener();
    buckEventBus.register(listener);

    // Create an ArtifactCache whose expectations will be set later.
    ArtifactCache mockArtifactCache = createMock(ArtifactCache.class);

    ArtifactCache artifactCache = new LoggingArtifactCacheDecorator(buckEventBus)
        .decorate(mockArtifactCache);

    // Replay the mocks to instantiate the AbstractCachingBuildRule.
    replayAll();
    String pathToOutputFile = "buck-out/gen/src/com/facebook/orca/some_file";
    List<Step> buildSteps = Lists.newArrayList();
    BuildRule ruleToTest = createRule(
        resolver,
        ImmutableSet.<BuildRule>of(dep),
        buildSteps,
        /* postBuildSteps */ ImmutableList.<Step>of(),
        pathToOutputFile);
    verifyAll();
    resetAll();

    // The BuildContext that will be used by the rule's build() method.
    BuildContext context = createMock(BuildContext.class);
    expect(context.getArtifactCache()).andReturn(artifactCache).times(2);
    expect(context.getProjectRoot()).andReturn(createMock(Path.class));

    // Configure the OnDiskBuildInfo.
    OnDiskBuildInfo onDiskBuildInfo = new FakeOnDiskBuildInfo();
    expect(context.createOnDiskBuildInfoFor(buildTarget)).andReturn(onDiskBuildInfo);

    // Configure the BuildInfoRecorder.
    BuildInfoRecorder buildInfoRecorder = createNiceMock(BuildInfoRecorder.class);
    Capture<RuleKey> ruleKeyForRecorder = newCapture();
    expect(
        context.createBuildInfoRecorder(
            eq(buildTarget),
            capture(ruleKeyForRecorder),
            /* ruleKeyWithoutDepsForRecorder */ anyObject(RuleKey.class)))
        .andReturn(buildInfoRecorder);
    expect(
        buildInfoRecorder.fetchArtifactForBuildable(
            anyObject(File.class),
            eq(artifactCache)))
        .andReturn(CacheResult.miss());

    // Set the requisite expectations to build the rule.
    expect(context.getEventBus()).andReturn(buckEventBus).anyTimes();
    expect(context.getStepRunner()).andReturn(createStepRunner(buckEventBus)).anyTimes();

    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            CachingBuildEngine.BuildMode.SHALLOW);

    // Add a build step so we can verify that the steps are executed.
    Step buildStep = createMock(Step.class);
    expect(buildStep.getDescription(anyObject(ExecutionContext.class)))
        .andReturn("Some Description")
        .anyTimes();
    expect(buildStep.getShortName()).andReturn("Some Short Name").anyTimes();
    expect(buildStep.execute(anyObject(ExecutionContext.class))).andReturn(0);
    buildSteps.add(buildStep);

    // These methods should be invoked after the rule is built locally.
    buildInfoRecorder.recordArtifact(Paths.get(pathToOutputFile));
    buildInfoRecorder.writeMetadataToDisk(/* clearExistingMetadata */ true);
    buildInfoRecorder.performUploadToArtifactCache(artifactCache, buckEventBus);

    // Attempting to build the rule should force a rebuild due to a cache miss.
    replayAll();

    cachingBuildEngine.setBuildRuleResult(
        dep,
        BuildRuleSuccessType.FETCHED_FROM_CACHE,
        CacheResult.skip());

    BuildResult result = cachingBuildEngine.build(context, ruleToTest).get();
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());
    buckEventBus.post(CommandEvent.finished("build", ImmutableList.<String>of(), false, 0));
    verifyAll();

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
                Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)),
            buckEventBus),
        events.get(events.size() - 2));
  }

  /**
   * Rebuild a rule where one if its dependencies has been modified such that its RuleKey has
   * changed, but its ABI is the same.
   */
  @Test
  public void testAbiRuleCanAvoidRebuild()
      throws InterruptedException, ExecutionException, IOException {
    BuildRuleParams buildRuleParams = new FakeBuildRuleParamsBuilder(buildTarget).build();
    TestAbstractCachingBuildRule buildRule =
        new TestAbstractCachingBuildRule(
            buildRuleParams,
            new SourcePathResolver(new BuildRuleResolver()));

    // The EventBus should be updated with events indicating how the rule was built.
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    FakeBuckEventListener listener = new FakeBuckEventListener();
    buckEventBus.register(listener);

    BuildContext buildContext = createMock(BuildContext.class);

    BuildInfoRecorder buildInfoRecorder = createNiceMock(BuildInfoRecorder.class);
    expect(buildContext.createBuildInfoRecorder(
           eq(buildTarget),
           /* ruleKey */ anyObject(RuleKey.class),
           /* ruleKeyWithoutDeps */ anyObject(RuleKey.class)))
        .andReturn(buildInfoRecorder);

    // Populate the metadata that should be read from disk.
    OnDiskBuildInfo onDiskBuildInfo = new FakeOnDiskBuildInfo()
         // The RuleKey on disk should be different from the current RuleKey in memory, so reverse()
         // it.
         .setRuleKey(reverse(buildRule.getRuleKey()))
         // However, the RuleKey not including the deps in memory should be the same as the one on
         // disk.
         .setRuleKeyWithoutDeps(
             new RuleKey(TestAbstractCachingBuildRule.RULE_KEY_WITHOUT_DEPS_HASH))
         // Similarly, the ABI key for the deps in memory should be the same as the one on disk.
        .putMetadata(
            CachingBuildEngine.ABI_KEY_FOR_DEPS_ON_DISK_METADATA,
            TestAbstractCachingBuildRule.ABI_KEY_FOR_DEPS_HASH)
        .putMetadata(AbiRule.ABI_KEY_ON_DISK_METADATA,
            "At some point, this method call should go away.");

    // These methods should be invoked after the rule is built locally.
    buildInfoRecorder.writeMetadataToDisk(/* clearExistingMetadata */ false);
    expect(
        buildInfoRecorder.fetchArtifactForBuildable(
            anyObject(File.class),
            anyObject(ArtifactCache.class)))
        .andReturn(CacheResult.miss());

    expect(buildContext.createOnDiskBuildInfoFor(buildTarget)).andReturn(onDiskBuildInfo);
    expect(buildContext.getArtifactCache()).andStubReturn(new NoopArtifactCache());
    expect(buildContext.getProjectRoot()).andReturn(createMock(Path.class));
    expect(buildContext.getEventBus()).andReturn(buckEventBus).anyTimes();

    replayAll();
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            CachingBuildEngine.BuildMode.SHALLOW);

    ListenableFuture<BuildResult> buildResult = cachingBuildEngine.build(buildContext, buildRule);
    //assertTrue(
    //    "We expect build() to be synchronous in this case, " +
    //        "so the future should already be resolved.",
    //    MoreFutures.isSuccess(buildResult));
    buckEventBus.post(CommandEvent.finished("build", ImmutableList.<String>of(), false, 0));

    BuildResult result = buildResult.get();
    assertEquals(BuildRuleSuccessType.MATCHING_DEPS_ABI_AND_RULE_KEY_NO_DEPS, result.getSuccess());
    assertTrue(buildRule.isAbiLoadedFromDisk());

    List<BuckEvent> events = listener.getEvents();
    assertThat(events, Matchers.hasSize(7));
    Iterator<BuckEvent> eventIter = events.iterator();
    assertEquals(
        configureTestEvent(BuildRuleEvent.started(buildRule), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.suspended(buildRule), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.resumed(buildRule), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.suspended(buildRule), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.resumed(buildRule), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.finished(buildRule,
            BuildRuleStatus.SUCCESS,
            CacheResult.localKeyUnchangedHit(),
            Optional.of(BuildRuleSuccessType.MATCHING_DEPS_ABI_AND_RULE_KEY_NO_DEPS)),
            buckEventBus),
        eventIter.next());

    verifyAll();
  }

  private StepRunner createStepRunner(@Nullable BuckEventBus eventBus) {
    ExecutionContext executionContext = createMock(ExecutionContext.class);
    expect(executionContext.getVerbosity()).andReturn(Verbosity.SILENT).anyTimes();
    if (eventBus != null) {
      expect(executionContext.getBuckEventBus()).andStubReturn(eventBus);
      expect(executionContext.getBuckEventBus()).andStubReturn(eventBus);
    }
    executionContext.postEvent(anyObject(BuckEvent.class));
    expectLastCall().anyTimes();
    return new DefaultStepRunner(executionContext);
  }

  private StepRunner createStepRunner() {
    return createStepRunner(null);
  }

  @Test
  public void testAbiKeyAutomaticallyPopulated()
      throws IOException, ExecutionException, InterruptedException {
    BuildRuleParams buildRuleParams = new FakeBuildRuleParamsBuilder(buildTarget).build();
    TestAbstractCachingBuildRule buildRule =
        new LocallyBuiltTestAbstractCachingBuildRule(
            buildRuleParams,
            new SourcePathResolver(new BuildRuleResolver()));

    // The EventBus should be updated with events indicating how the rule was built.
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    FakeBuckEventListener listener = new FakeBuckEventListener();
    buckEventBus.register(listener);

    BuildContext buildContext = createMock(BuildContext.class);
    expect(buildContext.getProjectRoot()).andReturn(createMock(Path.class));
    NoopArtifactCache artifactCache = new NoopArtifactCache();
    expect(buildContext.getArtifactCache()).andStubReturn(artifactCache);
    expect(buildContext.getStepRunner()).andStubReturn(null);

    BuildInfoRecorder buildInfoRecorder = createNiceMock(BuildInfoRecorder.class);
    expect(buildContext.createBuildInfoRecorder(
        eq(buildTarget),
           /* ruleKey */ anyObject(RuleKey.class),
           /* ruleKeyWithoutDeps */ anyObject(RuleKey.class)))
        .andReturn(buildInfoRecorder);

    expect(buildInfoRecorder.fetchArtifactForBuildable(anyObject(File.class), eq(artifactCache)))
        .andReturn(CacheResult.miss());

    // Populate the metadata that should be read from disk.
    OnDiskBuildInfo onDiskBuildInfo = new FakeOnDiskBuildInfo();

    // This metadata must be added to the buildInfoRecorder so that it is written as part of
    // writeMetadataToDisk().
    buildInfoRecorder.addMetadata(CachingBuildEngine.ABI_KEY_FOR_DEPS_ON_DISK_METADATA,
        TestAbstractCachingBuildRule.ABI_KEY_FOR_DEPS_HASH);

    // These methods should be invoked after the rule is built locally.
    buildInfoRecorder.writeMetadataToDisk(/* clearExistingMetadata */ true);
    buildInfoRecorder.performUploadToArtifactCache(artifactCache, buckEventBus);

    expect(buildContext.createOnDiskBuildInfoFor(buildTarget)).andReturn(onDiskBuildInfo);
    expect(buildContext.getStepRunner()).andReturn(createStepRunner());
    expect(buildContext.getEventBus()).andReturn(buckEventBus).anyTimes();

    replayAll();

    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            CachingBuildEngine.BuildMode.SHALLOW);
    ListenableFuture<BuildResult> buildResult = cachingBuildEngine.build(buildContext, buildRule);
    buckEventBus.post(CommandEvent.finished("build", ImmutableList.<String>of(), false, 0));

    BuildResult result = buildResult.get();
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());

    List<BuckEvent> events = listener.getEvents();
    assertThat(events, Matchers.hasSize(7));
    Iterator<BuckEvent> eventIter = events.iterator();
    assertEquals(
        configureTestEvent(BuildRuleEvent.started(buildRule), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.suspended(buildRule), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.resumed(buildRule), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.suspended(buildRule), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.resumed(buildRule), buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(BuildRuleEvent.finished(buildRule,
            BuildRuleStatus.SUCCESS,
            CacheResult.miss(),
            Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)),
            buckEventBus),
        eventIter.next());

    verifyAll();
  }

  @Test
  public void testAsyncJobsAreNotLeftInExecutor()
      throws IOException, ExecutionException, InterruptedException {
    BuildRuleParams buildRuleParams = new FakeBuildRuleParamsBuilder(buildTarget).build();
    TestAbstractCachingBuildRule buildRule =
        new LocallyBuiltTestAbstractCachingBuildRule(
            buildRuleParams,
            new SourcePathResolver(new BuildRuleResolver()));

    // The EventBus should be updated with events indicating how the rule was built.
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    FakeBuckEventListener listener = new FakeBuckEventListener();
    buckEventBus.register(listener);

    BuildContext buildContext = createMock(BuildContext.class);
    expect(buildContext.getProjectRoot()).andReturn(createMock(Path.class));
    NoopArtifactCache artifactCache = new NoopArtifactCache();
    expect(buildContext.getArtifactCache()).andStubReturn(artifactCache);
    expect(buildContext.getStepRunner()).andStubReturn(null);

    BuildInfoRecorder buildInfoRecorder = createNiceMock(BuildInfoRecorder.class);
    expect(buildContext.createBuildInfoRecorder(
            eq(buildTarget),
           /* ruleKey */ anyObject(RuleKey.class),
           /* ruleKeyWithoutDeps */ anyObject(RuleKey.class)))
        .andReturn(buildInfoRecorder);

    expect(buildInfoRecorder.fetchArtifactForBuildable(anyObject(File.class), eq(artifactCache)))
        .andReturn(CacheResult.miss());

    // Populate the metadata that should be read from disk.
    OnDiskBuildInfo onDiskBuildInfo = new FakeOnDiskBuildInfo();

    // This metadata must be added to the buildInfoRecorder so that it is written as part of
    // writeMetadataToDisk().
    buildInfoRecorder.addMetadata(
        CachingBuildEngine.ABI_KEY_FOR_DEPS_ON_DISK_METADATA,
        TestAbstractCachingBuildRule.ABI_KEY_FOR_DEPS_HASH);

    // These methods should be invoked after the rule is built locally.
    buildInfoRecorder.writeMetadataToDisk(/* clearExistingMetadata */ true);
    buildInfoRecorder.performUploadToArtifactCache(artifactCache, buckEventBus);
    expectLastCall().andAnswer(
        new IAnswer<Object>() {
          @Override
          public Object answer() throws Throwable {
            Thread.sleep(500);
            return null;
          }
        });

    ListeningExecutorService service = listeningDecorator(Executors.newFixedThreadPool(2));
    expect(buildContext.createOnDiskBuildInfoFor(buildTarget)).andReturn(onDiskBuildInfo);
    expect(buildContext.getStepRunner()).andReturn(createStepRunner(null));
    expect(buildContext.getEventBus()).andReturn(buckEventBus).anyTimes();

    replayAll();

    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            service,
            CachingBuildEngine.BuildMode.SHALLOW);
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
            BuildRuleEvent.finished(buildRule,
                BuildRuleStatus.SUCCESS,
                CacheResult.miss(),
                Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)),
            buckEventBus)
                .getEventName(),
        eventIter.next().getEventName());

    verifyAll();
  }

  @Test
  public void testArtifactFetchedFromCache()
      throws InterruptedException, ExecutionException, IOException {
    Step step = new AbstractExecutionStep("exploding step") {
      @Override
      public int execute(ExecutionContext context) {
        throw new UnsupportedOperationException("build step should not be executed");
      }
    };
    BuildRule buildRule = createRule(
        new SourcePathResolver(new BuildRuleResolver()),
        /* deps */ ImmutableSet.<BuildRule>of(),
        ImmutableList.of(step),
        /* postBuildSteps */ ImmutableList.<Step>of(),
        /* pathToOutputFile */ null);

    StepRunner stepRunner = createStepRunner();

    // Mock out all of the disk I/O.
    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);
    expect(projectFilesystem
        .readFileIfItExists(
            Paths.get("buck-out/bin/src/com/facebook/orca/.orca/metadata/RULE_KEY")))
        .andReturn(Optional.<String>absent());
    expect(projectFilesystem.getRootPath()).andReturn(tmp.getRoot().toPath());

    // Simulate successfully fetching the output file from the ArtifactCache.
    ArtifactCache artifactCache = createMock(ArtifactCache.class);
    Map<String, String> desiredZipEntries = ImmutableMap.of(
        "buck-out/gen/src/com/facebook/orca/orca.jar",
        "Imagine this is the contents of a valid JAR file.");
    expect(
        artifactCache.fetch(
            eq(buildRule.getRuleKey()),
            isA(File.class)))
        .andDelegateTo(new FakeArtifactCacheThatWritesAZipFile(desiredZipEntries));

    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    BuildContext buildContext = ImmutableBuildContext.builder()
        .setActionGraph(RuleMap.createGraphFromSingleRule(buildRule))
        .setStepRunner(stepRunner)
        .setProjectFilesystem(projectFilesystem)
        .setClock(new DefaultClock())
        .setBuildId(new BuildId())
        .setArtifactCache(artifactCache)
        .setJavaPackageFinder(createMock(JavaPackageFinder.class))
        .setEventBus(buckEventBus)
        .build();

    // Build the rule!
    replayAll();
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            CachingBuildEngine.BuildMode.SHALLOW);
    ListenableFuture<BuildResult> buildResult = cachingBuildEngine.build(buildContext, buildRule);
    buckEventBus.post(CommandEvent.finished("build", ImmutableList.<String>of(), false, 0));
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
        new File(tmp.getRoot(), "buck-out/gen/src/com/facebook/orca/orca.jar").isFile());
  }

  @Test
  public void testArtifactFetchedFromCacheStillRunsPostBuildSteps()
      throws InterruptedException, ExecutionException, IOException {

    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    StepRunner stepRunner = createStepRunner(buckEventBus);

    // Mock out all of the disk I/O.
    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);
    expect(projectFilesystem
            .readFileIfItExists(
                Paths.get("buck-out/bin/src/com/facebook/orca/.orca/metadata/RULE_KEY")))
        .andReturn(Optional.<String>absent());
    expect(projectFilesystem.getRootPath()).andReturn(tmp.getRoot().toPath());

    // Add a post build step so we can verify that it's steps are executed.
    Step buildStep = createMock(Step.class);
    expect(buildStep.getDescription(anyObject(ExecutionContext.class)))
        .andReturn("Some Description")
        .anyTimes();
    expect(buildStep.getShortName()).andReturn("Some Short Name").anyTimes();
    expect(buildStep.execute(anyObject(ExecutionContext.class))).andReturn(0);

    BuildRule buildRule = createRule(
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
            isA(File.class)))
        .andDelegateTo(new FakeArtifactCacheThatWritesAZipFile(desiredZipEntries));

    BuildContext buildContext = ImmutableBuildContext.builder()
        .setActionGraph(RuleMap.createGraphFromSingleRule(buildRule))
        .setStepRunner(stepRunner)
        .setProjectFilesystem(projectFilesystem)
        .setClock(new DefaultClock())
        .setBuildId(new BuildId())
        .setArtifactCache(artifactCache)
        .setJavaPackageFinder(createMock(JavaPackageFinder.class))
        .setEventBus(buckEventBus)
        .build();

    // Build the rule!
    replayAll();
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            CachingBuildEngine.BuildMode.SHALLOW);
    ListenableFuture<BuildResult> buildResult = cachingBuildEngine.build(buildContext, buildRule);
    buckEventBus.post(CommandEvent.finished("build", ImmutableList.<String>of(), false, 0));
    verifyAll();

    BuildResult result = buildResult.get();
    assertEquals(BuildRuleSuccessType.FETCHED_FROM_CACHE, result.getSuccess());
    assertTrue(
        ((BuildableAbstractCachingBuildRule) buildRule).isInitializedFromDisk());
    assertTrue(
        "The entries in the zip should be extracted as a result of building the rule.",
        new File(tmp.getRoot(), "buck-out/gen/src/com/facebook/orca/orca.jar").isFile());
  }

  @Test
  public void testMatchingTopLevelRuleKeyAvoidsProcessingDepInShallowMode() throws Exception {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ArtifactCache cache = new NoopArtifactCache();

    // The EventBus should be updated with events indicating how the rule was built.
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    FakeBuckEventListener listener = new FakeBuckEventListener();
    buckEventBus.register(listener);

    // Create a dep for the build rule.
    BuildTarget depTarget = BuildTargetFactory.newInstance("//src/com/facebook/orca:lib");
    FakeBuildRule dep = new FakeBuildRule(depTarget, pathResolver);
    dep.setRuleKey(new RuleKey("aaaa"));
    FakeBuildRule ruleToTest = new FakeBuildRule(buildTarget, pathResolver, dep);
    ruleToTest.setRuleKey(new RuleKey("bbbb"));

    // The BuildContext that will be used by the rule's build() method.
    BuildContext context = createMock(BuildContext.class);
    expect(context.getArtifactCache()).andReturn(cache).anyTimes();
    expect(context.getEventBus()).andReturn(buckEventBus).anyTimes();
    expect(context.getStepRunner()).andReturn(createStepRunner(buckEventBus)).anyTimes();
    expect(context.createOnDiskBuildInfoFor(buildTarget))
        .andReturn(new FakeOnDiskBuildInfo().setRuleKey(ruleToTest.getRuleKey()));
    BuildInfoRecorder buildInfoRecorder = createNiceMock(BuildInfoRecorder.class);
    Capture<RuleKey> ruleKeyForRecorder = newCapture();
    expect(
        context.createBuildInfoRecorder(
            eq(buildTarget),
            capture(ruleKeyForRecorder),
            /* ruleKeyWithoutDepsForRecorder */ anyObject(RuleKey.class)))
        .andReturn(buildInfoRecorder);

    // Create the build engine.
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            CachingBuildEngine.BuildMode.SHALLOW);

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
                Optional.of(BuildRuleSuccessType.MATCHING_RULE_KEY)),
            buckEventBus),
        eventIter.next());
  }

  @Test
  public void testMatchingTopLevelRuleKeyStillProcessesDepInDeepMode() throws Exception {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ArtifactCache cache = new NoopArtifactCache();

    // The EventBus should be updated with events indicating how the rule was built.
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    FakeBuckEventListener listener = new FakeBuckEventListener();
    buckEventBus.register(listener);

    // Create a dep for the build rule.
    BuildTarget depTarget = BuildTargetFactory.newInstance("//src/com/facebook/orca:lib");
    FakeBuildRule dep = new FakeBuildRule(depTarget, pathResolver);
    dep.setRuleKey(new RuleKey("aaaa"));
    FakeBuildRule ruleToTest = new FakeBuildRule(buildTarget, pathResolver, dep);
    ruleToTest.setRuleKey(new RuleKey("bbbb"));

    // The BuildContext that will be used by the rule's build() method.
    BuildContext context = createMock(BuildContext.class);
    expect(context.getArtifactCache()).andReturn(cache).anyTimes();
    expect(context.getEventBus()).andReturn(buckEventBus).anyTimes();
    expect(context.getStepRunner()).andReturn(createStepRunner(buckEventBus)).anyTimes();
    expect(context.createOnDiskBuildInfoFor(buildTarget))
        .andReturn(new FakeOnDiskBuildInfo().setRuleKey(ruleToTest.getRuleKey()));
    expect(context.createOnDiskBuildInfoFor(dep.getBuildTarget()))
        .andReturn(new FakeOnDiskBuildInfo().setRuleKey(dep.getRuleKey()));
    expect(
        context.createBuildInfoRecorder(
            anyObject(BuildTarget.class),
            anyObject(RuleKey.class),
            /* ruleKeyWithoutDepsForRecorder */ anyObject(RuleKey.class)))
        .andReturn(createNiceMock(BuildInfoRecorder.class))
        .anyTimes();

    // Create the build engine.
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            CachingBuildEngine.BuildMode.DEEP);

    // Run the build.
    replayAll();
    BuildResult result = cachingBuildEngine.build(context, ruleToTest).get();
    assertEquals(BuildRuleSuccessType.MATCHING_RULE_KEY, result.getSuccess());
    verifyAll();

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
                Optional.of(BuildRuleSuccessType.MATCHING_RULE_KEY)),
            buckEventBus),
        eventIter.next());
    assertEquals(
        configureTestEvent(
            BuildRuleEvent.finished(
                ruleToTest,
                BuildRuleStatus.SUCCESS,
                CacheResult.localKeyUnchangedHit(),
                Optional.of(BuildRuleSuccessType.MATCHING_RULE_KEY)),
            buckEventBus),
        eventIter.next());
  }

  @Test
  public void testMatchingTopLevelRuleKeyStillProcessesRuntimeDeps() throws Exception {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ArtifactCache cache = new NoopArtifactCache();

    // The EventBus should be updated with events indicating how the rule was built.
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    FakeBuckEventListener listener = new FakeBuckEventListener();
    buckEventBus.register(listener);

    // Setup a runtime dependency that is found transitively from the top-level rule.
    FakeBuildRule transitiveRuntimeDep =
        new FakeBuildRule(
            BuildTargetFactory.newInstance("//:transitive_dep"),
            pathResolver);
    transitiveRuntimeDep.setRuleKey(new RuleKey("aaaa"));

    // Setup a runtime dependency that is referenced directly by the top-level rule.
    FakeBuildRule runtimeDep =
        new FakeHasRuntimeDeps(
            BuildTargetFactory.newInstance("//:runtime_dep"),
            pathResolver,
            transitiveRuntimeDep);
    runtimeDep.setRuleKey(new RuleKey("bbbb"));

    // Create a dep for the build rule.
    FakeBuildRule ruleToTest = new FakeHasRuntimeDeps(buildTarget, pathResolver, runtimeDep);
    ruleToTest.setRuleKey(new RuleKey("cccc"));

    // The BuildContext that will be used by the rule's build() method.
    BuildContext context = createNiceMock(BuildContext.class);
    expect(context.getArtifactCache()).andReturn(cache).anyTimes();
    expect(context.getEventBus()).andReturn(buckEventBus).anyTimes();
    expect(context.getStepRunner()).andReturn(createStepRunner(buckEventBus)).anyTimes();
    expect(context.createOnDiskBuildInfoFor(buildTarget))
        .andReturn(new FakeOnDiskBuildInfo().setRuleKey(ruleToTest.getRuleKey()))
        .anyTimes();
    expect(context.createOnDiskBuildInfoFor(runtimeDep.getBuildTarget()))
        .andReturn(new FakeOnDiskBuildInfo().setRuleKey(runtimeDep.getRuleKey()))
        .anyTimes();
    expect(context.createOnDiskBuildInfoFor(transitiveRuntimeDep.getBuildTarget()))
        .andReturn(new FakeOnDiskBuildInfo().setRuleKey(transitiveRuntimeDep.getRuleKey()))
        .anyTimes();
    expect(
        context.createBuildInfoRecorder(
            anyObject(BuildTarget.class),
            anyObject(RuleKey.class),
            /* ruleKeyWithoutDepsForRecorder */ anyObject(RuleKey.class)))
        .andReturn(createNiceMock(BuildInfoRecorder.class))
        .anyTimes();

    // Create the build engine.
    CachingBuildEngine cachingBuildEngine =
        new CachingBuildEngine(
            MoreExecutors.newDirectExecutorService(),
            CachingBuildEngine.BuildMode.SHALLOW);

    // Run the build.
    replayAll();
    BuildResult result = cachingBuildEngine.build(context, ruleToTest).get();
    assertEquals(BuildRuleSuccessType.MATCHING_RULE_KEY, result.getSuccess());
    verifyAll();

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
                Optional.of(BuildRuleSuccessType.MATCHING_RULE_KEY)),
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
                Optional.of(BuildRuleSuccessType.MATCHING_RULE_KEY)),
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
                Optional.of(BuildRuleSuccessType.MATCHING_RULE_KEY)),
            buckEventBus),
        eventIter.next());
  }


  // TODO(mbolin): Test that when the success files match, nothing is built and nothing is written
  // back to the cache.

  // TODO(mbolin): Test that when the value in the success file does not agree with the current
  // value, the rule is rebuilt and the result is written back to the cache.

  // TODO(mbolin): Test that a failure when executing the build steps is propagated appropriately.

  // TODO(mbolin): Test what happens when the cache's methods throw an exception.

  private BuildRule createRule(
      SourcePathResolver resolver,
      ImmutableSet<BuildRule> deps,
      List<Step> buildSteps,
      ImmutableList<Step> postBuildSteps,
      @Nullable String pathToOutputFile) {
    Comparator<BuildRule> comparator = RetainOrderComparator.createComparator(deps);
    ImmutableSortedSet<BuildRule> sortedDeps = ImmutableSortedSet.copyOf(comparator, deps);

    final FileHashCache fileHashCache = FakeFileHashCache.createFromStrings(ImmutableMap.of(
            "/dev/null", "ae8c0f860a0ecad94ecede79b69460434eddbfbc"));

    BuildRuleParams buildRuleParams = new FakeBuildRuleParamsBuilder(buildTarget)
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
   * {@link AbstractBuildRule} that implements {@link AbiRule}.
   */
  private static class TestAbstractCachingBuildRule extends AbstractBuildRule
      implements AbiRule, BuildRule, InitializableFromDisk<Object> {

    private static final String RULE_KEY_HASH = "bfcd53a794e7c732019e04e08b30b32e26e19d50";
    private static final String RULE_KEY_WITHOUT_DEPS_HASH =
        "efd7d450d9f1c3d9e43392dec63b1f31692305b9";
    private static final String ABI_KEY_FOR_DEPS_HASH = "92d6de0a59080284055bcde5d2923f144b216a59";

    private boolean isAbiLoadedFromDisk = false;
    private final BuildOutputInitializer<Object> buildOutputInitializer;

    TestAbstractCachingBuildRule(BuildRuleParams buildRuleParams, SourcePathResolver resolver) {
      super(buildRuleParams, resolver);
      this.buildOutputInitializer =
          new BuildOutputInitializer<>(buildRuleParams.getBuildTarget(), this);
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context,
        BuildableContext buildableContext) {
      throw new UnsupportedOperationException("method should not be called");
    }

    @Nullable
    @Override
    public Path getPathToOutput() {
      return null;
    }

    @Override
    public RuleKey getRuleKey() {
      return new RuleKey(RULE_KEY_HASH);
    }

    @Override
    public RuleKey getRuleKeyWithoutDeps() {
      return new RuleKey(RULE_KEY_WITHOUT_DEPS_HASH);
    }

    @Override
    public Object initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
      isAbiLoadedFromDisk = true;
      return new Object();
    }

    @Override
    public BuildOutputInitializer<Object> getBuildOutputInitializer() {
      return buildOutputInitializer;
    }

    public boolean isAbiLoadedFromDisk() {
      return isAbiLoadedFromDisk;
    }

    @Override
    public Sha1HashCode getAbiKeyForDeps() {
      return Sha1HashCode.of(ABI_KEY_FOR_DEPS_HASH);
    }
  }

  private static class LocallyBuiltTestAbstractCachingBuildRule
      extends TestAbstractCachingBuildRule {
    LocallyBuiltTestAbstractCachingBuildRule(
        BuildRuleParams buildRuleParams,
        SourcePathResolver resolver) {
      super(buildRuleParams, resolver);
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context,
        BuildableContext buildableContext) {
      return ImmutableList.of();
    }
  }

  /**
   * Implementation of {@link ArtifactCache} that, when its fetch method is called, takes the
   * location of requested {@link File} and writes a zip file there with the entries specified to
   * its constructor.
   * <p>
   * This makes it possible to react to a call to {@link ArtifactCache#store(RuleKey, File)} and
   * ensure that there will be a zip file in place immediately after the captured method has been
   * invoked.
   */
  private static class FakeArtifactCacheThatWritesAZipFile implements ArtifactCache {

    private final Map<String, String> desiredEntries;

    public FakeArtifactCacheThatWritesAZipFile(Map<String, String> desiredEntries) {
      this.desiredEntries = ImmutableMap.copyOf(desiredEntries);
    }

    @Override
    public CacheResult fetch(RuleKey ruleKey, File file) throws InterruptedException {
      // This must have the side-effect of writing a zip file in the specified place.
      try {
        writeEntries(file);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      return CacheResult.hit("dir");
    }

    private void writeEntries(File file) throws IOException {
      try (ZipOutputStream zip = new ZipOutputStream(
          new BufferedOutputStream(
              new FileOutputStream(file)))) {
        for (Map.Entry<String, String> mapEntry : desiredEntries.entrySet()) {
          ZipEntry entry = new ZipEntry(mapEntry.getKey());
          zip.putNextEntry(entry);
          zip.write(mapEntry.getValue().getBytes());
          zip.closeEntry();
        }
      }
    }

    @Override
    public void store(RuleKey ruleKey, File output) throws InterruptedException {
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

  /**
   * @return a RuleKey with the bits of the hash in reverse order, just to be different.
   */
  private static RuleKey reverse(RuleKey ruleKey) {
    String hash = ruleKey.getHashCode().toString();
    String reverseHash = new StringBuilder(hash).reverse().toString();
    return new RuleKey(reverseHash);
  }

  private static class FakeHasRuntimeDeps extends FakeBuildRule implements HasRuntimeDeps {

    private final ImmutableSortedSet<BuildRule> runtimeDeps;

    public FakeHasRuntimeDeps(
        BuildTarget target,
        SourcePathResolver resolver,
        BuildRule... runtimeDeps) {
      super(target, resolver);
      this.runtimeDeps = ImmutableSortedSet.copyOf(runtimeDeps);
    }

    @Override
    public ImmutableSortedSet<BuildRule> getRuntimeDeps() {
      return runtimeDeps;
    }

  }

}
