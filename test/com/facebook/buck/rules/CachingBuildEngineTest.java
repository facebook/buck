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
import com.facebook.buck.artifact_cache.ArtifactCacheMode;
import com.facebook.buck.artifact_cache.ArtifactInfo;
import com.facebook.buck.artifact_cache.CacheReadMode;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.artifact_cache.InMemoryArtifactCache;
import com.facebook.buck.artifact_cache.NoopArtifactCache;
import com.facebook.buck.cli.CommandThreadManager;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.CommandEvent;
import com.facebook.buck.event.FakeBuckEventListener;
import com.facebook.buck.event.TestEventConfigurator;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.io.BorrowablePath;
import com.facebook.buck.io.LazyPath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.keys.DefaultDependencyFileRuleKeyFactory;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.DependencyFileEntry;
import com.facebook.buck.rules.keys.DependencyFileRuleKeyFactory;
import com.facebook.buck.rules.keys.FakeRuleKeyFactory;
import com.facebook.buck.rules.keys.InputBasedRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyAndInputs;
import com.facebook.buck.rules.keys.RuleKeyFactories;
import com.facebook.buck.rules.keys.RuleKeyFieldLoader;
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
import com.facebook.buck.timing.IncrementingFakeClock;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.NullFileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
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
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.AbstractListeningExecutorService;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.easymock.EasyMockSupport;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsInstanceOf;
import org.hamcrest.core.StringContains;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Ensuring that build rule caching works correctly in Buck is imperative for both its performance
 * and correctness.
 */
@SuppressWarnings("PMD.TestClassWithoutTestCases")
@RunWith(Enclosed.class)
public class CachingBuildEngineTest {

