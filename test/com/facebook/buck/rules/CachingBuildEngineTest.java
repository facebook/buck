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

import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.isA;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.ArtifactInfo;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.artifact_cache.InMemoryArtifactCache;
import com.facebook.buck.artifact_cache.NoopArtifactCache;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.CommandEvent;
import com.facebook.buck.event.FakeBuckEventListener;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.io.BorrowablePath;
import com.facebook.buck.io.LazyPath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.keys.AbiRuleKeyFactory;
import com.facebook.buck.rules.keys.DefaultDependencyFileRuleKeyFactory;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.DependencyFileEntry;
import com.facebook.buck.rules.keys.DependencyFileRuleKeyFactory;
import com.facebook.buck.rules.keys.InputBasedRuleKeyFactory;
import com.facebook.buck.rules.keys.InputCountingRuleKeyFactory;
import com.facebook.buck.rules.keys.SupportsDependencyFileRuleKey;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.NullFileHashCache;
import com.facebook.buck.util.concurrent.ListeningMultiSemaphore;
import com.facebook.buck.util.concurrent.MoreFutures;
import com.facebook.buck.util.concurrent.ResourceAllocationFairness;
import com.facebook.buck.util.concurrent.ResourceAmounts;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.facebook.buck.zip.CustomZipEntry;
import com.facebook.buck.zip.CustomZipOutputStream;
import com.facebook.buck.zip.ZipConstants;
import com.facebook.buck.zip.ZipOutputStreams;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.AbstractListeningExecutorService;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import org.easymock.EasyMockSupport;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsInstanceOf;
import org.hamcrest.core.StringContains;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Ensuring that build rule caching works correctly in Buck is imperative for both its performance
 * and correctness.
 */
@SuppressWarnings("PMD.TestClassWithoutTestCases")
@RunWith(Enclosed.class)
public class CachingBuildEngineTest {

