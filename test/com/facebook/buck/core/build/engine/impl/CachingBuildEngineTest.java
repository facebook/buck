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

package com.facebook.buck.core.build.engine.impl;

import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.isA;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.ArtifactInfo;
import com.facebook.buck.artifact_cache.CacheDeleteResult;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.artifact_cache.InMemoryArtifactCache;
import com.facebook.buck.artifact_cache.NoopArtifactCache;
import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.artifact_cache.config.CacheReadMode;
import com.facebook.buck.cli.CommandThreadManager;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.build.distributed.synchronization.RemoteBuildRuleCompletionWaiter;
import com.facebook.buck.core.build.distributed.synchronization.impl.NoOpRemoteBuildRuleCompletionWaiter;
import com.facebook.buck.core.build.engine.BuildEngineBuildContext;
import com.facebook.buck.core.build.engine.BuildEngineResult;
import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.build.engine.BuildStrategyContext;
import com.facebook.buck.core.build.engine.buildinfo.BuildInfo;
import com.facebook.buck.core.build.engine.buildinfo.BuildInfoRecorder;
import com.facebook.buck.core.build.engine.buildinfo.BuildInfoStore;
import com.facebook.buck.core.build.engine.buildinfo.OnDiskBuildInfo;
import com.facebook.buck.core.build.engine.cache.manager.BuildInfoStoreManager;
import com.facebook.buck.core.build.engine.cache.manager.ManifestRuleKeyManagerTestUtil;
import com.facebook.buck.core.build.engine.delegate.CachingBuildEngineDelegate;
import com.facebook.buck.core.build.engine.delegate.LocalCachingBuildEngineDelegate;
import com.facebook.buck.core.build.engine.manifest.Manifest;
import com.facebook.buck.core.build.engine.manifest.ManifestUtil;
import com.facebook.buck.core.build.engine.type.BuildType;
import com.facebook.buck.core.build.engine.type.DepFiles;
import com.facebook.buck.core.build.engine.type.MetadataStorage;
import com.facebook.buck.core.build.engine.type.UploadToCacheResultType;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.build.stats.BuildRuleDurationTracker;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.BuildRuleKeys;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.attr.BuildOutputInitializer;
import com.facebook.buck.core.rules.attr.HasPostBuildSteps;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.attr.InitializableFromDisk;
import com.facebook.buck.core.rules.attr.SupportsDependencyFileRuleKey;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.core.rules.build.strategy.BuildRuleStrategy;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.rules.schedule.OverrideScheduleRule;
import com.facebook.buck.core.rules.schedule.RuleScheduleInfo;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.CommandEvent;
import com.facebook.buck.event.FakeBuckEventListener;
import com.facebook.buck.event.TestEventConfigurator;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.rules.keys.DefaultDependencyFileRuleKeyFactory;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.DependencyFileEntry;
import com.facebook.buck.rules.keys.DependencyFileRuleKeyFactory;
import com.facebook.buck.rules.keys.FakeRuleKeyFactory;
import com.facebook.buck.rules.keys.InputBasedRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyAndInputs;
import com.facebook.buck.rules.keys.RuleKeyFactories;
import com.facebook.buck.rules.keys.RuleKeyFieldLoader;
import com.facebook.buck.rules.keys.TestInputBasedRuleKeyFactory;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.testutil.DummyFileHashCache;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.TarInspector;
import com.facebook.buck.util.ErrorLogger;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.facebook.buck.util.concurrent.ListeningMultiSemaphore;
import com.facebook.buck.util.concurrent.MoreFutures;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.concurrent.ResourceAllocationFairness;
import com.facebook.buck.util.concurrent.ResourceAmounts;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.facebook.buck.util.exceptions.ExceptionWithContext;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.timing.DefaultClock;
import com.facebook.buck.util.timing.IncrementingFakeClock;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.util.zip.ZipConstants;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.AbstractListeningExecutorService;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
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
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.SortedSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream;
import org.easymock.EasyMockSupport;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsInstanceOf;
import org.junit.Before;
import org.junit.Ignore;
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
  private static final boolean DEBUG = false;
  private static final BuildTarget BUILD_TARGET =
      BuildTargetFactory.newInstance("//src/com/facebook/orca:orca");
  private static final SourcePathRuleFinder DEFAULT_RULE_FINDER =
      new SourcePathRuleFinder(new TestActionGraphBuilder());
  private static final SourcePathResolver DEFAULT_SOURCE_PATH_RESOLVER =
      DefaultSourcePathResolver.from(DEFAULT_RULE_FINDER);
  public static final long NO_INPUT_FILE_SIZE_LIMIT = Long.MAX_VALUE;
  public static final RuleKeyFieldLoader FIELD_LOADER =
      new RuleKeyFieldLoader(TestRuleKeyConfigurationFactory.create());
  private static final DefaultRuleKeyFactory NOOP_RULE_KEY_FACTORY =
      new DefaultRuleKeyFactory(
          FIELD_LOADER,
          new DummyFileHashCache(),
          DEFAULT_SOURCE_PATH_RESOLVER,
          DEFAULT_RULE_FINDER);
  private static final InputBasedRuleKeyFactory NOOP_INPUT_BASED_RULE_KEY_FACTORY =
      new TestInputBasedRuleKeyFactory(
          FIELD_LOADER,
          new DummyFileHashCache(),
          DEFAULT_SOURCE_PATH_RESOLVER,
          DEFAULT_RULE_FINDER,
          NO_INPUT_FILE_SIZE_LIMIT);
  private static final DependencyFileRuleKeyFactory NOOP_DEP_FILE_RULE_KEY_FACTORY =
      new DefaultDependencyFileRuleKeyFactory(
          FIELD_LOADER,
          new DummyFileHashCache(),
          DEFAULT_SOURCE_PATH_RESOLVER,
          DEFAULT_RULE_FINDER);

  @RunWith(Parameterized.class)
  public abstract static class CommonFixture extends EasyMockSupport {
    @Rule public TemporaryPaths tmp = new TemporaryPaths();

    protected final InMemoryArtifactCache cache = new InMemoryArtifactCache();
    protected final FakeBuckEventListener listener = new FakeBuckEventListener();
    protected ProjectFilesystem filesystem;
    protected BuildInfoStoreManager buildInfoStoreManager;
    protected BuildInfoStore buildInfoStore;
    protected RemoteBuildRuleCompletionWaiter defaultRemoteBuildRuleCompletionWaiter;
    protected FileHashCache fileHashCache;
    protected BuildEngineBuildContext buildContext;
    protected ActionGraphBuilder graphBuilder;
    protected SourcePathRuleFinder ruleFinder;
    protected SourcePathResolver pathResolver;
    protected DefaultRuleKeyFactory defaultRuleKeyFactory;
    protected InputBasedRuleKeyFactory inputBasedRuleKeyFactory;
    protected BuildRuleDurationTracker durationTracker;
    protected MetadataStorage metadataStorage;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
      return Arrays.stream(MetadataStorage.values())
          .map(v -> new Object[] {v})
          .collect(ImmutableList.toImmutableList());
    }

    public CommonFixture(MetadataStorage metadataStorage) {
      this.metadataStorage = metadataStorage;
    }

    @Before
    public void setUp() throws Exception {
      filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
      buildInfoStoreManager = new BuildInfoStoreManager();
      Files.createDirectories(filesystem.resolve(filesystem.getBuckPaths().getScratchDir()));
      buildInfoStore = buildInfoStoreManager.get(filesystem, metadataStorage);
      defaultRemoteBuildRuleCompletionWaiter = new NoOpRemoteBuildRuleCompletionWaiter();
      fileHashCache =
          StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT);
      buildContext =
          BuildEngineBuildContext.builder()
              .setBuildContext(FakeBuildContext.NOOP_CONTEXT)
              .setArtifactCache(cache)
              .setBuildId(new BuildId())
              .setClock(new IncrementingFakeClock())
              .build();
      buildContext.getEventBus().register(listener);
      graphBuilder = new TestActionGraphBuilder();
      ruleFinder = new SourcePathRuleFinder(graphBuilder);
      pathResolver = DefaultSourcePathResolver.from(ruleFinder);
      defaultRuleKeyFactory =
          new DefaultRuleKeyFactory(FIELD_LOADER, fileHashCache, pathResolver, ruleFinder);
      inputBasedRuleKeyFactory =
          new TestInputBasedRuleKeyFactory(
              FIELD_LOADER, fileHashCache, pathResolver, ruleFinder, NO_INPUT_FILE_SIZE_LIMIT);
      durationTracker = new BuildRuleDurationTracker();
    }

    protected CachingBuildEngineFactory cachingBuildEngineFactory() {
      return cachingBuildEngineFactory(defaultRemoteBuildRuleCompletionWaiter);
    }

    protected CachingBuildEngineFactory cachingBuildEngineFactory(
        RemoteBuildRuleCompletionWaiter remoteBuildRuleCompletionWaiter) {
      return new CachingBuildEngineFactory(
              graphBuilder, buildInfoStoreManager, remoteBuildRuleCompletionWaiter)
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
    public OtherTests(MetadataStorage metadataStorage) {
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
        throws InterruptedException, ExecutionException {
      // Create a dep for the build rule.
      BuildTarget depTarget = BuildTargetFactory.newInstance("//src/com/facebook/orca:lib");
      FakeBuildRule dep = new FakeBuildRule(depTarget);

      // The EventBus should be updated with events indicating how the rule was built.
      BuckEventBus buckEventBus = BuckEventBusForTests.newInstance();
      FakeBuckEventListener listener = new FakeBuckEventListener();
      buckEventBus.register(listener);

      // Replay the mocks to instantiate the AbstractCachingBuildRule.
      replayAll();
      String pathToOutputFile = "buck-out/gen/src/com/facebook/orca/some_file";
      List<Step> buildSteps = new ArrayList<>();
      BuildRule ruleToTest =
          createRule(
              filesystem,
              graphBuilder,
              ImmutableSortedSet.of(dep),
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
              Path outputPath = pathResolver.getRelativePath(ruleToTest.getSourcePathToOutput());
              filesystem.mkdirs(outputPath.getParent());
              filesystem.touch(outputPath);
              return StepExecutionResults.SUCCESS;
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
          CommandEvent.finished(
              CommandEvent.started("build", ImmutableList.of(), OptionalLong.empty(), 23L),
              ExitCode.SUCCESS));
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
                  UploadToCacheResultType.UNCACHEABLE,
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty())));
    }

    @Test
    public void testAsyncJobsAreNotLeftInExecutor()
        throws ExecutionException, InterruptedException {
      BuildRuleParams buildRuleParams = TestBuildRuleParams.create();
      FakeBuildRule buildRule = new FakeBuildRule(BUILD_TARGET, filesystem, buildRuleParams);

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

      try (CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory().setExecutorService(service).build()) {
        ListenableFuture<BuildResult> buildResult =
            cachingBuildEngine
                .build(buildContext, TestExecutionContext.newInstance(), buildRule)
                .getResult();

        BuildResult result = buildResult.get();
        assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());
      }
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
                  UploadToCacheResultType.UNCACHEABLE,
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
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
              graphBuilder,
              /* deps */ ImmutableSortedSet.of(),
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
      Path metadataDirectory =
          BuildInfo.getPathToArtifactMetadataDirectory(buildRule.getBuildTarget(), filesystem);
      ImmutableMap<Path, String> desiredZipEntries =
          ImmutableMap.of(
              metadataDirectory.resolve(BuildInfo.MetadataKey.RECORDED_PATHS),
              ObjectMappers.WRITER.writeValueAsString(ImmutableList.of()),
              metadataDirectory.resolve(BuildInfo.MetadataKey.RECORDED_PATH_HASHES),
              ObjectMappers.WRITER.writeValueAsString(ImmutableMap.of()),
              Paths.get("buck-out/gen/src/com/facebook/orca/orca.jar"),
              "Imagine this is the contents of a valid JAR file.",
              metadataDirectory.resolve(BuildInfo.MetadataKey.OUTPUT_SIZE),
              "123",
              metadataDirectory.resolve(BuildInfo.MetadataKey.OUTPUT_HASH),
              HashCode.fromInt(123).toString());
      expect(
              artifactCache.fetchAsync(
                  eq(buildRule.getBuildTarget()),
                  eq(defaultRuleKeyFactory.build(buildRule)),
                  isA(LazyPath.class)))
          .andDelegateTo(new FakeArtifactCacheThatWritesAZipFile(desiredZipEntries, metadata));

      BuildEngineBuildContext buildContext =
          BuildEngineBuildContext.builder()
              .setBuildContext(FakeBuildContext.withSourcePathResolver(pathResolver))
              .setClock(new DefaultClock())
              .setBuildId(new BuildId())
              .setArtifactCache(artifactCache)
              .build();

      // Build the rule!
      replayAll();

      try (CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build()) {
        ListenableFuture<BuildResult> buildResult =
            cachingBuildEngine
                .build(buildContext, TestExecutionContext.newInstance(), buildRule)
                .getResult();
        buildContext
            .getBuildContext()
            .getEventBus()
            .post(
                CommandEvent.finished(
                    CommandEvent.started("build", ImmutableList.of(), OptionalLong.empty(), 23L),
                    ExitCode.SUCCESS));

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
    }

    @Test
    public void testArtifactFetchedFromCacheStillRunsPostBuildSteps()
        throws InterruptedException, ExecutionException, IOException {
      // Add a post build step so we can verify that it's steps are executed.
      Step buildStep = createMock(Step.class);
      expect(buildStep.getDescription(anyObject(ExecutionContext.class)))
          .andReturn("Some Description")
          .anyTimes();
      expect(buildStep.getShortName()).andReturn("Some Short Name").anyTimes();
      expect(buildStep.execute(anyObject(ExecutionContext.class)))
          .andReturn(StepExecutionResults.SUCCESS);

      BuildRule buildRule =
          createRule(
              filesystem,
              graphBuilder,
              /* deps */ ImmutableSortedSet.of(),
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
      Path metadataDirectory =
          BuildInfo.getPathToArtifactMetadataDirectory(buildRule.getBuildTarget(), filesystem);
      ImmutableMap<Path, String> desiredZipEntries =
          ImmutableMap.of(
              metadataDirectory.resolve(BuildInfo.MetadataKey.RECORDED_PATHS),
              ObjectMappers.WRITER.writeValueAsString(ImmutableList.of()),
              metadataDirectory.resolve(BuildInfo.MetadataKey.RECORDED_PATH_HASHES),
              ObjectMappers.WRITER.writeValueAsString(ImmutableMap.of()),
              metadataDirectory.resolve(BuildInfo.MetadataKey.OUTPUT_SIZE),
              "123",
              metadataDirectory.resolve(BuildInfo.MetadataKey.OUTPUT_HASH),
              HashCode.fromInt(123).toString(),
              Paths.get("buck-out/gen/src/com/facebook/orca/orca.jar"),
              "Imagine this is the contents of a valid JAR file.");
      expect(
              artifactCache.fetchAsync(
                  eq(buildRule.getBuildTarget()),
                  eq(defaultRuleKeyFactory.build(buildRule)),
                  isA(LazyPath.class)))
          .andDelegateTo(new FakeArtifactCacheThatWritesAZipFile(desiredZipEntries, metadata));

      BuildEngineBuildContext buildContext =
          BuildEngineBuildContext.builder()
              .setBuildContext(FakeBuildContext.withSourcePathResolver(pathResolver))
              .setClock(new DefaultClock())
              .setBuildId(new BuildId())
              .setArtifactCache(artifactCache)
              .build();

      // Build the rule!
      replayAll();
      try (CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build()) {
        ListenableFuture<BuildResult> buildResult =
            cachingBuildEngine
                .build(buildContext, TestExecutionContext.newInstance(), buildRule)
                .getResult();
        buildContext
            .getBuildContext()
            .getEventBus()
            .post(
                CommandEvent.finished(
                    CommandEvent.started("build", ImmutableList.of(), OptionalLong.empty(), 23L),
                    ExitCode.SUCCESS));

        BuildResult result = buildResult.get();
        verifyAll();
        assertEquals(BuildRuleSuccessType.FETCHED_FROM_CACHE, result.getSuccess());
        assertTrue(((BuildableAbstractCachingBuildRule) buildRule).isInitializedFromDisk());
        assertTrue(
            "The entries in the zip should be extracted as a result of building the rule.",
            filesystem.exists(Paths.get("buck-out/gen/src/com/facebook/orca/orca.jar")));
      }
    }

    @Test
    public void testMatchingTopLevelRuleKeyAvoidsProcessingDepInShallowMode() throws Exception {
      // Create a dep for the build rule.
      BuildTarget depTarget = BuildTargetFactory.newInstance("//src/com/facebook/orca:lib");
      FakeBuildRule dep = new FakeBuildRule(depTarget);
      FakeBuildRule ruleToTest = new FakeBuildRule(BUILD_TARGET, filesystem, dep);
      RuleKey ruleToTestKey = defaultRuleKeyFactory.build(ruleToTest);

      BuildInfoRecorder recorder = createBuildInfoRecorder(BUILD_TARGET);
      recorder.addBuildMetadata(BuildInfo.MetadataKey.RULE_KEY, ruleToTestKey.toString());
      recorder.addMetadata(BuildInfo.MetadataKey.RECORDED_PATHS, ImmutableList.of());
      recorder.writeMetadataToDisk(true);

      // The BuildContext that will be used by the rule's build() method.
      BuildEngineBuildContext context =
          this.buildContext.withArtifactCache(new NoopArtifactCache());

      // Create the build engine.
      try (CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build()) {
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
                    UploadToCacheResultType.UNCACHEABLE,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty())));
      }
    }

    @Test
    public void testMatchingTopLevelRuleKeyStillProcessesDepInDeepMode() throws Exception {
      // Create a dep for the build rule.
      BuildTarget depTarget = BuildTargetFactory.newInstance("//src/com/facebook/orca:lib");
      BuildRuleParams ruleParams = TestBuildRuleParams.create();
      FakeBuildRule dep = new FakeBuildRule(depTarget, filesystem, ruleParams);
      RuleKey depKey = defaultRuleKeyFactory.build(dep);
      BuildInfoRecorder depRecorder = createBuildInfoRecorder(depTarget);
      depRecorder.addBuildMetadata(BuildInfo.MetadataKey.RULE_KEY, depKey.toString());
      depRecorder.addMetadata(BuildInfo.MetadataKey.RECORDED_PATHS, ImmutableList.of());
      depRecorder.writeMetadataToDisk(true);

      FakeBuildRule ruleToTest = new FakeBuildRule(BUILD_TARGET, filesystem, dep);
      RuleKey ruleToTestKey = defaultRuleKeyFactory.build(ruleToTest);
      BuildInfoRecorder recorder = createBuildInfoRecorder(BUILD_TARGET);
      recorder.addBuildMetadata(BuildInfo.MetadataKey.RULE_KEY, ruleToTestKey.toString());
      recorder.addMetadata(BuildInfo.MetadataKey.RECORDED_PATHS, ImmutableList.of());
      recorder.writeMetadataToDisk(true);

      // Create the build engine.
      try (CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory().setBuildMode(BuildType.DEEP).build()) {

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
                    UploadToCacheResultType.UNCACHEABLE,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
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
                    UploadToCacheResultType.UNCACHEABLE,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty())));
      }
    }

    @Test
    public void testMatchingTopLevelRuleKeyStillProcessesRuntimeDeps() throws Exception {
      // Setup a runtime dependency that is found transitively from the top-level rule.
      BuildTarget buildTarget = BuildTargetFactory.newInstance("//:transitive_dep");
      BuildRuleParams ruleParams = TestBuildRuleParams.create();
      FakeBuildRule transitiveRuntimeDep = new FakeBuildRule(buildTarget, filesystem, ruleParams);
      graphBuilder.addToIndex(transitiveRuntimeDep);
      RuleKey transitiveRuntimeDepKey = defaultRuleKeyFactory.build(transitiveRuntimeDep);

      BuildInfoRecorder recorder = createBuildInfoRecorder(transitiveRuntimeDep.getBuildTarget());
      recorder.addBuildMetadata(BuildInfo.MetadataKey.RULE_KEY, transitiveRuntimeDepKey.toString());
      recorder.addMetadata(BuildInfo.MetadataKey.RECORDED_PATHS, ImmutableList.of());
      recorder.writeMetadataToDisk(true);

      // Setup a runtime dependency that is referenced directly by the top-level rule.
      FakeBuildRule runtimeDep =
          new FakeHasRuntimeDeps(
              BuildTargetFactory.newInstance("//:runtime_dep"), filesystem, transitiveRuntimeDep);
      graphBuilder.addToIndex(runtimeDep);
      RuleKey runtimeDepKey = defaultRuleKeyFactory.build(runtimeDep);
      BuildInfoRecorder runtimeDepRec = createBuildInfoRecorder(runtimeDep.getBuildTarget());
      runtimeDepRec.addBuildMetadata(BuildInfo.MetadataKey.RULE_KEY, runtimeDepKey.toString());
      runtimeDepRec.addMetadata(BuildInfo.MetadataKey.RECORDED_PATHS, ImmutableList.of());
      runtimeDepRec.writeMetadataToDisk(true);

      // Create a dep for the build rule.
      FakeBuildRule ruleToTest = new FakeHasRuntimeDeps(BUILD_TARGET, filesystem, runtimeDep);
      RuleKey ruleToTestKey = defaultRuleKeyFactory.build(ruleToTest);
      BuildInfoRecorder testRec = createBuildInfoRecorder(BUILD_TARGET);
      testRec.addBuildMetadata(BuildInfo.MetadataKey.RULE_KEY, ruleToTestKey.toString());
      testRec.addMetadata(BuildInfo.MetadataKey.RECORDED_PATHS, ImmutableList.of());
      testRec.writeMetadataToDisk(true);

      // Create the build engine.
      try (CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build()) {
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
                    UploadToCacheResultType.UNCACHEABLE,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
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
                    UploadToCacheResultType.UNCACHEABLE,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
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
                    UploadToCacheResultType.UNCACHEABLE,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty())));
      }
    }

    @Test
    public void multipleTopLevelRulesDontBlockEachOther() throws Exception {
      Exchanger<Boolean> exchanger = new Exchanger<>();
      Step exchangerStep =
          new AbstractExecutionStep("interleaved_step") {
            @Override
            public StepExecutionResult execute(ExecutionContext context)
                throws InterruptedException {
              try {
                // Forces both rules to wait for the other at this point.
                exchanger.exchange(true, 6, TimeUnit.SECONDS);
              } catch (TimeoutException e) {
                throw new RuntimeException(e);
              }
              return StepExecutionResults.SUCCESS;
            }
          };
      BuildRule interleavedRuleOne =
          createRule(
              filesystem,
              graphBuilder,
              /* deps */ ImmutableSortedSet.of(),
              /* buildSteps */ ImmutableList.of(exchangerStep),
              /* postBuildSteps */ ImmutableList.of(),
              /* pathToOutputFile */ null,
              ImmutableList.of(InternalFlavor.of("interleaved-1")));
      graphBuilder.addToIndex(interleavedRuleOne);
      BuildRule interleavedRuleTwo =
          createRule(
              filesystem,
              graphBuilder,
              /* deps */ ImmutableSortedSet.of(),
              /* buildSteps */ ImmutableList.of(exchangerStep),
              /* postBuildSteps */ ImmutableList.of(),
              /* pathToOutputFile */ null,
              ImmutableList.of(InternalFlavor.of("interleaved-2")));
      graphBuilder.addToIndex(interleavedRuleTwo);

      // The engine needs a couple of threads to ensure that it can schedule multiple steps at the
      // same time.
      ListeningExecutorService executorService =
          listeningDecorator(Executors.newFixedThreadPool(4));
      try (CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory().setExecutorService(executorService).build()) {
        BuildEngineResult engineResultOne =
            cachingBuildEngine.build(
                buildContext, TestExecutionContext.newInstance(), interleavedRuleOne);
        BuildEngineResult engineResultTwo =
            cachingBuildEngine.build(
                buildContext, TestExecutionContext.newInstance(), interleavedRuleTwo);
        assertThat(engineResultOne.getResult().get().getStatus(), equalTo(BuildRuleStatus.SUCCESS));
        assertThat(engineResultTwo.getResult().get().getStatus(), equalTo(BuildRuleStatus.SUCCESS));
      }
      executorService.shutdown();
    }

    @Test
    public void failedRuntimeDepsArePropagated() throws Exception {
      String description = "failing step";
      Step failingStep =
          new AbstractExecutionStep(description) {
            @Override
            public StepExecutionResult execute(ExecutionContext context) {
              return StepExecutionResults.ERROR;
            }
          };
      BuildRule ruleToTest =
          createRule(
              filesystem,
              graphBuilder,
              /* deps */ ImmutableSortedSet.of(),
              /* buildSteps */ ImmutableList.of(failingStep),
              /* postBuildSteps */ ImmutableList.of(),
              /* pathToOutputFile */ null,
              ImmutableList.of());
      graphBuilder.addToIndex(ruleToTest);

      FakeBuildRule withRuntimeDep =
          new FakeHasRuntimeDeps(
              BuildTargetFactory.newInstance("//:with_runtime_dep"), filesystem, ruleToTest);

      try (CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build()) {
        BuildResult result =
            cachingBuildEngine
                .build(buildContext, TestExecutionContext.newInstance(), withRuntimeDep)
                .getResult()
                .get();

        assertThat(result.getStatus(), equalTo(BuildRuleStatus.CANCELED));
        assertThat(result.getFailure(), instanceOf(BuildRuleFailedException.class));
        Throwable cause = result.getFailure().getCause();
        assertThat(cause, instanceOf(StepFailedException.class));
        assertThat(((StepFailedException) cause).getStep().getShortName(), equalTo(description));
      }
    }

    @Test
    public void pendingWorkIsCancelledOnFailures() throws Exception {
      String description = "failing step";
      AtomicInteger failedSteps = new AtomicInteger(0);
      Step failingStep =
          new AbstractExecutionStep(description) {
            @Override
            public StepExecutionResult execute(ExecutionContext context) {
              System.out.println("Failing");
              failedSteps.incrementAndGet();
              return StepExecutionResults.ERROR;
            }
          };
      ImmutableSortedSet.Builder<BuildRule> depsBuilder = ImmutableSortedSet.naturalOrder();
      for (int i = 0; i < 20; i++) {
        BuildRule failingDep =
            createRule(
                filesystem,
                graphBuilder,
                /* deps */ ImmutableSortedSet.of(),
                /* buildSteps */ ImmutableList.of(failingStep),
                /* postBuildSteps */ ImmutableList.of(),
                /* pathToOutputFile */ null,
                ImmutableList.of(InternalFlavor.of("failing-" + i)));
        graphBuilder.addToIndex(failingDep);
        depsBuilder.add(failingDep);
      }

      FakeBuildRule withFailingDeps =
          new FakeBuildRule(
              BuildTargetFactory.newInstance("//:with_failing_deps"), depsBuilder.build());

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
            cachingBuildEngineFactory()
                .setExecutorService(threadManager.getWeightedListeningExecutorService())
                .build();
        BuildResult result =
            cachingBuildEngine
                .build(buildContext, TestExecutionContext.newInstance(), withFailingDeps)
                .getResult()
                .get();

        assertThat(result.getStatus(), equalTo(BuildRuleStatus.CANCELED));
        assertThat(result.getFailure(), instanceOf(BuildRuleFailedException.class));
        Throwable cause = result.getFailure().getCause();
        assertThat(cause, instanceOf(StepFailedException.class));
        assertThat(failedSteps.get(), equalTo(1));
        assertThat(((StepFailedException) cause).getStep().getShortName(), equalTo(description));
      }
    }

    @Test
    public void failedRuntimeDepsAreNotPropagatedWithKeepGoing() throws Exception {
      buildContext = this.buildContext.withKeepGoing(true);
      String description = "failing step";
      Step failingStep =
          new AbstractExecutionStep(description) {
            @Override
            public StepExecutionResult execute(ExecutionContext context) {
              return StepExecutionResults.ERROR;
            }
          };
      BuildRule ruleToTest =
          createRule(
              filesystem,
              graphBuilder,
              /* deps */ ImmutableSortedSet.of(),
              /* buildSteps */ ImmutableList.of(failingStep),
              /* postBuildSteps */ ImmutableList.of(),
              /* pathToOutputFile */ null,
              ImmutableList.of());
      graphBuilder.addToIndex(ruleToTest);

      FakeBuildRule withRuntimeDep =
          new FakeHasRuntimeDeps(
              BuildTargetFactory.newInstance("//:with_runtime_dep"), filesystem, ruleToTest);

      try (CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build()) {
        BuildResult result =
            cachingBuildEngine
                .build(buildContext, TestExecutionContext.newInstance(), withRuntimeDep)
                .getResult()
                .get();

        assertThat(result.getStatus(), equalTo(BuildRuleStatus.SUCCESS));
      }
    }

    @Test
    public void matchingRuleKeyDoesNotRunPostBuildSteps() throws Exception {
      // Add a post build step so we can verify that it's steps are executed.
      Step failingStep =
          new AbstractExecutionStep("test") {
            @Override
            public StepExecutionResult execute(ExecutionContext context) {
              return StepExecutionResults.ERROR;
            }
          };
      BuildRule ruleToTest =
          createRule(
              filesystem,
              graphBuilder,
              /* deps */ ImmutableSortedSet.of(),
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
      try (CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build()) {
        // Run the build.
        BuildResult result =
            cachingBuildEngine
                .build(buildContext, TestExecutionContext.newInstance(), ruleToTest)
                .getResult()
                .get();
        assertEquals(BuildRuleSuccessType.MATCHING_RULE_KEY, result.getSuccess());
      }
    }

    @Test
    public void testBuildRuleLocallyWithCacheError() throws Exception {
      // Create an artifact cache that always errors out.
      ArtifactCache cache =
          new NoopArtifactCache() {
            @Override
            public ListenableFuture<CacheResult> fetchAsync(
                BuildTarget target, RuleKey ruleKey, LazyPath output) {
              return Futures.immediateFuture(
                  CacheResult.error("cache", ArtifactCacheMode.dir, "error"));
            }
          };

      // Use the artifact cache when running a simple rule that will build locally.
      BuildEngineBuildContext buildContext = this.buildContext.withArtifactCache(cache);

      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRule rule = new EmptyBuildRule(target, filesystem);
      try (CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build()) {
        BuildResult result =
            cachingBuildEngine
                .build(buildContext, TestExecutionContext.newInstance(), rule)
                .getResult()
                .get();
        assertThat(result.getSuccess(), equalTo(BuildRuleSuccessType.BUILT_LOCALLY));
        assertThat(
            result.getCacheResult().map(CacheResult::getType),
            equalTo(Optional.of(CacheResultType.ERROR)));
      }
    }

    @Test
    public void testCancelledRulesHaveRuleContextFromFailingRule() throws Exception {
      class SimpleBuildRule extends AbstractBuildRule {
        final ImmutableSortedSet<BuildRule> deps;
        final Supplier<ImmutableList<Step>> stepsSupplier;

        SimpleBuildRule(
            String buildTarget,
            ImmutableSortedSet<BuildRule> deps,
            Supplier<ImmutableList<Step>> stepsSupplier) {
          super(BuildTargetFactory.newInstance(buildTarget), filesystem);
          this.deps = deps;
          this.stepsSupplier = stepsSupplier;
        }

        @Override
        public SortedSet<BuildRule> getBuildDeps() {
          return deps;
        }

        @Override
        public ImmutableList<? extends Step> getBuildSteps(
            BuildContext context, BuildableContext buildableContext) {
          return stepsSupplier.get();
        }

        @Nullable
        @Override
        public SourcePath getSourcePathToOutput() {
          return null;
        }
      }

      BuildRule rule1 =
          new SimpleBuildRule(
              "//:rule1",
              ImmutableSortedSet.of(),
              () -> {
                throw new RuntimeException();
              });
      BuildRule rule2 =
          new SimpleBuildRule(
              "//:rule2",
              ImmutableSortedSet.of(),
              () -> {
                throw new RuntimeException();
              });
      BuildRule rule3 =
          new SimpleBuildRule(
              "//:rule3",
              ImmutableSortedSet.of(),
              () -> {
                throw new RuntimeException();
              });

      BuildRule dependent =
          new SimpleBuildRule(
              "//:dep", ImmutableSortedSet.of(rule1, rule2, rule3), ImmutableList::of);

      ListeningExecutorService executor = MoreExecutors.newDirectExecutorService();
      CachingBuildEngine engine = cachingBuildEngineFactory().setExecutorService(executor).build();
      Throwable depFailure =
          engine
              .build(buildContext, TestExecutionContext.newInstance(), dependent)
              .getResult()
              .get()
              .getFailure();

      BuildResult result1 =
          engine.build(buildContext, TestExecutionContext.newInstance(), rule1).getResult().get();
      BuildResult result2 =
          engine.build(buildContext, TestExecutionContext.newInstance(), rule2).getResult().get();
      BuildResult result3 =
          engine.build(buildContext, TestExecutionContext.newInstance(), rule3).getResult().get();

      BuildResult failingResult = null;
      if (result1.getStatus().equals(BuildRuleStatus.FAIL)) {
        failingResult = result1;
        assertEquals(BuildRuleStatus.CANCELED, result2.getStatus());
        assertEquals(BuildRuleStatus.CANCELED, result3.getStatus());
      }

      if (result2.getStatus().equals(BuildRuleStatus.FAIL)) {
        failingResult = result2;
        assertEquals(BuildRuleStatus.CANCELED, result1.getStatus());
        assertEquals(BuildRuleStatus.CANCELED, result3.getStatus());
      }

      if (result3.getStatus().equals(BuildRuleStatus.FAIL)) {
        failingResult = result3;
        assertEquals(BuildRuleStatus.CANCELED, result1.getStatus());
        assertEquals(BuildRuleStatus.CANCELED, result2.getStatus());
      }

      // One of the three rules should've failed.
      assertNotNull(failingResult);

      Throwable failure1 = result1.getFailure();
      Throwable failure2 = result2.getFailure();
      Throwable failure3 = result3.getFailure();

      // These should all be the same underlying failure.
      assertSame(failure1, failure2);
      assertSame(failure2, failure3);
      assertSame(failure1, depFailure);

      assertThat(depFailure, instanceOf(BuildRuleFailedException.class));
      assertThat(
          ((ExceptionWithContext) depFailure).getContext().get(),
          containsString(failingResult.getRule().toString()));
    }

    @Test
    public void testExceptionMessagesAreInformative() throws Exception {
      AtomicReference<RuntimeException> throwable = new AtomicReference<>();
      BuildTarget buildTarget = BuildTargetFactory.newInstance("//:rule");
      BuildRule rule =
          new AbstractBuildRule(buildTarget, filesystem) {
            @Override
            public SortedSet<BuildRule> getBuildDeps() {
              return ImmutableSortedSet.of();
            }

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
      assertThat(thrown, instanceOf(BuildRuleFailedException.class));
      assertThat(thrown.getCause(), new IsInstanceOf(IllegalArgumentException.class));
      assertThat(((ExceptionWithContext) thrown).getContext().get(), containsString("//:rule"));

      // HumanReadableExceptions shouldn't be changed.
      throwable.set(new HumanReadableException("message"));
      thrown =
          cachingBuildEngineFactory()
              .build()
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get()
              .getFailure();
      assertThat(thrown, instanceOf(BuildRuleFailedException.class));
      assertEquals(throwable.get(), thrown.getCause());
      assertThat(((ExceptionWithContext) thrown).getContext().get(), containsString("//:rule"));
    }

    @Test
    public void testDelegateCalledBeforeRuleCreation() throws Exception {
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRule rule = new EmptyBuildRule(target, filesystem);
      AtomicReference<BuildRule> lastRuleToBeBuilt = new AtomicReference<>();
      CachingBuildEngineDelegate testDelegate =
          new LocalCachingBuildEngineDelegate(fileHashCache) {
            @Override
            public void onRuleAboutToBeBuilt(BuildRule buildRule) {
              super.onRuleAboutToBeBuilt(buildRule);
              lastRuleToBeBuilt.set(buildRule);
            }
          };
      try (CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory().setCachingBuildEngineDelegate(testDelegate).build()) {
        cachingBuildEngine
            .build(buildContext, TestExecutionContext.newInstance(), rule)
            .getResult()
            .get();
        assertThat(lastRuleToBeBuilt.get(), is(rule));
      }
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
      BuildRule rule =
          new WriteFile(target, filesystem, "something else", output, /* executable */ false);

      // Create the build engine.
      try (CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build()) {
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
    }

    @Test
    public void dependencyFailuresDoesNotOrphanOtherDependencies() throws Exception {
      ListeningExecutorService service = listeningDecorator(Executors.newFixedThreadPool(2));

      // Create a dep chain comprising one side of the dep tree of the main rule, where the first-
      // running rule fails immediately, canceling the second rule, and ophaning at least one rule
      // in the other side of the dep tree.
      BuildTarget target1 = BuildTargetFactory.newInstance("//:dep1");
      BuildRule dep1 =
          new RuleWithSteps(
              target1,
              filesystem,
              TestBuildRuleParams.create(),
              ImmutableList.of(new FailingStep()),
              /* output */ null);
      BuildTarget target2 = BuildTargetFactory.newInstance("//:dep2");
      BuildRule dep2 =
          new RuleWithSteps(
              target2,
              filesystem,
              TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.of(dep1)),
              ImmutableList.of(new SleepStep(0)),
              /* output */ null);

      // Create another dep chain, which is two deep with rules that just sleep.
      BuildTarget target3 = BuildTargetFactory.newInstance("//:dep3");
      BuildRule dep3 =
          new RuleWithSteps(
              target3,
              filesystem,
              TestBuildRuleParams.create(),
              ImmutableList.of(new SleepStep(300)),
              /* output */ null);
      BuildTarget target5 = BuildTargetFactory.newInstance("//:dep4");
      BuildRule dep4 =
          new RuleWithSteps(
              target5,
              filesystem,
              TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.of(dep3)),
              ImmutableList.of(new SleepStep(300)),
              /* output */ null);

      // Create the top-level rule which pulls in the two sides of the dep tree.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRule rule =
          new RuleWithSteps(
              target,
              filesystem,
              TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.of(dep2, dep4)),
              ImmutableList.of(new SleepStep(1000)),
              /* output */ null);

      // Create the build engine.
      try (CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory().setBuildMode(BuildType.DEEP).build()) {

        // Run the build.
        BuildResult result =
            cachingBuildEngine
                .build(buildContext, TestExecutionContext.newInstance(), rule)
                .getResult()
                .get();
        assertTrue(service.shutdownNow().isEmpty());
        assertThat(result.getStatus(), equalTo(BuildRuleStatus.CANCELED));
        assertThat(
            Objects.requireNonNull(cachingBuildEngine.getBuildRuleResult(dep1.getBuildTarget()))
                .getStatus(),
            equalTo(BuildRuleStatus.FAIL));
        assertThat(
            Objects.requireNonNull(cachingBuildEngine.getBuildRuleResult(dep2.getBuildTarget()))
                .getStatus(),
            equalTo(BuildRuleStatus.CANCELED));
        assertThat(
            Objects.requireNonNull(cachingBuildEngine.getBuildRuleResult(dep3.getBuildTarget()))
                .getStatus(),
            Matchers.oneOf(BuildRuleStatus.SUCCESS, BuildRuleStatus.CANCELED));
        assertThat(
            Objects.requireNonNull(cachingBuildEngine.getBuildRuleResult(dep4.getBuildTarget()))
                .getStatus(),
            Matchers.oneOf(BuildRuleStatus.SUCCESS, BuildRuleStatus.CANCELED));
      }
    }

    @Test
    public void runningWithKeepGoingBuildsAsMuchAsPossible() throws Exception {
      ListeningExecutorService service = listeningDecorator(Executors.newFixedThreadPool(2));
      buildContext = this.buildContext.withKeepGoing(true);

      // Create a dep chain comprising one side of the dep tree of the main rule, where the first-
      // running rule fails immediately, canceling the second rule, and ophaning at least one rule
      // in the other side of the dep tree.
      BuildTarget target1 = BuildTargetFactory.newInstance("//:dep1");
      BuildRule dep1 =
          new RuleWithSteps(
              target1,
              filesystem,
              TestBuildRuleParams.create(),
              ImmutableList.of(new FailingStep()),
              /* output */ null);
      BuildTarget target2 = BuildTargetFactory.newInstance("//:dep2");
      BuildRule dep2 =
          new RuleWithSteps(
              target2,
              filesystem,
              TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.of(dep1)),
              ImmutableList.of(new SleepStep(0)),
              /* output */ null);

      // Create another dep chain, which is two deep with rules that just sleep.
      BuildTarget target3 = BuildTargetFactory.newInstance("//:dep3");
      BuildRule dep3 =
          new RuleWithSteps(
              target3,
              filesystem,
              TestBuildRuleParams.create(),
              ImmutableList.of(new SleepStep(300)),
              /* output */ null);
      BuildTarget target4 = BuildTargetFactory.newInstance("//:dep4");
      BuildRule dep4 =
          new RuleWithSteps(
              target4,
              filesystem,
              TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.of(dep3)),
              ImmutableList.of(new SleepStep(300)),
              /* output */ null);

      // Create the top-level rule which pulls in the two sides of the dep tree.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRule rule =
          new RuleWithSteps(
              target,
              filesystem,
              TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.of(dep2, dep4)),
              ImmutableList.of(new SleepStep(1000)),
              /* output */ null);

      // Create the build engine.
      try (CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory().setExecutorService(service).build()) {
        // Run the build.
        BuildResult result =
            cachingBuildEngine
                .build(buildContext, TestExecutionContext.newInstance(), rule)
                .getResult()
                .get();
        assertThat(result.getStatus(), equalTo(BuildRuleStatus.CANCELED));
        assertThat(
            Objects.requireNonNull(cachingBuildEngine.getBuildRuleResult(dep1.getBuildTarget()))
                .getStatus(),
            equalTo(BuildRuleStatus.FAIL));
        assertThat(
            Objects.requireNonNull(cachingBuildEngine.getBuildRuleResult(dep2.getBuildTarget()))
                .getStatus(),
            equalTo(BuildRuleStatus.CANCELED));
        assertThat(
            Objects.requireNonNull(cachingBuildEngine.getBuildRuleResult(dep3.getBuildTarget()))
                .getStatus(),
            equalTo(BuildRuleStatus.SUCCESS));
        assertThat(
            Objects.requireNonNull(cachingBuildEngine.getBuildRuleResult(dep4.getBuildTarget()))
                .getStatus(),
            equalTo(BuildRuleStatus.SUCCESS));
      }
      assertTrue(service.shutdownNow().isEmpty());
    }

    @Test
    public void getNumRulesToBuild() {
      BuildRule rule3 =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule3"))
              .setOut("out3")
              .build(graphBuilder);
      BuildRule rule2 =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule2"))
              .setOut("out2")
              .setSrcs(ImmutableList.of(rule3.getSourcePathToOutput()))
              .build(graphBuilder);
      BuildRule rule1 =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule1"))
              .setOut("out1")
              .setSrcs(ImmutableList.of(rule2.getSourcePathToOutput()))
              .build(graphBuilder);

      // Create the build engine.
      try (CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setCachingBuildEngineDelegate(new LocalCachingBuildEngineDelegate(fileHashCache))
              .build()) {
        assertThat(cachingBuildEngine.getNumRulesToBuild(ImmutableList.of(rule1)), equalTo(3));
      }
    }

    @Test
    public void artifactCacheSizeLimit() throws Exception {
      // Create a simple rule which just writes something new to the output file.
      BuildTarget buildTarget = BuildTargetFactory.newInstance("//:rule");
      BuildRule rule =
          new WriteFile(
              buildTarget, filesystem, "data", Paths.get("output/path"), /* executable */ false);

      // Create the build engine with low cache artifact limit which prevents caching the above\
      // rule.
      try (CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setCachingBuildEngineDelegate(new LocalCachingBuildEngineDelegate(fileHashCache))
              .setArtifactCacheSizeLimit(Optional.of(2L))
              .build()) {
        // Verify that after building successfully, nothing is cached.
        BuildResult result =
            cachingBuildEngine
                .build(buildContext, TestExecutionContext.newInstance(), rule)
                .getResult()
                .get();
        assertThat(result.getSuccess(), equalTo(BuildRuleSuccessType.BUILT_LOCALLY));
        assertTrue(cache.isEmpty());
      }
    }

    @Test
    public void fetchingFromCacheSeedsFileHashCache() throws Throwable {
      // Create a simple rule which just writes something new to the output file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      Path output = filesystem.getPath("output/path");
      BuildRule rule =
          new WriteFile(target, filesystem, "something else", output, /* executable */ false);

      // Run an initial build to seed the cache.
      try (CachingBuildEngine cachingBuildEngine = cachingBuildEngineFactory().build()) {
        BuildResult result =
            cachingBuildEngine
                .build(buildContext, TestExecutionContext.newInstance(), rule)
                .getResult()
                .get();
        assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());

        // Clear the file system.
        filesystem.deleteRecursivelyIfExists(Paths.get(""));
        buildInfoStore.deleteMetadata(target);
      }
      // Now run a second build that gets a cache hit.  We use an empty `FakeFileHashCache` which
      // does *not* contain the path, so any attempts to hash it will fail.
      FakeFileHashCache fakeFileHashCache = new FakeFileHashCache(new HashMap<Path, HashCode>());
      try (CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setCachingBuildEngineDelegate(new LocalCachingBuildEngineDelegate(fakeFileHashCache))
              .build()) {
        BuildResult result =
            cachingBuildEngine
                .build(buildContext, TestExecutionContext.newInstance(), rule)
                .getResult()
                .get();
        assertEquals(BuildRuleSuccessType.FETCHED_FROM_CACHE, result.getSuccess());

        // Verify that the cache hit caused the file hash cache to contain the path.
        assertTrue(fakeFileHashCache.contains(filesystem.resolve(output)));
      }
    }
  }

  public static class InputBasedRuleKeyTests extends CommonFixture {
    public InputBasedRuleKeyTests(MetadataStorage metadataStorage) {
      super(metadataStorage);
    }

    @Test
    public void inputBasedRuleKeyAndArtifactAreWrittenForSupportedRules() throws Exception {
      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params = TestBuildRuleParams.create();
      RuleKey inputRuleKey = new RuleKey("aaaa");
      Path output = Paths.get("output");
      BuildRule rule =
          new InputRuleKeyBuildRule(target, filesystem, params) {
            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
            }
          };

      // Create the build engine.
      try (CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setRuleKeyFactories(
                  RuleKeyFactories.of(
                      NOOP_RULE_KEY_FACTORY,
                      new FakeRuleKeyFactory(ImmutableMap.of(rule.getBuildTarget(), inputRuleKey)),
                      NOOP_DEP_FILE_RULE_KEY_FACTORY))
              .build()) {
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
    }

    @Test
    public void inputBasedRuleKeyLimit() throws Exception {
      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params = TestBuildRuleParams.create();
      RuleKey inputRuleKey = new RuleKey("aaaa");
      Path output = Paths.get("output");
      BuildRule rule =
          new InputRuleKeyBuildRule(target, filesystem, params) {
            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              buildableContext.recordArtifact(output);
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "12345", output, /* executable */ false));
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
            }
          };

      FakeRuleKeyFactory fakeInputRuleKeyFactory =
          new FakeRuleKeyFactory(
              ImmutableMap.of(rule.getBuildTarget(), inputRuleKey),
              ImmutableSet.of(rule.getBuildTarget())) {
            @Override
            public Optional<Long> getInputSizeLimit() {
              return Optional.of(2L);
            }
          };
      // Create the build engine.
      try (CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setRuleKeyFactories(
                  RuleKeyFactories.of(
                      NOOP_RULE_KEY_FACTORY,
                      fakeInputRuleKeyFactory,
                      NOOP_DEP_FILE_RULE_KEY_FACTORY))
              .build()) {
        // Run the build.
        BuildResult result =
            cachingBuildEngine
                .build(buildContext, TestExecutionContext.newInstance(), rule)
                .getResult()
                .get();
        assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());

        // Verify that the artifact was indexed in the cache by the input rule key.
        assertFalse(cache.hasArtifact(inputRuleKey));

        // Verify the input rule key was written to disk.
        OnDiskBuildInfo onDiskBuildInfo =
            buildContext.createOnDiskBuildInfoFor(target, filesystem, buildInfoStore);
        assertThat(
            onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY),
            equalTo(Optional.empty()));
        assertThat(
            onDiskBuildInfo.getValue(BuildInfo.MetadataKey.OUTPUT_SIZE), equalTo(Optional.of("6")));
        assertThat(
            onDiskBuildInfo.getHash(BuildInfo.MetadataKey.OUTPUT_HASH), equalTo(Optional.empty()));
      }
    }

    @Test
    public void inputBasedRuleKeyLimitCacheHit() throws Exception {
      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params = TestBuildRuleParams.create();
      RuleKey ruleKey = new RuleKey("ba5e");
      RuleKey inputRuleKey = new RuleKey("ba11");
      Path output = Paths.get("output");
      BuildRule rule =
          new InputRuleKeyBuildRule(target, filesystem, params) {
            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              buildableContext.recordArtifact(output);
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "12345", output, /* executable */ false));
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
            }
          };

      // Prepopulate the cache with an artifact indexed by the input-based rule key.  Pretend
      // OUTPUT_HASH and RECORDED_PATH_HASHES are missing b/c we exceeded the input based
      // threshold.
      Path metadataDirectory = BuildInfo.getPathToArtifactMetadataDirectory(target, filesystem);
      filesystem.mkdirs(metadataDirectory);
      Path outputPath = pathResolver.getRelativePath(rule.getSourcePathToOutput());
      filesystem.writeContentsToPath(
          ObjectMappers.WRITER.writeValueAsString(ImmutableList.of(outputPath.toString())),
          metadataDirectory.resolve(BuildInfo.MetadataKey.RECORDED_PATHS));

      Path artifact = tmp.newFile("artifact.zip");
      writeEntriesToArchive(
          artifact,
          ImmutableMap.of(
              metadataDirectory.resolve(BuildInfo.MetadataKey.RECORDED_PATHS),
              ObjectMappers.WRITER.writeValueAsString(ImmutableList.of(outputPath.toString())),
              metadataDirectory.resolve(BuildInfo.MetadataKey.OUTPUT_SIZE),
              "123",
              outputPath,
              "stuff"),
          ImmutableList.of(metadataDirectory));
      cache.store(
          ArtifactInfo.builder()
              .addRuleKeys(ruleKey)
              .putMetadata(BuildInfo.MetadataKey.BUILD_ID, buildContext.getBuildId().toString())
              .putMetadata(
                  BuildInfo.MetadataKey.ORIGIN_BUILD_ID, buildContext.getBuildId().toString())
              .putMetadata(BuildInfo.MetadataKey.RULE_KEY, ruleKey.toString())
              .putMetadata(BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY, inputRuleKey.toString())
              .build(),
          BorrowablePath.notBorrowablePath(artifact));

      FakeRuleKeyFactory fakeInputRuleKeyFactory =
          new FakeRuleKeyFactory(
              ImmutableMap.of(rule.getBuildTarget(), inputRuleKey),
              ImmutableSet.of(rule.getBuildTarget())) {
            @Override
            public Optional<Long> getInputSizeLimit() {
              return Optional.of(2L);
            }
          };
      try (CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setRuleKeyFactories(
                  RuleKeyFactories.of(
                      new FakeRuleKeyFactory(ImmutableMap.of(target, ruleKey)),
                      fakeInputRuleKeyFactory,
                      NOOP_DEP_FILE_RULE_KEY_FACTORY))
              .build()) {
        // Run the build.
        BuildResult result =
            cachingBuildEngine
                .build(buildContext, TestExecutionContext.newInstance(), rule)
                .getResult()
                .get();
        assertEquals(BuildRuleSuccessType.FETCHED_FROM_CACHE, result.getSuccess());
      }
    }

    @Test
    public void inputBasedRuleKeyMatchAvoidsBuildingLocally() throws Exception {
      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params = TestBuildRuleParams.create();
      RuleKey inputRuleKey = new RuleKey("aaaa");
      BuildRule rule = new FailingInputRuleKeyBuildRule(target, filesystem, params);
      graphBuilder.addToIndex(rule);

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
      try (CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setRuleKeyFactories(
                  RuleKeyFactories.of(
                      defaultRuleKeyFactory,
                      new FakeRuleKeyFactory(ImmutableMap.of(rule.getBuildTarget(), inputRuleKey)),
                      NOOP_DEP_FILE_RULE_KEY_FACTORY))
              .build()) {
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
            Futures.getUnchecked(
                    cache.fetchAsync(null, defaultRuleKeyFactory.build(rule), fetchedArtifact))
                .getType(),
            equalTo(CacheResultType.MISS));
      }
    }

    @Test
    public void inputBasedRuleKeyCacheHitAvoidsBuildingLocally() throws Exception {
      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      RuleKey inputRuleKey = new RuleKey("aaaa");
      BuildRuleParams params = TestBuildRuleParams.create();
      BuildRule rule = new FailingInputRuleKeyBuildRule(target, filesystem, params);
      graphBuilder.addToIndex(rule);

      // Prepopulate the recorded paths metadata.
      Path metadataDirectory = BuildInfo.getPathToArtifactMetadataDirectory(target, filesystem);
      filesystem.mkdirs(metadataDirectory);
      Path outputPath = pathResolver.getRelativePath(rule.getSourcePathToOutput());
      filesystem.writeContentsToPath(
          ObjectMappers.WRITER.writeValueAsString(ImmutableList.of(outputPath.toString())),
          metadataDirectory.resolve(BuildInfo.MetadataKey.RECORDED_PATHS));

      // Prepopulate the cache with an artifact indexed by the input-based rule key.
      Path artifact = tmp.newFile("artifact.zip");
      writeEntriesToArchive(
          artifact,
          ImmutableMap.of(
              metadataDirectory.resolve(BuildInfo.MetadataKey.RECORDED_PATHS),
              ObjectMappers.WRITER.writeValueAsString(ImmutableList.of(outputPath.toString())),
              metadataDirectory.resolve(BuildInfo.MetadataKey.RECORDED_PATH_HASHES),
              ObjectMappers.WRITER.writeValueAsString(
                  ImmutableMap.of(outputPath.toString(), HashCode.fromInt(123).toString())),
              metadataDirectory.resolve(BuildInfo.MetadataKey.OUTPUT_SIZE),
              "123",
              metadataDirectory.resolve(BuildInfo.MetadataKey.OUTPUT_HASH),
              HashCode.fromInt(123).toString(),
              outputPath,
              "stuff"),
          ImmutableList.of(metadataDirectory));
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
      try (CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setRuleKeyFactories(
                  RuleKeyFactories.of(
                      defaultRuleKeyFactory,
                      new FakeRuleKeyFactory(ImmutableMap.of(rule.getBuildTarget(), inputRuleKey)),
                      NOOP_DEP_FILE_RULE_KEY_FACTORY))
              .build()) {
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
            Futures.getUnchecked(
                    cache.fetchAsync(
                        null,
                        defaultRuleKeyFactory.build(rule),
                        LazyPath.ofInstance(fetchedArtifact)))
                .getType(),
            equalTo(CacheResultType.HIT));

        ImmutableMap<String, byte[]> artifactEntries = TarInspector.readTarZst(artifact);
        ImmutableMap<String, byte[]> fetchedArtifactEntries =
            TarInspector.readTarZst(fetchedArtifact);

        assertEquals(
            Sets.union(ImmutableSet.of(metadataDirectory + "/"), artifactEntries.keySet()),
            fetchedArtifactEntries.keySet());
        assertThat(
            fetchedArtifactEntries,
            Matchers.hasEntry(
                pathResolver.getRelativePath(rule.getSourcePathToOutput()).toString(),
                "stuff".getBytes(UTF_8)));
      }
    }

    public static class CustomStrategyTests extends CommonFixture {
      private BuildTarget target;
      private ThrowingSupplier<StepExecutionResult, InterruptedException> resultSupplier;
      private BuildRule rule;
      private FakeStrategy strategy;

      public CustomStrategyTests(MetadataStorage metadataStorage) {
        super(metadataStorage);
      }

      interface Builder {
        ListenableFuture<Optional<BuildResult>> build(
            ListeningExecutorService service, BuildRule rule, BuildStrategyContext executorRunner);
      }

      private static class FakeStrategy implements BuildRuleStrategy {
        boolean closed = false;
        Predicate<BuildRule> canBuild = rule -> false;
        Optional<Builder> builder = Optional.empty();
        Runnable cancelCallback = () -> {};

        @Override
        public StrategyBuildResult build(BuildRule rule, BuildStrategyContext strategyContext) {
          Preconditions.checkState(builder.isPresent());
          ListenableFuture<Optional<BuildResult>> buildResult =
              builder.get().build(strategyContext.getExecutorService(), rule, strategyContext);
          return new StrategyBuildResult() {
            @Override
            public boolean cancelIfNotStarted(Throwable reason) {
              return false;
            }

            @Override
            public void cancel(Throwable cause) {
              cancelCallback.run();
            }

            @Override
            public ListenableFuture<Optional<BuildResult>> getBuildResult() {
              return buildResult;
            }
          };
        }

        @Override
        public boolean canBuild(BuildRule instance) {
          return canBuild.test(instance);
        }

        @Override
        public void close() {
          Preconditions.checkState(!closed);
          closed = true;
        }

        void assertClosed() {
          assertTrue(closed);
        }
      }

      @Override
      @Before
      public void setUp() throws Exception {
        super.setUp();
        target = BuildTargetFactory.newInstance("//:rule");
        resultSupplier = () -> StepExecutionResults.ERROR;
        Step step =
            new AbstractExecutionStep("step") {
              @Override
              public StepExecutionResult execute(ExecutionContext context)
                  throws InterruptedException {
                return resultSupplier.get();
              }
            };
        rule =
            new EmptyBuildRule(target, filesystem) {
              @Override
              public ImmutableList<Step> getBuildSteps(
                  BuildContext context, BuildableContext buildableContext) {
                return ImmutableList.of(step);
              }
            };
        strategy = new FakeStrategy();
      }

      public void runVerifiedBuild(BuildRule rule) throws InterruptedException, ExecutionException {
        try (CachingBuildEngine cachingBuildEngine =
            cachingBuildEngineFactory().setCustomBuildRuleStrategy(strategy).build()) {
          ExecutionContext executionContext = TestExecutionContext.newInstance();
          BuildResult buildResult =
              cachingBuildEngine.build(buildContext, executionContext, rule).getResult().get();
          assertTrue(
              buildResult.getFailureOptional().map(ErrorLogger::getUserFriendlyMessage).toString(),
              buildResult.isSuccess());
        }
        strategy.assertClosed();
      }

      @Test
      public void testCustomBuildRuleStrategyCanRejectRules() throws Exception {
        resultSupplier = () -> StepExecutionResults.SUCCESS;
        runVerifiedBuild(rule);
      }

      @Test
      public void testCustomBuildRuleStrategyCanRunRulesWithDefaultBehavior() throws Exception {
        resultSupplier = () -> StepExecutionResults.SUCCESS;
        strategy.canBuild = rule -> true;
        strategy.builder =
            Optional.of((service, rule, executorRunner) -> executorRunner.runWithDefaultBehavior());
        runVerifiedBuild(rule);
      }

      @Test
      public void testCustomBuildRuleStrategyCanRunRulesWithCustomBehavior() throws Exception {
        resultSupplier =
            () -> {
              fail();
              return null;
            };
        strategy.canBuild = rule -> true;
        strategy.builder =
            Optional.of(
                (service, rule, strategyContext) -> {
                  try (Scope ignored = strategyContext.buildRuleScope()) {
                    return Futures.immediateFuture(
                        Optional.of(
                            strategyContext.createBuildResult(BuildRuleSuccessType.BUILT_LOCALLY)));
                  }
                });
        runVerifiedBuild(rule);
      }

      @Test
      public void customBuildRuleStrategyGetsCancelCallOnFirstFailure() throws Exception {
        CountDownLatch failureBlocker = new CountDownLatch(1);
        CountDownLatch cancelSignal = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        strategy.canBuild = rule -> rule == this.rule;

        ListeningExecutorService executorService =
            listeningDecorator(MostExecutors.newMultiThreadExecutor("test", 4));

        // It can be pretty difficult to determine the state of the different threads when
        // debugging, so we print a little bit about the state.
        strategy.builder =
            Optional.of(
                (service, rule, strategyContext) ->
                    // Only async strategies can actually receive a cancellation signal.
                    executorService.submit(
                        () -> {
                          try (Scope ignored = strategyContext.buildRuleScope()) {
                            System.err.println("Signalling failure to continue.");
                            failureBlocker.countDown();
                            System.err.println("Waiting for cancellation signal.");
                            if (cancelSignal.await(1, TimeUnit.SECONDS)) {
                              System.err.println("Got cancellation.");
                            } else {
                              failure.set(new RuntimeException("Never got cancel signal."));
                            }

                            return Optional.of(
                                strategyContext.createBuildResult(
                                    BuildRuleSuccessType.BUILT_LOCALLY));
                          }
                        }));
        strategy.cancelCallback = () -> cancelSignal.countDown();

        FakeBuildRule failingRule =
            new FakeBuildRule("//:failing_rule", filesystem) {
              @Override
              public ImmutableList<Step> getBuildSteps(
                  BuildContext context, BuildableContext buildableContext) {
                return ImmutableList.of(
                    new AbstractExecutionStep("failing_step") {
                      @Override
                      public StepExecutionResult execute(ExecutionContext context)
                          throws InterruptedException {
                        System.err.println("Waiting to fail.");
                        if (failureBlocker.await(1, TimeUnit.SECONDS)) {
                          System.err.println("Failing.");
                        } else {
                          failure.set(new RuntimeException("Never got failure signal."));
                        }
                        return StepExecutionResults.ERROR;
                      }
                    });
              }
            };
        try (CachingBuildEngine cachingBuildEngine =
            cachingBuildEngineFactory()
                .setCustomBuildRuleStrategy(strategy)
                .setExecutorService(executorService)
                .build()) {
          ExecutionContext executionContext = TestExecutionContext.newInstance();

          cachingBuildEngine
              .build(
                  buildContext,
                  executionContext,
                  new FakeBuildRule("//:with_deps", filesystem, rule, failingRule))
              .getResult()
              .get();
        }
        strategy.assertClosed();

        if (failure.get() != null) {
          failure.get().printStackTrace(System.err);
          fail("Failing due to thrown exception: " + failure.get().getMessage());
        }
        assertEquals(0, cancelSignal.getCount());
      }
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
      Path output = Paths.get("output");
      BuildRuleParams params = TestBuildRuleParams.create();
      BuildRule rule =
          new InputRuleKeyBuildRule(target, filesystem, params) {
            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
            }
          };
      graphBuilder.addToIndex(rule);

      // Create the output file.
      filesystem.writeContentsToPath(
          "stuff", pathResolver.getRelativePath(rule.getSourcePathToOutput()));

      // Prepopulate the recorded paths metadata.
      Path metadataDirectory = BuildInfo.getPathToArtifactMetadataDirectory(target, filesystem);
      filesystem.mkdirs(metadataDirectory);
      filesystem.writeContentsToPath(
          ObjectMappers.WRITER.writeValueAsString(
              ImmutableList.of(
                  pathResolver.getRelativePath(rule.getSourcePathToOutput()).toString())),
          metadataDirectory.resolve(BuildInfo.MetadataKey.RECORDED_PATHS));

      if (previousRuleKey.isPresent()) {
        // Prepopulate the input rule key on disk.
        filesystem.writeContentsToPath(
            previousRuleKey.get().toString(),
            metadataDirectory.resolve(BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY));
      }

      // Create the build engine.
      try (CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setRuleKeyFactories(
                  RuleKeyFactories.of(
                      defaultRuleKeyFactory,
                      new FakeRuleKeyFactory(
                          ImmutableMap.of(), ImmutableSet.of(rule.getBuildTarget())),
                      NOOP_DEP_FILE_RULE_KEY_FACTORY))
              .build()) {
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
    }

    private static class FailingInputRuleKeyBuildRule extends InputRuleKeyBuildRule {
      public FailingInputRuleKeyBuildRule(
          BuildTarget buildTarget,
          ProjectFilesystem projectFilesystem,
          BuildRuleParams buildRuleParams) {
        super(buildTarget, projectFilesystem, buildRuleParams);
      }

      @Override
      public ImmutableList<Step> getBuildSteps(
          BuildContext context, BuildableContext buildableContext) {
        return ImmutableList.of(
            new AbstractExecutionStep("false") {
              @Override
              public StepExecutionResult execute(ExecutionContext context) {
                return StepExecutionResults.ERROR;
              }
            });
      }

      @Override
      public SourcePath getSourcePathToOutput() {
        return ExplicitBuildTargetSourcePath.of(getBuildTarget(), Paths.get("output"));
      }
    }
  }

  public static class DepFileTests extends CommonFixture {

    private DefaultDependencyFileRuleKeyFactory depFileFactory;

    public DepFileTests(MetadataStorage metadataStorage) {
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
      Genrule genrule =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
              .setOut("input")
              .build(graphBuilder, filesystem);
      Path input =
          pathResolver.getRelativePath(Objects.requireNonNull(genrule.getSourcePathToOutput()));
      filesystem.mkdirs(input.getParent());
      filesystem.writeContentsToPath("contents", input);

      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params = TestBuildRuleParams.create();
      Path output = Paths.get("output");
      DepFileBuildRule rule =
          new DepFileBuildRule(target, filesystem, params) {
            @AddToRuleKey private final SourcePath path = genrule.getSourcePathToOutput();

            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }

            @Override
            public Predicate<SourcePath> getCoveredByDepFilePredicate(
                SourcePathResolver pathResolver) {
              return (SourcePath path) -> true;
            }

            @Override
            public Predicate<SourcePath> getExistenceOfInterestPredicate(
                SourcePathResolver pathResolver) {
              return (SourcePath path) -> false;
            }

            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
                BuildContext context, CellPathResolver cellPathResolver) {
              return ImmutableList.of(PathSourcePath.of(filesystem, input));
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
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
          Futures.getUnchecked(
              cache.fetchAsync(
                  null, defaultRuleKeyFactory.build(rule), LazyPath.ofInstance(fetchedArtifact)));
      assertThat(cacheResult.getType(), equalTo(CacheResultType.HIT));
      assertThat(
          cacheResult.getMetadata().get(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY),
          equalTo(depFileRuleKey.toString()));
      ImmutableMap<String, byte[]> fetchedArtifactEntries =
          TarInspector.readTarZst(fetchedArtifact);
      assertThat(
          fetchedArtifactEntries,
          Matchers.hasEntry(
              BuildInfo.getPathToArtifactMetadataDirectory(target, filesystem)
                  .resolve(BuildInfo.MetadataKey.DEP_FILE)
                  .toString(),
              ObjectMappers.WRITER
                  .writeValueAsString(ImmutableList.of(fileToDepFileEntryString(input)))
                  .getBytes(UTF_8)));
    }

    @Test
    public void depFileRuleKeyMatchAvoidsBuilding() throws Exception {
      // Prepare an input file that should appear in the dep file.
      Path input = Paths.get("input_file");
      filesystem.touch(input);

      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params = TestBuildRuleParams.create();
      RuleKey depFileRuleKey = new RuleKey("aaaa");
      Path output = Paths.get("output");
      filesystem.touch(output);
      BuildRule rule =
          new DepFileBuildRule(target, filesystem, params) {
            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              return ImmutableList.of(
                  new AbstractExecutionStep("false") {
                    @Override
                    public StepExecutionResult execute(ExecutionContext context) {
                      return StepExecutionResults.ERROR;
                    }
                  });
            }

            @Override
            public Predicate<SourcePath> getCoveredByDepFilePredicate(
                SourcePathResolver pathResolver) {
              return (SourcePath path) -> true;
            }

            @Override
            public Predicate<SourcePath> getExistenceOfInterestPredicate(
                SourcePathResolver pathResolver) {
              return (SourcePath path) -> false;
            }

            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
                BuildContext context, CellPathResolver cellPathResolver) {
              return ImmutableList.of(PathSourcePath.of(filesystem, input));
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
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
      Genrule genrule =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
              .setOut("input")
              .build(graphBuilder, filesystem);
      Path input =
          pathResolver.getRelativePath(Objects.requireNonNull(genrule.getSourcePathToOutput()));

      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params = TestBuildRuleParams.create();
      Path output = Paths.get("output");
      DepFileBuildRule rule =
          new DepFileBuildRule(target, filesystem, params) {
            @AddToRuleKey private final SourcePath path = genrule.getSourcePathToOutput();

            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }

            @Override
            public Predicate<SourcePath> getCoveredByDepFilePredicate(
                SourcePathResolver pathResolver) {
              return (SourcePath path) -> true;
            }

            @Override
            public Predicate<SourcePath> getExistenceOfInterestPredicate(
                SourcePathResolver pathResolver) {
              return (SourcePath path) -> false;
            }

            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
                BuildContext context, CellPathResolver cellPathResolver) {
              return ImmutableList.of(PathSourcePath.of(filesystem, input));
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
            }
          };

      // Prepare an input file that should appear in the dep file.
      filesystem.mkdirs(input.getParent());
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
      Path inputFile = Paths.get("input");

      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params = TestBuildRuleParams.create();
      Path output = Paths.get("output");
      ImmutableSet<SourcePath> inputsBefore = ImmutableSet.of();
      DepFileBuildRule rule =
          new DepFileBuildRule(target, filesystem, params) {
            @AddToRuleKey private final SourcePath path = PathSourcePath.of(filesystem, inputFile);

            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }

            @Override
            public Predicate<SourcePath> getCoveredByDepFilePredicate(
                SourcePathResolver pathResolver) {
              return inputsBefore::contains;
            }

            @Override
            public Predicate<SourcePath> getExistenceOfInterestPredicate(
                SourcePathResolver pathResolver) {
              return (SourcePath path) -> false;
            }

            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
                BuildContext context, CellPathResolver cellPathResolver) {
              return ImmutableList.of();
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
            }
          };

      // Prepare an input file that will not appear in the dep file. This is to simulate a
      // a dependency that the dep-file generator is not aware of.
      filesystem.writeContentsToPath("something", inputFile);

      // Prepopulate the dep file rule key and dep file.
      RuleKey depFileRuleKey = depFileFactory.build(rule, ImmutableList.of()).getRuleKey();

      Path metadataDirectory = BuildInfo.getPathToArtifactMetadataDirectory(target, filesystem);
      filesystem.mkdirs(metadataDirectory);
      filesystem.writeContentsToPath(
          depFileRuleKey.toString(),
          metadataDirectory.resolve(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY));
      String emptyDepFileContents = "[]";
      filesystem.writeContentsToPath(
          emptyDepFileContents, metadataDirectory.resolve(BuildInfo.MetadataKey.DEP_FILE));

      // Prepopulate the recorded paths metadata.
      filesystem.writeContentsToPath(
          ObjectMappers.WRITER.writeValueAsString(ImmutableList.of(output.toString())),
          metadataDirectory.resolve(BuildInfo.MetadataKey.RECORDED_PATHS));

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
          filesystem.readLines(metadataDirectory.resolve(BuildInfo.MetadataKey.DEP_FILE)).get(0);
      assertEquals(emptyDepFileContents, newDepFile);
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, getSuccess(result));
    }

    @Test
    public void depFileDeletedInputCausesRebuild() throws Exception {
      // Use a genrule to produce the input file.
      Genrule genrule =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
              .setOut("input")
              .build(graphBuilder, filesystem);
      Path input =
          pathResolver.getRelativePath(Objects.requireNonNull(genrule.getSourcePathToOutput()));

      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params = TestBuildRuleParams.create();
      Path output = Paths.get("output");
      DepFileBuildRule rule =
          new DepFileBuildRule(target, filesystem, params) {
            @AddToRuleKey private final SourcePath path = genrule.getSourcePathToOutput();

            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }

            @Override
            public Predicate<SourcePath> getCoveredByDepFilePredicate(
                SourcePathResolver pathResolver) {
              return (SourcePath path) -> true;
            }

            @Override
            public Predicate<SourcePath> getExistenceOfInterestPredicate(
                SourcePathResolver pathResolver) {
              return (SourcePath path) -> false;
            }

            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
                BuildContext context, CellPathResolver cellPathResolver) {
              return ImmutableList.of();
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
            }
          };

      // Prepare an input file that should appear in the dep file.
      filesystem.mkdirs(input.getParent());
      filesystem.writeContentsToPath("something", input);
      RuleKey depFileRuleKey =
          depFileFactory
              .build(rule, ImmutableList.of(DependencyFileEntry.of(input, Optional.empty())))
              .getRuleKey();

      // Prepopulate the dep file rule key and dep file.
      Path metadataDirectory = BuildInfo.getPathToArtifactMetadataDirectory(target, filesystem);
      filesystem.mkdirs(metadataDirectory);
      filesystem.writeContentsToPath(
          depFileRuleKey.toString(),
          metadataDirectory.resolve(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY));
      filesystem.writeContentsToPath(
          ObjectMappers.WRITER.writeValueAsString(
              ImmutableList.of(fileToDepFileEntryString(input))),
          metadataDirectory.resolve(BuildInfo.MetadataKey.DEP_FILE));

      // Prepopulate the recorded paths metadata.
      filesystem.writeContentsToPath(
          ObjectMappers.WRITER.writeValueAsString(ImmutableList.of(output.toString())),
          metadataDirectory.resolve(BuildInfo.MetadataKey.RECORDED_PATHS));

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
      Genrule genrule =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
              .setOut("input")
              .build(graphBuilder, filesystem);
      Path input =
          pathResolver.getRelativePath(Objects.requireNonNull(genrule.getSourcePathToOutput()));

      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params = TestBuildRuleParams.create();
      Path output = Paths.get("output");
      DepFileBuildRule rule =
          new DepFileBuildRule(target, filesystem, params) {
            @AddToRuleKey private final SourcePath path = genrule.getSourcePathToOutput();

            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }

            @Override
            public Predicate<SourcePath> getCoveredByDepFilePredicate(
                SourcePathResolver pathResolver) {
              return (SourcePath path) -> true;
            }

            @Override
            public Predicate<SourcePath> getExistenceOfInterestPredicate(
                SourcePathResolver pathResolver) {
              return (SourcePath path) -> false;
            }

            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
                BuildContext context, CellPathResolver cellPathResolver) {
              return ImmutableList.of();
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
            }
          };

      DependencyFileRuleKeyFactory depFileRuleKeyFactory =
          new FakeRuleKeyFactory(ImmutableMap.of(rule.getBuildTarget(), new RuleKey("aa")));

      // Prepare an input file that should appear in the dep file.
      filesystem.mkdirs(input.getParent());
      filesystem.writeContentsToPath("something", input);

      RuleKey depFileRuleKey =
          depFileFactory
              .build(rule, ImmutableList.of(DependencyFileEntry.of(input, Optional.empty())))
              .getRuleKey();

      // Prepopulate the dep file rule key and dep file.
      Path metadataDirectory = BuildInfo.getPathToArtifactMetadataDirectory(target, filesystem);
      filesystem.mkdirs(metadataDirectory);
      filesystem.writeContentsToPath(
          depFileRuleKey.toString(),
          metadataDirectory.resolve(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY));
      filesystem.writeContentsToPath(
          ObjectMappers.WRITER.writeValueAsString(
              ImmutableList.of(fileToDepFileEntryString(input))),
          metadataDirectory.resolve(BuildInfo.MetadataKey.DEP_FILE));

      // Prepopulate the recorded paths metadata.
      filesystem.writeContentsToPath(
          ObjectMappers.WRITER.writeValueAsString(ImmutableList.of(output.toString())),
          metadataDirectory.resolve(BuildInfo.MetadataKey.RECORDED_PATHS));

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
    public ManifestTests(MetadataStorage metadataStorage) {
      super(metadataStorage);
    }

    private static Optional<RuleKey> getManifestRuleKeyForTest(
        CachingBuildEngine engine, SupportsDependencyFileRuleKey rule, BuckEventBus eventBus)
        throws IOException {
      return engine
          .ruleKeyFactories
          .calculateManifestKey(rule, eventBus)
          .map(RuleKeyAndInputs::getRuleKey);
    }

    @Test
    public void manifestIsWrittenWhenBuiltLocally() throws Exception {
      DefaultDependencyFileRuleKeyFactory depFilefactory =
          new DefaultDependencyFileRuleKeyFactory(
              FIELD_LOADER, fileHashCache, pathResolver, ruleFinder);

      // Use a genrule to produce the input file.
      Genrule genrule =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
              .setOut("input")
              .build(graphBuilder, filesystem);
      Path input =
          pathResolver.getRelativePath(Objects.requireNonNull(genrule.getSourcePathToOutput()));
      filesystem.mkdirs(input.getParent());
      filesystem.writeContentsToPath("contents", input);

      // Create another input that will be ineligible for the dep file. Such inputs should still
      // be part of the manifest.
      Path input2 = Paths.get("input2");
      filesystem.writeContentsToPath("contents2", input2);

      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params = TestBuildRuleParams.create();
      Path output = Paths.get("output");
      DepFileBuildRule rule =
          new DepFileBuildRule(target, filesystem, params) {
            @AddToRuleKey private final SourcePath path = genrule.getSourcePathToOutput();

            @AddToRuleKey private final SourcePath otherDep = PathSourcePath.of(filesystem, input2);

            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }

            @Override
            public Predicate<SourcePath> getCoveredByDepFilePredicate(
                SourcePathResolver pathResolver) {
              return ImmutableSet.of(path)::contains;
            }

            @Override
            public Predicate<SourcePath> getExistenceOfInterestPredicate(
                SourcePathResolver pathResolver) {
              return (SourcePath path) -> false;
            }

            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
                BuildContext context, CellPathResolver cellPathResolver) {
              return ImmutableList.of(path);
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
            }
          };

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setDepFiles(DepFiles.CACHE)
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
          Futures.getUnchecked(
              cache.fetchAsync(
                  null,
                  getManifestRuleKeyForTest(cachingBuildEngine, rule, buildContext.getEventBus())
                      .get(),
                  LazyPath.ofInstance(fetchedManifest)));
      assertThat(cacheResult.getType(), equalTo(CacheResultType.HIT));
      Manifest manifest = loadManifest(fetchedManifest);
      // The manifest should only contain the inputs that were in the dep file. The non-eligible
      // dependency went toward computing the manifest key and thus doesn't need to be in the value.
      assertThat(
          ManifestUtil.toMap(manifest),
          equalTo(
              ImmutableMap.of(
                  depFileRuleKey,
                  ImmutableMap.of(
                      input.toString(), fileHashCache.get(filesystem.resolve(input))))));

      // Verify that the artifact is also cached via the dep file rule key.
      Path fetchedArtifact = tmp.newFile("artifact");
      cacheResult =
          Futures.getUnchecked(
              cache.fetchAsync(null, depFileRuleKey, LazyPath.ofInstance(fetchedArtifact)));
      assertThat(cacheResult.getType(), equalTo(CacheResultType.HIT));
    }

    @Test
    public void manifestIsUpdatedWhenBuiltLocally() throws Exception {
      DefaultDependencyFileRuleKeyFactory depFilefactory =
          new DefaultDependencyFileRuleKeyFactory(
              FIELD_LOADER, fileHashCache, pathResolver, ruleFinder);

      // Use a genrule to produce the input file.
      Genrule genrule =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
              .setOut("input")
              .build(graphBuilder, filesystem);
      Path input =
          pathResolver.getRelativePath(Objects.requireNonNull(genrule.getSourcePathToOutput()));
      filesystem.mkdirs(input.getParent());
      filesystem.writeContentsToPath("contents", input);

      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params = TestBuildRuleParams.create();
      Path output = Paths.get("output");
      DepFileBuildRule rule =
          new DepFileBuildRule(target, filesystem, params) {
            @AddToRuleKey private final SourcePath path = genrule.getSourcePathToOutput();

            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }

            @Override
            public Predicate<SourcePath> getCoveredByDepFilePredicate(
                SourcePathResolver pathResolver) {
              return (SourcePath path) -> true;
            }

            @Override
            public Predicate<SourcePath> getExistenceOfInterestPredicate(
                SourcePathResolver pathResolver) {
              return (SourcePath path) -> false;
            }

            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
                BuildContext context, CellPathResolver cellPathResolver) {
              return ImmutableList.of(PathSourcePath.of(filesystem, input));
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
            }
          };

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setDepFiles(DepFiles.CACHE)
              .setRuleKeyFactories(
                  RuleKeyFactories.of(
                      defaultRuleKeyFactory, inputBasedRuleKeyFactory, depFilefactory))
              .build();

      // Seed the cache with an existing manifest with a dummy entry.
      Manifest manifest =
          ManifestUtil.fromMap(
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
                  getManifestRuleKeyForTest(cachingBuildEngine, rule, buildContext.getEventBus())
                      .get())
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
          Futures.getUnchecked(
              cache.fetchAsync(
                  null,
                  getManifestRuleKeyForTest(cachingBuildEngine, rule, buildContext.getEventBus())
                      .get(),
                  LazyPath.ofInstance(fetchedManifest)));
      assertThat(cacheResult.getType(), equalTo(CacheResultType.HIT));
      manifest = loadManifest(fetchedManifest);
      assertThat(
          ManifestUtil.toMap(manifest),
          equalTo(
              ImmutableMap.of(
                  depFileRuleKey,
                  ImmutableMap.of(input.toString(), fileHashCache.get(filesystem.resolve(input))),
                  new RuleKey("abcd"),
                  ImmutableMap.of("some/path.h", HashCode.fromInt(12)))));

      // Verify that the artifact is also cached via the dep file rule key.
      Path fetchedArtifact = tmp.newFile("artifact");
      cacheResult =
          Futures.getUnchecked(
              cache.fetchAsync(null, depFileRuleKey, LazyPath.ofInstance(fetchedArtifact)));
      assertThat(cacheResult.getType(), equalTo(CacheResultType.HIT));
    }

    @Test
    public void manifestIsTruncatedWhenGrowingPastSizeLimit() throws Exception {
      DefaultDependencyFileRuleKeyFactory depFilefactory =
          new DefaultDependencyFileRuleKeyFactory(
              FIELD_LOADER, fileHashCache, pathResolver, ruleFinder);

      // Use a genrule to produce the input file.
      Genrule genrule =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
              .setOut("input")
              .build(graphBuilder, filesystem);
      Path input =
          pathResolver.getRelativePath(Objects.requireNonNull(genrule.getSourcePathToOutput()));
      filesystem.mkdirs(input.getParent());
      filesystem.writeContentsToPath("contents", input);

      // Create a simple rule which just writes a file.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params = TestBuildRuleParams.create();
      Path output = Paths.get("output");
      DepFileBuildRule rule =
          new DepFileBuildRule(target, filesystem, params) {
            @AddToRuleKey private final SourcePath path = genrule.getSourcePathToOutput();

            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }

            @Override
            public Predicate<SourcePath> getCoveredByDepFilePredicate(
                SourcePathResolver pathResolver) {
              return (SourcePath path) -> true;
            }

            @Override
            public Predicate<SourcePath> getExistenceOfInterestPredicate(
                SourcePathResolver pathResolver) {
              return (SourcePath path) -> false;
            }

            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
                BuildContext context, CellPathResolver cellPathResolver) {
              return ImmutableList.of(PathSourcePath.of(filesystem, input));
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
            }
          };

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setDepFiles(DepFiles.CACHE)
              .setMaxDepFileCacheEntries(1L)
              .setRuleKeyFactories(
                  RuleKeyFactories.of(
                      defaultRuleKeyFactory, inputBasedRuleKeyFactory, depFilefactory))
              .build();

      // Seed the cache with an existing manifest with a dummy entry so that it's already at the max
      // size.
      Manifest manifest =
          ManifestUtil.fromMap(
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
                  getManifestRuleKeyForTest(cachingBuildEngine, rule, buildContext.getEventBus())
                      .get())
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
          Futures.getUnchecked(
              cache.fetchAsync(
                  null,
                  getManifestRuleKeyForTest(cachingBuildEngine, rule, buildContext.getEventBus())
                      .get(),
                  LazyPath.ofInstance(fetchedManifest)));
      assertThat(cacheResult.getType(), equalTo(CacheResultType.HIT));
      manifest = loadManifest(fetchedManifest);
      assertThat(
          ManifestUtil.toMap(manifest),
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
      BuildRuleParams params = TestBuildRuleParams.create();
      SourcePath input =
          PathSourcePath.of(filesystem, filesystem.getRootPath().getFileSystem().getPath("input"));
      filesystem.touch(pathResolver.getRelativePath(input));
      Path output = BuildTargetPaths.getGenPath(filesystem, target, "%s/output");
      DepFileBuildRule rule =
          new DepFileBuildRule(target, filesystem, params) {
            @AddToRuleKey private final SourcePath path = input;

            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              buildableContext.recordArtifact(output);
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }

            @Override
            public Predicate<SourcePath> getCoveredByDepFilePredicate(
                SourcePathResolver pathResolver) {
              return (SourcePath path) -> true;
            }

            @Override
            public Predicate<SourcePath> getExistenceOfInterestPredicate(
                SourcePathResolver pathResolver) {
              return (SourcePath path) -> false;
            }

            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
                BuildContext context, CellPathResolver cellPathResolver) {
              return ImmutableList.of(input);
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
            }
          };

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setDepFiles(DepFiles.CACHE)
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
                  getManifestRuleKeyForTest(cachingBuildEngine, rule, buildContext.getEventBus())
                      .get())
              .build(),
          byteArrayOutputStream.toByteArray());
      Path artifact = tmp.newFile("artifact.zip");
      Path metadataDirectory = BuildInfo.getPathToArtifactMetadataDirectory(target, filesystem);
      writeEntriesToArchive(
          artifact,
          ImmutableMap.of(
              output,
              "stuff",
              metadataDirectory.resolve(BuildInfo.MetadataKey.RECORDED_PATHS),
              ObjectMappers.WRITER.writeValueAsString(ImmutableList.of(output.toString())),
              metadataDirectory.resolve(BuildInfo.MetadataKey.RECORDED_PATH_HASHES),
              ObjectMappers.WRITER.writeValueAsString(
                  ImmutableMap.of(output.toString(), HashCode.fromInt(123).toString())),
              metadataDirectory.resolve(BuildInfo.MetadataKey.OUTPUT_SIZE),
              "123",
              metadataDirectory.resolve(BuildInfo.MetadataKey.OUTPUT_HASH),
              HashCode.fromInt(123).toString()),
          ImmutableList.of());
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
                          .collect(ImmutableList.toImmutableList())))
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
        CacheResult cacheResult =
            Futures.getUnchecked(cache.fetchAsync(null, key, fetchedArtifact));
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
                        .collect(ImmutableList.toImmutableList()))));
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
      BuildRuleParams params = TestBuildRuleParams.create();
      SourcePath input =
          PathSourcePath.of(filesystem, filesystem.getRootPath().getFileSystem().getPath("input"));
      filesystem.touch(pathResolver.getRelativePath(input));
      Path output = Paths.get("output");
      DepFileBuildRule rule =
          new DepFileBuildRule(target, filesystem, params) {
            @AddToRuleKey private final SourcePath path = input;

            @Override
            public ImmutableList<Step> getBuildSteps(
                BuildContext context, BuildableContext buildableContext) {
              return ImmutableList.of(
                  new WriteFileStep(filesystem, "", output, /* executable */ false));
            }

            @Override
            public Predicate<SourcePath> getCoveredByDepFilePredicate(
                SourcePathResolver pathResolver) {
              return (SourcePath path) -> true;
            }

            @Override
            public Predicate<SourcePath> getExistenceOfInterestPredicate(
                SourcePathResolver pathResolver) {
              return (SourcePath path) -> false;
            }

            @Override
            public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
                BuildContext context, CellPathResolver cellPathResolver) {
              return ImmutableList.of(input);
            }

            @Override
            public SourcePath getSourcePathToOutput() {
              return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
            }
          };

      // Create the build engine.
      CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setDepFiles(DepFiles.CACHE)
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
      Path manifestPath = ManifestRuleKeyManagerTestUtil.getManifestPath(rule);
      filesystem.mkdirs(manifestPath.getParent());
      try (OutputStream outputStream = filesystem.newFileOutputStream(manifestPath)) {
        manifest.serialize(outputStream);
      }

      // Run the build.
      BuildResult result =
          cachingBuildEngine
              .build(buildContext, TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertThat(getSuccess(result), equalTo(BuildRuleSuccessType.BUILT_LOCALLY));
      assertManifestLoaded(
          listener
              .getEvents()
              .stream()
              .filter(BuildRuleEvent.Finished.class::isInstance)
              .map(BuildRuleEvent.Finished.class::cast)
              .collect(Collectors.toList()));

      // Verify there's no stale entry in the manifest.
      LazyPath fetchedManifest = LazyPath.ofInstance(tmp.newFile("fetched_artifact.zip"));
      CacheResult cacheResult =
          Futures.getUnchecked(
              cache.fetchAsync(
                  null, depFilefactory.buildManifestKey(rule).getRuleKey(), fetchedManifest));
      assertTrue(cacheResult.getType().isSuccess());
      Manifest cachedManifest = loadManifest(fetchedManifest.get());
      assertThat(
          ManifestUtil.toMap(cachedManifest).keySet(), Matchers.not(hasItem(staleDepFileRuleKey)));
    }

    private void assertManifestLoaded(List<BuildRuleEvent.Finished> events) {
      assertFalse(events.isEmpty());
      events.forEach(
          event -> {
            assertFalse(event.getManifestStoreResult().get().getManifestLoadError().isPresent());
          });
    }
  }

  public static class UncachableRuleTests extends CommonFixture {
    public UncachableRuleTests(MetadataStorage metadataStorage) {
      super(metadataStorage);
    }

    @Test
    public void uncachableRulesDoNotTouchTheCache() throws Exception {
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRuleParams params = TestBuildRuleParams.create();
      BuildRule rule =
          new UncachableRule(target, filesystem, params, ImmutableList.of(), Paths.get("foo.out"));
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
      assertThat(
          "Should not attempt to fetch from cache",
          result.getCacheResult().map(CacheResult::getType),
          equalTo(Optional.of(CacheResultType.IGNORED)));
      assertEquals("should not have written to the cache", 0, cache.getArtifactCount());
    }

    private static class UncachableRule extends RuleWithSteps
        implements SupportsDependencyFileRuleKey {
      public UncachableRule(
          BuildTarget buildTarget,
          ProjectFilesystem projectFilesystem,
          BuildRuleParams buildRuleParams,
          ImmutableList<Step> steps,
          Path output) {
        super(buildTarget, projectFilesystem, buildRuleParams, steps, output);
      }

      @Override
      public boolean useDependencyFileRuleKeys() {
        return true;
      }

      @Override
      public Predicate<SourcePath> getCoveredByDepFilePredicate(SourcePathResolver pathResolver) {
        return (SourcePath path) -> true;
      }

      @Override
      public Predicate<SourcePath> getExistenceOfInterestPredicate(
          SourcePathResolver pathResolver) {
        return (SourcePath path) -> false;
      }

      @Override
      public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
          BuildContext context, CellPathResolver cellPathResolver) {
        return ImmutableList.of();
      }

      @Override
      public boolean isCacheable() {
        return false;
      }
    }
  }

  public static class ScheduleOverrideTests extends CommonFixture {
    public ScheduleOverrideTests(MetadataStorage metadataStorage) {
      super(metadataStorage);
    }

    @Test
    public void customWeights() throws Exception {
      BuildTarget target1 = BuildTargetFactory.newInstance("//:rule1");
      ControlledRule rule1 =
          new ControlledRule(
              target1, filesystem, RuleScheduleInfo.builder().setJobsMultiplier(2).build());
      BuildTarget target2 = BuildTargetFactory.newInstance("//:rule2");
      ControlledRule rule2 =
          new ControlledRule(
              target2, filesystem, RuleScheduleInfo.builder().setJobsMultiplier(2).build());
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

    private class ControlledRule extends AbstractBuildRule implements OverrideScheduleRule {

      private final RuleScheduleInfo ruleScheduleInfo;

      private final Semaphore started = new Semaphore(0);
      private final Semaphore finish = new Semaphore(0);

      private ControlledRule(
          BuildTarget buildTarget,
          ProjectFilesystem projectFilesystem,
          RuleScheduleInfo ruleScheduleInfo) {
        super(buildTarget, projectFilesystem);
        this.ruleScheduleInfo = ruleScheduleInfo;
      }

      @Override
      public SortedSet<BuildRule> getBuildDeps() {
        return ImmutableSortedSet.of();
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
                return StepExecutionResults.SUCCESS;
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

    public BuildRuleEventTests(MetadataStorage metadataStorage) {
      super(metadataStorage);
    }

    @Ignore
    @Test
    public void eventsForBuiltLocallyRuleAreOnCorrectThreads() throws Exception {
      // Create a noop simple rule.
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRule rule = new EmptyBuildRule(target, filesystem);

      // Create the build engine.
      try (CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setExecutorService(SERVICE)
              .setRuleKeyFactories(
                  RuleKeyFactories.of(
                      NOOP_RULE_KEY_FACTORY,
                      NOOP_INPUT_BASED_RULE_KEY_FACTORY,
                      NOOP_DEP_FILE_RULE_KEY_FACTORY))
              .build()) {
        // Run the build.
        BuildResult result =
            cachingBuildEngine
                .build(buildContext, TestExecutionContext.newInstance(), rule)
                .getResult()
                .get();
        assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());
      }
      // Verify that events have correct thread IDs
      assertRelatedBuildRuleEventsOnSameThread(
          FluentIterable.from(listener.getEvents()).filter(BuildRuleEvent.class));
      assertRelatedBuildRuleEventsDuration(
          FluentIterable.from(listener.getEvents()).filter(BuildRuleEvent.class));
    }

    @Test
    public void eventsForMatchingRuleKeyRuleAreOnCorrectThreads() throws Exception {
      // Create a simple rule and set it up so that it has a matching rule key.
      BuildTarget buildTarget = BuildTargetFactory.newInstance("//:rule");
      BuildRule rule = new EmptyBuildRule(buildTarget, filesystem);
      BuildInfoRecorder recorder = createBuildInfoRecorder(rule.getBuildTarget());
      recorder.addBuildMetadata(
          BuildInfo.MetadataKey.RULE_KEY, defaultRuleKeyFactory.build(rule).toString());
      recorder.addMetadata(BuildInfo.MetadataKey.RECORDED_PATHS, ImmutableList.of());
      recorder.writeMetadataToDisk(true);

      // Create the build engine.
      try (CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setExecutorService(SERVICE)
              .setRuleKeyFactories(
                  RuleKeyFactories.of(
                      NOOP_RULE_KEY_FACTORY,
                      NOOP_INPUT_BASED_RULE_KEY_FACTORY,
                      NOOP_DEP_FILE_RULE_KEY_FACTORY))
              .build()) {
        // Run the build.
        BuildResult result =
            cachingBuildEngine
                .build(buildContext, TestExecutionContext.newInstance(), rule)
                .getResult()
                .get();
        assertEquals(BuildRuleSuccessType.MATCHING_RULE_KEY, result.getSuccess());
      }

      // Verify that events have correct thread IDs
      assertRelatedBuildRuleEventsOnSameThread(
          FluentIterable.from(listener.getEvents()).filter(BuildRuleEvent.class));
      assertRelatedBuildRuleEventsDuration(
          FluentIterable.from(listener.getEvents()).filter(BuildRuleEvent.class));
    }

    @Ignore
    @Test
    public void eventsForBuiltLocallyRuleAndDepAreOnCorrectThreads() throws Exception {
      // Create a simple rule and dep.
      BuildTarget depTarget = BuildTargetFactory.newInstance("//:dep");
      BuildRule dep = new EmptyBuildRule(depTarget, filesystem);
      BuildTarget target = BuildTargetFactory.newInstance("//:rule");
      BuildRule rule = new EmptyBuildRule(target, filesystem, dep);

      // Create the build engine.
      try (CachingBuildEngine cachingBuildEngine =
          cachingBuildEngineFactory()
              .setExecutorService(SERVICE)
              .setRuleKeyFactories(
                  RuleKeyFactories.of(
                      NOOP_RULE_KEY_FACTORY,
                      NOOP_INPUT_BASED_RULE_KEY_FACTORY,
                      NOOP_DEP_FILE_RULE_KEY_FACTORY))
              .build()) {
        // Run the build.
        BuildResult result =
            cachingBuildEngine
                .build(buildContext, TestExecutionContext.newInstance(), rule)
                .getResult()
                .get();
        assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result.getSuccess());
      }
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
      BuildRule rule =
          new WriteFile(target, filesystem, "something else", output, /* executable */ false);

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
      BuildRule rule =
          new WriteFile(target, filesystem, "something else", output, /* executable */ false);

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
      BuildRule rule =
          new WriteFile(target, filesystem, "something else", output, /* executable */ false);

      // Run an initial build to seed the cache.
      CachingBuildEngine cachingBuildEngine1 = cachingBuildEngineFactory().build();
      BuildId buildId1 = new BuildId("id1");
      BuildResult result1 =
          cachingBuildEngine1
              .build(buildContext.withBuildId(buildId1), TestExecutionContext.newInstance(), rule)
              .getResult()
              .get();
      assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, result1.getSuccess());

      filesystem.deleteRecursivelyIfExists(Paths.get(""));
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
                .collect(ImmutableList.toImmutableList());
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
      public boolean awaitTermination(long timeout, @Nonnull TimeUnit unit) {
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
      ActionGraphBuilder graphBuilder,
      ImmutableSortedSet<BuildRule> deps,
      List<Step> buildSteps,
      ImmutableList<Step> postBuildSteps,
      @Nullable String pathToOutputFile,
      ImmutableList<Flavor> flavors) {

    BuildTarget buildTarget = BUILD_TARGET.withFlavors(flavors);

    BuildableAbstractCachingBuildRule rule =
        new BuildableAbstractCachingBuildRule(
            buildTarget, filesystem, deps, pathToOutputFile, buildSteps, postBuildSteps);
    graphBuilder.addToIndex(rule);
    return rule;
  }

  private static AbstractCachingBuildRuleWithInputs createInputBasedRule(
      ProjectFilesystem filesystem,
      ActionGraphBuilder graphBuilder,
      ImmutableSortedSet<BuildRule> deps,
      List<Step> buildSteps,
      ImmutableList<Step> postBuildSteps,
      @Nullable String pathToOutputFile,
      ImmutableList<Flavor> flavors,
      ImmutableSortedSet<SourcePath> inputs,
      ImmutableSortedSet<SourcePath> depfileInputs) {
    BuildTarget buildTarget = BUILD_TARGET.withFlavors(flavors);
    AbstractCachingBuildRuleWithInputs rule =
        new AbstractCachingBuildRuleWithInputs(
            buildTarget,
            filesystem,
            pathToOutputFile,
            buildSteps,
            postBuildSteps,
            deps,
            inputs,
            depfileInputs);
    graphBuilder.addToIndex(rule);
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
        Throwables.throwIfUnchecked(Objects.requireNonNull(result.getFailure()));
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

  public static class BuildableAbstractCachingBuildRule extends AbstractBuildRule
      implements HasPostBuildSteps, InitializableFromDisk<Object> {

    private final ImmutableSortedSet<BuildRule> deps;
    private final Path pathToOutputFile;
    private final List<Step> buildSteps;
    private final ImmutableList<Step> postBuildSteps;
    private final BuildOutputInitializer<Object> buildOutputInitializer;

    private BuildableAbstractCachingBuildRule(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        ImmutableSortedSet<BuildRule> deps,
        @Nullable String pathToOutputFile,
        List<Step> buildSteps,
        ImmutableList<Step> postBuildSteps) {
      super(buildTarget, projectFilesystem);
      this.deps = deps;
      this.pathToOutputFile = pathToOutputFile == null ? null : Paths.get(pathToOutputFile);
      this.buildSteps = buildSteps;
      this.postBuildSteps = postBuildSteps;
      this.buildOutputInitializer = new BuildOutputInitializer<>(buildTarget, this);
    }

    @Override
    @Nullable
    public SourcePath getSourcePathToOutput() {
      if (pathToOutputFile == null) {
        return null;
      }
      return ExplicitBuildTargetSourcePath.of(getBuildTarget(), pathToOutputFile);
    }

    @Override
    public SortedSet<BuildRule> getBuildDeps() {
      return deps;
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
    public Object initializeFromDisk(SourcePathResolver pathResolver) {
      return new Object();
    }

    @Override
    public BuildOutputInitializer<Object> getBuildOutputInitializer() {
      return buildOutputInitializer;
    }

    public boolean isInitializedFromDisk() {
      return getBuildOutputInitializer().getBuildOutput() != null;
    }
  }

  public static class AbstractCachingBuildRuleWithInputs extends BuildableAbstractCachingBuildRule
      implements SupportsInputBasedRuleKey, SupportsDependencyFileRuleKey {
    @AddToRuleKey private final ImmutableSortedSet<SourcePath> inputs;
    private final ImmutableSortedSet<SourcePath> depfileInputs;

    public AbstractCachingBuildRuleWithInputs(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        @Nullable String pathToOutputFile,
        List<Step> buildSteps,
        ImmutableList<Step> postBuildSteps,
        ImmutableSortedSet<BuildRule> deps,
        ImmutableSortedSet<SourcePath> inputs,
        ImmutableSortedSet<SourcePath> depfileInputs) {
      super(buildTarget, projectFilesystem, deps, pathToOutputFile, buildSteps, postBuildSteps);
      this.inputs = inputs;
      this.depfileInputs = depfileInputs;
    }

    @Override
    public boolean useDependencyFileRuleKeys() {
      return true;
    }

    @Override
    public Predicate<SourcePath> getCoveredByDepFilePredicate(SourcePathResolver pathResolver) {
      return path -> true;
    }

    @Override
    public Predicate<SourcePath> getExistenceOfInterestPredicate(SourcePathResolver pathResolver) {
      return path -> false;
    }

    @Override
    public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
        BuildContext context, CellPathResolver cellPathResolver) {
      return ImmutableList.copyOf(depfileInputs);
    }
  }

  /**
   * Implementation of {@link ArtifactCache} that, when its fetch method is called, takes the
   * location of requested {@link File} and writes an archive file there with the entries specified
   * to its constructor.
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
    public ListenableFuture<CacheResult> fetchAsync(
        BuildTarget target, RuleKey ruleKey, LazyPath output) {
      try {
        writeEntriesToArchive(
            output.get(), ImmutableMap.copyOf(desiredEntries), ImmutableList.of());
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      return Futures.immediateFuture(
          CacheResult.hit("dir", ArtifactCacheMode.dir).withMetadata(metadata));
    }

    @Override
    public void skipPendingAndFutureAsyncFetches() {
      // Async requests are not supported by FakeArtifactCacheThatWritesAZipFile, so do nothing
    }

    @Override
    public ListenableFuture<Void> store(ArtifactInfo info, BorrowablePath output) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ListenableFuture<Void> store(
        ImmutableList<Pair<ArtifactInfo, BorrowablePath>> artifacts) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ListenableFuture<ImmutableMap<RuleKey, CacheResult>> multiContainsAsync(
        ImmutableSet<RuleKey> ruleKeys) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ListenableFuture<CacheDeleteResult> deleteAsync(List<RuleKey> ruleKeys) {
      throw new RuntimeException("Delete operation is not supported");
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
        BuildTarget target, ProjectFilesystem filesystem, BuildRule... runtimeDeps) {
      super(target, filesystem);
      this.runtimeDeps = ImmutableSortedSet.copyOf(runtimeDeps);
    }

    @Override
    public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
      return runtimeDeps.stream().map(BuildRule::getBuildTarget);
    }
  }

  private abstract static class InputRuleKeyBuildRule
      extends AbstractBuildRuleWithDeclaredAndExtraDeps implements SupportsInputBasedRuleKey {
    public InputRuleKeyBuildRule(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams buildRuleParams) {
      super(buildTarget, projectFilesystem, buildRuleParams);
    }
  }

  private abstract static class DepFileBuildRule extends AbstractBuildRuleWithDeclaredAndExtraDeps
      implements SupportsDependencyFileRuleKey {
    public DepFileBuildRule(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams buildRuleParams) {
      super(buildTarget, projectFilesystem, buildRuleParams);
    }

    @Override
    public boolean useDependencyFileRuleKeys() {
      return true;
    }
  }

  private static class RuleWithSteps extends AbstractBuildRuleWithDeclaredAndExtraDeps {

    private final ImmutableList<Step> steps;
    @Nullable private final Path output;

    public RuleWithSteps(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams buildRuleParams,
        ImmutableList<Step> steps,
        @Nullable Path output) {
      super(buildTarget, projectFilesystem, buildRuleParams);
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
      return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
    }
  }

  private static class SleepStep extends AbstractExecutionStep {

    private final long millis;

    public SleepStep(long millis) {
      super(String.format("sleep %sms", millis));
      this.millis = millis;
    }

    @Override
    public StepExecutionResult execute(ExecutionContext context) throws InterruptedException {
      Thread.sleep(millis);
      return StepExecutionResults.SUCCESS;
    }
  }

  private static class FailingStep extends AbstractExecutionStep {

    public FailingStep() {
      super("failing step");
    }

    @Override
    public StepExecutionResult execute(ExecutionContext context) {
      return StepExecutionResults.ERROR;
    }
  }

  private static void writeEntriesToArchive(
      Path file, ImmutableMap<Path, String> entries, ImmutableList<Path> directories)
      throws IOException {
    try (OutputStream o = new BufferedOutputStream(Files.newOutputStream(file));
        OutputStream z = new ZstdCompressorOutputStream(o);
        TarArchiveOutputStream archive = new TarArchiveOutputStream(z)) {
      archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
      for (Map.Entry<Path, String> mapEntry : entries.entrySet()) {
        TarArchiveEntry e = new TarArchiveEntry(mapEntry.getKey().toString());
        e.setModTime(ZipConstants.getFakeTime());
        byte[] bytes = mapEntry.getValue().getBytes(UTF_8);
        e.setSize(bytes.length);
        archive.putArchiveEntry(e);
        archive.write(bytes);
        archive.closeArchiveEntry();
      }
      for (Path dir : directories) {
        TarArchiveEntry e = new TarArchiveEntry(dir.toString() + "/");
        e.setModTime(ZipConstants.getFakeTime());
      }
      archive.finish();
    }
  }

  private static class EmptyBuildRule extends AbstractBuildRule {

    private final ImmutableSortedSet<BuildRule> deps;

    public EmptyBuildRule(
        BuildTarget buildTarget, ProjectFilesystem projectFilesystem, BuildRule... deps) {
      super(buildTarget, projectFilesystem);
      this.deps = ImmutableSortedSet.copyOf(deps);
    }

    @Override
    public SortedSet<BuildRule> getBuildDeps() {
      return deps;
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