  private static final BuildTarget BUILD_TARGET =
      BuildTargetFactory.newInstance("//src/com/facebook/orca:orca");
  private static final SourcePathRuleFinder DEFAULT_RULE_FINDER =
      new SourcePathRuleFinder(
          new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
  private static final SourcePathResolver DEFAULT_SOURCE_PATH_RESOLVER =
      new SourcePathResolver(DEFAULT_RULE_FINDER);
  private static final long NO_INPUT_FILE_SIZE_LIMIT = Long.MAX_VALUE;
  private static final RuleKeyFieldLoader FIELD_LOADER = new RuleKeyFieldLoader(0);
  private static final DefaultRuleKeyFactory NOOP_RULE_KEY_FACTORY =
      new DefaultRuleKeyFactory(
          FIELD_LOADER, new NullFileHashCache(), DEFAULT_SOURCE_PATH_RESOLVER, DEFAULT_RULE_FINDER);
  private static final InputBasedRuleKeyFactory NOOP_INPUT_BASED_RULE_KEY_FACTORY =
      new InputBasedRuleKeyFactory(
          FIELD_LOADER,
          new NullFileHashCache(),
          DEFAULT_SOURCE_PATH_RESOLVER,
          DEFAULT_RULE_FINDER,
          NO_INPUT_FILE_SIZE_LIMIT);
  private static final DependencyFileRuleKeyFactory NOOP_DEP_FILE_RULE_KEY_FACTORY =
      new DefaultDependencyFileRuleKeyFactory(
          FIELD_LOADER, new NullFileHashCache(), DEFAULT_SOURCE_PATH_RESOLVER, DEFAULT_RULE_FINDER);

  @RunWith(Parameterized.class)
  public abstract static class CommonFixture extends EasyMockSupport {
    @Rule public TemporaryPaths tmp = new TemporaryPaths();

    protected final InMemoryArtifactCache cache = new InMemoryArtifactCache();
    protected final FakeBuckEventListener listener = new FakeBuckEventListener();
    protected FakeProjectFilesystem filesystem;
    protected BuildInfoStoreManager buildInfoStoreManager;
    protected BuildInfoStore buildInfoStore;
    protected FileHashCache fileHashCache;
    protected BuildEngineBuildContext buildContext;
    protected BuildRuleResolver resolver;
    protected SourcePathRuleFinder ruleFinder;
    protected SourcePathResolver pathResolver;
    protected DefaultRuleKeyFactory defaultRuleKeyFactory;
    protected InputBasedRuleKeyFactory inputBasedRuleKeyFactory;
    protected BuildRuleDurationTracker durationTracker;
    protected CachingBuildEngine.MetadataStorage metadataStorage;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
      return Arrays.stream(CachingBuildEngine.MetadataStorage.values())
          .map(v -> new Object[] {v})
          .collect(MoreCollectors.toImmutableList());
    }

    public CommonFixture(CachingBuildEngine.MetadataStorage metadataStorage) throws IOException {
      this.metadataStorage = metadataStorage;
    }

    @Before
    public void setUp() throws IOException {
      filesystem = new FakeProjectFilesystem(tmp.getRoot());
      buildInfoStoreManager = new BuildInfoStoreManager();
      Files.createDirectories(filesystem.resolve(filesystem.getBuckPaths().getScratchDir()));
      buildInfoStore = buildInfoStoreManager.get(filesystem, metadataStorage);
      fileHashCache =
          new StackedFileHashCache(
              ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem)));
      buildContext =
          BuildEngineBuildContext.builder()
              .setBuildContext(FakeBuildContext.NOOP_CONTEXT)
              .setArtifactCache(cache)
              .setBuildId(new BuildId())
              .setClock(new IncrementingFakeClock())
              .build();
      buildContext.getEventBus().register(listener);
      resolver =
          new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
      ruleFinder = new SourcePathRuleFinder(resolver);
      pathResolver = new SourcePathResolver(ruleFinder);
      defaultRuleKeyFactory =
          new DefaultRuleKeyFactory(FIELD_LOADER, fileHashCache, pathResolver, ruleFinder);
      inputBasedRuleKeyFactory =
          new InputBasedRuleKeyFactory(
              FIELD_LOADER, fileHashCache, pathResolver, ruleFinder, NO_INPUT_FILE_SIZE_LIMIT);
      durationTracker = new BuildRuleDurationTracker();
    }

    protected CachingBuildEngineFactory cachingBuildEngineFactory() {
      return new CachingBuildEngineFactory(resolver, buildInfoStoreManager)
          .setCachingBuildEngineDelegate(new LocalCachingBuildEngineDelegate(fileHashCache));
    }

    protected BuildInfoRecorder createBuildInfoRecorder(BuildTarget buildTarget) {
      return new BuildInfoRecorder(
          buildTarget,
          filesystem,
          buildInfoStore,
          new DefaultClock(),
          new BuildId(),
          ImmutableMap.of());
    }
  }

  public static class OtherTests extends CommonFixture {
    public OtherTests(CachingBuildEngine.MetadataStorage metadataStorage) throws IOException {
      super(metadataStorage);
    }

    /**
     * Tests what should happen when a rule is built for the first time: it should have no cached
     * RuleKey, nor should it have any artifact in the ArtifactCache. The sequence of events should
     * be as follows:
     *
     * <ol>
     *   <li>The build engine invokes the {@link CachingBuildEngine#build(BuildEngineBuildContext,
     *       ExecutionContext, BuildRule)} method on each of the transitive deps.
     *   <li>The rule computes its own {@link RuleKey}.
     *   <li>The engine compares its {@link RuleKey} to the one on disk, if present.
     *   <li>Because the rule has no {@link RuleKey} on disk, the engine tries to build the rule.
     *   <li>First, it checks the artifact cache, but there is a cache miss.
     *   <li>The rule generates its build steps and the build engine executes them.
     *   <li>Upon executing its steps successfully, the build engine should write the rule's {@link
     *       RuleKey} to disk.
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
      List<Step> buildSteps = new ArrayList<>();
      final BuildRule ruleToTest =
          createRule(
              filesystem,
              resolver,
              pathResolver,
              ImmutableSet.of(dep),
              buildSteps,
              /* postBuildSteps */ ImmutableList.of(),
              pathToOutputFile,
              ImmutableList.of());
      verifyAll();
      resetAll();

      // The BuildContext that will be used by the rule's build() method.
      BuildEngineBuildContext buildContext =
          this.buildContext.withBuildContext(
              this.buildContext.getBuildContext().withEventBus(buckEventBus));

      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build();

      // Add a build step so we can verify that the steps are executed.
      buildSteps.add(
          new AbstractExecutionStep("Some Short Name") {
            @Override
            public StepExecutionResult execute(ExecutionContext context) throws IOException {
              filesystem.touch(pathResolver.getRelativePath(ruleToTest.getSourcePathToOutput()));
              return StepExecutionResult.SUCCESS;
            }
          });

      // Attempting to build the rule should force a rebuild due to a cache miss.
      replayAll();

      cachingBuildEngine.setBuildRuleResult(
          dep, BuildRuleSuccessType.FETCHED_FROM_CACHE, CacheResult.miss());

      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), ruleToTest)
              .getResult()
              .get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());
      buckEventBus.post(
          CommandEvent.finished(CommandEvent.started("build", ImmutableList.of(), false, 23L), 0));
      verifyAll();

      RuleKey ruleToTestKey = defaultRuleKeyFactory.build(ruleToTest);
      assertTrue(cache.hasArtifact(ruleToTestKey));

      // Verify the events logged to the BuckEventBus.
      List<BuckEvent> events = listener.getEvents();
      assertThat(events, hasItem(BuildRuleEvent.ruleKeyCalculationStarted(dep, durationTracker)));
      BuildRuleEvent.Started started =
          TestEventConfigurator.configureTestEvent(
              BuildRuleEvent.ruleKeyCalculationStarted(ruleToTest, durationTracker));
      assertThat(
          listener.getEvents(),
          Matchers.containsInRelativeOrder(
              started,
              BuildRuleEvent.finished(
                  started,
                  BuildRuleKeys.of(ruleToTestKey),
                  BuildRuleStatus.SUCCESS,
                  CacheResult.miss(),
                  Optional.empty(),
                  Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty())));
    }

    @Test
    public void testAsyncJobsAreNotLeftInExecutor()
        throws IOException, ExecutionException, InterruptedException {
      BuildRuleParams buildRuleParams =
          new FakeBuildRuleParamsBuilder(BUILD_TARGET).setProjectFilesystem(filesystem).build();
      FakeBuildRule buildRule = new FakeBuildRule(buildRuleParams, pathResolver);

      // The BuildContext that will be used by the rule's build() method.
      BuildEngineBuildContext buildContext =
          this.buildContext.withArtifactCache(
              new NoopArtifactCache() {
                @Override
                public ListenableFuture<Void> store(ArtifactInfo info, BorrowablePath output) {
                  try {
                    Thread.sleep(500);
                  } catch (InterruptedException e) {
                    Throwables.throwIfUnchecked(e);
                    throw new RuntimeException(e);
                  }
                  return Futures.immediateFuture(null);
                }
              });

      ListeningExecutorService service = listeningDecorator(Executors.newFixedThreadPool(2));

      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory().setExecutorService(service).build();
      ListenableFuture<BuildResult> buildResult =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), buildRule)
              .getResult();

      BuildResult result = buildResult.get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());

      assertTrue(service.shutdownNow().isEmpty());

      BuildRuleEvent.Started started =
          TestEventConfigurator.configureTestEvent(
              BuildRuleEvent.ruleKeyCalculationStarted(buildRule, durationTracker));
      assertThat(
          listener.getEvents(),
          Matchers.containsInRelativeOrder(
              started,
              BuildRuleEvent.finished(
                  started,
                  BuildRuleKeys.of(defaultRuleKeyFactory.build(buildRule)),
                  BuildRuleStatus.SUCCESS,
                  CacheResult.miss(),
                  Optional.empty(),
                  Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty())));
    }

    @Test
    public void testArtifactFetchedFromCache()
        throws InterruptedException, ExecutionException, IOException {
      Step step =
          new AbstractExecutionStep("exploding step") {
            @Override
            public StepExecutionResult execute(ExecutionContext context) {
              throw new UnsupportedOperationException("build step should not be executed");
            }
          };
      BuildRule buildRule =
          createRule(
              filesystem,
              resolver,
              pathResolver,
              /* deps */ ImmutableSet.of(),
              ImmutableList.of(step),
              /* postBuildSteps */ ImmutableList.of(),
              /* pathToOutputFile */ null,
              ImmutableList.of());

      // Simulate successfully fetching the output file from the ArtifactCache.
      ArtifactCache artifactCache = createMock(ArtifactCache.class);
      ImmutableMap<String, String> metadata =
          ImmutableMap.of(
              BuildInfo.MetadataKey.RULE_KEY,
              defaultRuleKeyFactory.build(buildRule).toString(),
              BuildInfo.MetadataKey.BUILD_ID,
              buildContext.getBuildId().toString(),
              BuildInfo.MetadataKey.ORIGIN_BUILD_ID,
              buildContext.getBuildId().toString());
      ImmutableMap<Path, String> desiredZipEntries =
          ImmutableMap.of(
              BuildInfo.getPathToMetadataDirectory(buildRule.getBuildTarget(), filesystem)
                  .resolve(BuildInfo.MetadataKey.RECORDED_PATHS),
              ObjectMappers.WRITER.writeValueAsString(ImmutableList.of()),
              Paths.get("buck-out/gen/src/com/facebook/orca/orca.jar"),
              "Imagine this is the contents of a valid JAR file.");
      expect(artifactCache.fetch(eq(defaultRuleKeyFactory.build(buildRule)), isA(LazyPath.class)))
          .andDelegateTo(new FakeArtifactCacheThatWritesAZipFile(desiredZipEntries, metadata));

      BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();
      BuildEngineBuildContext buildContext =
          BuildEngineBuildContext.builder()
              .setBuildContext(
                  BuildContext.builder()
                      .setActionGraph(new ActionGraph(ImmutableList.of(buildRule)))
                      .setSourcePathResolver(pathResolver)
                      .setJavaPackageFinder(createMock(JavaPackageFinder.class))
                      .setEventBus(buckEventBus)
                      .build())
              .setClock(new DefaultClock())
              .setBuildId(new BuildId())
              .setArtifactCache(artifactCache)
              .build();

      // Build the rule!
      replayAll();

      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build();

      ListenableFuture<BuildResult> buildResult =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), buildRule)
              .getResult();
      buckEventBus.post(
          CommandEvent.finished(CommandEvent.started("build", ImmutableList.of(), false, 23L), 0));

      BuildResult result = buildResult.get();
      verifyAll();
      assertTrue(
          "We expect build() to be synchronous in this case, "
              + "so the future should already be resolved.",
          MoreFutures.isSuccess(buildResult));
      assertEquals(BuildRuleSuccessType.FETCHED_FROM_CACHE, getSuccess(result));
      assertTrue(((BuildableAbstractCachingBuildRule) buildRule).isInitializedFromDisk());
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

      BuildRule buildRule =
          createRule(
              filesystem,
              resolver,
              pathResolver,
              /* deps */ ImmutableSet.of(),
              /* buildSteps */ ImmutableList.of(),
              /* postBuildSteps */ ImmutableList.of(buildStep),
              /* pathToOutputFile */ null,
              ImmutableList.of());

      // Simulate successfully fetching the output file from the ArtifactCache.
      ArtifactCache artifactCache = createMock(ArtifactCache.class);
      ImmutableMap<String, String> metadata =
          ImmutableMap.of(
              BuildInfo.MetadataKey.RULE_KEY,
              defaultRuleKeyFactory.build(buildRule).toString(),
              BuildInfo.MetadataKey.BUILD_ID,
              buildContext.getBuildId().toString(),
              BuildInfo.MetadataKey.ORIGIN_BUILD_ID,
              buildContext.getBuildId().toString());
      ImmutableMap<Path, String> desiredZipEntries =
          ImmutableMap.of(
              BuildInfo.getPathToMetadataDirectory(buildRule.getBuildTarget(), filesystem)
                  .resolve(BuildInfo.MetadataKey.RECORDED_PATHS),
              ObjectMappers.WRITER.writeValueAsString(ImmutableList.of()),
              Paths.get("buck-out/gen/src/com/facebook/orca/orca.jar"),
              "Imagine this is the contents of a valid JAR file.");
      expect(artifactCache.fetch(eq(defaultRuleKeyFactory.build(buildRule)), isA(LazyPath.class)))
          .andDelegateTo(new FakeArtifactCacheThatWritesAZipFile(desiredZipEntries, metadata));

      BuildEngineBuildContext buildContext =
          BuildEngineBuildContext.builder()
              .setBuildContext(
                  BuildContext.builder()
                      .setActionGraph(new ActionGraph(ImmutableList.of(buildRule)))
                      .setSourcePathResolver(pathResolver)
                      .setJavaPackageFinder(createMock(JavaPackageFinder.class))
                      .setEventBus(buckEventBus)
                      .build())
              .setClock(new DefaultClock())
              .setBuildId(new BuildId())
              .setArtifactCache(artifactCache)
              .build();

      // Build the rule!
      replayAll();
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build();
      ListenableFuture<BuildResult> buildResult =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), buildRule)
              .getResult();
      buckEventBus.post(
          CommandEvent.finished(CommandEvent.started("build", ImmutableList.of(), false, 23L), 0));

      BuildResult result = buildResult.get();
      verifyAll();
      assertEquals(BuildRuleSuccessType.FETCHED_FROM_CACHE, result.getSuccess());
      assertTrue(((BuildableAbstractCachingBuildRule) buildRule).isInitializedFromDisk());
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

      BuildInfoRecorder recorder = createBuildInfoRecorder(BUILD_TARGET);
      recorder.addBuildMetadata(BuildInfo.MetadataKey.RULE_KEY, ruleToTestKey.toString());
      recorder.addMetadata(BuildInfo.MetadataKey.RECORDED_PATHS, ImmutableList.of());
      recorder.writeMetadataToDisk(true);

      // The BuildContext that will be used by the rule's build() method.
      BuildEngineBuildContext context =
          this.buildContext.withArtifactCache(new NoopArtifactCache());

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build();

      // Run the build.
      replayAll();
      BuildResult result =
          cachingBuildEngine
              .build(context, TestExecutionContext.newInstance(), ruleToTest)
              .getResult()
              .get();
      assertEquals(BuildRuleSuccessType.MATCHING_RULE_KEY, result.getSuccess());
      verifyAll();

      // Verify the events logged to the BuckEventBus.
      List<BuckEvent> events = listener.getEvents();
      assertThat(events, hasItem(BuildRuleEvent.ruleKeyCalculationStarted(dep, durationTracker)));
      BuildRuleEvent.Started started =
          TestEventConfigurator.configureTestEvent(
              BuildRuleEvent.ruleKeyCalculationStarted(ruleToTest, durationTracker));
      assertThat(
          events,
          Matchers.containsInRelativeOrder(
              started,
              BuildRuleEvent.finished(
                  started,
                  BuildRuleKeys.of(ruleToTestKey),
                  BuildRuleStatus.SUCCESS,
                  CacheResult.localKeyUnchangedHit(),
                  Optional.empty(),
                  Optional.of(BuildRuleSuccessType.MATCHING_RULE_KEY),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty())));
    }

    @Test
    public void testMatchingTopLevelRuleKeyStillProcessesDepInDeepMode() throws Exception {
      // Create a dep for the build rule.
      BuildTarget depTarget = BuildTargetFactory.newInstance("//src/com/facebook/orca:lib");
      BuildRuleParams ruleParams =
          new FakeBuildRuleParamsBuilder(depTarget).setProjectFilesystem(filesystem).build();
      FakeBuildRule dep = new FakeBuildRule(ruleParams, pathResolver);
      RuleKey depKey = defaultRuleKeyFactory.build(dep);
      BuildInfoRecorder depRecorder = createBuildInfoRecorder(depTarget);
      depRecorder.addBuildMetadata(BuildInfo.MetadataKey.RULE_KEY, depKey.toString());
      depRecorder.addMetadata(BuildInfo.MetadataKey.RECORDED_PATHS, ImmutableList.of());
      depRecorder.writeMetadataToDisk(true);

      FakeBuildRule ruleToTest = new FakeBuildRule(BUILD_TARGET, filesystem, pathResolver, dep);
      RuleKey ruleToTestKey = defaultRuleKeyFactory.build(ruleToTest);
      BuildInfoRecorder recorder = createBuildInfoRecorder(BUILD_TARGET);
      recorder.addBuildMetadata(BuildInfo.MetadataKey.RULE_KEY, ruleToTestKey.toString());
      recorder.addMetadata(BuildInfo.MetadataKey.RECORDED_PATHS, ImmutableList.of());
      recorder.writeMetadataToDisk(true);

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory().setBuildMode(CachingBuildEngine.BuildMode.DEEP).build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), ruleToTest)
              .getResult()
              .get();
      assertEquals(BuildRuleSuccessType.MATCHING_RULE_KEY, result.getSuccess());

      // Verify the events logged to the BuckEventBus.
      List<BuckEvent> events = listener.getEvents();
      BuildRuleEvent.Started startedDep =
          TestEventConfigurator.configureTestEvent(
              BuildRuleEvent.ruleKeyCalculationStarted(dep, durationTracker));
      assertThat(
          events,
          Matchers.containsInRelativeOrder(
              startedDep,
              BuildRuleEvent.finished(
                  startedDep,
                  BuildRuleKeys.of(depKey),
                  BuildRuleStatus.SUCCESS,
                  CacheResult.localKeyUnchangedHit(),
                  Optional.empty(),
                  Optional.of(BuildRuleSuccessType.MATCHING_RULE_KEY),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty())));
      BuildRuleEvent.Started started =
          TestEventConfigurator.configureTestEvent(
              BuildRuleEvent.ruleKeyCalculationStarted(ruleToTest, durationTracker));
      assertThat(
          events,
          Matchers.containsInRelativeOrder(
              started,
              BuildRuleEvent.finished(
                  started,
                  BuildRuleKeys.of(ruleToTestKey),
                  BuildRuleStatus.SUCCESS,
                  CacheResult.localKeyUnchangedHit(),
                  Optional.empty(),
                  Optional.of(BuildRuleSuccessType.MATCHING_RULE_KEY),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty())));
    }

    @Test
    public void testMatchingTopLevelRuleKeyStillProcessesRuntimeDeps() throws Exception {
      // Setup a runtime dependency that is found transitively from the top-level rule.
      BuildRuleParams ruleParams =
          new FakeBuildRuleParamsBuilder("//:transitive_dep")
              .setProjectFilesystem(filesystem)
              .build();
      FakeBuildRule transitiveRuntimeDep = new FakeBuildRule(ruleParams, pathResolver);
      resolver.addToIndex(transitiveRuntimeDep);
      RuleKey transitiveRuntimeDepKey = defaultRuleKeyFactory.build(transitiveRuntimeDep);

      BuildInfoRecorder recorder = createBuildInfoRecorder(transitiveRuntimeDep.getBuildTarget());
      recorder.addBuildMetadata(BuildInfo.MetadataKey.RULE_KEY, transitiveRuntimeDepKey.toString());
      recorder.addMetadata(BuildInfo.MetadataKey.RECORDED_PATHS, ImmutableList.of());
      recorder.writeMetadataToDisk(true);

      // Setup a runtime dependency that is referenced directly by the top-level rule.
      FakeBuildRule runtimeDep =
          new FakeHasRuntimeDeps(
              BuildTargetFactory.newInstance("//:runtime_dep"),
              filesystem,
              pathResolver,
              transitiveRuntimeDep);
      resolver.addToIndex(runtimeDep);
      RuleKey runtimeDepKey = defaultRuleKeyFactory.build(runtimeDep);
      BuildInfoRecorder runtimeDepRec = createBuildInfoRecorder(runtimeDep.getBuildTarget());
      runtimeDepRec.addBuildMetadata(BuildInfo.MetadataKey.RULE_KEY, runtimeDepKey.toString());
      runtimeDepRec.addMetadata(BuildInfo.MetadataKey.RECORDED_PATHS, ImmutableList.of());
      runtimeDepRec.writeMetadataToDisk(true);

      // Create a dep for the build rule.
      FakeBuildRule ruleToTest =
          new FakeHasRuntimeDeps(BUILD_TARGET, filesystem, pathResolver, runtimeDep);
      RuleKey ruleToTestKey = defaultRuleKeyFactory.build(ruleToTest);
      BuildInfoRecorder testRec = createBuildInfoRecorder(BUILD_TARGET);
      testRec.addBuildMetadata(BuildInfo.MetadataKey.RULE_KEY, ruleToTestKey.toString());
      testRec.addMetadata(BuildInfo.MetadataKey.RECORDED_PATHS, ImmutableList.of());
      testRec.writeMetadataToDisk(true);

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), ruleToTest)
              .getResult()
              .get();
      assertEquals(BuildRuleSuccessType.MATCHING_RULE_KEY, result.getSuccess());

      // Verify the events logged to the BuckEventBus.
      List<BuckEvent> events = listener.getEvents();
      BuildRuleEvent.Started started =
          TestEventConfigurator.configureTestEvent(
              BuildRuleEvent.ruleKeyCalculationStarted(ruleToTest, durationTracker));
      assertThat(
          events,
          Matchers.containsInRelativeOrder(
              started,
              BuildRuleEvent.finished(
                  started,
                  BuildRuleKeys.of(ruleToTestKey),
                  BuildRuleStatus.SUCCESS,
                  CacheResult.localKeyUnchangedHit(),
                  Optional.empty(),
                  Optional.of(BuildRuleSuccessType.MATCHING_RULE_KEY),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty())));
      BuildRuleEvent.Started startedDep =
          TestEventConfigurator.configureTestEvent(
              BuildRuleEvent.ruleKeyCalculationStarted(runtimeDep, durationTracker));
      assertThat(
          events,
          Matchers.containsInRelativeOrder(
              startedDep,
              BuildRuleEvent.finished(
                  startedDep,
                  BuildRuleKeys.of(runtimeDepKey),
                  BuildRuleStatus.SUCCESS,
                  CacheResult.localKeyUnchangedHit(),
                  Optional.empty(),
                  Optional.of(BuildRuleSuccessType.MATCHING_RULE_KEY),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty())));
      BuildRuleEvent.Started startedTransitive =
          TestEventConfigurator.configureTestEvent(
              BuildRuleEvent.ruleKeyCalculationStarted(transitiveRuntimeDep, durationTracker));
      assertThat(
          events,
          Matchers.containsInRelativeOrder(
              startedTransitive,
              BuildRuleEvent.finished(
                  startedTransitive,
                  BuildRuleKeys.of(transitiveRuntimeDepKey),
                  BuildRuleStatus.SUCCESS,
                  CacheResult.localKeyUnchangedHit(),
                  Optional.empty(),
                  Optional.of(BuildRuleSuccessType.MATCHING_RULE_KEY),
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
      BuildRule ruleToTest =
          createRule(
              filesystem,
              resolver,
              pathResolver,
              /* deps */ ImmutableSet.of(),
              /* buildSteps */ ImmutableList.of(failingStep),
              /* postBuildSteps */ ImmutableList.of(),
              /* pathToOutputFile */ null,
              ImmutableList.of());
      resolver.addToIndex(ruleToTest);

      FakeBuildRule withRuntimeDep =
          new FakeHasRuntimeDeps(
              BuildTargetFactory.newInstance("//:with_runtime_dep"),
              filesystem,
              pathResolver,
              ruleToTest);

      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build();
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), withRuntimeDep)
              .getResult()
              .get();

      assertThat(result.getStatus(), equalTo(BuildRuleStatus.CANCELED));
      assertThat(result.getFailure(), instanceOf(StepFailedException.class));
      assertThat(
          ((StepFailedException) result.getFailure()).getStep().getShortName(),
          equalTo(description));
    }

    @Test
    public void pendingWorkIsCancelledOnFailures() throws Exception {
      final String description = "failing step";
      AtomicInteger failedSteps = new AtomicInteger(0);
      Step failingStep =
          new AbstractExecutionStep(description) {
            @Override
            public StepExecutionResult execute(ExecutionContext context) throws IOException {
              System.out.println("Failing");
              failedSteps.incrementAndGet();
              return StepExecutionResult.ERROR;
            }
          };
      ImmutableSortedSet.Builder<BuildRule> depsBuilder = ImmutableSortedSet.naturalOrder();
      for (int i = 0; i < 20; i++) {
        BuildRule failingDep =
            createRule(
                filesystem,
                resolver,
                pathResolver,
                /* deps */ ImmutableSet.of(),
                /* buildSteps */ ImmutableList.of(failingStep),
                /* postBuildSteps */ ImmutableList.of(),
                /* pathToOutputFile */ null,
                ImmutableList.of(InternalFlavor.of("failing-" + i)));
        resolver.addToIndex(failingDep);
        depsBuilder.add(failingDep);
      }

      FakeBuildRule withFailingDeps =
          new FakeBuildRule(
              BuildTargetFactory.newInstance("//:with_failing_deps"),
              pathResolver,
              depsBuilder.build());

      // Use a CommandThreadManager to closely match the real-world CachingBuildEngine experience.
      // Limit it to 1 thread so that we don't start multiple deps at the same time.
      try (CommandThreadManager threadManager =
          new CommandThreadManager(
              "cachingBuildEngingTest",
              new ConcurrencyLimit(
                  1,
                  ResourceAllocationFairness.FAIR,
                  1,
                  ResourceAmounts.of(100, 100, 100, 100),
                  ResourceAmounts.of(0, 0, 0, 0)))) {
        CachingBuildEngine cachingBuildEngine =
            cachingBuildEngineFactory().setExecutorService(threadManager.getExecutor()).build();
        BuildResult result =
            cachingBuildEngine
                .build(buildContext, TestExecutionContext.newInstance(), withFailingDeps)
                .getResult()
                .get();

        assertThat(result.getStatus(), equalTo(BuildRuleStatus.CANCELED));
        assertThat(result.getFailure(), instanceOf(StepFailedException.class));
        assertThat(failedSteps.get(), equalTo(1));
        assertThat(
            ((StepFailedException) result.getFailure()).getStep().getShortName(),
            equalTo(description));
      }
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
      BuildRule ruleToTest =
          createRule(
              filesystem,
              resolver,
              pathResolver,
              /* deps */ ImmutableSet.of(),
              /* buildSteps */ ImmutableList.of(failingStep),
              /* postBuildSteps */ ImmutableList.of(),
              /* pathToOutputFile */ null,
              ImmutableList.of());
      resolver.addToIndex(ruleToTest);

      FakeBuildRule withRuntimeDep =
          new FakeHasRuntimeDeps(
              BuildTargetFactory.newInstance("//:with_runtime_dep"),
              filesystem,
              pathResolver,
              ruleToTest);

      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build();
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), withRuntimeDep)
              .getResult()
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
      BuildRule ruleToTest =
          createRule(
              filesystem,
              resolver,
              pathResolver,
              /* deps */ ImmutableSet.of(),
              /* buildSteps */ ImmutableList.of(),
              /* postBuildSteps */ ImmutableList.of(failingStep),
              /* pathToOutputFile */ null,
              ImmutableList.of());
      BuildInfoRecorder recorder = createBuildInfoRecorder(ruleToTest.getBuildTarget());

      recorder.addBuildMetadata(
          BuildInfo.MetadataKey.RULE_KEY, defaultRuleKeyFactory.build(ruleToTest).toString());
      recorder.addMetadata(BuildInfo.MetadataKey.RECORDED_PATHS, ImmutableList.of());
      recorder.writeMetadataToDisk(true);

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), ruleToTest)
              .getResult()
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
              return CacheResult.error("cache", ArtifactCacheMode.dir, "error");
            }
          };

      // Use the artifact cache when running a simple rule that will build locally.
      BuildEngineBuildContext buildContext = this.buildContext.withArtifactCache(cache);

      BuildRule rule =
          new EmptyBuildRule(
              new FakeBuildRuleParamsBuilder("//:rule").setProjectFilesystem(filesystem).build(),
              pathResolver);
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build();

      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertThat(result.getSuccess(), equalTo(BuildRuleSuccessType.BUILT_LOCALLY));
      assertThat(result.getCacheResult().getType(), equalTo(CacheResultType.ERROR));
    }

    @Test
    public void testExceptionMessagesAreInformative() throws Exception {
      AtomicReference<RuntimeException> throwable = new AtomicReference<>();
      BuildRule rule =
          new AbstractBuildRuleWithResolver(
              new FakeBuildRuleParamsBuilder("//:rule").setProjectFilesystem(filesystem).build(),
              pathResolver) {
            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              throw throwable.get();
            }

            @Nullable
            @Override
            public SourcePath getSourcePathToOutput() {
              return null;
            }
          };
      throwable.set(new IllegalArgumentException("bad arg"));

      Throwable thrown =
          cachingBuildEngineFactory()
              .build()
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get()
              .getFailure();
      assertThat(thrown.getCause(), new IsInstanceOf(IllegalArgumentException.class));
      assertThat(thrown.getMessage(), new StringContains(false, "//:rule"));

      // HumanReadableExceptions shouldn't be changed.
      throwable.set(new HumanReadableException("message"));
      thrown =
          cachingBuildEngineFactory()
              .build()
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get()
              .getFailure();
      assertEquals(throwable.get(), thrown);

      // Exceptions that contain the rule already shouldn't be changed.
      throwable.set(new IllegalArgumentException("bad arg in //:rule"));
      thrown =
          cachingBuildEngineFactory()
              .build()
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get()
              .getFailure();
      assertEquals(throwable.get(), thrown);
    }

    @Test
    public void testDelegateCalledBeforeRuleCreation() throws Exception {
      BuildRule rule =
          new EmptyBuildRule(
              new FakeBuildRuleParamsBuilder("//:rule").setProjectFilesystem(filesystem).build(),
              pathResolver);
      final AtomicReference<BuildRule> lastRuleToBeBuilt = new AtomicReference<>();
      CachingBuildEngineDelegate testDelegate =
          new LocalCachingBuildEngineDelegate(fileHashCache) {
            @Override
            public void onRuleAboutToBeBuilt(BuildRule buildRule) {
              super.onRuleAboutToBeBuilt(buildRule);
              lastRuleToBeBuilt.set(buildRule);
            }
          };
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory().setCachingBuildEngineDelegate(testDelegate).build();
      cachingBuildEngine
          .build(buildContext, TestExecutionContext.newInstance(), rule)
          .getResult()
          .get();
      assertThat(lastRuleToBeBuilt.get(), is(rule));
    }

    @Test
    public void buildingRuleLocallyInvalidatesOutputs() throws Exception {
      // First, write something to the output file and get it's hash.
      Path output = Paths.get("output/path");
      filesystem.mkdirs(output.getParent());
      filesystem.writeContentsToPath("something", output);
      HashCode originalHashCode = fileHashCache.get(filesystem.resolve(output));

      // Create a simple rule which just writes something new to the output file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target).setProjectFilesystem(filesystem).build();
      BuildRule rule = new WriteFile(params, "something else", output, /* executable */ false);

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
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
              ImmutableList.of(new FailingStep()),
              /* output */ null);
      BuildRule dep2 =
          new RuleWithSteps(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:dep2"))
                  .setDeclaredDeps(ImmutableSortedSet.of(dep1))
                  .setProjectFilesystem(filesystem)
                  .build(),
              ImmutableList.of(new SleepStep(0)),
              /* output */ null);

      // Create another dep chain, which is two deep with rules that just sleep.
      BuildRule dep3 =
          new RuleWithSteps(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:dep3"))
                  .setProjectFilesystem(filesystem)
                  .build(),
              ImmutableList.of(new SleepStep(300)),
              /* output */ null);
      BuildRule dep4 =
          new RuleWithSteps(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:dep4"))
                  .setDeclaredDeps(ImmutableSortedSet.of(dep3))
                  .setProjectFilesystem(filesystem)
                  .build(),
              ImmutableList.of(new SleepStep(300)),
              /* output */ null);

      // Create the top-level rule which pulls in the two sides of the dep tree.
      BuildRule rule =
          new RuleWithSteps(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule"))
                  .setDeclaredDeps(ImmutableSortedSet.of(dep2, dep4))
                  .setProjectFilesystem(filesystem)
                  .build(),
              ImmutableList.of(new SleepStep(1000)),
              /* output */ null);

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory().setBuildMode(CachingBuildEngine.BuildMode.DEEP).build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertTrue(service.shutdownNow().isEmpty());
      assertThat(result.getStatus(), equalTo(BuildRuleStatus.CANCELED));
      assertThat(
          Preconditions.checkNotNull(cachingBuildEngine.getBuildRuleResult(dep1.getBuildTarget()))
              .getStatus(),
          equalTo(BuildRuleStatus.FAIL));
      assertThat(
          Preconditions.checkNotNull(cachingBuildEngine.getBuildRuleResult(dep2.getBuildTarget()))
              .getStatus(),
          equalTo(BuildRuleStatus.CANCELED));
      assertThat(
          Preconditions.checkNotNull(cachingBuildEngine.getBuildRuleResult(dep3.getBuildTarget()))
              .getStatus(),
          Matchers.oneOf(BuildRuleStatus.SUCCESS, BuildRuleStatus.CANCELED));
      assertThat(
          Preconditions.checkNotNull(cachingBuildEngine.getBuildRuleResult(dep4.getBuildTarget()))
              .getStatus(),
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
              ImmutableList.of(new FailingStep()),
              /* output */ null);
      BuildRule dep2 =
          new RuleWithSteps(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:dep2"))
                  .setDeclaredDeps(ImmutableSortedSet.of(dep1))
                  .setProjectFilesystem(filesystem)
                  .build(),
              ImmutableList.of(new SleepStep(0)),
              /* output */ null);

      // Create another dep chain, which is two deep with rules that just sleep.
      BuildRule dep3 =
          new RuleWithSteps(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:dep3"))
                  .setProjectFilesystem(filesystem)
                  .build(),
              ImmutableList.of(new SleepStep(300)),
              /* output */ null);
      BuildRule dep4 =
          new RuleWithSteps(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:dep4"))
                  .setDeclaredDeps(ImmutableSortedSet.of(dep3))
                  .setProjectFilesystem(filesystem)
                  .build(),
              ImmutableList.of(new SleepStep(300)),
              /* output */ null);

      // Create the top-level rule which pulls in the two sides of the dep tree.
      BuildRule rule =
          new RuleWithSteps(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule"))
                  .setDeclaredDeps(ImmutableSortedSet.of(dep2, dep4))
                  .setProjectFilesystem(filesystem)
                  .build(),
              ImmutableList.of(new SleepStep(1000)),
              /* output */ null);

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory().setExecutorService(service).build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertTrue(service.shutdownNow().isEmpty());
      assertThat(result.getStatus(), equalTo(BuildRuleStatus.CANCELED));
      assertThat(
          Preconditions.checkNotNull(cachingBuildEngine.getBuildRuleResult(dep1.getBuildTarget()))
              .getStatus(),
          equalTo(BuildRuleStatus.FAIL));
      assertThat(
          Preconditions.checkNotNull(cachingBuildEngine.getBuildRuleResult(dep2.getBuildTarget()))
              .getStatus(),
          equalTo(BuildRuleStatus.CANCELED));
      assertThat(
          Preconditions.checkNotNull(cachingBuildEngine.getBuildRuleResult(dep3.getBuildTarget()))
              .getStatus(),
          equalTo(BuildRuleStatus.SUCCESS));
      assertThat(
          Preconditions.checkNotNull(cachingBuildEngine.getBuildRuleResult(dep4.getBuildTarget()))
              .getStatus(),
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
              .setSrcs(ImmutableList.of(rule3.getSourcePathToOutput()))
              .build(resolver);
      BuildRule rule1 =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule1"))
              .setOut("out1")
              .setSrcs(ImmutableList.of(rule2.getSourcePathToOutput()))
              .build(resolver);

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setCachingBuildEngineDelegate(
                  new LocalCachingBuildEngineDelegate(new NullFileHashCache()))
              .build();

      assertThat(cachingBuildEngine.getNumRulesToBuild(ImmutableList.of(rule1)), equalTo(3));
    }

    @Test
    public void artifactCacheSizeLimit() throws Exception {
      // Create a simple rule which just writes something new to the output file.
      BuildRule rule =
          new WriteFile(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule"))
                  .setProjectFilesystem(filesystem)
                  .build(),
              "data",
              Paths.get("output/path"),
              /* executable */ false);

      // Create the build engine with low cache artifact limit which prevents caching the above\
      // rule.
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setCachingBuildEngineDelegate(
                  new LocalCachingBuildEngineDelegate(new NullFileHashCache()))
              .setArtifactCacheSizeLimit(Optional.of(2L))
              .build();

      // Verify that after building successfully, nothing is cached.
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertThat(result.getSuccess(), equalTo(BuildRuleSuccessType.BUILT_LOCALLY));
      assertTrue(cache.isEmpty());
    }

    @Test
    public void fetchingFromCacheSeedsFileHashCache() throws Throwable {
      // Create a simple rule which just writes something new to the output file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      Path output = filesystem.getPath("output/path");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target).setProjectFilesystem(filesystem).build();
      BuildRule rule = new WriteFile(params, "something else", output, /* executable */ false);

      // Run an initial build to seed the cache.
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build();
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());

      // Clear the file system.
      filesystem.clear();
      buildInfoStore.deleteMetadata(target);

      // Now run a second build that gets a cache hit.  We use an empty `FakeFileHashCache` which
      // does *not* contain the path, so any attempts to hash it will fail.
      FakeFileHashCache fakeFileHashCache = new FakeFileHashCache(new HashMap<Path, HashCode>());
      cachingBuildEngine =
          cachingBuildEngineFactory()
              .setCachingBuildEngineDelegate(new LocalCachingBuildEngineDelegate(fakeFileHashCache))
              .build();
      result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertEquals(BuildRuleSuccessType.FETCHED_FROM_CACHE, result.getSuccess());

      // Verify that the cache hit caused the file hash cache to contain the path.
      assertTrue(fakeFileHashCache.contains(filesystem.resolve(output)));
    }
  }

  public static class InputBasedRuleKeyTests extends CommonFixture {
    public InputBasedRuleKeyTests(CachingBuildEngine.MetadataStorage metadataStorage)
        throws IOException {
      super(metadataStorage);
    }

    @Test
    public void inputBasedRuleKeyAndArtifactAreWrittenForSupportedRules() throws Exception {
      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target).setProjectFilesystem(filesystem).build();
      final RuleKey inputRuleKey = new RuleKey("aaaa");
      final Path output = Paths.get("output");
      final BuildRule rule =
          new InputRuleKeyBuildRule(params, pathResolver) {
            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return new ExplicitBuildTargetSourcePath(getBuildTarget(), output);
            }
          };

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setRuleKeyFactories(
                  RuleKeyFactories.of(
                      NOOP_RULE_KEY_FACTORY,
                      new FakeRuleKeyFactory(ImmutableMap.of(rule.getBuildTarget(), inputRuleKey)),
                      NOOP_DEP_FILE_RULE_KEY_FACTORY))
              .build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());

      // Verify that the artifact was indexed in the cache by the input rule key.
      assertTrue(cache.hasArtifact(inputRuleKey));

      // Verify the input rule key was written to disk.
      OnDiskBuildInfo onDiskBuildInfo =
          buildContext.createOnDiskBuildInfoFor(target, filesystem, buildInfoStore);
      assertThat(
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY),
          equalTo(Optional.of(inputRuleKey)));
    }

    @Test
    public void inputBasedRuleKeyMatchAvoidsBuildingLocally() throws Exception {
      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target).setProjectFilesystem(filesystem).build();
      final RuleKey inputRuleKey = new RuleKey("aaaa");
      final BuildRule rule = new FailingInputRuleKeyBuildRule(params, pathResolver);
      resolver.addToIndex(rule);

      // Create the output file.
      filesystem.writeContentsToPath(
          "stuff", pathResolver.getRelativePath(rule.getSourcePathToOutput()));

      // Prepopulate the recorded paths metadata.
      BuildInfoRecorder recorder = createBuildInfoRecorder(target);
      recorder.addMetadata(
          BuildInfo.MetadataKey.RECORDED_PATHS,
          ImmutableList.of(pathResolver.getRelativePath(rule.getSourcePathToOutput()).toString()));

      // Prepopulate the input rule key on disk, so that we avoid a rebuild.
      recorder.addBuildMetadata(
          BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY, inputRuleKey.toString());
      recorder.writeMetadataToDisk(true);

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setRuleKeyFactories(
                  RuleKeyFactories.of(
                      defaultRuleKeyFactory,
                      new FakeRuleKeyFactory(ImmutableMap.of(rule.getBuildTarget(), inputRuleKey)),
                      NOOP_DEP_FILE_RULE_KEY_FACTORY))
              .build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertEquals(BuildRuleSuccessType.MATCHING_INPUT_BASED_RULE_KEY, result.getSuccess());

      // Verify the input-based and actual rule keys were updated on disk.
      OnDiskBuildInfo onDiskBuildInfo =
          buildContext.createOnDiskBuildInfoFor(target, filesystem, buildInfoStore);
      assertThat(
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.RULE_KEY),
          equalTo(Optional.of(defaultRuleKeyFactory.build(rule))));

      // Verify that the artifact is *not* re-cached under the main rule key.
      LazyPath fetchedArtifact = LazyPath.ofInstance(tmp.newFile("fetched_artifact.zip"));
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
          new FakeBuildRuleParamsBuilder(target).setProjectFilesystem(filesystem).build();
      final BuildRule rule = new FailingInputRuleKeyBuildRule(params, pathResolver);
      resolver.addToIndex(rule);

      // Prepopulate the recorded paths metadata.
      filesystem.writeContentsToPath(
          ObjectMappers.WRITER.writeValueAsString(
              ImmutableList.of(
                  pathResolver.getRelativePath(rule.getSourcePathToOutput()).toString())),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.RECORDED_PATHS));

      // Prepopulate the cache with an artifact indexed by the input-based rule key.
      Path artifact = tmp.newFile("artifact.zip");
      writeEntriesToZip(
          artifact,
          ImmutableMap.of(
              BuildInfo.getPathToMetadataDirectory(target, filesystem)
                  .resolve(BuildInfo.MetadataKey.RECORDED_PATHS),
              ObjectMappers.WRITER.writeValueAsString(
                  ImmutableList.of(
                      pathResolver.getRelativePath(rule.getSourcePathToOutput()).toString())),
              pathResolver.getRelativePath(rule.getSourcePathToOutput()),
              "stuff"));
      cache.store(
          ArtifactInfo.builder()
              .addRuleKeys(inputRuleKey)
              .putMetadata(BuildInfo.MetadataKey.BUILD_ID, buildContext.getBuildId().toString())
              .putMetadata(
                  BuildInfo.MetadataKey.ORIGIN_BUILD_ID, buildContext.getBuildId().toString())
              .putMetadata(BuildInfo.MetadataKey.RULE_KEY, new RuleKey("bbbb").toString())
              .putMetadata(BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY, inputRuleKey.toString())
              .build(),
          BorrowablePath.notBorrowablePath(artifact));

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setRuleKeyFactories(
                  RuleKeyFactories.of(
                      defaultRuleKeyFactory,
                      new FakeRuleKeyFactory(ImmutableMap.of(rule.getBuildTarget(), inputRuleKey)),
                      NOOP_DEP_FILE_RULE_KEY_FACTORY))
              .build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertEquals(BuildRuleSuccessType.FETCHED_FROM_CACHE_INPUT_BASED, result.getSuccess());

      // Verify the input-based and actual rule keys were updated on disk.
      OnDiskBuildInfo onDiskBuildInfo =
          buildContext.createOnDiskBuildInfoFor(target, filesystem, buildInfoStore);
      assertThat(
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.RULE_KEY),
          equalTo(Optional.of(defaultRuleKeyFactory.build(rule))));
      assertThat(
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY),
          equalTo(Optional.of(inputRuleKey)));

      // Verify that the artifact is re-cached correctly under the main rule key.
      Path fetchedArtifact = tmp.newFile("fetched_artifact.zip");
      assertThat(
          cache
              .fetch(defaultRuleKeyFactory.build(rule), LazyPath.ofInstance(fetchedArtifact))
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
          new FakeBuildRuleParamsBuilder(target).setProjectFilesystem(filesystem).build();
      final BuildRule rule =
          new InputRuleKeyBuildRule(params, pathResolver) {
            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return new ExplicitBuildTargetSourcePath(getBuildTarget(), output);
            }
          };
      resolver.addToIndex(rule);

      // Create the output file.
      filesystem.writeContentsToPath(
          "stuff", pathResolver.getRelativePath(rule.getSourcePathToOutput()));

      // Prepopulate the recorded paths metadata.
      filesystem.writeContentsToPath(
          ObjectMappers.WRITER.writeValueAsString(
              ImmutableList.of(
                  pathResolver.getRelativePath(rule.getSourcePathToOutput()).toString())),
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
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setRuleKeyFactories(
                  RuleKeyFactories.of(
                      defaultRuleKeyFactory,
                      new FakeRuleKeyFactory(
                          ImmutableMap.of(), ImmutableSet.of(rule.getBuildTarget())),
                      NOOP_DEP_FILE_RULE_KEY_FACTORY))
              .build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());

      // Verify the input-based and actual rule keys were updated on disk.
      OnDiskBuildInfo onDiskBuildInfo =
          buildContext.createOnDiskBuildInfoFor(target, filesystem, buildInfoStore);
      assertThat(
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.RULE_KEY),
          equalTo(Optional.of(defaultRuleKeyFactory.build(rule))));
      assertThat(
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY),
          equalTo(Optional.empty()));
    }

    private static class FailingInputRuleKeyBuildRule extends InputRuleKeyBuildRule {
      public FailingInputRuleKeyBuildRule(
          BuildRuleParams buildRuleParams, SourcePathResolver resolver) {
        super(buildRuleParams, resolver);
      }

      @Override
      public ImmutableList<Step> getBuildSteps(
          BuildContext context, BuildableContext buildableContext) {
        return ImmutableList.of(
            new AbstractExecutionStep("false") {
              @Override
              public StepExecutionResult execute(ExecutionContext context) {
                return StepExecutionResult.ERROR;
              }
            });
      }

      @Override
      public SourcePath getSourcePathToOutput() {
        return new ExplicitBuildTargetSourcePath(getBuildTarget(), Paths.get("output"));
      }
    }
  }

  public static class DepFileTests extends CommonFixture {

    private DefaultDependencyFileRuleKeyFactory depFileFactory;

    public DepFileTests(CachingBuildEngine.MetadataStorage metadataStorage) throws IOException {
      super(metadataStorage);
    }

    @Before
    public void setUpDepFileFixture() {
      depFileFactory =
          new DefaultDependencyFileRuleKeyFactory(
              FIELD_LOADER, fileHashCache, pathResolver, ruleFinder);
    }

    @Test
    public void depFileRuleKeyAndDepFileAreWrittenForSupportedRules() throws Exception {
      // Use a genrule to produce the input file.
      final Genrule genrule =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
              .setOut("input")
              .build(resolver, filesystem);
      final Path input =
          pathResolver.getRelativePath(Preconditions.checkNotNull(genrule.getSourcePathToOutput()));
      filesystem.writeContentsToPath("contents", input);

      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target).setProjectFilesystem(filesystem).build();
      final Path output = Paths.get("output");
      final DepFileBuildRule rule =
          new DepFileBuildRule(params) {
            @AddToRuleKey private final SourcePath path = genrule.getSourcePathToOutput();

            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }

            @Override
            public Predicate<SourcePath> getCoveredByDepFilePredicate() {
              return (SourcePath path) -> true;
            }

            @Override
            public Predicate<SourcePath> getExistenceOfInterestPredicate() {
              return (SourcePath path) -> false;
            }

            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally(BuildContext context) {
              return ImmutableList.of(new PathSourcePath(filesystem, input));
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return new ExplicitBuildTargetSourcePath(getBuildTarget(), output);
            }
          };

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine = engineWithDepFileFactory(depFileFactory);

      // Run the build.
      RuleKey depFileRuleKey =
          depFileFactory
              .build(rule, ImmutableList.of(DependencyFileEntry.of(input, Optional.empty())))
              .getRuleKey();
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, getSuccess(result));

      // Verify the dep file rule key and dep file contents were written to disk.
      OnDiskBuildInfo onDiskBuildInfo =
          buildContext.createOnDiskBuildInfoFor(target, filesystem, buildInfoStore);
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
      assertThat(cacheResult.getType(), equalTo(CacheResultType.HIT));
      assertThat(
          cacheResult.getMetadata().get(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY),
          equalTo(depFileRuleKey.toString()));
      ZipInspector inspector = new ZipInspector(fetchedArtifact);
      inspector.assertFileContents(
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.DEP_FILE),
          ObjectMappers.WRITER.writeValueAsString(
              ImmutableList.of(fileToDepFileEntryString(input))));
    }

    @Test
    public void depFileRuleKeyMatchAvoidsBuilding() throws Exception {
      // Prepare an input file that should appear in the dep file.
      final Path input = Paths.get("input_file");
      filesystem.touch(input);

      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target).setProjectFilesystem(filesystem).build();
      final RuleKey depFileRuleKey = new RuleKey("aaaa");
      final Path output = Paths.get("output");
      filesystem.touch(output);
      final BuildRule rule =
          new DepFileBuildRule(params) {
            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              return ImmutableList.of(
                  new AbstractExecutionStep("false") {
                    @Override
                    public StepExecutionResult execute(ExecutionContext context) {
                      return StepExecutionResult.ERROR;
                    }
                  });
            }

            @Override
            public Predicate<SourcePath> getCoveredByDepFilePredicate() {
              return (SourcePath path) -> true;
            }

            @Override
            public Predicate<SourcePath> getExistenceOfInterestPredicate() {
              return (SourcePath path) -> false;
            }

            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally(BuildContext context) {
              return ImmutableList.of(new PathSourcePath(filesystem, input));
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return new ExplicitBuildTargetSourcePath(getBuildTarget(), output);
            }
          };

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine =
          engineWithDepFileFactory(
              new FakeRuleKeyFactory(ImmutableMap.of(rule.getBuildTarget(), depFileRuleKey)));

      // Prepopulate the dep file rule key and dep file.
      BuildInfoRecorder recorder = createBuildInfoRecorder(rule.getBuildTarget());
      recorder.addBuildMetadata(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY, depFileRuleKey.toString());
      recorder.addMetadata(
          BuildInfo.MetadataKey.DEP_FILE, ImmutableList.of(fileToDepFileEntryString(input)));
      // Prepopulate the recorded paths metadata.
      recorder.addMetadata(
          BuildInfo.MetadataKey.RECORDED_PATHS, ImmutableList.of(output.toString()));
      recorder.writeMetadataToDisk(true);

      // Run the build.
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertEquals(BuildRuleSuccessType.MATCHING_DEP_FILE_RULE_KEY, result.getSuccess());
    }

    @Test
    public void depFileInputChangeCausesRebuild() throws Exception {
      // Use a genrule to produce the input file.
      final Genrule genrule =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
              .setOut("input")
              .build(resolver, filesystem);
      final Path input =
          pathResolver.getRelativePath(Preconditions.checkNotNull(genrule.getSourcePathToOutput()));

      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target).setProjectFilesystem(filesystem).build();
      final Path output = Paths.get("output");
      DepFileBuildRule rule =
          new DepFileBuildRule(params) {
            @AddToRuleKey private final SourcePath path = genrule.getSourcePathToOutput();

            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              buildableContext.addMetadata(
                  BuildInfo.MetadataKey.DEP_FILE,
                  ImmutableList.of(fileToDepFileEntryString(input)));
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }

            @Override
            public Predicate<SourcePath> getCoveredByDepFilePredicate() {
              return (SourcePath path) -> true;
            }

            @Override
            public Predicate<SourcePath> getExistenceOfInterestPredicate() {
              return (SourcePath path) -> false;
            }

            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally(BuildContext context) {
              return ImmutableList.of(new PathSourcePath(filesystem, input));
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return new ExplicitBuildTargetSourcePath(getBuildTarget(), output);
            }
          };

      // Prepare an input file that should appear in the dep file.
      filesystem.writeContentsToPath("something", input);
      RuleKey depFileRuleKey =
          depFileFactory
              .build(rule, ImmutableList.of(DependencyFileEntry.of(input, Optional.empty())))
              .getRuleKey();

      // Prepopulate the dep file rule key and dep file.
      BuildInfoRecorder recorder = createBuildInfoRecorder(rule.getBuildTarget());
      recorder.addBuildMetadata(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY, depFileRuleKey.toString());
      recorder.addMetadata(
          BuildInfo.MetadataKey.DEP_FILE, ImmutableList.of(fileToDepFileEntryString(input)));

      // Prepopulate the recorded paths metadata.
      recorder.addMetadata(
          BuildInfo.MetadataKey.RECORDED_PATHS, ImmutableList.of(output.toString()));
      recorder.writeMetadataToDisk(true);

      // Now modify the input file and invalidate it in the cache.
      filesystem.writeContentsToPath("something else", input);
      fileHashCache.invalidate(filesystem.resolve(input));

      // Run the build.
      CachingBuildEngine cachingBuildEngine = engineWithDepFileFactory(depFileFactory);
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, getSuccess(result));
    }

    @Test
    public void nonDepFileEligibleInputChangeCausesRebuild() throws Exception {
      final Path inputFile = Paths.get("input");

      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target).setProjectFilesystem(filesystem).build();
      final Path output = Paths.get("output");
      final ImmutableSet<SourcePath> inputsBefore = ImmutableSet.of();
      DepFileBuildRule rule =
          new DepFileBuildRule(params) {
            @AddToRuleKey private final SourcePath path = new PathSourcePath(filesystem, inputFile);

            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              buildableContext.addMetadata(BuildInfo.MetadataKey.DEP_FILE, ImmutableList.of());
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }

            @Override
            public Predicate<SourcePath> getCoveredByDepFilePredicate() {
              return inputsBefore::contains;
            }

            @Override
            public Predicate<SourcePath> getExistenceOfInterestPredicate() {
              return (SourcePath path) -> false;
            }

            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally(BuildContext context) {
              return ImmutableList.of();
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return new ExplicitBuildTargetSourcePath(getBuildTarget(), output);
            }
          };

      // Prepare an input file that will not appear in the dep file. This is to simulate a
      // a dependency that the dep-file generator is not aware of.
      filesystem.writeContentsToPath("something", inputFile);

      // Prepopulate the dep file rule key and dep file.
      RuleKey depFileRuleKey = depFileFactory.build(rule, ImmutableList.of()).getRuleKey();
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
          ObjectMappers.WRITER.writeValueAsString(ImmutableList.of(output.toString())),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.RECORDED_PATHS));

      // Now modify the input file and invalidate it in the cache.
      filesystem.writeContentsToPath("something else", inputFile);
      fileHashCache.invalidate(filesystem.resolve(inputFile));

      // Run the build.
      CachingBuildEngine cachingBuildEngine = engineWithDepFileFactory(depFileFactory);
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();

      // The dep file should still be empty, yet the target will rebuild because of the change
      // to the non-dep-file-eligible input file
      String newDepFile =
          filesystem
              .readLines(
                  BuildInfo.getPathToMetadataDirectory(target, filesystem)
                      .resolve(BuildInfo.MetadataKey.DEP_FILE))
              .get(0);
      assertEquals(emptyDepFileContents, newDepFile);
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, getSuccess(result));
    }

    @Test
    public void depFileDeletedInputCausesRebuild() throws Exception {
      // Use a genrule to produce the input file.
      final Genrule genrule =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
              .setOut("input")
              .build(resolver, filesystem);
      final Path input =
          pathResolver.getRelativePath(Preconditions.checkNotNull(genrule.getSourcePathToOutput()));

      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target).setProjectFilesystem(filesystem).build();
      final Path output = Paths.get("output");
      DepFileBuildRule rule =
          new DepFileBuildRule(params) {
            @AddToRuleKey private final SourcePath path = genrule.getSourcePathToOutput();

            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              buildableContext.addMetadata(
                  BuildInfo.MetadataKey.DEP_FILE,
                  ImmutableList.of(fileToDepFileEntryString(input)));
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }

            @Override
            public Predicate<SourcePath> getCoveredByDepFilePredicate() {
              return (SourcePath path) -> true;
            }

            @Override
            public Predicate<SourcePath> getExistenceOfInterestPredicate() {
              return (SourcePath path) -> false;
            }

            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally(BuildContext context) {
              return ImmutableList.of();
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return new ExplicitBuildTargetSourcePath(getBuildTarget(), output);
            }
          };

      // Prepare an input file that should appear in the dep file.
      filesystem.writeContentsToPath("something", input);
      RuleKey depFileRuleKey =
          depFileFactory
              .build(rule, ImmutableList.of(DependencyFileEntry.of(input, Optional.empty())))
              .getRuleKey();

      // Prepopulate the dep file rule key and dep file.
      filesystem.writeContentsToPath(
          depFileRuleKey.toString(),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY));
      filesystem.writeContentsToPath(
          ObjectMappers.WRITER.writeValueAsString(
              ImmutableList.of(fileToDepFileEntryString(input))),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.DEP_FILE));

      // Prepopulate the recorded paths metadata.
      filesystem.writeContentsToPath(
          ObjectMappers.WRITER.writeValueAsString(ImmutableList.of(output.toString())),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.RECORDED_PATHS));

      // Now delete the input and invalidate it in the cache.
      filesystem.deleteFileAtPath(input);
      fileHashCache.invalidate(filesystem.resolve(input));

      // Run the build.
      CachingBuildEngine cachingBuildEngine = engineWithDepFileFactory(depFileFactory);
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, getSuccess(result));
    }

    @Test
    public void missingDepFileKeyCausesLocalBuild() throws Exception {
      // Use a genrule to produce the input file.
      final Genrule genrule =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
              .setOut("input")
              .build(resolver, filesystem);
      final Path input =
          pathResolver.getRelativePath(Preconditions.checkNotNull(genrule.getSourcePathToOutput()));

      // Create a simple rule which just writes a file.
      final BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target).setProjectFilesystem(filesystem).build();
      final Path output = Paths.get("output");
      DepFileBuildRule rule =
          new DepFileBuildRule(params) {
            @AddToRuleKey private final SourcePath path = genrule.getSourcePathToOutput();

            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              buildableContext.addMetadata(
                  BuildInfo.MetadataKey.DEP_FILE,
                  ImmutableList.of(fileToDepFileEntryString(input)));
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }

            @Override
            public Predicate<SourcePath> getCoveredByDepFilePredicate() {
              return (SourcePath path) -> true;
            }

            @Override
            public Predicate<SourcePath> getExistenceOfInterestPredicate() {
              return (SourcePath path) -> false;
            }

            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally(BuildContext context) {
              return ImmutableList.of();
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return new ExplicitBuildTargetSourcePath(getBuildTarget(), output);
            }
          };

      DependencyFileRuleKeyFactory depFileRuleKeyFactory =
          new FakeRuleKeyFactory(ImmutableMap.of(rule.getBuildTarget(), new RuleKey("aa")));

      // Prepare an input file that should appear in the dep file.
      filesystem.writeContentsToPath("something", input);

      RuleKey depFileRuleKey =
          depFileFactory
              .build(rule, ImmutableList.of(DependencyFileEntry.of(input, Optional.empty())))
              .getRuleKey();

      // Prepopulate the dep file rule key and dep file.
      filesystem.writeContentsToPath(
          depFileRuleKey.toString(),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY));
      filesystem.writeContentsToPath(
          ObjectMappers.WRITER.writeValueAsString(
              ImmutableList.of(fileToDepFileEntryString(input))),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.DEP_FILE));

      // Prepopulate the recorded paths metadata.
      filesystem.writeContentsToPath(
          ObjectMappers.WRITER.writeValueAsString(ImmutableList.of(output.toString())),
          BuildInfo.getPathToMetadataDirectory(target, filesystem)
              .resolve(BuildInfo.MetadataKey.RECORDED_PATHS));

      // Run the build.
      CachingBuildEngine cachingBuildEngine = engineWithDepFileFactory(depFileRuleKeyFactory);
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, getSuccess(result));

      // Verify the input-based and actual rule keys were updated on disk.
      OnDiskBuildInfo onDiskBuildInfo =
          buildContext.createOnDiskBuildInfoFor(target, filesystem, buildInfoStore);
      assertThat(
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.RULE_KEY),
          equalTo(Optional.of(defaultRuleKeyFactory.build(rule))));
      assertThat(
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY),
          equalTo(Optional.of(new RuleKey("aa"))));
    }

    public CachingBuildEngine engineWithDepFileFactory(
        DependencyFileRuleKeyFactory depFileFactory) {
      return cachingBuildEngineFactory()
          .setRuleKeyFactories(
              RuleKeyFactories.of(
                  defaultRuleKeyFactory, NOOP_INPUT_BASED_RULE_KEY_FACTORY, depFileFactory))
          .build();
    }
  }

  public static class ManifestTests extends CommonFixture {
    public ManifestTests(CachingBuildEngine.MetadataStorage metadataStorage) throws IOException {
      super(metadataStorage);
    }

    @Test
    public void manifestIsWrittenWhenBuiltLocally() throws Exception {
      DefaultDependencyFileRuleKeyFactory depFilefactory =
          new DefaultDependencyFileRuleKeyFactory(
              FIELD_LOADER, fileHashCache, pathResolver, ruleFinder);

      // Use a genrule to produce the input file.
      final Genrule genrule =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
              .setOut("input")
              .build(resolver, filesystem);
      final Path input =
          pathResolver.getRelativePath(Preconditions.checkNotNull(genrule.getSourcePathToOutput()));
      filesystem.writeContentsToPath("contents", input);

      // Create another input that will be ineligible for the dep file. Such inputs should still
      // be part of the manifest.
      final Path input2 = Paths.get("input2");
      filesystem.writeContentsToPath("contents2", input2);

      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target).setProjectFilesystem(filesystem).build();
      final Path output = Paths.get("output");
      DepFileBuildRule rule =
          new DepFileBuildRule(params) {
            @AddToRuleKey private final SourcePath path = genrule.getSourcePathToOutput();

            @AddToRuleKey
            private final SourcePath otherDep = new PathSourcePath(filesystem, input2);

            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }

            @Override
            public Predicate<SourcePath> getCoveredByDepFilePredicate() {
              return ImmutableSet.of(path)::contains;
            }

            @Override
            public Predicate<SourcePath> getExistenceOfInterestPredicate() {
              return (SourcePath path) -> false;
            }

            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally(BuildContext context) {
              return ImmutableList.of(path);
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return new ExplicitBuildTargetSourcePath(getBuildTarget(), output);
            }
          };

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setDepFiles(CachingBuildEngine.DepFiles.CACHE)
              .setRuleKeyFactories(
                  RuleKeyFactories.of(
                      defaultRuleKeyFactory, inputBasedRuleKeyFactory, depFilefactory))
              .build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertThat(getSuccess(result), equalTo(BuildRuleSuccessType.BUILT_LOCALLY));

      OnDiskBuildInfo onDiskBuildInfo =
          buildContext.createOnDiskBuildInfoFor(target, filesystem, buildInfoStore);
      RuleKey depFileRuleKey =
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY).get();

      // Verify that the manifest written to the cache is correct.
      Path fetchedManifest = tmp.newFile("manifest");
      CacheResult cacheResult =
          cache.fetch(
              cachingBuildEngine.getManifestRuleKey(rule, buildContext.getEventBus()).get(),
              LazyPath.ofInstance(fetchedManifest));
      assertThat(cacheResult.getType(), equalTo(CacheResultType.HIT));
      Manifest manifest = loadManifest(fetchedManifest);
      // The manifest should only contain the inputs that were in the dep file. The non-eligible
      // dependency went toward computing the manifest key and thus doesn't need to be in the value.
      assertThat(
          manifest.toMap(),
          equalTo(
              ImmutableMap.of(
                  depFileRuleKey,
                  ImmutableMap.of(
                      input.toString(), fileHashCache.get(filesystem.resolve(input))))));

      // Verify that the artifact is also cached via the dep file rule key.
      Path fetchedArtifact = tmp.newFile("artifact");
      cacheResult = cache.fetch(depFileRuleKey, LazyPath.ofInstance(fetchedArtifact));
      assertThat(cacheResult.getType(), equalTo(CacheResultType.HIT));
    }

    @Test
    public void manifestIsUpdatedWhenBuiltLocally() throws Exception {
      DefaultDependencyFileRuleKeyFactory depFilefactory =
          new DefaultDependencyFileRuleKeyFactory(
              FIELD_LOADER, fileHashCache, pathResolver, ruleFinder);

      // Use a genrule to produce the input file.
      final Genrule genrule =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
              .setOut("input")
              .build(resolver, filesystem);
      final Path input =
          pathResolver.getRelativePath(Preconditions.checkNotNull(genrule.getSourcePathToOutput()));
      filesystem.writeContentsToPath("contents", input);

      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target).setProjectFilesystem(filesystem).build();
      final Path output = Paths.get("output");
      DepFileBuildRule rule =
          new DepFileBuildRule(params) {
            @AddToRuleKey private final SourcePath path = genrule.getSourcePathToOutput();

            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }

            @Override
            public Predicate<SourcePath> getCoveredByDepFilePredicate() {
              return (SourcePath path) -> true;
            }

            @Override
            public Predicate<SourcePath> getExistenceOfInterestPredicate() {
              return (SourcePath path) -> false;
            }

            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally(BuildContext context) {
              return ImmutableList.of(new PathSourcePath(filesystem, input));
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return new ExplicitBuildTargetSourcePath(getBuildTarget(), output);
            }
          };

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setDepFiles(CachingBuildEngine.DepFiles.CACHE)
              .setRuleKeyFactories(
                  RuleKeyFactories.of(
                      defaultRuleKeyFactory, inputBasedRuleKeyFactory, depFilefactory))
              .build();

      // Seed the cache with an existing manifest with a dummy entry.
      Manifest manifest =
          Manifest.fromMap(
              depFilefactory.buildManifestKey(rule).getRuleKey(),
              ImmutableMap.of(
                  new RuleKey("abcd"), ImmutableMap.of("some/path.h", HashCode.fromInt(12))));
      ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
      try (GZIPOutputStream outputStream = new GZIPOutputStream(byteArrayOutputStream)) {
        manifest.serialize(outputStream);
      }
      cache.store(
          ArtifactInfo.builder()
              .addRuleKeys(
                  cachingBuildEngine.getManifestRuleKey(rule, buildContext.getEventBus()).get())
              .build(),
          byteArrayOutputStream.toByteArray());

      // Run the build.
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertThat(getSuccess(result), equalTo(BuildRuleSuccessType.BUILT_LOCALLY));

      OnDiskBuildInfo onDiskBuildInfo =
          buildContext.createOnDiskBuildInfoFor(target, filesystem, buildInfoStore);
      RuleKey depFileRuleKey =
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY).get();

      // Verify that the manifest written to the cache is correct.
      Path fetchedManifest = tmp.newFile("manifest");
      CacheResult cacheResult =
          cache.fetch(
              cachingBuildEngine.getManifestRuleKey(rule, buildContext.getEventBus()).get(),
              LazyPath.ofInstance(fetchedManifest));
      assertThat(cacheResult.getType(), equalTo(CacheResultType.HIT));
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
      cacheResult = cache.fetch(depFileRuleKey, LazyPath.ofInstance(fetchedArtifact));
      assertThat(cacheResult.getType(), equalTo(CacheResultType.HIT));
    }

    @Test
    public void manifestIsTruncatedWhenGrowingPastSizeLimit() throws Exception {
      DefaultDependencyFileRuleKeyFactory depFilefactory =
          new DefaultDependencyFileRuleKeyFactory(
              FIELD_LOADER, fileHashCache, pathResolver, ruleFinder);

      // Use a genrule to produce the input file.
      final Genrule genrule =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
              .setOut("input")
              .build(resolver, filesystem);
      final Path input =
          pathResolver.getRelativePath(Preconditions.checkNotNull(genrule.getSourcePathToOutput()));
      filesystem.writeContentsToPath("contents", input);

      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target).setProjectFilesystem(filesystem).build();
      final Path output = Paths.get("output");
      DepFileBuildRule rule =
          new DepFileBuildRule(params) {
            @AddToRuleKey private final SourcePath path = genrule.getSourcePathToOutput();

            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }

            @Override
            public Predicate<SourcePath> getCoveredByDepFilePredicate() {
              return (SourcePath path) -> true;
            }

            @Override
            public Predicate<SourcePath> getExistenceOfInterestPredicate() {
              return (SourcePath path) -> false;
            }

            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally(BuildContext context) {
              return ImmutableList.of(new PathSourcePath(filesystem, input));
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return new ExplicitBuildTargetSourcePath(getBuildTarget(), output);
            }
          };

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setDepFiles(CachingBuildEngine.DepFiles.CACHE)
              .setMaxDepFileCacheEntries(1L)
              .setRuleKeyFactories(
                  RuleKeyFactories.of(
                      defaultRuleKeyFactory, inputBasedRuleKeyFactory, depFilefactory))
              .build();

      // Seed the cache with an existing manifest with a dummy entry so that it's already at the max
      // size.
      Manifest manifest =
          Manifest.fromMap(
              depFilefactory.buildManifestKey(rule).getRuleKey(),
              ImmutableMap.of(
                  new RuleKey("abcd"), ImmutableMap.of("some/path.h", HashCode.fromInt(12))));
      ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
      try (GZIPOutputStream outputStream = new GZIPOutputStream(byteArrayOutputStream)) {
        manifest.serialize(outputStream);
      }
      cache.store(
          ArtifactInfo.builder()
              .addRuleKeys(
                  cachingBuildEngine.getManifestRuleKey(rule, buildContext.getEventBus()).get())
              .build(),
          byteArrayOutputStream.toByteArray());

      // Run the build.
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertThat(getSuccess(result), equalTo(BuildRuleSuccessType.BUILT_LOCALLY));

      OnDiskBuildInfo onDiskBuildInfo =
          buildContext.createOnDiskBuildInfoFor(target, filesystem, buildInfoStore);
      RuleKey depFileRuleKey =
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY).get();

      // Verify that the manifest is truncated and now only contains the newly written entry.
      Path fetchedManifest = tmp.newFile("manifest");
      CacheResult cacheResult =
          cache.fetch(
              cachingBuildEngine.getManifestRuleKey(rule, buildContext.getEventBus()).get(),
              LazyPath.ofInstance(fetchedManifest));
      assertThat(cacheResult.getType(), equalTo(CacheResultType.HIT));
      manifest = loadManifest(fetchedManifest);
      assertThat(
          manifest.toMap(),
          equalTo(
              ImmutableMap.of(
                  depFileRuleKey,
                  ImmutableMap.of(
                      input.toString(), fileHashCache.get(filesystem.resolve(input))))));
    }

    @Test
    public void manifestBasedCacheHit() throws Exception {
      DefaultDependencyFileRuleKeyFactory depFilefactory =
          new DefaultDependencyFileRuleKeyFactory(
              FIELD_LOADER, fileHashCache, pathResolver, ruleFinder);

      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target).setProjectFilesystem(filesystem).build();
      final SourcePath input =
          new PathSourcePath(filesystem, filesystem.getRootPath().getFileSystem().getPath("input"));
      filesystem.touch(pathResolver.getRelativePath(input));
      final Path output = Paths.get("output");
      DepFileBuildRule rule =
          new DepFileBuildRule(params) {
            @AddToRuleKey private final SourcePath path = input;

            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }

            @Override
            public Predicate<SourcePath> getCoveredByDepFilePredicate() {
              return (SourcePath path) -> true;
            }

            @Override
            public Predicate<SourcePath> getExistenceOfInterestPredicate() {
              return (SourcePath path) -> false;
            }

            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally(BuildContext context) {
              return ImmutableList.of(input);
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return new ExplicitBuildTargetSourcePath(getBuildTarget(), output);
            }
          };

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setDepFiles(CachingBuildEngine.DepFiles.CACHE)
              .setRuleKeyFactories(
                  RuleKeyFactories.of(
                      defaultRuleKeyFactory, inputBasedRuleKeyFactory, depFilefactory))
              .build();

      // Calculate expected rule keys.
      RuleKey ruleKey = defaultRuleKeyFactory.build(rule);
      RuleKeyAndInputs depFileKey =
          depFilefactory.build(
              rule, ImmutableList.of(DependencyFileEntry.fromSourcePath(input, pathResolver)));

      // Seed the cache with the manifest and a referenced artifact.
      Manifest manifest = new Manifest(depFilefactory.buildManifestKey(rule).getRuleKey());
      manifest.addEntry(
          fileHashCache,
          depFileKey.getRuleKey(),
          pathResolver,
          ImmutableSet.of(input),
          ImmutableSet.of(input));
      ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
      try (GZIPOutputStream outputStream = new GZIPOutputStream(byteArrayOutputStream)) {
        manifest.serialize(outputStream);
      }
      cache.store(
          ArtifactInfo.builder()
              .addRuleKeys(
                  cachingBuildEngine.getManifestRuleKey(rule, buildContext.getEventBus()).get())
              .build(),
          byteArrayOutputStream.toByteArray());
      Path artifact = tmp.newFile("artifact.zip");
      writeEntriesToZip(
          artifact,
          ImmutableMap.of(
              BuildInfo.getPathToMetadataDirectory(target, filesystem)
                  .resolve(BuildInfo.MetadataKey.RECORDED_PATHS),
              ObjectMappers.WRITER.writeValueAsString(ImmutableList.of(output.toString())),
              output,
              "stuff"));
      cache.store(
          ArtifactInfo.builder()
              .addRuleKeys(depFileKey.getRuleKey())
              .putMetadata(BuildInfo.MetadataKey.BUILD_ID, buildContext.getBuildId().toString())
              .putMetadata(
                  BuildInfo.MetadataKey.ORIGIN_BUILD_ID, buildContext.getBuildId().toString())
              .putMetadata(
                  BuildInfo.MetadataKey.DEP_FILE_RULE_KEY, depFileKey.getRuleKey().toString())
              .putMetadata(
                  BuildInfo.MetadataKey.DEP_FILE,
                  ObjectMappers.WRITER.writeValueAsString(
                      depFileKey
                          .getInputs()
                          .stream()
                          .map(pathResolver::getRelativePath)
                          .collect(MoreCollectors.toImmutableList())))
              .build(),
          BorrowablePath.notBorrowablePath(artifact));

      // Run the build.
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertThat(
          getSuccess(result), equalTo(BuildRuleSuccessType.FETCHED_FROM_CACHE_MANIFEST_BASED));

      // Verify that the result has been re-written to the cache with the expected meta-data.
      for (RuleKey key : ImmutableSet.of(ruleKey, depFileKey.getRuleKey())) {
        LazyPath fetchedArtifact = LazyPath.ofInstance(tmp.newFile("fetched_artifact.zip"));
        CacheResult cacheResult = cache.fetch(key, fetchedArtifact);
        assertThat(cacheResult.getType(), equalTo(CacheResultType.HIT));
        assertThat(
            cacheResult.getMetadata().get(BuildInfo.MetadataKey.RULE_KEY),
            equalTo(ruleKey.toString()));
        assertThat(
            cacheResult.getMetadata().get(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY),
            equalTo(depFileKey.getRuleKey().toString()));
        assertThat(
            cacheResult.getMetadata().get(BuildInfo.MetadataKey.DEP_FILE),
            equalTo(
                ObjectMappers.WRITER.writeValueAsString(
                    depFileKey
                        .getInputs()
                        .stream()
                        .map(pathResolver::getRelativePath)
                        .collect(MoreCollectors.toImmutableList()))));
        Files.delete(fetchedArtifact.get());
      }
    }

    @Test
    public void staleExistingManifestIsIgnored() throws Exception {
      DefaultDependencyFileRuleKeyFactory depFilefactory =
          new DefaultDependencyFileRuleKeyFactory(
              FIELD_LOADER, fileHashCache, pathResolver, ruleFinder);

      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target).setProjectFilesystem(filesystem).build();
      final SourcePath input =
          new PathSourcePath(filesystem, filesystem.getRootPath().getFileSystem().getPath("input"));
      filesystem.touch(pathResolver.getRelativePath(input));
      final Path output = Paths.get("output");
      DepFileBuildRule rule =
          new DepFileBuildRule(params) {
            @AddToRuleKey private final SourcePath path = input;

            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }

            @Override
            public Predicate<SourcePath> getCoveredByDepFilePredicate() {
              return (SourcePath path) -> true;
            }

            @Override
            public Predicate<SourcePath> getExistenceOfInterestPredicate() {
              return (SourcePath path) -> false;
            }

            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally(BuildContext context) {
              return ImmutableList.of(input);
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return new ExplicitBuildTargetSourcePath(getBuildTarget(), output);
            }
          };

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setDepFiles(CachingBuildEngine.DepFiles.CACHE)
              .setRuleKeyFactories(
                  RuleKeyFactories.of(
                      defaultRuleKeyFactory, inputBasedRuleKeyFactory, depFilefactory))
              .build();

      // Write out a stale manifest to the disk.
      RuleKey staleDepFileRuleKey = new RuleKey("dead");
      Manifest manifest = new Manifest(new RuleKey("beef"));
      manifest.addEntry(
          fileHashCache,
          staleDepFileRuleKey,
          pathResolver,
          ImmutableSet.of(input),
          ImmutableSet.of(input));
      try (OutputStream outputStream =
          filesystem.newFileOutputStream(cachingBuildEngine.getManifestPath(rule))) {
        manifest.serialize(outputStream);
      }

      // Run the build.
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertThat(getSuccess(result), equalTo(BuildRuleSuccessType.BUILT_LOCALLY));

      // Verify there's no stale entry in the manifest.
      LazyPath fetchedManifest = LazyPath.ofInstance(tmp.newFile("fetched_artifact.zip"));
      CacheResult cacheResult =
          cache.fetch(depFilefactory.buildManifestKey(rule).getRuleKey(), fetchedManifest);
      assertTrue(cacheResult.getType().isSuccess());
      Manifest cachedManifest = loadManifest(fetchedManifest.get());
      assertThat(cachedManifest.toMap().keySet(), Matchers.not(hasItem(staleDepFileRuleKey)));
    }
  }

  public static class UncachableRuleTests extends CommonFixture {
    public UncachableRuleTests(CachingBuildEngine.MetadataStorage metadataStorage)
        throws IOException {
      super(metadataStorage);
    }

    @Test
    public void uncachableRulesDoNotTouchTheCache() throws Exception {
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target).setProjectFilesystem(filesystem).build();
      BuildRule rule = new UncachableRule(params, ImmutableList.of(), Paths.get("foo.out"));
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setRuleKeyFactories(
                  RuleKeyFactories.of(
                      NOOP_RULE_KEY_FACTORY,
                      NOOP_INPUT_BASED_RULE_KEY_FACTORY,
                      NOOP_DEP_FILE_RULE_KEY_FACTORY))
              .build();
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());
      assertEquals(
          "Should not attempt to fetch from cache",
          CacheResultType.IGNORED,
          result.getCacheResult().getType());
      assertEquals("should not have written to the cache", 0, cache.getArtifactCount());
    }

    private static class UncachableRule extends RuleWithSteps
        implements SupportsDependencyFileRuleKey {
      public UncachableRule(
          BuildRuleParams buildRuleParams, ImmutableList<Step> steps, Path output) {
        super(buildRuleParams, steps, output);
      }

      @Override
      public boolean useDependencyFileRuleKeys() {
        return true;
      }

      @Override
      public Predicate<SourcePath> getCoveredByDepFilePredicate() {
        return (SourcePath path) -> true;
      }

      @Override
      public Predicate<SourcePath> getExistenceOfInterestPredicate() {
        return (SourcePath path) -> false;
      }

      @Override
      public ImmutableList<SourcePath> getInputsAfterBuildingLocally(BuildContext context)
          throws IOException {
        return ImmutableList.of();
      }

      @Override
      public boolean isCacheable() {
        return false;
      }
    }
  }

  public static class ScheduleOverrideTests extends CommonFixture {
    public ScheduleOverrideTests(CachingBuildEngine.MetadataStorage metadataStorage)
        throws IOException {
      super(metadataStorage);
    }

    @Test
    public void customWeights() throws Exception {
      ControlledRule rule1 =
          new ControlledRule(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule1"))
                  .setProjectFilesystem(filesystem)
                  .build(),
              pathResolver,
              RuleScheduleInfo.builder().setJobsMultiplier(2).build());
      ControlledRule rule2 =
          new ControlledRule(
              new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:rule2"))
                  .setProjectFilesystem(filesystem)
                  .build(),
              pathResolver,
              RuleScheduleInfo.builder().setJobsMultiplier(2).build());
      ListeningMultiSemaphore semaphore =
          new ListeningMultiSemaphore(
              ResourceAmounts.of(3, 0, 0, 0), ResourceAllocationFairness.FAIR);
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setExecutorService(
                  new WeightedListeningExecutorService(
                      semaphore,
                      /* defaultWeight */ ResourceAmounts.of(1, 0, 0, 0),
                      listeningDecorator(Executors.newCachedThreadPool())))
              .setRuleKeyFactories(
                  RuleKeyFactories.of(
                      NOOP_RULE_KEY_FACTORY,
                      NOOP_INPUT_BASED_RULE_KEY_FACTORY,
                      NOOP_DEP_FILE_RULE_KEY_FACTORY))
              .build();
      ListenableFuture<BuildResult> result1 =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule1)
              .getResult();
      rule1.waitForStart();
      assertThat(rule1.hasStarted(), equalTo(true));
      ListenableFuture<BuildResult> result2 =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule2)
              .getResult();
      Thread.sleep(250);
      assertThat(semaphore.getQueueLength(), equalTo(1));
      assertThat(rule2.hasStarted(), equalTo(false));
      rule1.finish();
      result1.get();
      rule2.finish();
      result2.get();
    }

    private class ControlledRule extends AbstractBuildRuleWithResolver
        implements OverrideScheduleRule {

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
          BuildContext context, BuildableContext buildableContext) {
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
      public SourcePath getSourcePathToOutput() {
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

    public BuildRuleEventTests(CachingBuildEngine.MetadataStorage metadataStorage)
        throws IOException {
      super(metadataStorage);
    }

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
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setExecutorService(SERVICE)
              .setRuleKeyFactories(
                  RuleKeyFactories.of(
                      NOOP_RULE_KEY_FACTORY,
                      NOOP_INPUT_BASED_RULE_KEY_FACTORY,
                      NOOP_DEP_FILE_RULE_KEY_FACTORY))
              .build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());

      // Verify that events have correct thread IDs
      assertRelatedBuildRuleEventsOnSameThread(
          FluentIterable.from(listener.getEvents()).filter(BuildRuleEvent.class));
      assertRelatedBuildRuleEventsDuration(
          FluentIterable.from(listener.getEvents()).filter(BuildRuleEvent.class));
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
      BuildInfoRecorder recorder = createBuildInfoRecorder(rule.getBuildTarget());
      recorder.addBuildMetadata(
          BuildInfo.MetadataKey.RULE_KEY, defaultRuleKeyFactory.build(rule).toString());
      recorder.addMetadata(BuildInfo.MetadataKey.RECORDED_PATHS, ImmutableList.of());
      recorder.writeMetadataToDisk(true);

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setExecutorService(SERVICE)
              .setRuleKeyFactories(
                  RuleKeyFactories.of(
                      NOOP_RULE_KEY_FACTORY,
                      NOOP_INPUT_BASED_RULE_KEY_FACTORY,
                      NOOP_DEP_FILE_RULE_KEY_FACTORY))
              .build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertEquals(BuildRuleSuccessType.MATCHING_RULE_KEY, result.getSuccess());

      // Verify that events have correct thread IDs
      assertRelatedBuildRuleEventsOnSameThread(
          FluentIterable.from(listener.getEvents()).filter(BuildRuleEvent.class));
      assertRelatedBuildRuleEventsDuration(
          FluentIterable.from(listener.getEvents()).filter(BuildRuleEvent.class));
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
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setExecutorService(SERVICE)
              .setRuleKeyFactories(
                  RuleKeyFactories.of(
                      NOOP_RULE_KEY_FACTORY,
                      NOOP_INPUT_BASED_RULE_KEY_FACTORY,
                      NOOP_DEP_FILE_RULE_KEY_FACTORY))
              .build();

      // Run the build.
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());

      // Verify that events have correct thread IDs
      assertRelatedBuildRuleEventsOnSameThread(
          FluentIterable.from(listener.getEvents()).filter(BuildRuleEvent.class));
      assertRelatedBuildRuleEventsDuration(
          FluentIterable.from(listener.getEvents()).filter(BuildRuleEvent.class));
    }

    @Test
    public void originForBuiltLocally() throws Exception {

      // Create a noop simple rule.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      Path output = filesystem.getPath("output/path");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target).setProjectFilesystem(filesystem).build();
      BuildRule rule = new WriteFile(params, "something else", output, /* executable */ false);

      // Run the build and extract the event.
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build();
      BuildId buildId = new BuildId("id");
      BuildResult result =
          cachingBuildEngine
              .build(buildContext.withBuildId(buildId), TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());
      BuildRuleEvent.Finished event =
          RichStream.from(listener.getEvents())
              .filter(BuildRuleEvent.Finished.class)
              .filter(e -> e.getBuildRule().equals(rule))
              .findAny()
              .orElseThrow(AssertionError::new);

      // Verify we found the correct build id.
      assertThat(event.getOrigin(), equalTo(Optional.of(buildId)));
    }

    @Test
    public void originForMatchingRuleKey() throws Exception {

      // Create a noop simple rule.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      Path output = filesystem.getPath("output/path");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target).setProjectFilesystem(filesystem).build();
      BuildRule rule = new WriteFile(params, "something else", output, /* executable */ false);

      // Run an initial build to seed the cache.
      CachingBuildEngine cachingBuildEngine1 = cachingBuildEngineFactory().build();
      BuildId buildId1 = new BuildId("id1");
      BuildResult result1 =
          cachingBuildEngine1
              .build(buildContext.withBuildId(buildId1), TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result1.getSuccess());

      // Run the build and extract the event.
      CachingBuildEngine cachingBuildEngine2 = cachingBuildEngineFactory().build();
      BuildId buildId2 = new BuildId("id2");
      BuildResult result2 =
          cachingBuildEngine2
              .build(buildContext.withBuildId(buildId2), TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertEquals(BuildRuleSuccessType.MATCHING_RULE_KEY, getSuccess(result2));
      BuildRuleEvent.Finished event =
          RichStream.from(listener.getEvents())
              .filter(BuildRuleEvent.Finished.class)
              .filter(e -> e.getBuildRule().equals(rule))
              .findAny()
              .orElseThrow(AssertionError::new);

      // Verify we found the correct build id.
      assertThat(event.getOrigin(), equalTo(Optional.of(buildId1)));
    }

    @Test
    public void originForCached() throws Exception {

      // Create a noop simple rule.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      Path output = filesystem.getPath("output/path");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target).setProjectFilesystem(filesystem).build();
      BuildRule rule = new WriteFile(params, "something else", output, /* executable */ false);

      // Run an initial build to seed the cache.
      CachingBuildEngine cachingBuildEngine1 = cachingBuildEngineFactory().build();
      BuildId buildId1 = new BuildId("id1");
      BuildResult result1 =
          cachingBuildEngine1
              .build(buildContext.withBuildId(buildId1), TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result1.getSuccess());

      filesystem.clear();
      buildInfoStore.deleteMetadata(target);

      // Run the build and extract the event.
      CachingBuildEngine cachingBuildEngine2 = cachingBuildEngineFactory().build();
      BuildId buildId2 = new BuildId("id2");
      BuildResult result2 =
          cachingBuildEngine2
              .build(buildContext.withBuildId(buildId2), TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertEquals(BuildRuleSuccessType.FETCHED_FROM_CACHE, getSuccess(result2));
      BuildRuleEvent.Finished event =
          RichStream.from(listener.getEvents())
              .filter(BuildRuleEvent.Finished.class)
              .filter(e -> e.getBuildRule().equals(rule))
              .findAny()
              .orElseThrow(AssertionError::new);

      // Verify we found the correct build id.
      assertThat(event.getOrigin(), equalTo(Optional.of(buildId1)));
    }

    @Test
    public void outputHashNotCalculatedWhenCacheNotWritable() throws Exception {

      // Create a noop simple rule.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      Path output = filesystem.getPath("output/path");
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(target).setProjectFilesystem(filesystem).build();
      BuildRule rule = new WriteFile(params, "something else", output, /* executable */ false);

      // Run the build and extract the event.
      CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build();
      BuildResult result =
          cachingBuildEngine
              .build(
                  buildContext.withArtifactCache(new NoopArtifactCache()),
                  TestExecutionContext.newInstance(),
                  rule)
              .getResult()
              .get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());
      BuildRuleEvent.Finished event =
          RichStream.from(listener.getEvents())
              .filter(BuildRuleEvent.Finished.class)
              .filter(e -> e.getBuildRule().equals(rule))
              .findAny()
              .orElseThrow(AssertionError::new);

      // Verify we found the correct build id.
      assertThat(event.getOutputHash(), equalTo(Optional.empty()));
    }

    /** Verify that the begin and end events in build rule event pairs occur on the same thread. */
    private void assertRelatedBuildRuleEventsOnSameThread(Iterable<BuildRuleEvent> events) {
      Map<Long, List<BuildRuleEvent>> grouped = new HashMap<>();
      for (BuildRuleEvent event : events) {
        if (!grouped.containsKey(event.getThreadId())) {
          grouped.put(event.getThreadId(), new ArrayList<>());
        }
        grouped.get(event.getThreadId()).add(event);
      }
      for (List<BuildRuleEvent> queue : grouped.values()) {
        queue.sort(Comparator.comparingLong(BuildRuleEvent::getNanoTime));
        ImmutableList<String> queueDescription =
            queue
                .stream()
                .map(event -> String.format("%s@%s", event, event.getNanoTime()))
                .collect(MoreCollectors.toImmutableList());
        Iterator<BuildRuleEvent> itr = queue.iterator();

        while (itr.hasNext()) {
          BuildRuleEvent event1 = itr.next();
          BuildRuleEvent event2 = itr.next();

          assertThat(
              String.format(
                  "Two consecutive events (%s,%s) should have the same BuildTarget. (%s)",
                  event1, event2, queueDescription),
              event1.getBuildRule().getBuildTarget(),
              equalTo(event2.getBuildRule().getBuildTarget()));
          assertThat(
              String.format(
                  "Two consecutive events (%s,%s) should be suspend/resume or resume/suspend. (%s)",
                  event1, event2, queueDescription),
              event1.isRuleRunningAfterThisEvent(),
              equalTo(!event2.isRuleRunningAfterThisEvent()));
        }
      }
    }

    private void assertRelatedBuildRuleEventsDuration(Iterable<BuildRuleEvent> events) {
      Map<BuildRule, List<BuildRuleEvent>> grouped = new HashMap<>();
      for (BuildRuleEvent event : events) {
        if (!grouped.containsKey(event.getBuildRule())) {
          grouped.put(event.getBuildRule(), new ArrayList<>());
        }
        grouped.get(event.getBuildRule()).add(event);
      }
      for (List<BuildRuleEvent> queue : grouped.values()) {
        queue.sort(Comparator.comparingLong(BuildRuleEvent::getNanoTime));
        long count = 0, wallStart = 0, nanoStart = 0, wall = 0, nano = 0, thread = 0;
        for (BuildRuleEvent event : queue) {
          if (event instanceof BuildRuleEvent.BeginningBuildRuleEvent) {
            if (count++ == 0) {
              wallStart = event.getTimestamp();
              nanoStart = event.getNanoTime();
            }
            assertEquals(
                wall + event.getTimestamp() - wallStart,
                event.getDuration().getWallMillisDuration());
            assertEquals(
                nano + event.getNanoTime() - nanoStart, event.getDuration().getNanoDuration());
            assertEquals(thread, event.getDuration().getThreadUserNanoDuration());
          } else if (event instanceof BuildRuleEvent.EndingBuildRuleEvent) {
            BuildRuleEvent.BeginningBuildRuleEvent beginning =
                ((BuildRuleEvent.EndingBuildRuleEvent) event).getBeginningEvent();
            thread += event.getThreadUserNanoTime() - beginning.getThreadUserNanoTime();
            assertEquals(
                wall + event.getTimestamp() - wallStart,
                event.getDuration().getWallMillisDuration());
            assertEquals(
                nano + event.getNanoTime() - nanoStart, event.getDuration().getNanoDuration());
            assertEquals(thread, event.getDuration().getThreadUserNanoDuration());
            if (--count == 0) {
              wall += event.getTimestamp() - wallStart;
              nano += event.getNanoTime() - nanoStart;
            }
          }
        }
        assertEquals("Different number of beginning and ending events: " + queue, 0, count);
      }
    }

    /** A {@link ListeningExecutorService} which runs every task on a completely new thread. */
    private static class NewThreadExecutorService extends AbstractListeningExecutorService {

      @Override
      public void shutdown() {}

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

      /** Spawn a new thread for every command. */
      @Override
      public void execute(@Nonnull Runnable command) {
        new Thread(command).start();
      }
    }
  }


  // TODO(mbolin): Test that when the success files match, nothing is built and nothing is
  // written back to the cache.

  // TODO(mbolin): Test that when the value in the success file does not agree with the current
  // value, the rule is rebuilt and the result is written back to the cache.

  // TODO(mbolin): Test that a failure when executing the build steps is propagated
  // appropriately.

  // TODO(mbolin): Test what happens when the cache's methods throw an exception.

  private static BuildRule createRule(
      ProjectFilesystem filesystem,
      BuildRuleResolver ruleResolver,
      SourcePathResolver resolver,
      ImmutableSet<BuildRule> deps,
      List<Step> buildSteps,
      ImmutableList<Step> postBuildSteps,
      @Nullable String pathToOutputFile,
      ImmutableList<Flavor> flavors) {
    Comparator<BuildRule> comparator = RetainOrderComparator.createComparator(deps);
    ImmutableSortedSet<BuildRule> sortedDeps = ImmutableSortedSet.copyOf(comparator, deps);

    BuildRuleParams buildRuleParams =
        new FakeBuildRuleParamsBuilder(BUILD_TARGET.withFlavors(flavors))
            .setProjectFilesystem(filesystem)
            .setDeclaredDeps(sortedDeps)
            .build();

    BuildableAbstractCachingBuildRule rule =
        new BuildableAbstractCachingBuildRule(
            buildRuleParams, resolver, pathToOutputFile, buildSteps, postBuildSteps);
    ruleResolver.addToIndex(rule);
    return rule;
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
        Throwables.throwIfUnchecked(Preconditions.checkNotNull(result.getFailure()));
        throw new RuntimeException(result.getFailure());
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
      return ObjectMappers.WRITER.writeValueAsString(entry);
    } catch (JsonProcessingException e) {
      throw new AssertionError(e);
    }
  }

  private static class BuildableAbstractCachingBuildRule extends AbstractBuildRuleWithResolver
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
      this.buildOutputInitializer = new BuildOutputInitializer<>(params.getBuildTarget(), this);
    }

    @Override
    @Nullable
    public SourcePath getSourcePathToOutput() {
      if (pathToOutputFile == null) {
        return null;
      }
      return new ExplicitBuildTargetSourcePath(getBuildTarget(), pathToOutputFile);
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      if (pathToOutputFile != null) {
        buildableContext.recordArtifact(pathToOutputFile);
      }
      return ImmutableList.copyOf(buildSteps);
    }

    @Override
    public ImmutableList<Step> getPostBuildSteps(BuildContext context) {
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
   *
   * <p>This makes it possible to react to a call to {@link ArtifactCache#store(ArtifactInfo,
   * BorrowablePath)} and ensure that there will be a zip file in place immediately after the
   * captured method has been invoked.
   */
  private static class FakeArtifactCacheThatWritesAZipFile implements ArtifactCache {

    private final ImmutableMap<Path, String> desiredEntries;
    private final ImmutableMap<String, String> metadata;

    public FakeArtifactCacheThatWritesAZipFile(
        ImmutableMap<Path, String> desiredEntries, ImmutableMap<String, String> metadata) {
      this.desiredEntries = desiredEntries;
      this.metadata = metadata;
    }

    @Override
    public CacheResult fetch(RuleKey ruleKey, LazyPath file) {
      try {
        writeEntriesToZip(file.get(), ImmutableMap.copyOf(desiredEntries));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      return CacheResult.hit("dir", ArtifactCacheMode.dir).withMetadata(metadata);
    }

    @Override
    public ListenableFuture<Void> store(ArtifactInfo info, BorrowablePath output) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CacheReadMode getCacheReadMode() {
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
    public Stream<BuildTarget> getRuntimeDeps() {
      return runtimeDeps.stream().map(BuildRule::getBuildTarget);
    }
  }

  private abstract static class InputRuleKeyBuildRule extends AbstractBuildRuleWithResolver
      implements SupportsInputBasedRuleKey {
    public InputRuleKeyBuildRule(BuildRuleParams buildRuleParams, SourcePathResolver resolver) {
      super(buildRuleParams, resolver);
    }
  }

  private abstract static class DepFileBuildRule extends AbstractBuildRule
      implements SupportsDependencyFileRuleKey {
    public DepFileBuildRule(BuildRuleParams buildRuleParams) {
      super(buildRuleParams);
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
        BuildRuleParams buildRuleParams, ImmutableList<Step> steps, @Nullable Path output) {
      super(buildRuleParams);
      this.steps = steps;
      this.output = output;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      return steps;
    }

    @Nullable
    @Override
    public SourcePath getSourcePathToOutput() {
      if (output == null) {
        return null;
      }
      return new ExplicitBuildTargetSourcePath(getBuildTarget(), output);
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
        Throwables.throwIfUnchecked(e);
        throw new RuntimeException(e);
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
        entry.setExternalAttributes(33188L << 16);
        zip.putNextEntry(entry);
        zip.write(mapEntry.getValue().getBytes());
        zip.closeEntry();
      }
    }
  }

  private static class EmptyBuildRule extends AbstractBuildRuleWithResolver {

    public EmptyBuildRule(BuildRuleParams buildRuleParams, SourcePathResolver resolver) {
      super(buildRuleParams, resolver);
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      return ImmutableList.of();
    }

    @Nullable
    @Override
    public SourcePath getSourcePathToOutput() {
      return null;
    }
  }
}