  private static final BuildTarget BUILD_TARGET =
      BuildTargetFactory.newInstance("//src/com/facebook/orca:orca");
  private static final SourcePathResolver DEFAULT_SOURCE_PATH_RESOLVER =
      new SourcePathResolver(
          new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
  private static final long NO_INPUT_FILE_SIZE_LIMIT = Long.MAX_VALUE;
  private static final DefaultRuleKeyFactory NOOP_RULE_KEY_FACTORY =
      new DefaultRuleKeyFactory(
          0,
          new NullFileHashCache(),
          DEFAULT_SOURCE_PATH_RESOLVER);
  private static final InputBasedRuleKeyFactory NOOP_INPUT_BASED_RULE_KEY_FACTORY =
      new InputBasedRuleKeyFactory(
          0,
          new NullFileHashCache(),
          DEFAULT_SOURCE_PATH_RESOLVER,
          NO_INPUT_FILE_SIZE_LIMIT);
  private static final DependencyFileRuleKeyFactory NOOP_DEP_FILE_RULE_KEY_FACTORY =
      new DefaultDependencyFileRuleKeyFactory(
          0,
          new NullFileHashCache(),
          DEFAULT_SOURCE_PATH_RESOLVER);
  private static final InputCountingRuleKeyFactory NOOP_INPUT_COUNTING_RULE_KEY_FACTORY =
      new InputCountingRuleKeyFactory(
          0,
          new NullFileHashCache(),
          DEFAULT_SOURCE_PATH_RESOLVER);
  private static final ObjectMapper MAPPER = ObjectMappers.newDefaultInstance();

  public abstract static class CommonFixture extends EasyMockSupport {
    @Rule
    public TemporaryPaths tmp = new TemporaryPaths();

    protected final InMemoryArtifactCache cache = new InMemoryArtifactCache();
    protected final FakeBuckEventListener listener = new FakeBuckEventListener();
    protected FakeProjectFilesystem filesystem;
    protected FileHashCache fileHashCache;
    protected BuildEngineBuildContext buildContext;
    protected BuildRuleResolver resolver;
    protected SourcePathResolver pathResolver;
    protected DefaultRuleKeyFactory defaultRuleKeyFactory;
    protected InputBasedRuleKeyFactory inputBasedRuleKeyFactory;
    protected InputCountingRuleKeyFactory inputCountingRuleKeyFactory;

    @Before
    public void setUp() {
      filesystem = new FakeProjectFilesystem(tmp.getRoot());
      fileHashCache = DefaultFileHashCache.createDefaultFileHashCache(filesystem);
      buildContext = BuildEngineBuildContext.builder()
          .setBuildContext(FakeBuildContext.NOOP_CONTEXT)
          .setArtifactCache(cache)
          .setBuildId(new BuildId())
          .setClock(new DefaultClock())
          .setObjectMapper(ObjectMappers.newDefaultInstance())
          .build();
      buildContext.getEventBus().register(listener);
      resolver =
          new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
      pathResolver = new SourcePathResolver(resolver);
      defaultRuleKeyFactory = new DefaultRuleKeyFactory(0, fileHashCache, pathResolver);
      inputBasedRuleKeyFactory = new InputBasedRuleKeyFactory(
          0,
          fileHashCache,
          pathResolver,
          NO_INPUT_FILE_SIZE_LIMIT);
      inputCountingRuleKeyFactory =
          new InputCountingRuleKeyFactory(0, fileHashCache, pathResolver);
    }


    protected CachingBuildEngineFactory cachingBuildEngineFactory() {
      return new CachingBuildEngineFactory(resolver)
          .setCachingBuildEngineDelegate(new LocalCachingBuildEngineDelegate(fileHashCache));
    }
  }

  public static class OtherTests extends CommonFixture {
    /**
     * Tests what should happen when a rule is built for the first time: it should have no cached
     * RuleKey, nor should it have any artifact in the ArtifactCache. The sequence of events should
     * be as follows:
     * <ol>
     *   <li>The build engine invokes the
     *   {@link CachingBuildEngine#build(BuildEngineBuildContext, ExecutionContext, BuildRule)}
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
      FakeBuildRule dep = new FakeBuildRule(depTarget, pathResolver);

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
          pathResolver,
          ImmutableSet.of(dep),
          buildSteps,
          /* postBuildSteps */ ImmutableList.of(),
          pathToOutputFile);
      verifyAll();
      resetAll();

      // The BuildContext that will be used by the rule's build() method.
      BuildEngineBuildContext buildContext = this.buildContext
          .withBuildContext(this.buildContext.getBuildContext().withEventBus(buckEventBus));

      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build();

      // Add a build step so we can verify that the steps are executed.
      buildSteps.add(
          new AbstractExecutionStep("Some Short Name") {
            @Override
            public StepExecutionResult execute(ExecutionContext context) throws IOException {
              filesystem.touch(ruleToTest.getPathToOutput());
              return StepExecutionResult.SUCCESS;
            }
          });

      // Attempting to build the rule should force a rebuild due to a cache miss.
      replayAll();

      cachingBuildEngine.setBuildRuleResult(
          dep,
          BuildRuleSuccessType.FETCHED_FROM_CACHE,
          CacheResult.miss());

      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), ruleToTest)
              .get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());
      buckEventBus.post(
          CommandEvent.finished(
              CommandEvent.started("build", ImmutableList.of(), false), 0));
      verifyAll();

      RuleKey ruleToTestKey = defaultRuleKeyFactory.build(ruleToTest);
      assertTrue(cache.hasArtifact(ruleToTestKey));

      // Verify the events logged to the BuckEventBus.
      List<BuckEvent> events = listener.getEvents();
      assertThat(events, hasItem(BuildRuleEvent.started(dep)));
      assertThat(
          listener.getEvents(),
          Matchers.containsInRelativeOrder(
              BuildRuleEvent.started(ruleToTest),
              BuildRuleEvent.finished(
                  ruleToTest,
                  BuildRuleKeys.of(ruleToTestKey),
                  BuildRuleStatus.SUCCESS,
                  CacheResult.miss(),
                  Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty())));
    }

    @Test
    public void testAsyncJobsAreNotLeftInExecutor()
        throws IOException, ExecutionException, InterruptedException {
      BuildRuleParams buildRuleParams = new FakeBuildRuleParamsBuilder(BUILD_TARGET)
          .setProjectFilesystem(filesystem)
          .build();
      FakeBuildRule buildRule = new FakeBuildRule(
          buildRuleParams,
          pathResolver);

      // The BuildContext that will be used by the rule's build() method.
      BuildEngineBuildContext buildContext = this.buildContext
          .withArtifactCache(
              new NoopArtifactCache() {
                @Override
                public ListenableFuture<Void> store(
                    ArtifactInfo info,
                    BorrowablePath output) {
                  try {
                    Thread.sleep(500);
                  } catch (InterruptedException e) {
                    throw Throwables.propagate(e);
                  }
                  return Futures.immediateFuture(null);
                }
              });

      ListeningExecutorService service = listeningDecorator(Executors.newFixedThreadPool(2));

      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory()
          .setExecutorService(service)
          .build();
      ListenableFuture<BuildResult> buildResult =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), buildRule);

      BuildResult result = buildResult.get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());

      assertTrue(service.shutdownNow().isEmpty());

      assertThat(
          listener.getEvents(),
          Matchers.containsInRelativeOrder(
              BuildRuleEvent.started(buildRule),
              BuildRuleEvent.finished(
                  buildRule,
                  BuildRuleKeys.of(defaultRuleKeyFactory.build(buildRule)),
                  BuildRuleStatus.SUCCESS,
                  CacheResult.miss(),
                  Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty())));
    }

    @Test
    public void testArtifactFetchedFromCache()
        throws InterruptedException, ExecutionException, IOException {
      Step step = new AbstractExecutionStep("exploding step") {
        @Override
        public StepExecutionResult execute(ExecutionContext context) {
          throw new UnsupportedOperationException("build step should not be executed");
        }
      };
      BuildRule buildRule = createRule(
          filesystem,
          pathResolver,
          /* deps */ ImmutableSet.of(),
          ImmutableList.of(step),
          /* postBuildSteps */ ImmutableList.of(),
          /* pathToOutputFile */ null);

      // Simulate successfully fetching the output file from the ArtifactCache.
      ArtifactCache artifactCache = createMock(ArtifactCache.class);
      Map<Path, String> desiredZipEntries = ImmutableMap.of(
          Paths.get("buck-out/gen/src/com/facebook/orca/orca.jar"),
          "Imagine this is the contents of a valid JAR file.",
          BuildInfo.getPathToMetadataDirectory(buildRule.getBuildTarget(), filesystem)
              .resolve(BuildInfo.MetadataKey.RECORDED_PATHS),
          MAPPER.writeValueAsString(ImmutableList.of()));
      expect(
          artifactCache.fetch(
              eq(defaultRuleKeyFactory.build(buildRule)),
              isA(LazyPath.class)))
          .andDelegateTo(
              new FakeArtifactCacheThatWritesAZipFile(desiredZipEntries));

      BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
      BuildEngineBuildContext buildContext = BuildEngineBuildContext.builder()
          .setBuildContext(
              BuildContext.builder()
                  .setActionGraph(new ActionGraph(ImmutableList.of(buildRule)))
                  .setJavaPackageFinder(createMock(JavaPackageFinder.class))
                  .setEventBus(buckEventBus)
                  .build())
          .setClock(new DefaultClock())
          .setBuildId(new BuildId())
          .setArtifactCache(artifactCache)
          .setObjectMapper(ObjectMappers.newDefaultInstance())
          .build();

      // Build the rule!
      replayAll();

      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build();

      ListenableFuture<BuildResult> buildResult =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), buildRule);
      buckEventBus.post(
          CommandEvent.finished(
              CommandEvent.started("build", ImmutableList.of(), false), 0));

      BuildResult result = buildResult.get();
      verifyAll();
      assertTrue(
          "We expect build() to be synchronous in this case, " +
              "so the future should already be resolved.",
          MoreFutures.isSuccess(buildResult));
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
      BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();

      // Add a post build step so we can verify that it's steps are executed.
      Step buildStep = createMock(Step.class);
      expect(buildStep.getDescription(anyObject(ExecutionContext.class)))
          .andReturn("Some Description")
          .anyTimes();
      expect(buildStep.getShortName()).andReturn("Some Short Name").anyTimes();
      expect(buildStep.execute(anyObject(ExecutionContext.class)))
          .andReturn(StepExecutionResult.SUCCESS);

      BuildRule buildRule = createRule(
          filesystem,
          pathResolver,
          /* deps */ ImmutableSet.of(),
          /* buildSteps */ ImmutableList.of(),
          /* postBuildSteps */ ImmutableList.of(buildStep),
          /* pathToOutputFile */ null);

      // Simulate successfully fetching the output file from the ArtifactCache.
      ArtifactCache artifactCache = createMock(ArtifactCache.class);
      Map<Path, String> desiredZipEntries = ImmutableMap.of(
          Paths.get("buck-out/gen/src/com/facebook/orca/orca.jar"),
          "Imagine this is the contents of a valid JAR file.",
          BuildInfo.getPathToMetadataDirectory(buildRule.getBuildTarget(), filesystem)
              .resolve(BuildInfo.MetadataKey.RECORDED_PATHS),
          MAPPER.writeValueAsString(ImmutableList.of()));
      expect(
          artifactCache.fetch(
              eq(defaultRuleKeyFactory.build(buildRule)),
              isA(LazyPath.class)))
          .andDelegateTo(
              new FakeArtifactCacheThatWritesAZipFile(desiredZipEntries));

      BuildEngineBuildContext buildContext = BuildEngineBuildContext.builder()
          .setBuildContext(BuildContext.builder()
              .setActionGraph(new ActionGraph(ImmutableList.of(buildRule)))
              .setJavaPackageFinder(createMock(JavaPackageFinder.class))
              .setEventBus(buckEventBus)
              .build())
          .setClock(new DefaultClock())
          .setBuildId(new BuildId())
          .setArtifactCache(artifactCache)
          .setObjectMapper(ObjectMappers.newDefaultInstance())
          .build();

      // Build the rule!
      replayAll();
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build();
      ListenableFuture<BuildResult> buildResult =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), buildRule);
      buckEventBus.post(
          CommandEvent.finished(
              CommandEvent.started("build", ImmutableList.of(), false), 0));

      BuildResult result = buildResult.get();
      verifyAll();
      assertEquals(BuildRuleSuccessType.FETCHED_FROM_CACHE, result.getSuccess());
      assertTrue(
          ((BuildableAbstractCachingBuildRule) buildRule).isInitializedFromDisk());
      assertTrue(
          "The entries in the zip should be extracted as a result of building the rule.",
          filesystem.exists(Paths.get("buck-out/gen/src/com/facebook/orca/orca.jar")));
    }

    @Test
    public void testMatchingTopLevelRuleKeyAvoidsProcessingDepInShallowMode() throws Exception {
      // Create a dep for the build rule.
      BuildTarget depTarget = BuildTargetFactory.newInstance("//src/com/facebook/orca:lib");
      FakeBuildRule dep = new FakeBuildRule(depTarget, pathResolver);
      FakeBuildRule ruleToTest = new FakeBuildRule(BUILD_TARGET, filesystem, pathResolver, dep);
      RuleKey ruleToTestKey = defaultRuleKeyFactory.build(ruleToTest);
      filesystem.writeContentsToPath(
          ruleToTestKey.toString(),
          BuildInfo.getPathToMetadataDirectory(BUILD_TARGET, filesystem)
              .resolve(BuildInfo.MetadataKey.RULE_KEY));
      filesystem.writeContentsToPath(
          MAPPER.writeValueAsString(ImmutableList.of()),
          BuildInfo.getPathToMetadataDirectory(BUILD_TARGET, filesystem)
              .resolve(BuildInfo.MetadataKey.RECORDED_PATHS));

      // The BuildContext that will be used by the rule's build() method.
      BuildEngineBuildContext context = this.buildContext
          .withArtifactCache(new NoopArtifactCache());

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build();

      // Run the build.
      replayAll();
      BuildResult result =
          cachingBuildEngine.build(context, TestExecutionContext.newInstance(), ruleToTest).get();
      assertEquals(BuildRuleSuccessType.MATCHING_RULE_KEY, result.getSuccess());
      verifyAll();

      // Verify the events logged to the BuckEventBus.
      List<BuckEvent> events = listener.getEvents();
      assertThat(events, hasItem(BuildRuleEvent.started(dep)));
      assertThat(
          events,
          Matchers.containsInRelativeOrder(
              BuildRuleEvent.started(ruleToTest),
              BuildRuleEvent.finished(
                  ruleToTest,
                  BuildRuleKeys.of(ruleToTestKey),
                  BuildRuleStatus.SUCCESS,
                  CacheResult.localKeyUnchangedHit(),
                  Optional.of(BuildRuleSuccessType.MATCHING_RULE_KEY),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty())));
    }

    @Test
    public void testMatchingTopLevelRuleKeyStillProcessesDepInDeepMode() throws Exception {
      // Create a dep for the build rule.
      BuildTarget depTarget = BuildTargetFactory.newInstance("//src/com/facebook/orca:lib");
      BuildRuleParams ruleParams = new FakeBuildRuleParamsBuilder(depTarget)
          .setProjectFilesystem(filesystem)
          .build();
      FakeBuildRule dep = new FakeBuildRule(ruleParams, pathResolver);
      RuleKey depKey = defaultRuleKeyFactory.build(dep);
      filesystem.writeContentsToPath(
          depKey.toString(),
          BuildInfo.getPathToMetadataDirectory(depTarget, filesystem)
              .resolve(BuildInfo.MetadataKey.RULE_KEY));
      filesystem.writeContentsToPath(
          MAPPER.writeValueAsString(ImmutableList.of()),
          BuildInfo.getPathToMetadataDirectory(depTarget, filesystem)
              .resolve(BuildInfo.MetadataKey.RECORDED_PATHS));
      FakeBuildRule ruleToTest = new FakeBuildRule(BUILD_TARGET, filesystem, pathResolver, dep);
      RuleKey ruleToTestKey = defaultRuleKeyFactory.build(ruleToTest);
      filesystem.writeContentsToPath(
          ruleToTestKey.toString(),
          BuildInfo.getPathToMetadataDirectory(BUILD_TARGET, filesystem)
              .resolve(BuildInfo.MetadataKey.RULE_KEY));
      filesystem.writeContentsToPath(
          MAPPER.writeValueAsString(ImmutableList.of()),
          BuildInfo.getPathToMetadataDirectory(BUILD_TARGET, filesystem)
              .resolve(BuildInfo.MetadataKey.RECORDED_PATHS));

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory()
          .setBuildMode(CachingBuildEngine.BuildMode.DEEP)
          .build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), ruleToTest)
              .get();
      assertEquals(BuildRuleSuccessType.MATCHING_RULE_KEY, result.getSuccess());

      // Verify the events logged to the BuckEventBus.
      List<BuckEvent> events = listener.getEvents();
      assertThat(
          events,
          Matchers.containsInRelativeOrder(
              BuildRuleEvent.started(dep),
              BuildRuleEvent.finished(
                  dep,
                  BuildRuleKeys.of(depKey),
                  BuildRuleStatus.SUCCESS,
                  CacheResult.localKeyUnchangedHit(),
                  Optional.of(BuildRuleSuccessType.MATCHING_RULE_KEY),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty())));
      assertThat(
          events,
          Matchers.containsInRelativeOrder(
              BuildRuleEvent.started(ruleToTest),
              BuildRuleEvent.finished(
                  ruleToTest,
                  BuildRuleKeys.of(ruleToTestKey),
                  BuildRuleStatus.SUCCESS,
                  CacheResult.localKeyUnchangedHit(),
                  Optional.of(BuildRuleSuccessType.MATCHING_RULE_KEY),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty())));
    }

    @Test
    public void testMatchingTopLevelRuleKeyStillProcessesRuntimeDeps() throws Exception {
      // Setup a runtime dependency that is found transitively from the top-level rule.
      BuildRuleParams ruleParams = new FakeBuildRuleParamsBuilder("//:transitive_dep")
          .setProjectFilesystem(filesystem)
          .build();
      FakeBuildRule transitiveRuntimeDep =
          new FakeBuildRule(ruleParams, pathResolver);
      RuleKey transitiveRuntimeDepKey = defaultRuleKeyFactory.build(transitiveRuntimeDep);
      filesystem.writeContentsToPath(
          transitiveRuntimeDepKey.toString(),
          BuildInfo.getPathToMetadataDirectory(transitiveRuntimeDep.getBuildTarget(), filesystem)
              .resolve(BuildInfo.MetadataKey.RULE_KEY));
      filesystem.writeContentsToPath(
          MAPPER.writeValueAsString(ImmutableList.of()),
          BuildInfo.getPathToMetadataDirectory(transitiveRuntimeDep.getBuildTarget(), filesystem)
              .resolve(BuildInfo.MetadataKey.RECORDED_PATHS));

      // Setup a runtime dependency that is referenced directly by the top-level rule.
      FakeBuildRule runtimeDep =
          new FakeHasRuntimeDeps(
              BuildTargetFactory.newInstance("//:runtime_dep"),
              filesystem,
              pathResolver,
              transitiveRuntimeDep);
      RuleKey runtimeDepKey = defaultRuleKeyFactory.build(runtimeDep);
      filesystem.writeContentsToPath(
          runtimeDepKey.toString(),
          BuildInfo.getPathToMetadataDirectory(runtimeDep.getBuildTarget(), filesystem)
              .resolve(BuildInfo.MetadataKey.RULE_KEY));
      filesystem.writeContentsToPath(
          MAPPER.writeValueAsString(ImmutableList.of()),
          BuildInfo.getPathToMetadataDirectory(runtimeDep.getBuildTarget(), filesystem)
              .resolve(BuildInfo.MetadataKey.RECORDED_PATHS));

      // Create a dep for the build rule.
      FakeBuildRule ruleToTest = new FakeHasRuntimeDeps(
          BUILD_TARGET,
          filesystem,
          pathResolver,
          runtimeDep);
      RuleKey ruleToTestKey = defaultRuleKeyFactory.build(ruleToTest);
      filesystem.writeContentsToPath(
          ruleToTestKey.toString(),
          BuildInfo.getPathToMetadataDirectory(ruleToTest.getBuildTarget(), filesystem)
              .resolve(BuildInfo.MetadataKey.RULE_KEY));
      filesystem.writeContentsToPath(
          MAPPER.writeValueAsString(ImmutableList.of()),
          BuildInfo.getPathToMetadataDirectory(ruleToTest.getBuildTarget(), filesystem)
              .resolve(BuildInfo.MetadataKey.RECORDED_PATHS));

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), ruleToTest)
              .get();
      assertEquals(BuildRuleSuccessType.MATCHING_RULE_KEY, result.getSuccess());

      // Verify the events logged to the BuckEventBus.
      List<BuckEvent> events = listener.getEvents();
      assertThat(
          events,
          Matchers.containsInRelativeOrder(
              BuildRuleEvent.started(ruleToTest),
              BuildRuleEvent.finished(
                  ruleToTest,
                  BuildRuleKeys.of(ruleToTestKey),
                  BuildRuleStatus.SUCCESS,
                  CacheResult.localKeyUnchangedHit(),
                  Optional.of(BuildRuleSuccessType.MATCHING_RULE_KEY),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty())));
      assertThat(
          events,
          Matchers.containsInRelativeOrder(
              BuildRuleEvent.started(runtimeDep),
              BuildRuleEvent.finished(
                  runtimeDep,
                  BuildRuleKeys.of(runtimeDepKey),
                  BuildRuleStatus.SUCCESS,
                  CacheResult.localKeyUnchangedHit(),
                  Optional.of(BuildRuleSuccessType.MATCHING_RULE_KEY),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty())));
      assertThat(
          events,
          Matchers.containsInRelativeOrder(
              BuildRuleEvent.started(transitiveRuntimeDep),
              BuildRuleEvent.finished(
                  transitiveRuntimeDep,
                  BuildRuleKeys.of(transitiveRuntimeDepKey),
                  BuildRuleStatus.SUCCESS,
                  CacheResult.localKeyUnchangedHit(),
                  Optional.of(BuildRuleSuccessType.MATCHING_RULE_KEY),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty())));
    }

    @Test
    public void failedRuntimeDepsArePropagated() throws Exception {
      final String description = "failing step";
      Step failingStep =
          new AbstractExecutionStep(description) {
            @Override
            public StepExecutionResult execute(ExecutionContext context) throws IOException {
              return StepExecutionResult.ERROR;
            }
          };
      BuildRule ruleToTest = createRule(
          filesystem,
          pathResolver,
          /* deps */ ImmutableSet.of(),
          /* buildSteps */ ImmutableList.of(failingStep),
          /* postBuildSteps */ ImmutableList.of(),
          /* pathToOutputFile */ null);

      FakeBuildRule withRuntimeDep =
          new FakeHasRuntimeDeps(
              BuildTargetFactory.newInstance("//:with_runtime_dep"),
              filesystem,
              pathResolver,
              ruleToTest);

      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build();
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), withRuntimeDep)
              .get();

      assertThat(result.getStatus(), equalTo(BuildRuleStatus.CANCELED));
      assertThat(result.getFailure(), instanceOf(StepFailedException.class));
      assertThat(
          ((StepFailedException) result.getFailure()).getStep().getShortName(),
          equalTo(description));
    }

    @Test
    public void failedRuntimeDepsAreNotPropagatedWithKeepGoing() throws Exception {
      buildContext = this.buildContext.withKeepGoing(true);
      final String description = "failing step";
      Step failingStep =
          new AbstractExecutionStep(description) {
            @Override
            public StepExecutionResult execute(ExecutionContext context) throws IOException {
              return StepExecutionResult.ERROR;
            }
          };
      BuildRule ruleToTest = createRule(
          filesystem,
          pathResolver,
          /* deps */ ImmutableSet.of(),
          /* buildSteps */ ImmutableList.of(failingStep),
          /* postBuildSteps */ ImmutableList.of(),
          /* pathToOutputFile */ null);

      FakeBuildRule withRuntimeDep =
          new FakeHasRuntimeDeps(
              BuildTargetFactory.newInstance("//:with_runtime_dep"),
              filesystem,
              pathResolver,
              ruleToTest);

      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build();
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), withRuntimeDep)
              .get();

      assertThat(result.getStatus(), equalTo(BuildRuleStatus.SUCCESS));
    }

    @Test
    public void matchingRuleKeyDoesNotRunPostBuildSteps() throws Exception {
      // Add a post build step so we can verify that it's steps are executed.
      Step failingStep =
          new AbstractExecutionStep("test") {
            @Override
            public StepExecutionResult execute(ExecutionContext context) throws IOException {
              return StepExecutionResult.ERROR;
            }
          };
      BuildRule ruleToTest = createRule(
          filesystem,
          pathResolver,
          /* deps */ ImmutableSet.of(),
          /* buildSteps */ ImmutableList.of(),
          /* postBuildSteps */ ImmutableList.of(failingStep),
          /* pathToOutputFile */ null);
      filesystem.writeContentsToPath(
          defaultRuleKeyFactory.build(ruleToTest).toString(),
          BuildInfo.getPathToMetadataDirectory(ruleToTest.getBuildTarget(), filesystem)
              .resolve(BuildInfo.MetadataKey.RULE_KEY));
      filesystem.writeContentsToPath(
          MAPPER.writeValueAsString(ImmutableList.of()),
          BuildInfo.getPathToMetadataDirectory(ruleToTest.getBuildTarget(), filesystem)
              .resolve(BuildInfo.MetadataKey.RECORDED_PATHS));

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), ruleToTest)
              .get();
      assertEquals(BuildRuleSuccessType.MATCHING_RULE_KEY, result.getSuccess());
    }

    @Test
    public void testBuildRuleLocallyWithCacheError() throws Exception {
      // Create an artifact cache that always errors out.
      ArtifactCache cache =
          new NoopArtifactCache() {
            @Override
            public CacheResult fetch(RuleKey ruleKey, LazyPath output) {
              return CacheResult.error("cache", "error");
            }
          };

      // Use the artifact cache when running a simple rule that will build locally.
      BuildEngineBuildContext buildContext = this.buildContext.withArtifactCache(cache);

      BuildRule rule =
          new EmptyBuildRule(
              new FakeBuildRuleParamsBuilder("//:rule")
                  .setProjectFilesystem(filesystem)
                  .build(),
              pathResolver);
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build();

      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
      assertThat(result.getSuccess(), equalTo(BuildRuleSuccessType.BUILT_LOCALLY));
      assertThat(result.getCacheResult().getType(), equalTo(CacheResultType.ERROR));
    }

    @Test
    public void testExceptionMessagesAreInformative() throws Exception {
      AtomicReference<RuntimeException> throwable = new AtomicReference<>();
      BuildRule rule = new AbstractBuildRule(new FakeBuildRuleParamsBuilder("//:rule")
          .setProjectFilesystem(filesystem)
          .build(),
          pathResolver) {
        @Override
        public ImmutableList<Step> getBuildSteps(
            BuildContext context, BuildableContext buildableContext) {
          throw throwable.get();
        }

        @Nullable
        @Override
        public Path getPathToOutput() {
          return null;
        }
      };
      throwable.set(new IllegalArgumentException("bad arg"));

      Throwable thrown = cachingBuildEngineFactory().build().build(
          buildContext, TestExecutionContext.newInstance(), rule).get().getFailure();
      assertThat(thrown.getCause(), new IsInstanceOf(IllegalArgumentException.class));
      assertThat(thrown.getMessage(), new StringContains(false, "//:rule"));

      // HumanReadableExceptions shouldn't be changed.
      throwable.set(new HumanReadableException("message"));
      thrown = cachingBuildEngineFactory().build().build(
          buildContext, TestExecutionContext.newInstance(), rule).get().getFailure();
      assertEquals(throwable.get(), thrown);

      // Exceptions that contain the rule already shouldn't be changed.
      throwable.set(new IllegalArgumentException("bad arg in //:rule"));
      thrown = cachingBuildEngineFactory().build().build(
          buildContext, TestExecutionContext.newInstance(), rule).get().getFailure();
      assertEquals(throwable.get(), thrown);
    }

    @Test
    public void testDelegateCalledBeforeRuleCreation() throws Exception {
      BuildRule rule =
          new EmptyBuildRule(
              new FakeBuildRuleParamsBuilder("//:rule")
                  .setProjectFilesystem(filesystem)
                  .build(),
              pathResolver);
      final AtomicReference<BuildRule> lastRuleToBeBuilt = new AtomicReference<>();
      CachingBuildEngineDelegate testDelegate = new LocalCachingBuildEngineDelegate(fileHashCache) {
        @Override
        public void onRuleAboutToBeBuilt(BuildRule buildRule) {
          super.onRuleAboutToBeBuilt(buildRule);
          lastRuleToBeBuilt.set(buildRule);
        }
      };
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory()
          .setCachingBuildEngineDelegate(testDelegate)
          .build();
      cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
      assertThat(lastRuleToBeBuilt.get(), is(rule));
    }

    @Test
    public void buildingRuleLocallyInvalidatesOutputs() throws Exception {
      // First, write something to the output file and get it's hash.
      Path output = Paths.get("output/path");
      filesystem.mkdirs(output.getParent());
      filesystem.writeContentsToPath("something", output);
      HashCode originalHashCode = fileHashCache.get(filesystem.resolve(output));
      assertTrue(fileHashCache.willGet(output));

      // Create a simple rule which just writes something new to the output file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target)
              .setProjectFilesystem(filesystem)
              .build();
      BuildRule rule =
          new WriteFile(params, pathResolver, "something else", output, /* executable */ false);

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());

      // Verify that we have a new hash.
      HashCode newHashCode = fileHashCache.get(filesystem.resolve(output));
      assertThat(newHashCode, Matchers.not(equalTo(originalHashCode)));
    }

    @Test
    public void dependencyFailuresDoesNotOrphanOtherDependencies() throws Exception {
      ListeningExecutorService service = listeningDecorator(Executors.newFixedThreadPool(2));

      // Create a dep chain comprising one side of the dep tree of the main rule, where the first-
      // running rule fails immediately, canceling the second rule, and ophaning at least one rule
      // in the other side of the dep tree.
      BuildRule dep1 =
          new RuleWithSteps(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:dep1"))
                  .setProjectFilesystem(filesystem)
                  .build(),
              pathResolver,
              ImmutableList.of(new FailingStep()),
              /* output */ null);
      BuildRule dep2 =
          new RuleWithSteps(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:dep2"))
                  .setDeclaredDeps(ImmutableSortedSet.of(dep1))
                  .setProjectFilesystem(filesystem)
                  .build(),
              pathResolver,
              ImmutableList.of(new SleepStep(0)),
              /* output */ null);

      // Create another dep chain, which is two deep with rules that just sleep.
      BuildRule dep3 =
          new RuleWithSteps(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:dep3"))
                  .setProjectFilesystem(filesystem)
                  .build(),
              pathResolver,
              ImmutableList.of(new SleepStep(300)),
              /* output */ null);
      BuildRule dep4 =
          new RuleWithSteps(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:dep4"))
                  .setDeclaredDeps(ImmutableSortedSet.of(dep3))
                  .setProjectFilesystem(filesystem)
                  .build(),
              pathResolver,
              ImmutableList.of(new SleepStep(300)),
              /* output */ null);

      // Create the top-level rule which pulls in the two sides of the dep tree.
      BuildRule rule =
          new RuleWithSteps(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule"))
                  .setDeclaredDeps(ImmutableSortedSet.of(dep2, dep4))
                  .setProjectFilesystem(filesystem)
                  .build(),
              pathResolver,
              ImmutableList.of(new SleepStep(1000)),
              /* output */ null);

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory()
          .setBuildMode(CachingBuildEngine.BuildMode.DEEP)
          .build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
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
      buildContext = this.buildContext.withKeepGoing(true);

      // Create a dep chain comprising one side of the dep tree of the main rule, where the first-
      // running rule fails immediately, canceling the second rule, and ophaning at least one rule
      // in the other side of the dep tree.
      BuildRule dep1 =
          new RuleWithSteps(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:dep1"))
                  .setProjectFilesystem(filesystem)
                  .build(),
              pathResolver,
              ImmutableList.of(new FailingStep()),
              /* output */ null);
      BuildRule dep2 =
          new RuleWithSteps(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:dep2"))
                  .setDeclaredDeps(ImmutableSortedSet.of(dep1))
                  .setProjectFilesystem(filesystem)
                  .build(),
              pathResolver,
              ImmutableList.of(new SleepStep(0)),
              /* output */ null);

      // Create another dep chain, which is two deep with rules that just sleep.
      BuildRule dep3 =
          new RuleWithSteps(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:dep3"))
                  .setProjectFilesystem(filesystem)
                  .build(),
              pathResolver,
              ImmutableList.of(new SleepStep(300)),
              /* output */ null);
      BuildRule dep4 =
          new RuleWithSteps(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:dep4"))
                  .setDeclaredDeps(ImmutableSortedSet.of(dep3))
                  .setProjectFilesystem(filesystem)
                  .build(),
              pathResolver,
              ImmutableList.of(new SleepStep(300)),
              /* output */ null);

      // Create the top-level rule which pulls in the two sides of the dep tree.
      BuildRule rule =
          new RuleWithSteps(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule"))
                  .setDeclaredDeps(ImmutableSortedSet.of(dep2, dep4))
                  .setProjectFilesystem(filesystem)
                  .build(),
              pathResolver,
              ImmutableList.of(new SleepStep(1000)),
              /* output */ null);

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory()
          .setExecutorService(service)
          .build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
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
    public void getNumRulesToBuild() throws Exception {
      BuildRule rule3 =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule3"))
              .setOut("out3")
              .build(resolver);
      BuildRule rule2 =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule2"))
              .setOut("out2")
              .setSrcs(
                  ImmutableList.of(
                      new BuildTargetSourcePath(rule3.getBuildTarget())))
              .build(resolver);
      BuildRule rule1 =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule1"))
              .setOut("out1")
              .setSrcs(
                  ImmutableList.of(
                      new BuildTargetSourcePath(rule2.getBuildTarget())))
              .build(resolver);

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory()
          .setCachingBuildEngineDelegate(
              new LocalCachingBuildEngineDelegate(new NullFileHashCache()))
          .build();

      assertThat(
          cachingBuildEngine.getNumRulesToBuild(ImmutableList.of(rule1)),
          equalTo(3));
    }

    @Test
    public void artifactCacheSizeLimit() throws Exception {
      // Create a simple rule which just writes something new to the output file.
      BuildRule rule =
          new WriteFile(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule"))
                  .setProjectFilesystem(filesystem)
                  .build(),
              pathResolver,
              "data",
              Paths.get("output/path"),
              /* executable */ false);

      // Create the build engine with low cache artifact limit which prevents caching the above\
      // rule.
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory()
          .setCachingBuildEngineDelegate(
              new LocalCachingBuildEngineDelegate(new NullFileHashCache()))
          .setArtifactCacheSizeLimit(Optional.of(2L))
          .build();

      // Verify that after building successfully, nothing is cached.
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
      assertThat(result.getSuccess(), equalTo(BuildRuleSuccessType.BUILT_LOCALLY));
      assertTrue(cache.isEmpty());
    }

    @Test
    public void fetchingFromCacheSeedsFileHashCache() throws Throwable {
      // Create a simple rule which just writes something new to the output file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      Path output = filesystem.getRootPath().getFileSystem().getPath("output/path");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target)
              .setProjectFilesystem(filesystem)
              .build();
      BuildRule rule =
          new WriteFile(params, pathResolver, "something else", output, /* executable */ false);

      // Run an initial build to seed the cache.
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory()
          .build();
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
      assertEquals(
          BuildRuleSuccessType.BUILT_LOCALLY,
          result.getSuccess());

      // Clear the file system.
      filesystem.clear();

      // Now run a second build that gets a cache hit.  We use an empty `FakeFileHashCache` which
      // does *not* contain the path, so any attempts to hash it will fail.
      FakeFileHashCache fakeFileHashCache =
          new FakeFileHashCache(new HashMap<Path, HashCode>());
      cachingBuildEngine = cachingBuildEngineFactory()
          .setCachingBuildEngineDelegate(new LocalCachingBuildEngineDelegate(fakeFileHashCache))
          .build();
      result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
      assertEquals(
          BuildRuleSuccessType.FETCHED_FROM_CACHE,
          result.getSuccess());

      // Verify that the cache hit caused the file hash cache to contain the path.
      assertTrue(fakeFileHashCache.contains(filesystem.resolve(output)));
    }

  }

  public static class InputBasedRuleKeyTests extends CommonFixture {
    @Test
    public void inputBasedRuleKeyAndArtifactAreWrittenForSupportedRules() throws Exception {
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
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }
            @Override
            public Path getPathToOutput() {
              return output;
            }
          };

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory()
          .setRuleKeyFactoriesFunction(
              Functions.constant(
                  new CachingBuildEngine.RuleKeyFactories(
                      NOOP_RULE_KEY_FACTORY,
                      new FakeInputBasedRuleKeyFactory(ImmutableMap.of(
                          rule.getBuildTarget(),
                          Optional.of(inputRuleKey))),
                      NOOP_RULE_KEY_FACTORY,
                      NOOP_DEP_FILE_RULE_KEY_FACTORY,
                      NOOP_INPUT_COUNTING_RULE_KEY_FACTORY)))
          .build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());

      // Verify that the artifact was indexed in the cache by the input rule key.
      assertTrue(cache.hasArtifact(inputRuleKey));

      // Verify the input rule key was written to disk.
      OnDiskBuildInfo onDiskBuildInfo = buildContext.createOnDiskBuildInfoFor(target, filesystem);
      assertThat(
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY),
          equalTo(Optional.of(inputRuleKey)));
    }

    @Test
    public void inputBasedRuleKeyMatchAvoidsBuildingLocally() throws Exception {
      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target)
              .setProjectFilesystem(filesystem)
              .build();
      final RuleKey inputRuleKey = new RuleKey("aaaa");
      final BuildRule rule = new FailingInputRuleKeyBuildRule(params, pathResolver);

      // Create the output file.
      filesystem.writeContentsToPath("stuff", rule.getPathToOutput());

      // Prepopulate the recorded paths metadata.
      filesystem.writeContentsToPath(
          MAPPER.writeValueAsString(ImmutableList.of(rule.getPathToOutput().toString())),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.RECORDED_PATHS));

      // Prepopulate the input rule key on disk, so that we avoid a rebuild.
      filesystem.writeContentsToPath(
          inputRuleKey.toString(),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY));

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory()
          .setRuleKeyFactoriesFunction(
              Functions.constant(
                  new CachingBuildEngine.RuleKeyFactories(
                      defaultRuleKeyFactory,
                      new FakeInputBasedRuleKeyFactory(
                          ImmutableMap.of(rule.getBuildTarget(), Optional.of(inputRuleKey))),
                      NOOP_RULE_KEY_FACTORY,
                      NOOP_DEP_FILE_RULE_KEY_FACTORY,
                      NOOP_INPUT_COUNTING_RULE_KEY_FACTORY)))
          .build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
      assertEquals(BuildRuleSuccessType.MATCHING_INPUT_BASED_RULE_KEY, result.getSuccess());

      // Verify the input-based and actual rule keys were updated on disk.
      OnDiskBuildInfo onDiskBuildInfo = buildContext.createOnDiskBuildInfoFor(target, filesystem);
      assertThat(
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.RULE_KEY),
          equalTo(Optional.of(defaultRuleKeyFactory.build(rule))));

      // Verify that the artifact is *not* re-cached under the main rule key.
      LazyPath fetchedArtifact = LazyPath.ofInstance(
          tmp.newFile("fetched_artifact.zip"));
      assertThat(
          cache.fetch(defaultRuleKeyFactory.build(rule), fetchedArtifact).getType(),
          equalTo(CacheResultType.MISS));
    }

    @Test
    public void inputBasedRuleKeyCacheHitAvoidsBuildingLocally() throws Exception {
      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      final RuleKey inputRuleKey = new RuleKey("aaaa");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target)
              .setProjectFilesystem(filesystem)
              .build();
      final BuildRule rule = new FailingInputRuleKeyBuildRule(params, pathResolver);

      // Prepopulate the recorded paths metadata.
      filesystem.writeContentsToPath(
          MAPPER.writeValueAsString(ImmutableList.of(rule.getPathToOutput().toString())),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.RECORDED_PATHS));

      // Prepopulate the cache with an artifact indexed by the input-based rule key.
      Path artifact = tmp.newFile("artifact.zip");
      writeEntriesToZip(
          artifact,
          ImmutableMap.of(
              BuildInfo.getPathToMetadataDirectory(target, filesystem)
                  .resolve(BuildInfo.MetadataKey.RECORDED_PATHS),
              MAPPER.writeValueAsString(ImmutableList.of(rule.getPathToOutput().toString())),
              rule.getPathToOutput(),
              "stuff"));
      cache.store(
          ArtifactInfo.builder()
              .addRuleKeys(inputRuleKey)
              .setMetadata(
                  ImmutableMap.of(
                      BuildInfo.MetadataKey.RULE_KEY,
                      new RuleKey("bbbb").toString(),
                      BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY,
                      inputRuleKey.toString()))
              .build(),
          BorrowablePath.notBorrowablePath(artifact));

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory()
          .setRuleKeyFactoriesFunction(
              Functions.constant(
                  new CachingBuildEngine.RuleKeyFactories(
                      defaultRuleKeyFactory,
                      new FakeInputBasedRuleKeyFactory(
                          ImmutableMap.of(rule.getBuildTarget(), Optional.of(inputRuleKey))),
                      NOOP_RULE_KEY_FACTORY,
                      NOOP_DEP_FILE_RULE_KEY_FACTORY,
                      NOOP_INPUT_COUNTING_RULE_KEY_FACTORY)))
          .build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
      assertEquals(BuildRuleSuccessType.FETCHED_FROM_CACHE_INPUT_BASED, result.getSuccess());

      // Verify the input-based and actual rule keys were updated on disk.
      OnDiskBuildInfo onDiskBuildInfo = buildContext.createOnDiskBuildInfoFor(target, filesystem);
      assertThat(
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.RULE_KEY),
          equalTo(Optional.of(defaultRuleKeyFactory.build(rule))));
      assertThat(
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY),
          equalTo(Optional.of(inputRuleKey)));

      // Verify that the artifact is re-cached correctly under the main rule key.
      Path fetchedArtifact = tmp.newFile("fetched_artifact.zip");
      assertThat(
          cache.fetch(defaultRuleKeyFactory.build(rule), LazyPath.ofInstance(fetchedArtifact))
              .getType(),
          equalTo(CacheResultType.HIT));
      assertEquals(
          new ZipInspector(artifact).getZipFileEntries(),
          new ZipInspector(fetchedArtifact).getZipFileEntries());
      MoreAsserts.assertContentsEqual(artifact, fetchedArtifact);
    }

    @Test
    public void missingInputBasedRuleKeyDoesNotMatchExistingRuleKey() throws Exception {
      missingInputBasedRuleKeyCausesLocalBuild(Optional.of(new RuleKey("aaaa")));
    }

    @Test
    public void missingInputBasedRuleKeyDoesNotMatchAbsentRuleKey() throws Exception {
      missingInputBasedRuleKeyCausesLocalBuild(Optional.empty());
    }

    private void missingInputBasedRuleKeyCausesLocalBuild(Optional<RuleKey> previousRuleKey)
        throws Exception {
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      final Path output = Paths.get("output");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target)
              .setProjectFilesystem(filesystem)
              .build();
      final BuildRule rule =
          new InputRuleKeyBuildRule(params, pathResolver) {
            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context,
                BuildableContext buildableContext) {
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }
            @Override
            public Path getPathToOutput() {
              return output;
            }
          };

      // Create the output file.
      filesystem.writeContentsToPath("stuff", rule.getPathToOutput());

      // Prepopulate the recorded paths metadata.
      filesystem.writeContentsToPath(
          MAPPER.writeValueAsString(ImmutableList.of(rule.getPathToOutput().toString())),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.RECORDED_PATHS));

      if (previousRuleKey.isPresent()) {
        // Prepopulate the input rule key on disk.
        filesystem.writeContentsToPath(
            previousRuleKey.get().toString(),
            BuildInfo.getPathToMetadataDirectory(target, filesystem)
                .resolve(BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY));
      }

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory()
          .setRuleKeyFactoriesFunction(
              Functions.constant(
                  new CachingBuildEngine.RuleKeyFactories(
                      defaultRuleKeyFactory,
                      new FakeInputBasedRuleKeyFactory(
                          ImmutableMap.of(rule.getBuildTarget(), Optional.empty())),
                      NOOP_RULE_KEY_FACTORY,
                      NOOP_DEP_FILE_RULE_KEY_FACTORY,
                      NOOP_INPUT_COUNTING_RULE_KEY_FACTORY)))
          .build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());

      // Verify the input-based and actual rule keys were updated on disk.
      OnDiskBuildInfo onDiskBuildInfo = buildContext.createOnDiskBuildInfoFor(target, filesystem);
      assertThat(
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.RULE_KEY),
          equalTo(Optional.of(defaultRuleKeyFactory.build(rule))));
      assertThat(
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY),
          equalTo(Optional.empty()));
    }

    private static class FailingInputRuleKeyBuildRule extends InputRuleKeyBuildRule {
      public FailingInputRuleKeyBuildRule(
          BuildRuleParams buildRuleParams,
          SourcePathResolver resolver) {
        super(buildRuleParams, resolver);
      }

      @Override
      public ImmutableList<Step> getBuildSteps(
          BuildContext context,
          BuildableContext buildableContext) {
        return ImmutableList.of(
            new AbstractExecutionStep("false") {
              @Override
              public StepExecutionResult execute(ExecutionContext context) {
                return StepExecutionResult.ERROR;
              }
            });
      }
      @Override
      public Path getPathToOutput() {
        return Paths.get("output");
      }
    }
  }

  public static class DepFileTests extends CommonFixture {

    private DefaultDependencyFileRuleKeyFactory depFileFactory;

    @Before
    public void setUpDepFileFixture() {
      depFileFactory = new DefaultDependencyFileRuleKeyFactory(
          0,
          fileHashCache,
          pathResolver);
    }

    @Test
    public void depFileRuleKeyAndDepFileAreWrittenForSupportedRules() throws Exception {
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
      final DepFileBuildRule rule =
          new DepFileBuildRule(params, pathResolver) {
            @AddToRuleKey
            private final SourcePath path = new BuildTargetSourcePath(genrule.getBuildTarget());
            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context,
                BuildableContext buildableContext) {
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }

            @Override
            public Optional<ImmutableSet<SourcePath>> getPossibleInputSourcePaths() {
              return Optional.empty();
            }

            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally() {
              return ImmutableList.of(new PathSourcePath(filesystem, input));
            }
            @Override
            public Path getPathToOutput() {
              return output;
            }
          };

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine = engineWithDepFileFactory(depFileFactory);

      // Run the build.
      RuleKey depFileRuleKey = depFileFactory.build(
          rule,
          ImmutableList.of(DependencyFileEntry.of(input, Optional.empty())))
          .get().getFirst();
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, getSuccess(result));

      // Verify the dep file rule key and dep file contents were written to disk.
      OnDiskBuildInfo onDiskBuildInfo = buildContext.createOnDiskBuildInfoFor(target, filesystem);
      assertThat(
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY),
          equalTo(Optional.of(depFileRuleKey)));
      assertThat(
          onDiskBuildInfo.getValues(BuildInfo.MetadataKey.DEP_FILE),
          equalTo(Optional.of(ImmutableList.of(fileToDepFileEntryString(input)))));

      // Verify that the dep file rule key and dep file were written to the cached artifact.
      Path fetchedArtifact = tmp.newFile("fetched_artifact.zip");
      CacheResult cacheResult =
          cache.fetch(defaultRuleKeyFactory.build(rule), LazyPath.ofInstance(fetchedArtifact));
      assertThat(
          cacheResult.getType(),
          equalTo(CacheResultType.HIT));
      assertThat(
          cacheResult.getMetadata().get(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY),
          equalTo(depFileRuleKey.toString()));
      ZipInspector inspector = new ZipInspector(fetchedArtifact);
      inspector.assertFileContents(
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.DEP_FILE),
          MAPPER.writeValueAsString(ImmutableList.of(fileToDepFileEntryString(input))));
    }

    @Test
    public void depFileRuleKeyMatchAvoidsBuilding() throws Exception {
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
              return ImmutableList.of(
                  new AbstractExecutionStep("false") {
                    @Override
                    public StepExecutionResult execute(ExecutionContext context) {
                      return StepExecutionResult.ERROR;
                    }
                  });
            }
            @Override
            public Optional<ImmutableSet<SourcePath>> getPossibleInputSourcePaths() {
              return Optional.empty();
            }
            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally() {
              return ImmutableList.of(new PathSourcePath(filesystem, input));
            }
            @Override
            public Path getPathToOutput() {
              return output;
            }
          };

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine = engineWithDepFileFactory(
          new FakeRuleKeyFactory(
              ImmutableMap.of(rule.getBuildTarget(), depFileRuleKey),
              fileHashCache));

      // Prepopulate the dep file rule key and dep file.
      filesystem.writeContentsToPath(
          depFileRuleKey.toString(),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY));
      filesystem.writeContentsToPath(
          MAPPER.writeValueAsString(ImmutableList.of(fileToDepFileEntryString(input))),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.DEP_FILE));

      // Prepopulate the recorded paths metadata.
      filesystem.writeContentsToPath(
          MAPPER.writeValueAsString(ImmutableList.of(output.toString())),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.RECORDED_PATHS));

      // Run the build.
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
      assertEquals(BuildRuleSuccessType.MATCHING_DEP_FILE_RULE_KEY, result.getSuccess());
    }

    @Test
    public void depFileInputChangeCausesRebuild() throws Exception {
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
      DepFileBuildRule rule =
          new DepFileBuildRule(params, pathResolver) {
            @AddToRuleKey
            private final SourcePath path = new BuildTargetSourcePath(genrule.getBuildTarget());
            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context,
                BuildableContext buildableContext) {
              buildableContext.addMetadata(
                  BuildInfo.MetadataKey.DEP_FILE,
                  ImmutableList.of(fileToDepFileEntryString(input)));
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }
            @Override
            public Optional<ImmutableSet<SourcePath>> getPossibleInputSourcePaths() {
              return Optional.empty();
            }
            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally() {
              return ImmutableList.of(new PathSourcePath(filesystem, input));
            }
            @Override
            public Path getPathToOutput() {
              return output;
            }
          };

      // Prepare an input file that should appear in the dep file.
      filesystem.writeContentsToPath("something", input);
      RuleKey depFileRuleKey = depFileFactory.build(
          rule,
          ImmutableList.of(DependencyFileEntry.of(input, Optional.empty())))
          .get().getFirst();

      // Prepopulate the dep file rule key and dep file.
      filesystem.writeContentsToPath(
          depFileRuleKey.toString(),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY));
      filesystem.writeContentsToPath(
          MAPPER.writeValueAsString(ImmutableList.of(fileToDepFileEntryString(input))),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.DEP_FILE));

      // Prepopulate the recorded paths metadata.
      filesystem.writeContentsToPath(
          MAPPER.writeValueAsString(ImmutableList.of(output.toString())),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.RECORDED_PATHS));

      // Now modify the input file and invalidate it in the cache.
      filesystem.writeContentsToPath("something else", input);
      fileHashCache.invalidate(filesystem.resolve(input));

      // Run the build.
      CachingBuildEngine cachingBuildEngine = engineWithDepFileFactory(depFileFactory);
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, getSuccess(result));
    }

    @Test
    public void nonDepFileEligibleInputChangeCausesRebuild() throws Exception {
      final Path inputFile = Paths.get("input");

      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target)
              .setProjectFilesystem(filesystem)
              .build();
      final Path output = Paths.get("output");
      final ImmutableSet<SourcePath> inputsBefore = ImmutableSet.of();
      DepFileBuildRule rule =
          new DepFileBuildRule(params, pathResolver) {
            @AddToRuleKey
            private final SourcePath path = new PathSourcePath(filesystem, inputFile);
            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context,
                BuildableContext buildableContext) {
              buildableContext.addMetadata(
                  BuildInfo.MetadataKey.DEP_FILE,
                  ImmutableList.of());
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }
            @Override
            public Optional<ImmutableSet<SourcePath>> getPossibleInputSourcePaths() {
              return Optional.of(inputsBefore);
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

      // Prepare an input file that will not appear in the dep file. This is to simulate a
      // a dependency that the dep-file generator is not aware of.
      filesystem.writeContentsToPath("something", inputFile);

      // Prepopulate the dep file rule key and dep file.
      RuleKey depFileRuleKey = depFileFactory.build(
          rule,
          ImmutableList.of()).get().getFirst();
      filesystem.writeContentsToPath(
          depFileRuleKey.toString(),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY));
      final String emptyDepFileContents = "[]";
      filesystem.writeContentsToPath(
          emptyDepFileContents,
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.DEP_FILE));

      // Prepopulate the recorded paths metadata.
      filesystem.writeContentsToPath(
          MAPPER.writeValueAsString(ImmutableList.of(output.toString())),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.RECORDED_PATHS));

      // Now modify the input file and invalidate it in the cache.
      filesystem.writeContentsToPath("something else", inputFile);
      fileHashCache.invalidate(filesystem.resolve(inputFile));

      // Run the build.
      CachingBuildEngine cachingBuildEngine = engineWithDepFileFactory(depFileFactory);
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();

      // The dep file should still be empty, yet the target will rebuild because of the change
      // to the non-dep-file-eligible input file
      String newDepFile = filesystem.readLines(
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.DEP_FILE)).get(0);
      assertEquals(emptyDepFileContents, newDepFile);
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, getSuccess(result));
    }

    @Test
    public void depFileDeletedInputCausesRebuild() throws Exception {
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
      DepFileBuildRule rule =
          new DepFileBuildRule(params, pathResolver) {
            @AddToRuleKey
            private final SourcePath path = new BuildTargetSourcePath(genrule.getBuildTarget());
            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context,
                BuildableContext buildableContext) {
              buildableContext.addMetadata(
                  BuildInfo.MetadataKey.DEP_FILE,
                  ImmutableList.of(fileToDepFileEntryString(input)));
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }
            @Override
            public Optional<ImmutableSet<SourcePath>> getPossibleInputSourcePaths() {
              return Optional.empty();
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
      RuleKey depFileRuleKey = depFileFactory.build(
          rule,
          ImmutableList.of(DependencyFileEntry.of(input, Optional.empty())))
          .get().getFirst();

      // Prepopulate the dep file rule key and dep file.
      filesystem.writeContentsToPath(
          depFileRuleKey.toString(),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY));
      filesystem.writeContentsToPath(
          MAPPER.writeValueAsString(ImmutableList.of(fileToDepFileEntryString(input))),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.DEP_FILE));

      // Prepopulate the recorded paths metadata.
      filesystem.writeContentsToPath(
          MAPPER.writeValueAsString(ImmutableList.of(output.toString())),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.RECORDED_PATHS));

      // Now delete the input and invalidate it in the cache.
      filesystem.deleteFileAtPath(input);
      fileHashCache.invalidate(filesystem.resolve(input));

      // Run the build.
      CachingBuildEngine cachingBuildEngine = engineWithDepFileFactory(depFileFactory);
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, getSuccess(result));
    }

    @Test
    public void missingDepFileKeyCausesLocalBuild() throws Exception {
      // Use a genrule to produce the input file.
      final Genrule genrule =
          (Genrule) GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
              .setOut("input")
              .build(resolver, filesystem);
      final Path input = Preconditions.checkNotNull(genrule.getPathToOutput());

      // Create a simple rule which just writes a file.
      final BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target)
              .setProjectFilesystem(filesystem)
              .build();
      final Path output = Paths.get("output");
      DepFileBuildRule rule =
          new DepFileBuildRule(params, pathResolver) {
            @AddToRuleKey
            private final SourcePath path = new BuildTargetSourcePath(genrule.getBuildTarget());
            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context,
                BuildableContext buildableContext) {
              buildableContext.addMetadata(
                  BuildInfo.MetadataKey.DEP_FILE,
                  ImmutableList.of(fileToDepFileEntryString(input)));
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }
            @Override
            public Optional<ImmutableSet<SourcePath>> getPossibleInputSourcePaths() {
              return Optional.empty();
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

      DependencyFileRuleKeyFactory depFileRuleKeyFactory =
          new DependencyFileRuleKeyFactory() {
            @Override
            public Optional<Pair<RuleKey, ImmutableSet<SourcePath>>> build(
                SupportsDependencyFileRuleKey rule,
                ImmutableList<DependencyFileEntry> inputs) {
              if (rule.getBuildTarget().equals(target)) {
                return Optional.empty();
              }

              throw new AssertionError();
            }

            @Override
            public Optional<Pair<RuleKey, ImmutableSet<SourcePath>>> buildManifestKey(
                SupportsDependencyFileRuleKey rule) {
              if (rule.getBuildTarget().equals(target)) {
                return Optional.empty();
              }

              throw new AssertionError();
            }
          };

      // Prepare an input file that should appear in the dep file.
      filesystem.writeContentsToPath("something", input);

      RuleKey depFileRuleKey = depFileFactory.build(
          rule,
          ImmutableList.of(DependencyFileEntry.of(input, Optional.empty())))
          .get().getFirst();

      // Prepopulate the dep file rule key and dep file.
      filesystem.writeContentsToPath(
          depFileRuleKey.toString(),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY));
      filesystem.writeContentsToPath(
          MAPPER.writeValueAsString(ImmutableList.of(fileToDepFileEntryString(input))),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.DEP_FILE));

      // Prepopulate the recorded paths metadata.
      filesystem.writeContentsToPath(
          MAPPER.writeValueAsString(ImmutableList.of(output.toString())),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.RECORDED_PATHS));

      // Run the build.
      CachingBuildEngine cachingBuildEngine =
          engineWithDepFileFactory(depFileRuleKeyFactory);
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, getSuccess(result));

      // Verify the input-based and actual rule keys were updated on disk.
      OnDiskBuildInfo onDiskBuildInfo = buildContext.createOnDiskBuildInfoFor(target, filesystem);
      assertThat(
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.RULE_KEY),
          equalTo(Optional.of(defaultRuleKeyFactory.build(rule))));
      assertThat(
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY),
          equalTo(Optional.empty()));
    }

    public CachingBuildEngine engineWithDepFileFactory(
        DependencyFileRuleKeyFactory depFileFactory) {
      return cachingBuildEngineFactory()
          .setRuleKeyFactoriesFunction(
              Functions.constant(
                  new CachingBuildEngine.RuleKeyFactories(
                      defaultRuleKeyFactory,
                      NOOP_INPUT_BASED_RULE_KEY_FACTORY,
                      NOOP_RULE_KEY_FACTORY,
                      depFileFactory,
                      inputCountingRuleKeyFactory)))
          .build();
    }
  }

  public static class ManifestTests extends CommonFixture {
    @Test
    public void manifestIsWrittenWhenBuiltLocally() throws Exception {
      DefaultDependencyFileRuleKeyFactory depFilefactory =
          new DefaultDependencyFileRuleKeyFactory(
              0,
              fileHashCache,
              pathResolver);

      // Use a genrule to produce the input file.
      final Genrule genrule =
          (Genrule) GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
              .setOut("input")
              .build(resolver, filesystem);
      final Path input = Preconditions.checkNotNull(genrule.getPathToOutput());
      filesystem.writeContentsToPath("contents", input);

      // Create another input that will be ineligible for the dep file. Such inputs should still
      // be part of the manifest.
      final Path input2 = Paths.get("input2");
      filesystem.writeContentsToPath("contents2", input2);

      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target)
              .setProjectFilesystem(filesystem)
              .build();
      final Path output = Paths.get("output");
      DepFileBuildRule rule =
          new DepFileBuildRule(params, pathResolver) {
            @AddToRuleKey
            private final SourcePath path = new BuildTargetSourcePath(genrule.getBuildTarget());
            @AddToRuleKey
            private final SourcePath otherDep = new PathSourcePath(filesystem, input2);
            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context,
                BuildableContext buildableContext) {
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }
            @Override
            public Optional<ImmutableSet<SourcePath>> getPossibleInputSourcePaths() {
              return Optional.of(ImmutableSet.of(path));
            }
            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally() {
              return ImmutableList.of(path);
            }
            @Override
            public Path getPathToOutput() {
              return output;
            }
          };

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setDepFiles(CachingBuildEngine.DepFiles.CACHE)
              .setRuleKeyFactoriesFunction(
                   Functions.constant(
                      new CachingBuildEngine.RuleKeyFactories(
                          defaultRuleKeyFactory,
                          inputBasedRuleKeyFactory,
                          defaultRuleKeyFactory,
                          depFilefactory,
                          inputCountingRuleKeyFactory)))
              .build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
      assertThat(
          getSuccess(result),
          equalTo(BuildRuleSuccessType.BUILT_LOCALLY));

      OnDiskBuildInfo onDiskBuildInfo = buildContext.createOnDiskBuildInfoFor(target, filesystem);
      RuleKey depFileRuleKey =
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY).get();

      // Verify that the manifest written to the cache is correct.
      Path fetchedManifest = tmp.newFile("manifest");
      CacheResult cacheResult =
          cache.fetch(
              cachingBuildEngine.getManifestRuleKey(rule).get(),
              LazyPath.ofInstance(fetchedManifest));
      assertThat(
          cacheResult.getType(),
          equalTo(CacheResultType.HIT));
      Manifest manifest = loadManifest(fetchedManifest);
      // The manifest should only contain the inputs that were in the dep file. The non-eligible
      // dependency went toward computing the manifest key and thus doesn't need to be in the value.
      assertThat(
          manifest.toMap(),
          equalTo(
              ImmutableMap.of(
                  depFileRuleKey,
                  ImmutableMap.of(
                      input.toString(),
                      fileHashCache.get(filesystem.resolve(input))))));

      // Verify that the artifact is also cached via the dep file rule key.
      Path fetchedArtifact = tmp.newFile("artifact");
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
      DefaultDependencyFileRuleKeyFactory depFilefactory =
          new DefaultDependencyFileRuleKeyFactory(
              0,
              fileHashCache,
              pathResolver);

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
      DepFileBuildRule rule =
          new DepFileBuildRule(params, pathResolver) {
            @AddToRuleKey
            private final SourcePath path = new BuildTargetSourcePath(genrule.getBuildTarget());
            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context,
                BuildableContext buildableContext) {
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }
            @Override
            public Optional<ImmutableSet<SourcePath>> getPossibleInputSourcePaths() {
              return Optional.empty();
            }
            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally() {
              return ImmutableList.of(new PathSourcePath(filesystem, input));
            }
            @Override
            public Path getPathToOutput() {
              return output;
            }
          };

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setDepFiles(CachingBuildEngine.DepFiles.CACHE)
              .setRuleKeyFactoriesFunction(
                  Functions.constant(
                      new CachingBuildEngine.RuleKeyFactories(
                          defaultRuleKeyFactory,
                          inputBasedRuleKeyFactory,
                          defaultRuleKeyFactory,
                          depFilefactory,
                          inputCountingRuleKeyFactory)))
              .build();

      // Seed the cache with an existing manifest with a dummy entry.
      Manifest manifest = Manifest.fromMap(
          ImmutableMap.of(
              new RuleKey("abcd"),
              ImmutableMap.of("some/path.h", HashCode.fromInt(12))));
      ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
      try (GZIPOutputStream outputStream = new GZIPOutputStream(byteArrayOutputStream)) {
        manifest.serialize(outputStream);
      }
      cache.store(
          ArtifactInfo.builder()
              .addRuleKeys(cachingBuildEngine.getManifestRuleKey(rule).get())
              .build(),
          byteArrayOutputStream.toByteArray());

      // Run the build.
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
      assertThat(
          getSuccess(result),
          equalTo(BuildRuleSuccessType.BUILT_LOCALLY));

      OnDiskBuildInfo onDiskBuildInfo = buildContext.createOnDiskBuildInfoFor(target, filesystem);
      RuleKey depFileRuleKey =
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY).get();

      // Verify that the manifest written to the cache is correct.
      Path fetchedManifest = tmp.newFile("manifest");
      CacheResult cacheResult =
          cache.fetch(
              cachingBuildEngine.getManifestRuleKey(rule).get(),
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
                  ImmutableMap.of(input.toString(), fileHashCache.get(filesystem.resolve(input))),
                  new RuleKey("abcd"),
                  ImmutableMap.of("some/path.h", HashCode.fromInt(12)))));

      // Verify that the artifact is also cached via the dep file rule key.
      Path fetchedArtifact = tmp.newFile("artifact");
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
      DefaultDependencyFileRuleKeyFactory depFilefactory =
          new DefaultDependencyFileRuleKeyFactory(
              0,
              fileHashCache,
              pathResolver);

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
      DepFileBuildRule rule =
          new DepFileBuildRule(params, pathResolver) {
            @AddToRuleKey
            private final SourcePath path = new BuildTargetSourcePath(genrule.getBuildTarget());
            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context,
                BuildableContext buildableContext) {
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }
            @Override
            public Optional<ImmutableSet<SourcePath>> getPossibleInputSourcePaths() {
              return Optional.empty();
            }
            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally() {
              return ImmutableList.of(new PathSourcePath(filesystem, input));
            }
            @Override
            public Path getPathToOutput() {
              return output;
            }
          };

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setDepFiles(CachingBuildEngine.DepFiles.CACHE)
              .setMaxDepFileCacheEntries(1L)
              .setRuleKeyFactoriesFunction(
                  Functions.constant(
                      new CachingBuildEngine.RuleKeyFactories(
                          defaultRuleKeyFactory,
                          inputBasedRuleKeyFactory,
                          defaultRuleKeyFactory,
                          depFilefactory,
                          inputCountingRuleKeyFactory)))
              .build();

      // Seed the cache with an existing manifest with a dummy entry so that it's already at the max
      // size.
      Manifest manifest = Manifest.fromMap(
          ImmutableMap.of(
              new RuleKey("abcd"),
              ImmutableMap.of("some/path.h", HashCode.fromInt(12))));
      ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
      try (GZIPOutputStream outputStream = new GZIPOutputStream(byteArrayOutputStream)) {
        manifest.serialize(outputStream);
      }
      cache.store(
          ArtifactInfo.builder()
              .addRuleKeys(cachingBuildEngine.getManifestRuleKey(rule).get())
              .build(),
          byteArrayOutputStream.toByteArray());

      // Run the build.
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
      assertThat(
          getSuccess(result),
          equalTo(BuildRuleSuccessType.BUILT_LOCALLY));

      OnDiskBuildInfo onDiskBuildInfo = buildContext.createOnDiskBuildInfoFor(target, filesystem);
      RuleKey depFileRuleKey =
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY).get();

      // Verify that the manifest is truncated and now only contains the newly written entry.
      Path fetchedManifest = tmp.newFile("manifest");
      CacheResult cacheResult =
          cache.fetch(
              cachingBuildEngine.getManifestRuleKey(rule).get(),
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
                  ImmutableMap.of(
                      input.toString(),
                      fileHashCache.get(filesystem.resolve(input))))));
    }

    @Test
    public void manifestBasedCacheHit() throws Exception {
      DefaultDependencyFileRuleKeyFactory depFilefactory =
          new DefaultDependencyFileRuleKeyFactory(
              0,
              fileHashCache,
              pathResolver);

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
      DepFileBuildRule rule =
          new DepFileBuildRule(params, pathResolver) {
            @AddToRuleKey
            private final SourcePath path = new BuildTargetSourcePath(genrule.getBuildTarget());
            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context,
                BuildableContext buildableContext) {
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }
            @Override
            public Optional<ImmutableSet<SourcePath>> getPossibleInputSourcePaths() {
              return Optional.empty();
            }
            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally() {
              return ImmutableList.of(new PathSourcePath(filesystem, input));
            }
            @Override
            public Path getPathToOutput() {
              return output;
            }
          };

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setDepFiles(CachingBuildEngine.DepFiles.CACHE)
              .setRuleKeyFactoriesFunction(
                  Functions.constant(
                      new CachingBuildEngine.RuleKeyFactories(
                          defaultRuleKeyFactory,
                          inputBasedRuleKeyFactory,
                          defaultRuleKeyFactory,
                          depFilefactory,
                          NOOP_INPUT_COUNTING_RULE_KEY_FACTORY)))
              .build();

      // Seed the cache with the manifest and a referenced artifact.
      RuleKey artifactKey = new RuleKey("bbbb");
      Manifest manifest = new Manifest();
      manifest.addEntry(
          fileHashCache,
          artifactKey,
          pathResolver,
          ImmutableSet.of(new PathSourcePath(filesystem, input)),
          ImmutableSet.of(new PathSourcePath(filesystem, input)));
      ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
      try (GZIPOutputStream outputStream = new GZIPOutputStream(byteArrayOutputStream)) {
        manifest.serialize(outputStream);
      }
      cache.store(
          ArtifactInfo.builder()
              .addRuleKeys(cachingBuildEngine.getManifestRuleKey(rule).get())
              .build(),
          byteArrayOutputStream.toByteArray());
      Path artifact = tmp.newFile("artifact.zip");
      writeEntriesToZip(
          artifact,
          ImmutableMap.of(
              BuildInfo.getPathToMetadataDirectory(target, filesystem)
                  .resolve(BuildInfo.MetadataKey.RECORDED_PATHS),
              MAPPER.writeValueAsString(ImmutableList.of(output.toString())),
              output,
              "stuff"));
      cache.store(
          ArtifactInfo.builder()
              .addRuleKeys(artifactKey)
              .build(),
          BorrowablePath.notBorrowablePath(artifact));

      // Run the build.
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
      assertThat(
          getSuccess(result),
          equalTo(BuildRuleSuccessType.FETCHED_FROM_CACHE_MANIFEST_BASED));
    }
  }

  public static class AbiRuleKeyTests extends CommonFixture {
    @Test
    public void abiRuleKeyIsWrittenForSupportedRules() throws Exception {
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target)
              .setProjectFilesystem(filesystem)
              .build();
      FakeAbiRuleBuildRule rule = new FakeAbiRuleBuildRule(params, pathResolver);
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory()
          .setRuleKeyFactoriesFunction(
              Functions.constant(
                  new CachingBuildEngine.RuleKeyFactories(
                      defaultRuleKeyFactory,
                      NOOP_INPUT_BASED_RULE_KEY_FACTORY,
                      new AbiRuleKeyFactory(
                          0,
                          fileHashCache,
                          pathResolver,
                          defaultRuleKeyFactory),
                      NOOP_DEP_FILE_RULE_KEY_FACTORY,
                      NOOP_INPUT_COUNTING_RULE_KEY_FACTORY)))
          .build();

      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());

      OnDiskBuildInfo onDiskBuildInfo = buildContext.createOnDiskBuildInfoFor(target, filesystem);
      Optional<RuleKey> abiRuleKey =
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.ABI_RULE_KEY);
      assertThat(abiRuleKey.isPresent(), is(true));

      // Verify that the dep file rule key and dep file were written to the cached artifact.
      Path fetchedArtifact = tmp.newFile("fetched_artifact.zip");
      CacheResult cacheResult =
          cache.fetch(defaultRuleKeyFactory.build(rule), LazyPath.ofInstance(fetchedArtifact));
      assertThat(
          cacheResult.getType(),
          equalTo(CacheResultType.HIT));
      assertThat(
          cacheResult.getMetadata().get(BuildInfo.MetadataKey.ABI_RULE_KEY),
          equalTo(abiRuleKey.get().toString()));
    }

    @Test
    public void abiRuleKeyMatchAvoidsBuilding() throws Exception {
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target)
              .setProjectFilesystem(filesystem)
              .build();
      FakeAbiRuleBuildRule rule = new FakeAbiRuleBuildRule(params, pathResolver);
      final AbiRuleKeyFactory abiRuleKeyFactory = new AbiRuleKeyFactory(
          0,
          fileHashCache,
          pathResolver,
          defaultRuleKeyFactory);
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory()
          .setRuleKeyFactoriesFunction(
              Functions.constant(
                  new CachingBuildEngine.RuleKeyFactories(
                      defaultRuleKeyFactory,
                      NOOP_INPUT_BASED_RULE_KEY_FACTORY,
                      abiRuleKeyFactory,
                      NOOP_DEP_FILE_RULE_KEY_FACTORY,
                      NOOP_INPUT_COUNTING_RULE_KEY_FACTORY)))
          .build();

      // Prepopulate the dep file rule key and dep file.
      filesystem.writeContentsToPath(
          abiRuleKeyFactory.build(rule).toString(),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.ABI_RULE_KEY));

      // Prepopulate the recorded paths metadata.
      filesystem.writeContentsToPath(
          MAPPER.writeValueAsString(ImmutableList.of()),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.RECORDED_PATHS));

      // Run the build.
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
      assertEquals(BuildRuleSuccessType.MATCHING_ABI_RULE_KEY, result.getSuccess());
    }

    @Test
    public void abiRuleKeyChangeCausesRebuild() throws Exception {
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target)
              .setProjectFilesystem(filesystem)
              .build();
      FakeAbiRuleBuildRule rule = new FakeAbiRuleBuildRule(params, pathResolver);
      final AbiRuleKeyFactory abiRuleKeyFactory = new AbiRuleKeyFactory(
          0,
          fileHashCache,
          pathResolver,
          NOOP_RULE_KEY_FACTORY);
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory()
          .setRuleKeyFactoriesFunction(
              Functions.constant(
                  new CachingBuildEngine.RuleKeyFactories(
                      NOOP_RULE_KEY_FACTORY,
                      NOOP_INPUT_BASED_RULE_KEY_FACTORY,
                      abiRuleKeyFactory,
                      NOOP_DEP_FILE_RULE_KEY_FACTORY,
                      NOOP_INPUT_COUNTING_RULE_KEY_FACTORY)))
          .build();

      // Prepopulate the dep file rule key and dep file.
      filesystem.writeContentsToPath(
          new StringBuilder(abiRuleKeyFactory.build(rule).toString())
              .reverse()
              .toString(),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.ABI_RULE_KEY));

      // Prepopulate the recorded paths metadata.
      filesystem.writeContentsToPath(
          MAPPER.writeValueAsString(ImmutableList.of()),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.RECORDED_PATHS));

      // Run the build.
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());
    }
  }

  public static class UncachableRuleTests extends CommonFixture {
    @Test
    public void uncachableRulesDoNotTouchTheCache() throws Exception {
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target)
              .setProjectFilesystem(filesystem)
              .build();
      BuildRule rule = new UncachableRule(
          params,
          pathResolver,
          ImmutableList.of(),
          Paths.get("foo.out"));
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory()
          .setRuleKeyFactoriesFunction(
              Functions.constant(
                  new CachingBuildEngine.RuleKeyFactories(
                      NOOP_RULE_KEY_FACTORY,
                      NOOP_INPUT_BASED_RULE_KEY_FACTORY,
                      NOOP_RULE_KEY_FACTORY,
                      NOOP_DEP_FILE_RULE_KEY_FACTORY,
                      NOOP_INPUT_COUNTING_RULE_KEY_FACTORY)))
          .build();
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
      assertEquals(
          BuildRuleSuccessType.BUILT_LOCALLY,
          result.getSuccess());
      assertEquals(
          "Should not attempt to fetch from cache",
          CacheResultType.IGNORED,
          result.getCacheResult().getType());
      assertEquals("should not have written to the cache", 0, cache.getArtifactCount());
    }

    private static class UncachableRule extends RuleWithSteps
        implements SupportsDependencyFileRuleKey {
      public UncachableRule(
          BuildRuleParams buildRuleParams,
          SourcePathResolver resolver,
          ImmutableList<Step> steps,
          Path output) {
        super(buildRuleParams, resolver, steps, output);
      }

      @Override
      public boolean useDependencyFileRuleKeys() {
        return true;
      }

      @Override
      public Optional<ImmutableSet<SourcePath>> getPossibleInputSourcePaths() {
        return Optional.empty();
      }

      @Override
      public ImmutableList<SourcePath> getInputsAfterBuildingLocally() throws IOException {
        return ImmutableList.of();
      }

      @Override
      public boolean isCacheable() {
        return false;
      }
    }
  }

  public static class ScheduleOverrideTests extends CommonFixture {

    @Test
    public void customWeights() throws Exception {
      ControlledRule rule1 =
          new ControlledRule(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule1"))
                  .setProjectFilesystem(filesystem)
                  .build(),
              pathResolver,
              RuleScheduleInfo.builder()
                  .setJobsMultiplier(2)
                  .build());
      ControlledRule rule2 =
          new ControlledRule(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule2"))
                  .setProjectFilesystem(filesystem)
                  .build(),
              pathResolver,
              RuleScheduleInfo.builder()
                  .setJobsMultiplier(2)
                  .build());
      ListeningMultiSemaphore semaphore = new ListeningMultiSemaphore(
          ResourceAmounts.of(3, 0, 0, 0),
          ResourceAllocationFairness.FAIR);
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory()
          .setExecutorService(
              new WeightedListeningExecutorService(
                  semaphore,
                  /* defaultWeight */ ResourceAmounts.of(1, 0, 0, 0),
                  listeningDecorator(Executors.newCachedThreadPool())))
          .setRuleKeyFactoriesFunction(
              Functions.constant(
                  new CachingBuildEngine.RuleKeyFactories(
                      NOOP_RULE_KEY_FACTORY,
                      NOOP_INPUT_BASED_RULE_KEY_FACTORY,
                      NOOP_RULE_KEY_FACTORY,
                      NOOP_DEP_FILE_RULE_KEY_FACTORY,
                      NOOP_INPUT_COUNTING_RULE_KEY_FACTORY)))
          .build();
      ListenableFuture<BuildResult> result1 =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule1);
      rule1.waitForStart();
      assertThat(rule1.hasStarted(), equalTo(true));
      ListenableFuture<BuildResult> result2 =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule2);
      Thread.sleep(250);
      assertThat(semaphore.getQueueLength(), equalTo(1));
      assertThat(rule2.hasStarted(), equalTo(false));
      rule1.finish();
      result1.get();
      rule2.finish();
      result2.get();
    }

    private class ControlledRule extends AbstractBuildRule implements OverrideScheduleRule {

      private final RuleScheduleInfo ruleScheduleInfo;

      private final Semaphore started = new Semaphore(0);
      private final Semaphore finish = new Semaphore(0);

      private ControlledRule(
          BuildRuleParams buildRuleParams,
          SourcePathResolver resolver,
          RuleScheduleInfo ruleScheduleInfo) {
        super(buildRuleParams, resolver);
        this.ruleScheduleInfo = ruleScheduleInfo;
      }

      @Override
      public ImmutableList<Step> getBuildSteps(
          BuildContext context,
          BuildableContext buildableContext) {
        return ImmutableList.of(
            new AbstractExecutionStep("step") {
              @Override
              public StepExecutionResult execute(ExecutionContext context)
                  throws InterruptedException {
                started.release();
                finish.acquire();
                return StepExecutionResult.SUCCESS;
              }
            });
      }

      @Nullable
      @Override
      public Path getPathToOutput() {
        return null;
      }

      @Override
      public RuleScheduleInfo getRuleScheduleInfo() {
        return ruleScheduleInfo;
      }

      public void finish() {
        finish.release();
      }

      public void waitForStart() {
        started.acquireUninterruptibly();
        started.release();
      }

      public boolean hasStarted() {
        return started.availablePermits() == 1;
      }

    }

  }

  public static class BuildRuleEventTests extends CommonFixture {

    // Use a executor service which uses a new thread for every task to help expose case where
    // the build engine issues begin and end rule events on different threads.
    private static final ListeningExecutorService SERVICE = new NewThreadExecutorService();

    @Test
    public void eventsForBuiltLocallyRuleAreOnCorrectThreads() throws Exception {
      // Create a noop simple rule.
      BuildRule rule =
          new EmptyBuildRule(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule"))
                  .setProjectFilesystem(filesystem)
                  .build(),
              pathResolver);

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory()
          .setExecutorService(SERVICE)
          .setRuleKeyFactoriesFunction(
              Functions.constant(
                  new CachingBuildEngine.RuleKeyFactories(
                      NOOP_RULE_KEY_FACTORY,
                      NOOP_INPUT_BASED_RULE_KEY_FACTORY,
                      NOOP_RULE_KEY_FACTORY,
                      NOOP_DEP_FILE_RULE_KEY_FACTORY,
                      NOOP_INPUT_COUNTING_RULE_KEY_FACTORY)))
          .build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());

      // Verify that events have correct thread IDs
      assertRelatedBuildRuleEventsOnSameThread(
          FluentIterable.from(listener.getEvents())
              .filter(BuildRuleEvent.class));
    }

    @Test
    public void eventsForMatchingRuleKeyRuleAreOnCorrectThreads() throws Exception {
      // Create a simple rule and set it up so that it has a matching rule key.
      BuildRule rule =
          new EmptyBuildRule(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule"))
                  .setProjectFilesystem(filesystem)
                  .build(),
              pathResolver);
      filesystem.writeContentsToPath(
          defaultRuleKeyFactory.build(rule).toString(),
          BuildInfo.getPathToMetadataDirectory(rule.getBuildTarget(), filesystem)
              .resolve(BuildInfo.MetadataKey.RULE_KEY));
      filesystem.writeContentsToPath(
          MAPPER.writeValueAsString(ImmutableList.of()),
          BuildInfo.getPathToMetadataDirectory(rule.getBuildTarget(), filesystem)
              .resolve(BuildInfo.MetadataKey.RECORDED_PATHS));

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory()
          .setExecutorService(SERVICE)
          .setRuleKeyFactoriesFunction(
              Functions.constant(
                  new CachingBuildEngine.RuleKeyFactories(
                      NOOP_RULE_KEY_FACTORY,
                      NOOP_INPUT_BASED_RULE_KEY_FACTORY,
                      NOOP_RULE_KEY_FACTORY,
                      NOOP_DEP_FILE_RULE_KEY_FACTORY,
                      NOOP_INPUT_COUNTING_RULE_KEY_FACTORY)))
          .build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
      assertEquals(BuildRuleSuccessType.MATCHING_RULE_KEY, result.getSuccess());

      // Verify that events have correct thread IDs
      assertRelatedBuildRuleEventsOnSameThread(
          FluentIterable.from(listener.getEvents())
              .filter(BuildRuleEvent.class));
    }

    @Test
    public void eventsForBuiltLocallyRuleAndDepAreOnCorrectThreads() throws Exception {
      // Create a simple rule and dep.
      BuildRule dep =
          new EmptyBuildRule(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:dep"))
                  .setProjectFilesystem(filesystem)
                  .build(),
              pathResolver);
      BuildRule rule =
          new EmptyBuildRule(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule"))
                  .setDeclaredDeps(ImmutableSortedSet.of(dep))
                  .setProjectFilesystem(filesystem)
                  .build(),
              pathResolver);

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory()
          .setExecutorService(SERVICE)
          .setRuleKeyFactoriesFunction(
              Functions.constant(
                  new CachingBuildEngine.RuleKeyFactories(
                      NOOP_RULE_KEY_FACTORY,
                      NOOP_INPUT_BASED_RULE_KEY_FACTORY,
                      NOOP_RULE_KEY_FACTORY,
                      NOOP_DEP_FILE_RULE_KEY_FACTORY,
                      NOOP_INPUT_COUNTING_RULE_KEY_FACTORY)))
          .build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine.build(buildContext, TestExecutionContext.newInstance(), rule).get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());

      // Verify that events have correct thread IDs
      assertRelatedBuildRuleEventsOnSameThread(
          FluentIterable.from(listener.getEvents())
              .filter(BuildRuleEvent.class));
    }

    /**
     * Verify that the begin and end events in build rule event pairs occur on the same thread.
     */
    private void assertRelatedBuildRuleEventsOnSameThread(Iterable<BuildRuleEvent> events) {
      Map<Long, List<BuildRuleEvent>> grouped = new HashMap<>();
      for (BuildRuleEvent event : events) {
        if (!grouped.containsKey(event.getThreadId())) {
          grouped.put(
              event.getThreadId(),
              new ArrayList<BuildRuleEvent>());
        }
        grouped.get(event.getThreadId()).add(event);
      }
      for (List<BuildRuleEvent> queue : grouped.values()) {
        Collections.sort(
            queue,
            (o1, o2) -> Long.compare(o1.getNanoTime(), o2.getNanoTime()));
        ImmutableList<String> queueDescription = queue.stream()
            .map(event -> String.format("%s@%s", event, event.getNanoTime()))
            .collect(MoreCollectors.toImmutableList());
        Iterator<BuildRuleEvent> itr = queue.iterator();

        while (itr.hasNext()) {
          BuildRuleEvent event1 = itr.next();
          BuildRuleEvent event2 = itr.next();

          assertThat(
              String.format(
                  "Two consecutive events (%s,%s) should have the same BuildTarget. (%s)",
                  event1,
                  event2,
                  queueDescription),
              event1.getBuildRule().getBuildTarget(),
              equalTo(event2.getBuildRule().getBuildTarget())
          );
          assertThat(
              String.format(
                  "Two consecutive events (%s,%s) should be suspend/resume or resume/suspend. (%s)",
                  event1,
                  event2,
                  queueDescription),
              event1.isRuleRunningAfterThisEvent(),
              equalTo(!event2.isRuleRunningAfterThisEvent())
          );
        }
      }
    }

    /**
     * A {@link ListeningExecutorService} which runs every task on a completely new thread.
     */
    private static class NewThreadExecutorService extends AbstractListeningExecutorService {

      @Override
      public void shutdown() {
      }

      @Nonnull
      @Override
      public List<Runnable> shutdownNow() {
        return ImmutableList.of();
      }

      @Override
      public boolean isShutdown() {
        return false;
      }

      @Override
      public boolean isTerminated() {
        return false;
      }

      @Override
      public boolean awaitTermination(long timeout, @Nonnull TimeUnit unit)
          throws InterruptedException {
        return false;
      }

      /**
       * Spawn a new thread for every command.
       */
      @Override
      public void execute(@Nonnull Runnable command) {
        new Thread(command).start();
      }

    }

  }


  // TODO(bolinfest): Test that when the success files match, nothing is built and nothing is
  // written back to the cache.

  // TODO(bolinfest): Test that when the value in the success file does not agree with the current
  // value, the rule is rebuilt and the result is written back to the cache.

  // TODO(bolinfest): Test that a failure when executing the build steps is propagated
  // appropriately.

  // TODO(bolinfest): Test what happens when the cache's methods throw an exception.

  private static BuildRule createRule(
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

  private static Manifest loadManifest(Path path) throws IOException {
    try (InputStream inputStream =
             new GZIPInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
      return new Manifest(inputStream);
    }
  }

  private static BuildRuleSuccessType getSuccess(BuildResult result) {
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

  private static String fileToDepFileEntryString(Path file) {
    DependencyFileEntry entry = DependencyFileEntry.of(file, Optional.empty());

    try {
      return MAPPER.writeValueAsString(entry);
    } catch (JsonProcessingException e) {
      throw new AssertionError(e);
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
    public ImmutableList<Step> getPostBuildSteps() {
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
   * {@link ArtifactCache#store(ArtifactInfo, BorrowablePath)} and ensure that
   * there will be a zip file in place immediately after the captured method has been invoked.
   */
  private static class FakeArtifactCacheThatWritesAZipFile implements ArtifactCache {

    private final Map<Path, String> desiredEntries;

    public FakeArtifactCacheThatWritesAZipFile(Map<Path, String> desiredEntries) {
      this.desiredEntries = desiredEntries;
    }

    @Override
    public CacheResult fetch(RuleKey ruleKey, LazyPath file) {
      try {
        writeEntriesToZip(file.get(), ImmutableMap.copyOf(desiredEntries));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      return CacheResult.hit("dir");
    }

    @Override
    public ListenableFuture<Void> store(
        ArtifactInfo info,
        BorrowablePath output) {
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
    public StepExecutionResult execute(ExecutionContext context) throws IOException {
      try {
        Thread.sleep(millis);
      } catch (InterruptedException e) {
        throw Throwables.propagate(e);
      }
      return StepExecutionResult.SUCCESS;
    }
  }

  private static class FailingStep extends AbstractExecutionStep {

    public FailingStep() {
      super("failing step");
    }

    @Override
    public StepExecutionResult execute(ExecutionContext context) throws IOException {
      return StepExecutionResult.ERROR;
    }

  }

  private static void writeEntriesToZip(Path file, ImmutableMap<Path, String> entries)
      throws IOException {
    try (CustomZipOutputStream zip = ZipOutputStreams.newOutputStream(file)) {
      for (Map.Entry<Path, String> mapEntry : entries.entrySet()) {
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

  private static class EmptyBuildRule extends AbstractBuildRule {

    public EmptyBuildRule(
        BuildRuleParams buildRuleParams,
        SourcePathResolver resolver) {
      super(buildRuleParams, resolver);
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      return ImmutableList.of();
    }

    @Nullable
    @Override
    public Path getPathToOutput() {
      return null;
    }

  }
}
