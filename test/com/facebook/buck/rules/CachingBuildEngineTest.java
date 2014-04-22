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
import static com.facebook.buck.rules.BuildRuleEvent.Finished;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.cli.CommandEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.FakeBuckEventListener;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.RuleMap;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.util.FileHashCache;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.concurrent.MoreFutures;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.annotation.Nullable;

/**
 * Ensuring that build rule caching works correctly in Buck is imperative for both its performance
 * and correctness.
 */
public class CachingBuildEngineTest extends EasyMockSupport {

  private static final BuildTarget buildTarget = new BuildTarget("//src/com/facebook/orca", "orca");

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
    // Create a dep for the build rule.
    BuildTarget depTarget = BuildTargetFactory.newInstance("//src/com/facebook/orca:lib");
    BuildRule dep = createMock(BuildRule.class);
    expect(dep.isVisibleTo(buildTarget)).andReturn(true);

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
        ImmutableSet.of(dep),
        ImmutableList.of(Paths.get("/dev/null")),
        buildSteps,
        pathToOutputFile);
    verifyAll();
    resetAll();

    String expectedRuleKeyHash = Hashing.sha1().newHasher()
        .putByte(RuleKey.Builder.SEPARATOR)
        .putBytes("name".getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)
        .putBytes(ruleToTest.getFullyQualifiedName().getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)

        .putByte(RuleKey.Builder.SEPARATOR)
        .putBytes("buck.type".getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)
        .putBytes("java_library".getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)

        .putByte(RuleKey.Builder.SEPARATOR)
        .putBytes("buck.inputs".getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)
        .putBytes("ae8c0f860a0ecad94ecede79b69460434eddbfbc".getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)
        .putByte(RuleKey.Builder.SEPARATOR)

        .putByte(RuleKey.Builder.SEPARATOR)
        .putBytes("buck.sourcepaths".getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)
        .putBytes("/dev/null".getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)
        .putBytes("/dev/null".getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)
        .putByte(RuleKey.Builder.SEPARATOR)

        .putByte(RuleKey.Builder.SEPARATOR)
        .putBytes("deps".getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)
        .putBytes("19d2558a6bd3a34fb3f95412de9da27ed32fe208".getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)
        .putByte(RuleKey.Builder.SEPARATOR)

        .hash()
        .toString();

    // The BuildContext that will be used by the rule's build() method.
    BuildContext context = createMock(BuildContext.class);
    expect(context.getArtifactCache()).andReturn(artifactCache).times(2);
    expect(context.getProjectRoot()).andReturn(createMock(Path.class));

    // Configure the OnDiskBuildInfo.
    OnDiskBuildInfo onDiskBuildInfo = new FakeOnDiskBuildInfo();
    expect(context.createOnDiskBuildInfoFor(buildTarget)).andReturn(onDiskBuildInfo);

    // Configure the BuildInfoRecorder.
    BuildInfoRecorder buildInfoRecorder = createMock(BuildInfoRecorder.class);
    Capture<RuleKey> ruleKeyForRecorder = new Capture<>();
    expect(
        context.createBuildInfoRecorder(
            eq(buildTarget),
            capture(ruleKeyForRecorder),
            /* ruleKeyWithoutDepsForRecorder */ anyObject(RuleKey.class)))
        .andReturn(buildInfoRecorder);
    expect(buildInfoRecorder.fetchArtifactForBuildable(
            anyObject(File.class),
            eq(artifactCache)))
        .andReturn(CacheResult.MISS);

    // Set the requisite expectations to build the rule.
    expect(context.getEventBus()).andReturn(buckEventBus).anyTimes();
    context.logBuildInfo("[BUILDING %s]", "//src/com/facebook/orca:orca");
    expect(context.getStepRunner()).andReturn(createSameThreadStepRunner()).anyTimes();

    expect(dep.getBuildTarget()).andStubReturn(depTarget);
    CachingBuildEngine cachingBuildEngine = new CachingBuildEngine();
    // The dependent rule will be built immediately with a distinct rule key.
    cachingBuildEngine.setBuildRuleResult(
        depTarget,
        new BuildRuleSuccess(dep, BuildRuleSuccess.Type.FETCHED_FROM_CACHE));
    expect(dep.getRuleKey()).andReturn(new RuleKey("19d2558a6bd3a34fb3f95412de9da27ed32fe208"));

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
    BuildRuleSuccess result = cachingBuildEngine.build(context, ruleToTest).get();
    assertEquals(BuildRuleSuccess.Type.BUILT_LOCALLY, result.getType());
    buckEventBus.post(CommandEvent.finished("build", ImmutableList.<String>of(), false, 0));
    verifyAll();

    assertEquals(expectedRuleKeyHash, ruleKeyForRecorder.getValue().toString());

    // Verify the events logged to the BuckEventBus.
    List<BuckEvent> events = listener.getEvents();
    assertEquals(configureTestEvent(BuildRuleEvent.started(ruleToTest), buckEventBus),
        events.get(0));
    assertEquals(configureTestEvent(BuildRuleEvent.finished(ruleToTest,
            BuildRuleStatus.SUCCESS,
            CacheResult.MISS,
            Optional.of(BuildRuleSuccess.Type.BUILT_LOCALLY)),
            buckEventBus),
        events.get(1));
  }

  @Test
  public void testDoNotFetchFromCacheIfDepBuiltLocally()
      throws ExecutionException, InterruptedException, IOException, StepFailedException {
    CachingBuildEngine cachingBuildEngine = new CachingBuildEngine();

    BuildTarget target1 = BuildTargetFactory.newInstance("//java/com/example:rule1");
    FakeBuildRule dep1 = new FakeBuildRule(AndroidResourceDescription.TYPE, target1);
    cachingBuildEngine.setBuildRuleResult(
        target1,
        new BuildRuleSuccess(dep1, BuildRuleSuccess.Type.BUILT_LOCALLY));
    dep1.setRuleKey(new RuleKey(Strings.repeat("a", 40)));

    BuildTarget target2 = BuildTargetFactory.newInstance("//java/com/example:rule2");
    FakeBuildRule dep2 = new FakeBuildRule(AndroidResourceDescription.TYPE, target2);
    cachingBuildEngine.setBuildRuleResult(
        target2,
        new BuildRuleSuccess(dep2, BuildRuleSuccess.Type.FETCHED_FROM_CACHE));
    dep2.setRuleKey(new RuleKey(Strings.repeat("b", 40)));

    final List<String> strings = Lists.newArrayList();
    Step buildStep = new AbstractExecutionStep("test_step") {
      @Override
      public int execute(ExecutionContext context) {
        strings.add("Step was executed.");
        return 0;
      }
    };
    BuildRule buildRuleToTest = createRule(
        ImmutableSet.<BuildRule>of(dep1, dep2),
        ImmutableList.of(Paths.get("/dev/null")),
        ImmutableList.of(buildStep),
        "buck-out/gen/src/com/facebook/orca/some_file");

    // Inject artifactCache to verify that its fetch method is never called.
    ArtifactCache artifactCache = new NoopArtifactCache() {
      @Override
      public CacheResult fetch(RuleKey ruleKey, File output) {
        throw new RuntimeException("Artifact cache must not be accessed while building the rule.");
      }
    };

    BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    FakeBuckEventListener listener = new FakeBuckEventListener();
    eventBus.register(listener);
    BuildContext buildContext = FakeBuildContext.newBuilder(new FakeProjectFilesystem())
        .setDependencyGraph(new DependencyGraph(new MutableDirectedGraph<BuildRule>()))
        .setJavaPackageFinder(new JavaPackageFinder() {
          @Override
          public String findJavaPackageFolderForPath(String pathRelativeToProjectRoot) {
            return null;
          }

          @Override
          public String findJavaPackageForPath(String pathRelativeToProjectRoot) {
            return null;
          }
        })
        .setArtifactCache(artifactCache)
        .setEventBus(eventBus)
        .build();

    BuildRuleSuccess result = cachingBuildEngine.build(buildContext, buildRuleToTest).get();
    assertEquals(result.getType(), BuildRuleSuccess.Type.BUILT_LOCALLY);
    eventBus.post(CommandEvent.finished("build", ImmutableList.<String>of(), false, 0));
    MoreAsserts.assertListEquals(Lists.newArrayList("Step was executed."), strings);

    Finished finishedEvent = null;
    for (BuckEvent event : listener.getEvents()) {
      if (event instanceof Finished) {
        finishedEvent = (Finished) event;
      }
    }
    assertNotNull("BuildRule did not fire a BuildRuleEvent.Finished event.", finishedEvent);
    assertEquals(CacheResult.SKIP, finishedEvent.getCacheResult());
  }

  /**
   * Rebuild a rule where one if its dependencies has been modified such that its RuleKey has
   * changed, but its ABI is the same.
   */
  @Test
  public void testAbiRuleCanAvoidRebuild()
      throws InterruptedException, ExecutionException, IOException {
    BuildRuleParams buildRuleParams = new FakeBuildRuleParams(buildTarget);
    TestAbstractCachingBuildRule buildRule = new TestAbstractCachingBuildRule(buildRuleParams);

    // The EventBus should be updated with events indicating how the rule was built.
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    FakeBuckEventListener listener = new FakeBuckEventListener();
    buckEventBus.register(listener);

    BuildContext buildContext = createMock(BuildContext.class);

    BuildInfoRecorder buildInfoRecorder = createMock(BuildInfoRecorder.class);
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

    expect(buildContext.createOnDiskBuildInfoFor(buildTarget)).andReturn(onDiskBuildInfo);
    expect(buildContext.getStepRunner()).andReturn(createSameThreadStepRunner());
    expect(buildContext.getEventBus()).andReturn(buckEventBus).anyTimes();

    replayAll();
    CachingBuildEngine cachingBuildEngine = new CachingBuildEngine();

    ListenableFuture<BuildRuleSuccess> result = cachingBuildEngine.build(buildContext, buildRule);
    assertTrue("We expect build() to be synchronous in this case, " +
               "so the future should already be resolved.",
               MoreFutures.isSuccess(result));
    buckEventBus.post(CommandEvent.finished("build", ImmutableList.<String>of(), false, 0));

    BuildRuleSuccess success = result.get();
    assertEquals(BuildRuleSuccess.Type.MATCHING_DEPS_ABI_AND_RULE_KEY_NO_DEPS, success.getType());
    assertTrue(buildRule.isAbiLoadedFromDisk());

    List<BuckEvent> events = listener.getEvents();
    assertEquals(events.get(0),
        configureTestEvent(BuildRuleEvent.started(buildRule), buckEventBus));
    assertEquals(events.get(1),
        configureTestEvent(BuildRuleEvent.finished(buildRule,
            BuildRuleStatus.SUCCESS,
            CacheResult.LOCAL_KEY_UNCHANGED_HIT,
            Optional.of(BuildRuleSuccess.Type.MATCHING_DEPS_ABI_AND_RULE_KEY_NO_DEPS)),
            buckEventBus));

    verifyAll();
  }

  private StepRunner createSameThreadStepRunner() {

    ExecutionContext executionContext = createMock(ExecutionContext.class);
    expect(executionContext.getVerbosity()).andReturn(Verbosity.SILENT).anyTimes();
    executionContext.postEvent(anyObject(BuckEvent.class));
    expectLastCall().anyTimes();
    return new DefaultStepRunner(
        executionContext,
        listeningDecorator(MoreExecutors.sameThreadExecutor()));
  }

  @Test
  public void testAbiKeyAutomaticallyPopulated()
      throws IOException, ExecutionException, InterruptedException {
    BuildRuleParams buildRuleParams = new FakeBuildRuleParams(buildTarget);
    TestAbstractCachingBuildRule buildRule =
        new LocallyBuiltTestAbstractCachingBuildRule(buildRuleParams);

    // The EventBus should be updated with events indicating how the rule was built.
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    FakeBuckEventListener listener = new FakeBuckEventListener();
    buckEventBus.register(listener);

    BuildContext buildContext = createMock(BuildContext.class);
    expect(buildContext.getProjectRoot()).andReturn(createMock(Path.class));
    NoopArtifactCache artifactCache = new NoopArtifactCache();
    expect(buildContext.getArtifactCache()).andStubReturn(artifactCache);
    buildContext.logBuildInfo(anyObject(String.class), anyObject());
    expectLastCall().asStub();
    expect(buildContext.getStepRunner()).andStubReturn(null);

    BuildInfoRecorder buildInfoRecorder = createMock(BuildInfoRecorder.class);
    expect(buildContext.createBuildInfoRecorder(
        eq(buildTarget),
           /* ruleKey */ anyObject(RuleKey.class),
           /* ruleKeyWithoutDeps */ anyObject(RuleKey.class)))
        .andReturn(buildInfoRecorder);

    expect(buildInfoRecorder.fetchArtifactForBuildable(anyObject(File.class), eq(artifactCache)))
        .andReturn(CacheResult.MISS);

    // Populate the metadata that should be read from disk.
    OnDiskBuildInfo onDiskBuildInfo = new FakeOnDiskBuildInfo();

    // This metadata must be added to the buildInfoRecorder so that it is written as part of
    // writeMetadataToDisk().
    buildInfoRecorder.addMetadata(CachingBuildEngine.ABI_KEY_FOR_DEPS_ON_DISK_METADATA,
        TestAbstractCachingBuildRule.ABI_KEY_FOR_DEPS_HASH);

    // These methods should be invoked after the rule is built locally.
    buildInfoRecorder.writeMetadataToDisk(/* clearExistingMetadata */ true);

    expect(buildContext.createOnDiskBuildInfoFor(buildTarget)).andReturn(onDiskBuildInfo);
    expect(buildContext.getStepRunner()).andReturn(createSameThreadStepRunner());
    expect(buildContext.getEventBus()).andReturn(buckEventBus).anyTimes();

    replayAll();

    CachingBuildEngine cachingBuildEngine = new CachingBuildEngine();
    ListenableFuture<BuildRuleSuccess> result = cachingBuildEngine.build(buildContext, buildRule);
    buckEventBus.post(CommandEvent.finished("build", ImmutableList.<String>of(), false, 0));
    assertTrue(
        "We expect build() to be synchronous in this case, " +
            "so the future should already be resolved.",
        MoreFutures.isSuccess(result));

    BuildRuleSuccess success = result.get();
    assertEquals(BuildRuleSuccess.Type.BUILT_LOCALLY, success.getType());

    List<BuckEvent> events = listener.getEvents();
    assertEquals(events.get(0),
        configureTestEvent(BuildRuleEvent.started(buildRule), buckEventBus));
    assertEquals(events.get(1),
        configureTestEvent(BuildRuleEvent.finished(buildRule,
            BuildRuleStatus.SUCCESS,
            CacheResult.MISS,
            Optional.of(BuildRuleSuccess.Type.BUILT_LOCALLY)),
            buckEventBus));

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
        /* deps */ ImmutableSet.<BuildRule>of(),
        ImmutableList.<Path>of(),
        ImmutableList.of(step),
        /* pathToOutputFile */ null);

    StepRunner stepRunner = createSameThreadStepRunner();

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
        "Imagine this is the contents of a valid JAR file."
        );
    expect(
        artifactCache.fetch(
            eq(buildRule.getRuleKey()),
            capture(new CaptureThatWritesAZipFile(desiredZipEntries))))
        .andReturn(CacheResult.DIR_HIT);

    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
    BuildContext buildContext = BuildContext.builder()
        .setDependencyGraph(RuleMap.createGraphFromSingleRule(buildRule))
        .setStepRunner(stepRunner)
        .setProjectFilesystem(projectFilesystem)
        .setArtifactCache(artifactCache)
        .setJavaPackageFinder(createMock(JavaPackageFinder.class))
        .setEventBus(buckEventBus)
        .build();

    // Build the rule!
    replayAll();
    CachingBuildEngine cachingBuildEngine = new CachingBuildEngine();
    ListenableFuture<BuildRuleSuccess> result = cachingBuildEngine.build(buildContext, buildRule);
    buckEventBus.post(CommandEvent.finished("build", ImmutableList.<String>of(), false, 0));
    verifyAll();

    assertTrue(
        "We expect build() to be synchronous in this case, " +
            "so the future should already be resolved.",
        MoreFutures.isSuccess(result)
    );
    BuildRuleSuccess success = result.get();
    assertEquals(BuildRuleSuccess.Type.FETCHED_FROM_CACHE, success.getType());
    assertTrue(
        ((BuildableAbstractCachingBuildRule) buildRule.getBuildable()).isInitializedFromDisk());
    assertTrue(
        "The entries in the zip should be extracted as a result of building the rule.",
        new File(tmp.getRoot(), "buck-out/gen/src/com/facebook/orca/orca.jar").isFile());
  }


  // TODO(mbolin): Test that when the success files match, nothing is built and nothing is written
  // back to the cache.

  // TODO(mbolin): Test that when the value in the success file does not agree with the current
  // value, the rule is rebuilt and the result is written back to the cache.

  // TODO(mbolin): Test that a failure when executing the build steps is propagated appropriately.

  // TODO(mbolin): Test what happens when the cache's methods throw an exception.

  private static BuildRule createRule(
      ImmutableSet<BuildRule> deps,
      Iterable<Path> inputs,
      List<Step> buildSteps,
      @Nullable String pathToOutputFile) {
    Comparator<BuildRule> comparator = RetainOrderComparator.createComparator(deps);
    ImmutableSortedSet<BuildRule> sortedDeps = ImmutableSortedSet.copyOf(comparator, deps);

    final FileHashCache fileHashCache = FakeFileHashCache.createFromStrings(ImmutableMap.of(
          "/dev/null", "ae8c0f860a0ecad94ecede79b69460434eddbfbc"));
    final RuleKeyBuilderFactory ruleKeyBuilderFactory = new RuleKeyBuilderFactory() {
      @Override
      public RuleKey.Builder newInstance(BuildRule buildRule) {
        return RuleKey.builder(buildRule, fileHashCache);
      }
    };
    BuildRuleParams buildRuleParams = new FakeBuildRuleParams(buildTarget, sortedDeps) {
      @Override
      public RuleKeyBuilderFactory getRuleKeyBuilderFactory() {
        return ruleKeyBuilderFactory;
      }
    };

    BuildableAbstractCachingBuildRule buildRule = new BuildableAbstractCachingBuildRule(
        buildRuleParams,
        inputs,
        pathToOutputFile,
        buildSteps);

    return new AbstractBuildable.AnonymousBuildRule(
        JavaLibraryDescription.TYPE,
        buildRule,
        buildRuleParams);
  }

  private static class BuildableAbstractCachingBuildRule extends AbstractBuildable
      implements InitializableFromDisk<Object> {

    private final Iterable<Path> inputs;
    private final Path pathToOutputFile;
    private final List<Step> buildSteps;
    private final BuildOutputInitializer<Object> buildOutputInitializer;

    private boolean isInitializedFromDisk = false;

    private BuildableAbstractCachingBuildRule(BuildRuleParams params,
        Iterable<Path> inputs,
        @Nullable String pathToOutputFile,
        List<Step> buildSteps) {
      this.inputs = inputs;
      this.pathToOutputFile = pathToOutputFile == null ? null : Paths.get(pathToOutputFile);
      this.buildSteps = buildSteps;
      this.buildOutputInitializer =
          new BuildOutputInitializer<>(params.getBuildTarget(), this);
    }

    @Override
    @Nullable
    public Path getPathToOutputFile() {
      return pathToOutputFile;
    }

    @Override
    public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext) {
      buildableContext.recordArtifact(pathToOutputFile);
      return buildSteps;
    }

    @Override
    public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
      return builder;
    }

    @Override
    public Collection<Path> getInputsToCompareToOutput() {
      return ImmutableList.copyOf(inputs);
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
  private static class TestAbstractCachingBuildRule extends AbstractBuildable
      implements AbiRule, BuildRule, InitializableFromDisk<Object> {

    private static final String RULE_KEY_HASH = "bfcd53a794e7c732019e04e08b30b32e26e19d50";
    private static final String RULE_KEY_WITHOUT_DEPS_HASH =
        "efd7d450d9f1c3d9e43392dec63b1f31692305b9";
    private static final String ABI_KEY_FOR_DEPS_HASH = "92d6de0a59080284055bcde5d2923f144b216a59";
    private final BuildTarget buildTarget;

    private boolean isAbiLoadedFromDisk = false;
    private final BuildOutputInitializer<Object> buildOutputInitializer;

    TestAbstractCachingBuildRule(BuildRuleParams buildRuleParams) {
      this.buildTarget = buildRuleParams.getBuildTarget();
      this.buildOutputInitializer =
          new BuildOutputInitializer<>(buildRuleParams.getBuildTarget(), this);
    }

    @Override
    public Collection<Path> getInputsToCompareToOutput() {
      throw new UnsupportedOperationException("method should not be called");
    }

    @Override
    public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext) {
      throw new UnsupportedOperationException("method should not be called");
    }

    @Override
    public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
      return builder;
    }

    @Nullable
    @Override
    public Path getPathToOutputFile() {
      return null;
    }

    @Nullable
    @Override
    public ImmutableSortedSet<BuildRule> getEnhancedDeps(
        BuildRuleResolver ruleResolver) {
      return null;
    }

    @Override
    public BuildTarget getBuildTarget() {
      return buildTarget;
    }

    @Override
    public String getFullyQualifiedName() {
      return buildTarget.getFullyQualifiedName();
    }

    @Override
    public BuildRuleType getType() {
      throw new UnsupportedOperationException("method should not be called");
    }

    @Override
    public Buildable getBuildable() {
      return this;
    }

    @Override
    public ImmutableSortedSet<BuildRule> getDeps() {
      return ImmutableSortedSet.of();
    }

    @Override
    public ImmutableSet<BuildTargetPattern> getVisibilityPatterns() {
      return ImmutableSet.of();
    }

    @Override
    public boolean isVisibleTo(BuildTarget target) {
      return false;
    }

    @Override
    public Iterable<Path> getInputs() {
      return ImmutableSet.of();
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
      return new Sha1HashCode(ABI_KEY_FOR_DEPS_HASH);
    }

    @Override
    public int compareTo(HasBuildTarget o) {
      return 0;
    }
  }

  private static class LocallyBuiltTestAbstractCachingBuildRule
      extends TestAbstractCachingBuildRule {
    LocallyBuiltTestAbstractCachingBuildRule(BuildRuleParams buildRuleParams) {
      super(buildRuleParams);
    }

    @Override
    public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext) {
      return ImmutableList.of();
    }
  }

  /**
   * Subclass of {@link Capture} that, when its {@link File} value is set, takes the location of
   * that {@link File} and writes a zip file there with the entries specified to the constructor of
   * {@link CaptureThatWritesAZipFile}.
   * <p>
   * This makes it possible to capture a call to {@link ArtifactCache#store(RuleKey, File)} and
   * ensure that there will be a zip file in place immediately after the captured method has been
   * invoked.
   */
  @SuppressWarnings("serial")
  private static class CaptureThatWritesAZipFile extends Capture<File> {

    private final Map<String, String> desiredEntries;

    public CaptureThatWritesAZipFile(Map<String, String> desiredEntries) {
      this.desiredEntries = ImmutableMap.copyOf(desiredEntries);
    }

    @Override
    public void setValue(File file) {
      super.setValue(file);

      // This must have the side-effect of writing a zip file in the specified place.
      try {
        writeEntries(file);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
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
  }

  /**
   * @return a RuleKey with the bits of the hash in reverse order, just to be different.
   */
  private static RuleKey reverse(RuleKey ruleKey) {
    String hash = ruleKey.getHashCode().toString();
    String reverseHash = new StringBuilder(hash).reverse().toString();
    return new RuleKey(reverseHash);
  }
}
