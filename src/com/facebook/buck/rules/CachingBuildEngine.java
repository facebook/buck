/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.event.ArtifactCompressionEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.ThrowableConsoleEvent;
import com.facebook.buck.io.BorrowablePath;
import com.facebook.buck.io.LazyPath;
import com.facebook.buck.io.MoreFiles;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.keys.AbiRule;
import com.facebook.buck.rules.keys.AbiRuleKeyBuilderFactory;
import com.facebook.buck.rules.keys.DefaultDependencyFileRuleKeyBuilderFactory;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.rules.keys.DependencyFileEntry;
import com.facebook.buck.rules.keys.DependencyFileRuleKeyBuilderFactory;
import com.facebook.buck.rules.keys.InputBasedRuleKeyBuilderFactory;
import com.facebook.buck.rules.keys.SupportsDependencyFileRuleKey;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreFunctions;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.facebook.buck.util.concurrent.MoreFutures;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.facebook.buck.zip.Unzip;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Atomics;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A build engine used to build a {@link BuildRule} which also caches the results. If the current
 * {@link RuleKey} of the build rules matches the one on disk, it does not do any work. It also
 * tries to fetch its output from an {@link ArtifactCache} to avoid doing any computation.
 */
public class CachingBuildEngine implements BuildEngine {

  // The default weight to use in the executor when building a rule locally.
  private static final int DEFAULT_BUILD_WEIGHT = 1;
  @VisibleForTesting
  static final int MAX_TEST_NETWORK_THREADS = 5;

  private static final Logger LOG = Logger.get(CachingBuildEngine.class);

  /**
   * These are the values returned by {@link #build(BuildContext, BuildRule)}.
   * This must always return the same value for the build of each target.
   */
  private final ConcurrentMap<BuildTarget, ListenableFuture<BuildResult>> results =
      Maps.newConcurrentMap();

  private final ConcurrentMap<BuildTarget, ListenableFuture<RuleKey>> ruleKeys =
      Maps.newConcurrentMap();

  private final RuleDepsCache ruleDeps;
  private final Optional<UnskippedRulesTracker> unskippedRulesTracker;

  @Nullable
  private volatile Throwable firstFailure = null;

  private final WeightedListeningExecutorService service;
  private final BuildMode buildMode;
  private final DependencySchedulingOrder dependencySchedulingOrder;
  private final DepFiles depFiles;
  private final long maxDepFileCacheEntries;
  private final ObjectMapper objectMapper;
  private final SourcePathResolver pathResolver;
  private final Optional<Long> artifactCacheSizeLimit;
  private final LoadingCache<ProjectFilesystem, FileHashCache> fileHashCaches;
  private final LoadingCache<ProjectFilesystem, RuleKeyFactories> ruleKeyFactories;
  private final ListeningExecutorService networkExecutor;

  public CachingBuildEngine(
      WeightedListeningExecutorService service,
      final FileHashCache fileHashCache,
      BuildMode buildMode,
      DependencySchedulingOrder dependencySchedulingOrder,
      DepFiles depFiles,
      long maxDepFileCacheEntries,
      Optional<Long> artifactCacheSizeLimit,
      ObjectMapper objectMapper,
      final BuildRuleResolver resolver,
      final ListeningExecutorService networkExecutor,
      final int keySeed) {
    this.ruleDeps = new RuleDepsCache(service);
    this.unskippedRulesTracker = createUnskippedRulesTracker(buildMode, ruleDeps, service);

    this.service = service;
    this.buildMode = buildMode;
    this.dependencySchedulingOrder = dependencySchedulingOrder;
    this.depFiles = depFiles;
    this.maxDepFileCacheEntries = maxDepFileCacheEntries;
    this.artifactCacheSizeLimit = artifactCacheSizeLimit;
    this.objectMapper = objectMapper;
    this.pathResolver = new SourcePathResolver(resolver);

    this.fileHashCaches = createFileHashCacheLoader(fileHashCache);
    this.ruleKeyFactories = CacheBuilder.newBuilder()
        .build(new CacheLoader<ProjectFilesystem, RuleKeyFactories>() {
          @Override
          public RuleKeyFactories load(@Nonnull  ProjectFilesystem filesystem) throws Exception {
            return RuleKeyFactories.build(keySeed, fileHashCaches.get(filesystem), resolver);
          }
        });
    this.networkExecutor = networkExecutor;
  }

  /**
   * This constructor MUST ONLY BE USED FOR TESTS.
   */
  @VisibleForTesting
  CachingBuildEngine(
      WeightedListeningExecutorService service,
      FileHashCache fileHashCache,
      BuildMode buildMode,
      DependencySchedulingOrder dependencySchedulingOrder,
      DepFiles depFiles,
      long maxDepFileCacheEntries,
      Optional<Long> artifactCacheSizeLimit,
      SourcePathResolver pathResolver,
      final Function<? super ProjectFilesystem, RuleKeyFactories> ruleKeyFactoriesFunction) {
    this.ruleDeps = new RuleDepsCache(service);
    this.unskippedRulesTracker = createUnskippedRulesTracker(buildMode, ruleDeps, service);

    this.service = service;
    this.buildMode = buildMode;
    this.dependencySchedulingOrder = dependencySchedulingOrder;
    this.depFiles = depFiles;
    this.maxDepFileCacheEntries = maxDepFileCacheEntries;
    this.artifactCacheSizeLimit = artifactCacheSizeLimit;
    this.objectMapper = ObjectMappers.newDefaultInstance();
    this.pathResolver = pathResolver;

    this.fileHashCaches = createFileHashCacheLoader(fileHashCache);
    this.ruleKeyFactories = CacheBuilder.newBuilder()
        .build(new CacheLoader<ProjectFilesystem, RuleKeyFactories>() {
          @Override
          public RuleKeyFactories load(@Nonnull  ProjectFilesystem filesystem) throws Exception {
            return ruleKeyFactoriesFunction.apply(filesystem);
          }
        });

    ThreadPoolExecutor networkExecutor = new ThreadPoolExecutor(
        /* corePoolSize */ MAX_TEST_NETWORK_THREADS,
        /* maximumPoolSize */ MAX_TEST_NETWORK_THREADS,
        /* keepAliveTime */ 500L, TimeUnit.MILLISECONDS,
        /* workQueue */ new LinkedBlockingQueue<Runnable>(MAX_TEST_NETWORK_THREADS),
        /* threadFactory */ new ThreadFactoryBuilder()
        .setNameFormat("Network Test I/O" + "-%d")
        .build(),
        /* handler */ new ThreadPoolExecutor.CallerRunsPolicy());
    networkExecutor.allowCoreThreadTimeOut(true);
    this.networkExecutor = MoreExecutors.listeningDecorator(networkExecutor);
  }

  private static Optional<UnskippedRulesTracker> createUnskippedRulesTracker(
      BuildMode buildMode,
      RuleDepsCache ruleDeps,
      ListeningExecutorService service) {
    if (buildMode == BuildMode.DEEP || buildMode == BuildMode.POPULATE_FROM_REMOTE_CACHE) {
      // Those modes never skip rules, there is no need to track unskipped rules.
      return Optional.absent();
    }
    return Optional.of(new UnskippedRulesTracker(ruleDeps, service));
  }

  private static LoadingCache<ProjectFilesystem, FileHashCache> createFileHashCacheLoader(
      final FileHashCache defaultCache) {
    return CacheBuilder.newBuilder()
        .build(new CacheLoader<ProjectFilesystem, FileHashCache>() {
          @Override
          public FileHashCache load(@Nonnull  ProjectFilesystem filesystem) {
            FileHashCache cellCache = new DefaultFileHashCache(filesystem);
            FileHashCache buckOutCache = new DefaultFileHashCache(
                new ProjectFilesystem(
                    filesystem.getRootPath(),
                    Optional.of(ImmutableSet.of(filesystem.getBuckPaths().getBuckOut())),
                    ImmutableSet.<ProjectFilesystem.PathOrGlobMatcher>of()));
            return new StackedFileHashCache(
                ImmutableList.of(defaultCache, cellCache, buckOutCache));
          }
        });
  }

  @VisibleForTesting
  void setBuildRuleResult(
      BuildRule buildRule,
      BuildRuleSuccessType success,
      CacheResult cacheResult) {
    results.put(
        buildRule.getBuildTarget(),
        Futures.immediateFuture(BuildResult.success(buildRule, success, cacheResult)));
  }

  @Override
  public boolean isRuleBuilt(BuildTarget buildTarget) throws InterruptedException {
    ListenableFuture<BuildResult> resultFuture = results.get(buildTarget);
    return resultFuture != null && MoreFutures.isSuccess(resultFuture);
  }

  @Nullable
  @Override
  public RuleKey getRuleKey(BuildTarget buildTarget) {
    return Futures.getUnchecked(ruleKeys.get(buildTarget));
  }

  // Dispatch and return a future resolving to a list of all results of this rules dependencies.
  private ListenableFuture<List<BuildResult>> getDepResults(
      BuildRule rule,
      BuildContext context,
      ConcurrentLinkedQueue<ListenableFuture<Void>> asyncCallbacks) {
    List<ListenableFuture<BuildResult>> depResults =
        Lists.newArrayListWithExpectedSize(rule.getDeps().size());
    Iterable<BuildRule> deps = rule.getDeps();
    switch (dependencySchedulingOrder) {
      case SORTED:
        deps = ImmutableSortedSet.copyOf(deps);
        break;
      case RANDOM:
        deps = shuffled(deps);
        break;
    }
    for (BuildRule dep : deps) {
      depResults.add(getBuildRuleResultWithRuntimeDeps(dep, context, asyncCallbacks));
    }
    return Futures.allAsList(depResults);
  }

  private static List<BuildRule> shuffled(Iterable<BuildRule> rules) {
    ArrayList<BuildRule> rulesList = Lists.newArrayList(rules);
    Collections.shuffle(rulesList);
    return rulesList;
  }

  private AsyncFunction<List<BuildResult>, List<BuildResult>> buildDependencies(
      final BuildRule rule,
      final BuildContext context) {
    return new AsyncFunction<List<BuildResult>, List<BuildResult>>() {
      @Override
      public ListenableFuture<List<BuildResult>> apply(List<BuildResult> input) {
        return Futures.transform(
            markRuleAsUsed(rule, context.getEventBus()),
            Functions.constant(input));
      }
    };
  }

  private AsyncFunction<Optional<BuildResult>, BuildResult> buildLocally(
      final BuildRule rule,
      final BuildContext context,
      final RuleKeyFactories ruleKeyFactory,
      final BuildableContext buildableContext,
      final ListenableFuture<CacheResult> cacheResultFuture) {
    return new AsyncFunction<Optional<BuildResult>, BuildResult>() {
      @Override
      public ListenableFuture<BuildResult> apply(Optional<BuildResult> result) {

        // If we already got a result checking the caches, then we don't
        // build locally.
        if (result.isPresent()) {
          return Futures.immediateFuture(result.get());
        }

        // Otherwise, build the rule.  We re-submit via the service so that we schedule
        // it with the custom weight assigned to this rules steps.
        RuleScheduleInfo ruleScheduleInfo = getRuleScheduleInfo(rule);
        return service.submit(
            new Callable<BuildResult>() {
              @Override
              public BuildResult call() throws Exception {
                if (!context.isKeepGoing() && firstFailure != null) {
                  return BuildResult.canceled(rule, firstFailure);
                }
                try (BuildRuleEvent.Scope scope = BuildRuleEvent.resumeSuspendScope(
                    context.getEventBus(),
                    rule,
                    ruleKeyFactory.defaultRuleKeyBuilderFactory)) {
                  executeCommandsNowThatDepsAreBuilt(rule, context, buildableContext);
                  return BuildResult.success(
                      rule,
                      BuildRuleSuccessType.BUILT_LOCALLY,
                      cacheResultFuture.get());
                }
              }
            },
            DEFAULT_BUILD_WEIGHT * ruleScheduleInfo.getJobsMultiplier());
      }
    };
  }

  private AsyncFunction<List<BuildResult>, Optional<BuildResult>> checkCaches(
      final BuildRule rule,
      final BuildContext context,
      final OnDiskBuildInfo onDiskBuildInfo,
      final BuildInfoRecorder buildInfoRecorder,
      final RuleKeyFactories ruleKeyFactory) {
    return new AsyncFunction<List<BuildResult>, Optional<BuildResult>>() {
      @Override
      public ListenableFuture<Optional<BuildResult>> apply(List<BuildResult> depResults)
          throws InterruptedException, IOException, ExecutionException {
        // If any dependency wasn't successful, cancel ourselves.
        for (BuildResult depResult : depResults) {
          if (buildMode != BuildMode.POPULATE_FROM_REMOTE_CACHE &&
              depResult.getStatus() != BuildRuleStatus.SUCCESS) {
            return Futures.immediateFuture(Optional.of(
                BuildResult.canceled(
                rule,
                Preconditions.checkNotNull(depResult.getFailure()))));
          }
        }

        // If we've already seen a failure, exit early.
        if (buildMode != BuildMode.POPULATE_FROM_REMOTE_CACHE &&
            !context.isKeepGoing() &&
            firstFailure != null) {
          return Futures.immediateFuture(Optional.of(BuildResult.canceled(rule, firstFailure)));
        }

        // Dep-file rule keys.
        if (useDependencyFileRuleKey(rule)) {
          // Try to get the current dep-file rule key.
          Optional<Pair<RuleKey, ImmutableSet<SourcePath>>> depFileRuleKeyAndInputs =
              calculateDepFileRuleKey(
                  rule,
                  onDiskBuildInfo.getValues(BuildInfo.METADATA_KEY_FOR_DEP_FILE),
                          /* allowMissingInputs */ true);
          if (depFileRuleKeyAndInputs.isPresent()) {

            RuleKey depFileRuleKey = depFileRuleKeyAndInputs.get().getFirst();

            // Check the input-based rule key says we're already built.
            Optional<RuleKey> lastDepFileRuleKey =
                onDiskBuildInfo.getRuleKey(BuildInfo.METADATA_KEY_FOR_DEP_FILE_RULE_KEY);
            if (lastDepFileRuleKey.isPresent() &&
                depFileRuleKey.equals(lastDepFileRuleKey.get())) {
              return Futures.immediateFuture(Optional.of(
                  BuildResult.success(
                      rule,
                      BuildRuleSuccessType.MATCHING_DEP_FILE_RULE_KEY,
                      CacheResult.localKeyUnchangedHit())));
            }
          }
        }

        // Manifest caching
        if (useManifestCaching(rule)) {
          Optional<Pair<RuleKey, ImmutableSet<SourcePath>>> manifestKey =
              ruleKeyFactories.getUnchecked(rule.getProjectFilesystem())
                  .depFileRuleKeyBuilderFactory.buildManifestKey(rule);

          if (manifestKey.isPresent()) {
            // Perform a manifest base cache lookup.
            CacheResult cacheResult =
                performManifestBasedCacheFetch(
                    rule,
                    context,
                    manifestKey.get(),
                    buildInfoRecorder).get();
            if (cacheResult.getType().isSuccess()) {
              return Futures.immediateFuture(
                  Optional.of(
                      BuildResult.success(
                          rule,
                          BuildRuleSuccessType.FETCHED_FROM_CACHE_MANIFEST_BASED,
                          cacheResult)));
            }
          }
        }

        // Input-based rule keys.
        if (rule instanceof SupportsInputBasedRuleKey) {
          // Calculate the input-based rule key and record it in the metadata.
          Optional<RuleKey> inputRuleKey = ruleKeyFactory
              .inputBasedRuleKeyBuilderFactory
              .build(rule);
          if (inputRuleKey.isPresent()) {
            buildInfoRecorder.addBuildMetadata(
                BuildInfo.METADATA_KEY_FOR_INPUT_BASED_RULE_KEY,
                inputRuleKey.get().toString());

            // Check the input-based rule key says we're already built.
            Optional<RuleKey> lastInputRuleKey = onDiskBuildInfo.getRuleKey(
                BuildInfo.METADATA_KEY_FOR_INPUT_BASED_RULE_KEY);
            if (inputRuleKey.get().equals(lastInputRuleKey.orNull())) {
              return Futures.immediateFuture(
                  Optional.of(
                      BuildResult.success(
                          rule,
                          BuildRuleSuccessType.MATCHING_INPUT_BASED_RULE_KEY,
                          CacheResult.localKeyUnchangedHit())));
            }

            // Try to fetch the artifact using the input-based rule key.
            CacheResult cacheResult =
                tryToFetchArtifactFromBuildCacheAndOverlayOnTopOfProjectFilesystem(
                    rule,
                    inputRuleKey.get(),
                    buildInfoRecorder,
                    context.getArtifactCache(),
                    // TODO(shs96c): Share this between all tests, not one per cell.
                    rule.getProjectFilesystem(),
                    context).get();
            if (cacheResult.getType().isSuccess()) {
              return Futures.immediateFuture(
                  Optional.of(
                      BuildResult.success(
                          rule,
                          BuildRuleSuccessType.FETCHED_FROM_CACHE_INPUT_BASED,
                          cacheResult)));
            }
          }
        }

        // ABI check:
        // Deciding whether we need to rebuild is tricky business. We want to rebuild as
        // little as possible while always being sound.
        //
        // For java_library rules that depend only on their first-order deps,
        // they only need to rebuild themselves if any of the following conditions hold:
        // (1) The definition of the build rule has changed.
        // (2) Any of the input files (which includes resources as well as .java files)
        //     have changed.
        // (3) The ABI of any of its dependent java_library rules has changed.
        //
        // For other types of build rules, we have to be more conservative when rebuilding
        // In those cases, we rebuild if any of the following conditions hold:
        // (1) The definition of the build rule has changed.
        // (2) Any of the input files have changed.
        // (3) Any of the RuleKeys of this rule's deps have changed.
        //
        // Because a RuleKey for a rule will change if any of its transitive deps have
        // changed, that means a change in one of the leaves can result in almost all
        // rules being rebuilt, which is slow. Fortunately, we limit the effects of this
        // when building Java code when checking the ABI of deps instead of the RuleKey
        // for deps.
        if (rule instanceof AbiRule) {
          RuleKey abiRuleKey = ruleKeyFactory.abiRuleKeyBuilderFactory.build(rule);
          buildInfoRecorder.addBuildMetadata(
              BuildInfo.METADATA_KEY_FOR_ABI_RULE_KEY,
              abiRuleKey.toString());

          Optional<RuleKey> lastAbiRuleKey =
              onDiskBuildInfo.getRuleKey(BuildInfo.METADATA_KEY_FOR_ABI_RULE_KEY);
          if (abiRuleKey.equals(lastAbiRuleKey.orNull())) {
            return Futures.immediateFuture(
                Optional.of(
                    BuildResult.success(
                        rule,
                        BuildRuleSuccessType.MATCHING_ABI_RULE_KEY,
                        CacheResult.localKeyUnchangedHit())));
          }
        }

        // Cache lookups failed, so if we're just trying to populate, we've failed.
        if (buildMode == BuildMode.POPULATE_FROM_REMOTE_CACHE) {
          LOG.info("Cannot populate cache for " +
              rule.getBuildTarget().getFullyQualifiedName());
          return Futures.immediateFuture(
              Optional.of(
                  BuildResult.canceled(
                      rule,
                      new HumanReadableException(
                          "Skipping %s: in cache population mode local builds are " +
                              "disabled",
                          rule))));
        }

        return Futures.immediateFuture(Optional.<BuildResult>absent());
      }
    };
  }

  private ListenableFuture<BuildResult> processBuildRule(
      final BuildRule rule,
      final BuildContext context,
      final OnDiskBuildInfo onDiskBuildInfo,
      final BuildInfoRecorder buildInfoRecorder,
      final BuildableContext buildableContext,
      final ConcurrentLinkedQueue<ListenableFuture<Void>> asyncCallbacks)
      throws InterruptedException {

    // If we've already seen a failure, exit early.
    if (!context.isKeepGoing() && firstFailure != null) {
      return Futures.immediateFuture(BuildResult.canceled(rule, firstFailure));
    }

    final RuleKeyFactories ruleKeyFactory =
        ruleKeyFactories.getUnchecked(rule.getProjectFilesystem());
    final ListenableFuture<CacheResult> cacheResultFuture;

    try (BuildRuleEvent.Scope scope =
             BuildRuleEvent.resumeSuspendScope(
                 context.getEventBus(),
                 rule,
                 ruleKeyFactory.defaultRuleKeyBuilderFactory)) {

      // 1. Check if it's already built.
      Optional<RuleKey> cachedRuleKey =
          onDiskBuildInfo.getRuleKey(BuildInfo.METADATA_KEY_FOR_RULE_KEY);
      if (ruleKeyFactory.defaultRuleKeyBuilderFactory.build(rule).equals(cachedRuleKey.orNull())) {
        return Futures.transform(
            markRuleAsUsed(rule, context.getEventBus()),
            Functions.constant(
                BuildResult.success(
                    rule,
                    BuildRuleSuccessType.MATCHING_RULE_KEY,
                    CacheResult.localKeyUnchangedHit())));
      }

      // 2. Rule key cache lookup.
      cacheResultFuture = tryToFetchArtifactFromBuildCacheAndOverlayOnTopOfProjectFilesystem(
          rule,
          ruleKeyFactory.defaultRuleKeyBuilderFactory.build(rule),
          buildInfoRecorder,
          context.getArtifactCache(),
          // TODO(shs96c): This should be a shared between all tests, not one per cell
          rule.getProjectFilesystem(),
          context);

      return Futures.transformAsync(
          cacheResultFuture,
          new AsyncFunction<CacheResult, BuildResult>() {
            @Override
            public ListenableFuture<BuildResult> apply(CacheResult cacheResult) {
              if (cacheResult.getType().isSuccess()) {
                return Futures.transform(
                    markRuleAsUsed(rule, context.getEventBus()), Functions.constant(
                        BuildResult.success(
                            rule,
                            BuildRuleSuccessType.FETCHED_FROM_CACHE,
                            cacheResult)));
              }

              // 3. Build deps.
              ListenableFuture<List<BuildResult>> getDepResults =
                  Futures.transformAsync(
                      getDepResults(rule, context, asyncCallbacks),
                      buildDependencies(rule, context),
                      service);

              // 4. Return to the current rule and check caches to see if we can avoid building
              // locally.
              AsyncFunction<List<BuildResult>, Optional<BuildResult>> checkCachesCallback =
                  checkCaches(rule, context, onDiskBuildInfo, buildInfoRecorder, ruleKeyFactory);

              ListenableFuture<Optional<BuildResult>> checkCachesResult =
                  Futures.transformAsync(
                      getDepResults,
                      ruleAsyncFunction(rule, context, checkCachesCallback),
                      service);

              // 5. Build the current rule locally, if we have to.
              return Futures.transformAsync(
                  checkCachesResult,
                  buildLocally(rule, context, ruleKeyFactory, buildableContext, cacheResultFuture),
                  service);
            }
      },
      service);
    }
  }

  private ListenableFuture<BuildResult> processBuildRule(
      final BuildRule rule,
      final BuildContext context,
      ConcurrentLinkedQueue<ListenableFuture<Void>> asyncCallbacks)
      throws InterruptedException, ExecutionException {

    final RuleKeyFactories keyFactories =
        ruleKeyFactories.getUnchecked(rule.getProjectFilesystem());
    final FileHashCache fileHashCache = fileHashCaches.getUnchecked(rule.getProjectFilesystem());
    final OnDiskBuildInfo onDiskBuildInfo =
        context.createOnDiskBuildInfoFor(
            rule.getBuildTarget(),
            rule.getProjectFilesystem());
    final BuildInfoRecorder buildInfoRecorder =
        context.createBuildInfoRecorder(rule.getBuildTarget(), rule.getProjectFilesystem())
            .addBuildMetadata(
                BuildInfo.METADATA_KEY_FOR_RULE_KEY,
                keyFactories.defaultRuleKeyBuilderFactory.build(rule).toString());
    final BuildableContext buildableContext = new DefaultBuildableContext(buildInfoRecorder);
    final AtomicReference<Long> outputSize = Atomics.newReference();

    ListenableFuture<BuildResult> buildResult =
          processBuildRule(
              rule,
              context,
              onDiskBuildInfo,
              buildInfoRecorder,
              buildableContext,
              asyncCallbacks);

    // If we're performing a deep build, guarantee that all dependencies will *always* get
    // materialized locally by chaining up to our result future.
    if (buildMode == BuildMode.DEEP || buildMode == BuildMode.POPULATE_FROM_REMOTE_CACHE) {
      buildResult =
          MoreFutures.chainExceptions(
              getDepResults(rule, context, asyncCallbacks),
              buildResult);
    }

    // Setup a callback to handle either the cached or built locally cases.
    AsyncFunction<BuildResult, BuildResult> callback =
        new AsyncFunction<BuildResult, BuildResult>() {
          @Override
          public ListenableFuture<BuildResult> apply(BuildResult input) throws Exception {

            // If we weren't successful, exit now.
            if (input.getStatus() != BuildRuleStatus.SUCCESS) {
              return Futures.immediateFuture(input);
            }

            // We shouldn't see any build fail result at this point.
            BuildRuleSuccessType success = Preconditions.checkNotNull(input.getSuccess());

            // If we didn't build the rule locally, reload the recorded paths from the build
            // metadata.
            if (success != BuildRuleSuccessType.BUILT_LOCALLY) {
              for (String str :
                   onDiskBuildInfo.getValues(BuildInfo.METADATA_KEY_FOR_RECORDED_PATHS).get()) {
                buildInfoRecorder.recordArtifact(Paths.get(str));
              }
            }

            // Try get the output size now that all outputs have been recorded.
            outputSize.set(buildInfoRecorder.getOutputSize());

            // If the success type means the rule has potentially changed it's outputs...
            if (success.outputsHaveChanged()) {

              // The build has succeeded, whether we've fetched from cache, or built locally.
              // So run the post-build steps.
              if (rule instanceof HasPostBuildSteps) {
                executePostBuildSteps(
                    rule,
                    ((HasPostBuildSteps) rule).getPostBuildSteps(context, buildableContext),
                    context);
              }

              // Invalidate any cached hashes for the output paths, since we've updated them.
              FileHashCache fileHashCache = fileHashCaches.get(rule.getProjectFilesystem());
              for (Path path : buildInfoRecorder.getRecordedPaths()) {
                fileHashCache.invalidate(rule.getProjectFilesystem().resolve(path));
              }
            }

            // If this rule uses dep files and we built locally, make sure we store the new dep file
            // list and re-calculate the dep file rule key.
            if (useDependencyFileRuleKey(rule) && success == BuildRuleSuccessType.BUILT_LOCALLY) {

              // Query the rule for the actual inputs it used.
              ImmutableList<SourcePath> inputs =
                  ((SupportsDependencyFileRuleKey) rule).getInputsAfterBuildingLocally();

              // Record the inputs into our metadata for next time.
              // TODO(#9117006): We don't support a way to serlialize `SourcePath`s to the cache,
              // so need to use DependencyFileEntry's instead and recover them on deserialization.
              ImmutableList<String> inputStrings =
                  FluentIterable.from(inputs)
                      .transform(DependencyFileEntry.fromSourcePathFunction(pathResolver))
                      .transform(MoreFunctions.toJsonFunction(objectMapper))
                      .toList();
              buildInfoRecorder.addMetadata(
                  BuildInfo.METADATA_KEY_FOR_DEP_FILE,
                  inputStrings);

              // Re-calculate and store the depfile rule key for next time.
              Optional<Pair<RuleKey, ImmutableSet<SourcePath>>> depFileRuleKeyAndInputs =
                  calculateDepFileRuleKey(
                      rule,
                      Optional.of(inputStrings),
                      /* allowMissingInputs */ false);
              if (depFileRuleKeyAndInputs.isPresent()) {
                RuleKey depFileRuleKey = depFileRuleKeyAndInputs.get().getFirst();
                buildInfoRecorder.addBuildMetadata(
                    BuildInfo.METADATA_KEY_FOR_DEP_FILE_RULE_KEY,
                    depFileRuleKey.toString());

                // Push an updated manifest to the cache.
                if (useManifestCaching(rule)) {
                  Optional<Pair<RuleKey, ImmutableSet<SourcePath>>> manifestKey =
                      ruleKeyFactories.getUnchecked(rule.getProjectFilesystem())
                          .depFileRuleKeyBuilderFactory.buildManifestKey(rule);
                  if (manifestKey.isPresent()) {
                    updateAndStoreManifest(
                        rule,
                        depFileRuleKeyAndInputs.get().getFirst(),
                        depFileRuleKeyAndInputs.get().getSecond(),
                        manifestKey.get(),
                        context.getArtifactCache());
                  }
                }
              }
            }

            // If this rule was built locally, grab and record the output hashes in the build
            // metadata so that cache hits avoid re-hashing file contents.  Since we use output
            // hashes for input-based rule keys and for detecting non-determinism, we would spend
            // a lot of time re-hashing output paths -- potentially in serialized in a single step.
            // So, do the hashing here to distribute the workload across several threads and cache
            // the results.
            //
            // Also, since hashing outputs can potentially be expensive, we avoid doing this for
            // rules that are marked as uncacheable.  The rationale here is that they are likely not
            // cached due to the sheer size which would be costly to hash or builtin non-determinism
            // in the rule which somewhat defeats the purpose of logging the hash.
            if (success == BuildRuleSuccessType.BUILT_LOCALLY &&
                shouldUploadToCache(rule, Preconditions.checkNotNull(outputSize.get()))) {
              ImmutableSortedMap.Builder<String, String> outputHashes =
                  ImmutableSortedMap.naturalOrder();
              for (Path path : buildInfoRecorder.getOutputPaths()) {
                outputHashes.put(
                    path.toString(),
                    fileHashCache.get(rule.getProjectFilesystem().resolve(path)).toString());
              }
              buildInfoRecorder.addBuildMetadata(
                  BuildInfo.METADATA_KEY_FOR_RECORDED_PATH_HASHES,
                  outputHashes.build());
            }

            // If this rule was fetched from cache, seed the file hash cache with the recorded
            // output hashes from the build metadata.  Since outputs which have been changed have
            // already been invalidated above, this is purely a best-effort optimization -- if the
            // the output hashes weren't recorded in the cache we do nothing.
            if (success != BuildRuleSuccessType.BUILT_LOCALLY && success.outputsHaveChanged()) {
              Optional<ImmutableMap<String, String>> hashes =
                  onDiskBuildInfo.getMap(BuildInfo.METADATA_KEY_FOR_RECORDED_PATH_HASHES);
              if (hashes.isPresent()) {
                for (Map.Entry<String, String> ent : hashes.get().entrySet()) {
                  Path path =
                      rule.getProjectFilesystem().getRootPath().getFileSystem()
                          .getPath(ent.getKey());
                  HashCode hashCode = HashCode.fromString(ent.getValue());
                  fileHashCache.set(rule.getProjectFilesystem().resolve(path), hashCode);
                }
              }
            }

            // Make sure that all of the local files have the same values they would as if the
            // rule had been built locally.
            buildInfoRecorder.addBuildMetadata(
                BuildInfo.METADATA_KEY_FOR_TARGET,
                rule.getBuildTarget().toString());
            buildInfoRecorder.addMetadata(
                BuildInfo.METADATA_KEY_FOR_RECORDED_PATHS,
                FluentIterable.from(buildInfoRecorder.getRecordedPaths())
                    .transform(Functions.toStringFunction())
                    .toList());
            if (success.shouldWriteRecordedMetadataToDiskAfterBuilding()) {
              try {
                boolean clearExistingMetadata = success.shouldClearAndOverwriteMetadataOnDisk();
                buildInfoRecorder.writeMetadataToDisk(clearExistingMetadata);
              } catch (IOException e) {
                throw new IOException(
                    String.format("Failed to write metadata to disk for %s.", rule),
                    e);
              }
            }

            // Give the rule a chance to populate its internal data structures now that all of
            // the files should be in a valid state.
            try {
              if (rule instanceof InitializableFromDisk) {
                doInitializeFromDisk((InitializableFromDisk<?>) rule, onDiskBuildInfo);
              }
            } catch (IOException e) {
              throw new IOException(String.format("Error initializing %s from disk.", rule), e);
            }

            return Futures.immediateFuture(input);
          }
        };
    buildResult =
        Futures.transformAsync(buildResult, ruleAsyncFunction(rule, context, callback), service);

    // Handle either build success or failure.
    final SettableFuture<BuildResult> result = SettableFuture.create();
    asyncCallbacks.add(
        MoreFutures.addListenableCallback(
            buildResult,
            new FutureCallback<BuildResult>() {

              // TODO(bolinfest): Delete all files produced by the rule, as they are not guaranteed
              // to be valid at this point?
              private void cleanupAfterError() {
                try {
                  onDiskBuildInfo.deleteExistingMetadata();
                } catch (Throwable t) {
                  context.getEventBus().post(
                      ThrowableConsoleEvent.create(
                          t,
                          "Error when deleting metadata for %s.",
                          rule));
                }
              }

              private void uploadToCache(BuildRuleSuccessType success) {

                // Collect up all the rule keys we have index the artifact in the cache with.
                Set<RuleKey> ruleKeys = Sets.newHashSet();

                // If the rule key has changed (and is not already in the cache), we need to push
                // the artifact to cache using the new key.
                if (success.shouldUploadResultingArtifact()) {
                  ruleKeys.add(
                      keyFactories.defaultRuleKeyBuilderFactory.build(rule));
                }

                // If the input-based rule key has changed, we need to push the artifact to cache
                // using the new key.
                if (rule instanceof SupportsInputBasedRuleKey &&
                    success.shouldUploadResultingArtifactInputBased()) {
                  ruleKeys.addAll(
                      onDiskBuildInfo.getRuleKey(
                          BuildInfo.METADATA_KEY_FOR_INPUT_BASED_RULE_KEY).asSet());
                }

                // If the manifest-based rule key has changed, we need to push the artifact to cache
                // using the new key.
                if (useManifestCaching(rule) &&
                    success.shouldUploadResultingArtifactManifestBased()) {
                  ruleKeys.addAll(
                      onDiskBuildInfo.getRuleKey(
                          BuildInfo.METADATA_KEY_FOR_DEP_FILE_RULE_KEY).asSet());
                }

                // If we have any rule keys to push to the cache with, do the upload now.
                if (!ruleKeys.isEmpty()) {
                  try {
                    buildInfoRecorder.performUploadToArtifactCache(
                        ImmutableSet.copyOf(ruleKeys),
                        context.getArtifactCache(),
                        context.getEventBus());
                  } catch (Throwable t) {
                    context.getEventBus().post(
                        ThrowableConsoleEvent.create(
                            t,
                            "Error uploading to cache for %s.",
                            rule));
                  }
                }

              }

              private void handleResult(BuildResult input) {
                Optional<Long> outputSize = Optional.absent();
                Optional<HashCode> outputHash = Optional.absent();
                Optional<BuildRuleSuccessType> successType = Optional.absent();

                context.getEventBus().logVerboseAndPost(
                    LOG,
                    BuildRuleEvent.resumed(rule, keyFactories.defaultRuleKeyBuilderFactory));

                if (input.getStatus() == BuildRuleStatus.FAIL) {

                  // Make this failure visible for other rules, so that they can stop early.
                  firstFailure = input.getFailure();

                  // If we failed, cleanup the state of this rule.
                  cleanupAfterError();
                }

                // Unblock dependents.
                result.set(input);

                if (input.getStatus() == BuildRuleStatus.SUCCESS) {
                  BuildRuleSuccessType success = Preconditions.checkNotNull(input.getSuccess());
                  successType = Optional.of(success);

                  // Try get the output size.
                  try {
                    outputSize = Optional.of(buildInfoRecorder.getOutputSize());
                  } catch (IOException e) {
                    context.getEventBus().post(
                        ThrowableConsoleEvent.create(
                            e,
                            "Error getting output size for %s.",
                            rule));
                  }

                  // If this rule is cacheable, upload it to the cache.
                  if (outputSize.isPresent() && shouldUploadToCache(rule, outputSize.get())) {
                    uploadToCache(success);
                  }

                  // Calculate the hash of outputs that were built locally and are cacheable.
                  if (success == BuildRuleSuccessType.BUILT_LOCALLY &&
                      shouldUploadToCache(rule, outputSize.get())) {
                    try {
                      outputHash = Optional.of(buildInfoRecorder.getOutputHash(fileHashCache));
                    } catch (IOException e) {
                      context.getEventBus().post(
                          ThrowableConsoleEvent.create(
                              e,
                              "Error getting output hash for %s.",
                              rule));
                    }
                  }
                }

                // Log the result to the event bus.
                context.getEventBus().logVerboseAndPost(
                    LOG,
                    BuildRuleEvent.finished(
                        rule,
                        BuildRuleKeys.builder()
                            .setRuleKey(keyFactories.defaultRuleKeyBuilderFactory.build(rule))
                            .setInputRuleKey(
                                onDiskBuildInfo.getRuleKey(
                                    BuildInfo.METADATA_KEY_FOR_INPUT_BASED_RULE_KEY))
                            .build(),
                        input.getStatus(),
                        input.getCacheResult(),
                        successType,
                        outputHash,
                        outputSize));
              }

              @Override
              public void onSuccess(BuildResult input) {
                handleResult(input);
              }

              @Override
              public void onFailure(@Nonnull Throwable thrown) {
                handleResult(BuildResult.failure(rule, thrown));

                // Reset interrupted flag once failure has been recorded.
                if (thrown instanceof InterruptedException) {
                  Thread.currentThread().interrupt();
                }
              }

            }));
    return result;
  }

  private ListenableFuture<Void> registerTopLevelRule(BuildRule rule, BuckEventBus eventBus) {
    if (unskippedRulesTracker.isPresent()) {
      return unskippedRulesTracker.get().registerTopLevelRule(rule, eventBus);
    } else {
      return Futures.immediateFuture(null);
    }
  }

  private ListenableFuture<Void> markRuleAsUsed(BuildRule rule, BuckEventBus eventBus) {
    if (unskippedRulesTracker.isPresent()) {
      return unskippedRulesTracker.get().markRuleAsUsed(rule, eventBus);
    } else {
      return Futures.immediateFuture(null);
    }
  }

  // Provide a future that resolve to the result of executing this rule and its runtime
  // dependencies.
  private ListenableFuture<BuildResult> getBuildRuleResultWithRuntimeDepsUnlocked(
      final BuildRule rule,
      final BuildContext context,
      final ConcurrentLinkedQueue<ListenableFuture<Void>> asyncCallbacks) {

    // If the rule is already executing, return it's result future from the cache.
    ListenableFuture<BuildResult> existingResult = results.get(rule.getBuildTarget());
    if (existingResult != null) {
      return existingResult;
    }

    // Get the future holding the result for this rule and, if we have no additional runtime deps
    // to attach, return it.
    ListenableFuture<RuleKey> ruleKey = calculateRuleKey(rule, context);
    ListenableFuture<BuildResult> result =
        Futures.transformAsync(
            ruleKey,
            new AsyncFunction<RuleKey, BuildResult>() {
              @Override
              public ListenableFuture<BuildResult> apply(RuleKey input) throws Exception {
                return processBuildRule(rule, context, asyncCallbacks);
              }
            },
            service);
    if (!(rule instanceof HasRuntimeDeps)) {
      results.put(rule.getBuildTarget(), result);
      return result;
    }

    // Collect any runtime deps we have into a list of futures.
    ImmutableSortedSet<BuildRule> runtimeDeps = ((HasRuntimeDeps) rule).getRuntimeDeps();
    List<ListenableFuture<BuildResult>> runtimeDepResults =
        Lists.newArrayListWithExpectedSize(runtimeDeps.size());
    for (BuildRule dep : runtimeDeps) {
      runtimeDepResults.add(
          getBuildRuleResultWithRuntimeDepsUnlocked(dep, context, asyncCallbacks));
    }

    // Create a new combined future, which runs the original rule and all the runtime deps in
    // parallel, but which propagates an error if any one of them fails.
    result =
        MoreFutures.chainExceptions(
            Futures.allAsList(runtimeDepResults),
            result);
    results.put(rule.getBuildTarget(), result);
    return result;
  }

  private ListenableFuture<BuildResult> getBuildRuleResultWithRuntimeDeps(
      final BuildRule rule,
      final BuildContext context,
      final ConcurrentLinkedQueue<ListenableFuture<Void>> asyncCallbacks) {

    // If the rule is already executing, return it's result future from the cache without acquiring
    // the lock.
    ListenableFuture<BuildResult> existingResult = results.get(rule.getBuildTarget());
    if (existingResult != null) {
      return existingResult;
    }

    // Otherwise, grab the lock and delegate to the real method,
    synchronized (results) {
      return getBuildRuleResultWithRuntimeDepsUnlocked(rule, context, asyncCallbacks);
    }
  }

  public ListenableFuture<?> walkRule(
      BuildRule rule,
      final Set<BuildRule> seen) {
    return Futures.transformAsync(
        ruleDeps.get(rule),
        new AsyncFunction<ImmutableSortedSet<BuildRule>, List<Object>>() {
          @Override
          public ListenableFuture<List<Object>> apply(ImmutableSortedSet<BuildRule> deps) {
            List<ListenableFuture<?>> results =
                Lists.newArrayListWithExpectedSize(deps.size());
            for (BuildRule dep : deps) {
              if (seen.add(dep)) {
                results.add(walkRule(dep, seen));
              }
            }
            return Futures.allAsList(results);
          }
        },
        service);
  }

  @Override
  public int getNumRulesToBuild(Iterable<BuildRule> rules) {
    Set<BuildRule> seen = Sets.newConcurrentHashSet();
    ImmutableList.Builder<ListenableFuture<?>> results = ImmutableList.builder();
    for (final BuildRule rule : rules) {
      if (seen.add(rule)) {
        results.add(walkRule(rule, seen));
      }
    }
    Futures.getUnchecked(Futures.allAsList(results.build()));
    return seen.size();
  }

  private synchronized ListenableFuture<RuleKey> calculateRuleKey(
      final BuildRule rule,
      final BuildContext context) {
    ListenableFuture<RuleKey> ruleKey = ruleKeys.get(rule.getBuildTarget());
    if (ruleKey == null) {

      // Grab all the dependency rule key futures.  Since our rule key calculation depends on this
      // one, we need to wait for them to complete.
      ListenableFuture<List<RuleKey>> depKeys =
          Futures.transformAsync(
              ruleDeps.get(rule),
              new AsyncFunction<ImmutableSortedSet<BuildRule>, List<RuleKey>>() {
                @Override
                public ListenableFuture<List<RuleKey>> apply(ImmutableSortedSet<BuildRule> deps) {
                  List<ListenableFuture<RuleKey>> depKeys =
                      Lists.newArrayListWithExpectedSize(rule.getDeps().size());
                  for (BuildRule dep : deps) {
                    depKeys.add(calculateRuleKey(dep, context));
                  }
                  return Futures.allAsList(depKeys);
                }
              },
              service);

      final RuleKeyFactories keyFactories =
          ruleKeyFactories.getUnchecked(rule.getProjectFilesystem());

      // Setup a future to calculate this rule key once the dependencies have been calculated.
      ruleKey = Futures.transform(
          depKeys,
          new Function<List<RuleKey>, RuleKey>() {
            @Override
            public RuleKey apply(List<RuleKey> input) {
              try (BuildRuleEvent.Scope scope =
                       BuildRuleEvent.startSuspendScope(
                           context.getEventBus(),
                           rule,
                           keyFactories.defaultRuleKeyBuilderFactory)) {
                return keyFactories.defaultRuleKeyBuilderFactory.build(rule);
              }
            }
          },
          service);

      // Record the rule key future.
      ruleKeys.put(rule.getBuildTarget(), ruleKey);
    }

    return ruleKey;
  }

  @Override
  public ListenableFuture<BuildResult> build(BuildContext context, BuildRule rule) {
    // Keep track of all jobs that run asynchronously with respect to the build dep chain.  We want
    // to make sure we wait for these before calling yielding the final build result.
    final ConcurrentLinkedQueue<ListenableFuture<Void>> asyncCallbacks =
        new ConcurrentLinkedQueue<>();
    ListenableFuture<BuildResult> resultFuture = MoreFutures.chainExceptions(
        registerTopLevelRule(rule, context.getEventBus()),
        getBuildRuleResultWithRuntimeDeps(rule, context, asyncCallbacks),
        service);
    return Futures.transformAsync(
        resultFuture,
        new AsyncFunction<BuildResult, BuildResult>() {
          @Override
          public ListenableFuture<BuildResult> apply(BuildResult result)
              throws Exception {
            return Futures.transform(
                Futures.allAsList(asyncCallbacks),
                Functions.constant(result));
          }
        },
        service);
  }

  private ListenableFuture<CacheResult>
  tryToFetchArtifactFromBuildCacheAndOverlayOnTopOfProjectFilesystem(
      final BuildRule rule,
      final RuleKey ruleKey,
      final BuildInfoRecorder buildInfoRecorder,
      final ArtifactCache artifactCache,
      final ProjectFilesystem filesystem,
      final BuildContext buildContext) throws InterruptedException {

    if (!rule.isCacheable()) {
      return Futures.immediateFuture(CacheResult.ignored());
    }

    // Create a temp file whose extension must be ".zip" for Filesystems.newFileSystem() to infer
    // that we are creating a zip-based FileSystem.
    final LazyPath lazyZipPath = new LazyPath() {
      @Override
      protected Path create() throws IOException {
        return Files.createTempFile(
            "buck_artifact_" + MoreFiles.sanitize(rule.getBuildTarget().getShortName()),
            ".zip");
      }
    };

    // TODO(bolinfest): Change ArtifactCache.fetch() so that it returns a File instead of takes one.
    // Then we could download directly from the remote cache into the on-disk cache and unzip it
    // from there.
    ListenableFuture<CacheResult> cacheResult =
        asyncFetchArtifactForBuildable(ruleKey, lazyZipPath, artifactCache, buildInfoRecorder);

    ListenableFuture<CacheResult> unzipResult =
        Futures.transformAsync(
            cacheResult,
            new AsyncFunction<CacheResult, CacheResult>() {
              @Override
              public ListenableFuture<CacheResult> apply(CacheResult cacheResult)
                  throws Exception {
                return unzipArtifactFromCacheResult(
                    rule,
                    ruleKey,
                    lazyZipPath,
                    buildContext,
                    filesystem,
                    cacheResult);
              }
            },
            networkExecutor);

    return unzipResult;
  }

  private ListenableFuture<CacheResult> unzipArtifactFromCacheResult(
      BuildRule rule,
      RuleKey ruleKey,
      LazyPath lazyZipPath,
      BuildContext buildContext,
      ProjectFilesystem filesystem,
      CacheResult cacheResult) {
    if (!cacheResult.getType().isSuccess()) {
      LOG.debug("Cache miss for '%s' with rulekey '%s'", rule, ruleKey);
      return Futures.immediateFuture(cacheResult);
    }
    LOG.debug("Fetched '%s' from cache with rulekey '%s'", rule, ruleKey);

    // It should be fine to get the path straight away, since cache already did it's job.
    Path zipPath = lazyZipPath.getUnchecked();

    // We unzip the file in the root of the project directory.
    // Ideally, the following would work:
    //
    // Path pathToZip = Paths.get(zipPath.getAbsolutePath());
    // FileSystem fs = FileSystems.newFileSystem(pathToZip, /* loader */ null);
    // Path root = Iterables.getOnlyElement(fs.getRootDirectories());
    // MoreFiles.copyRecursively(root, projectRoot);
    //
    // Unfortunately, this does not appear to work, in practice, because MoreFiles fails when trying
    // to resolve a Path for a zip entry against a file Path on disk.
    ArtifactCompressionEvent.Started started = ArtifactCompressionEvent.started(
        ArtifactCompressionEvent.Operation.DECOMPRESS,
        ImmutableSet.of(ruleKey));
    buildContext.getEventBus().post(started);
    try {
      Unzip.extractZipFile(
          zipPath.toAbsolutePath(),
          filesystem,
          Unzip.ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);

      // We only delete the ZIP file when it has been unzipped successfully. Otherwise, we leave it
      // around for debugging purposes.
      Files.delete(zipPath);

      if (cacheResult.getType() == CacheResultType.HIT) {

        // If we have a hit, also write out the build metadata.
        Path metadataDir =
            BuildInfo.getPathToMetadataDirectory(
                rule.getBuildTarget(),
                rule.getProjectFilesystem());
        for (Map.Entry<String, String> ent : cacheResult.getMetadata().entrySet()) {
          Path dest = metadataDir.resolve(ent.getKey());
          filesystem.createParentDirs(dest);
          filesystem.writeContentsToPath(ent.getValue(), dest);
        }
      }

    } catch (IOException e) {
      // In the wild, we have seen some inexplicable failures during this step. For now, we try to
      // give the user as much information as we can to debug the issue, but return CacheResult.MISS
      // so that Buck will fall back on doing a local build.
      buildContext.getEventBus().post(ConsoleEvent.warning(
              "Failed to unzip the artifact for %s at %s.\n" +
                  "The rule will be built locally, " +
                  "but here is the stacktrace of the failed unzip call:\n" +
                  rule.getBuildTarget(),
              zipPath.toAbsolutePath(),
              Throwables.getStackTraceAsString(e)));
      return Futures.immediateCheckedFuture(CacheResult.miss());
    } finally {
      buildContext.getEventBus().post(ArtifactCompressionEvent.finished(started));
    }

    return Futures.immediateFuture(cacheResult);
  }

  private ListenableFuture<CacheResult> asyncFetchArtifactForBuildable(
      final RuleKey ruleKey,
      final LazyPath lazyZipPath,
      final ArtifactCache artifactCache,
      final BuildInfoRecorder buildInfoRecorder
  ) throws InterruptedException {
    return networkExecutor.submit(
        new Callable<CacheResult>() {
          @Override
          public CacheResult call() throws Exception {
            return buildInfoRecorder.fetchArtifactForBuildable(ruleKey, lazyZipPath, artifactCache);
          }
        }
    );
  }

  /**
   * Execute the commands for this build rule. Requires all dependent rules are already built
   * successfully.
   */
  private void executeCommandsNowThatDepsAreBuilt(
      BuildRule rule,
      BuildContext context,
      BuildableContext buildableContext)
      throws InterruptedException, StepFailedException {

    LOG.debug("Building locally: %s", rule);
    // Attempt to get an approximation of how long it takes to actually run the command.
    @SuppressWarnings("PMD.PrematureDeclaration")
    long start = System.nanoTime();

    // Get and run all of the commands.
    List<Step> steps = rule.getBuildSteps(context, buildableContext);

    StepRunner stepRunner = context.getStepRunner();
    Optional<BuildTarget> optionalTarget = Optional.of(rule.getBuildTarget());
    for (Step step : steps) {
      stepRunner.runStepForBuildTarget(step, optionalTarget);

      // Check for interruptions that may have been ignored by step.
      if (Thread.interrupted()) {
        Thread.currentThread().interrupt();
        throw new InterruptedException();
      }
    }

    long end = System.nanoTime();
    LOG.debug("Build completed: %s %s (%dns)",
        rule.getType(),
        rule.getFullyQualifiedName(),
        end - start);
  }

  private void executePostBuildSteps(
      BuildRule rule,
      Iterable<Step> postBuildSteps,
      BuildContext context)
      throws InterruptedException, StepFailedException {

    LOG.debug("Running post-build steps for %s", rule);

    StepRunner stepRunner = context.getStepRunner();
    Optional<BuildTarget> optionalTarget = Optional.of(rule.getBuildTarget());
    for (Step step : postBuildSteps) {
      stepRunner.runStepForBuildTarget(step, optionalTarget);

      // Check for interruptions that may have been ignored by step.
      if (Thread.interrupted()) {
        Thread.currentThread().interrupt();
        throw new InterruptedException();
      }
    }

    LOG.debug("Finished running post-build steps for %s", rule);
  }

  private <T> void doInitializeFromDisk(
      InitializableFromDisk<T> initializable,
      OnDiskBuildInfo onDiskBuildInfo)
      throws IOException {
    BuildOutputInitializer<T> buildOutputInitializer = initializable.getBuildOutputInitializer();
    T buildOutput = buildOutputInitializer.initializeFromDisk(onDiskBuildInfo);
    buildOutputInitializer.setBuildOutput(buildOutput);
  }

  @Nullable
  @Override
  public BuildResult getBuildRuleResult(BuildTarget buildTarget)
      throws ExecutionException, InterruptedException {
    ListenableFuture<BuildResult> result = results.get(buildTarget);
    if (result == null) {
      return null;
    }
    return result.get();
  }

  /**
   * @return whether we should upload the given rules artifacts to cache.
   */
  private boolean shouldUploadToCache(BuildRule rule, long outputSize) {

    // If the rule is explicitly marked uncacheable, don't cache it.
    if (!rule.isCacheable()) {
      return false;
    }

    // If the rule's outputs are bigger than the preset size limit, don't cache it.
    if (artifactCacheSizeLimit.isPresent() &&
        outputSize > artifactCacheSizeLimit.get()) {
      return false;
    }

    return true;
  }

  private boolean useDependencyFileRuleKey(BuildRule rule) {
    return depFiles != DepFiles.DISABLED &&
        rule instanceof SupportsDependencyFileRuleKey &&
        ((SupportsDependencyFileRuleKey) rule).useDependencyFileRuleKeys();
  }

  private boolean useManifestCaching(BuildRule rule) {
    return depFiles == DepFiles.CACHE &&
        rule instanceof SupportsDependencyFileRuleKey &&
        rule.isCacheable() &&
        ((SupportsDependencyFileRuleKey) rule).useDependencyFileRuleKeys();
  }

  private Optional<Pair<RuleKey, ImmutableSet<SourcePath>>> calculateDepFileRuleKey(
      BuildRule rule,
      Optional<ImmutableList<String>> depFile,
      boolean allowMissingInputs)
      throws IOException {

    Preconditions.checkState(useDependencyFileRuleKey(rule));

    // Extract the dep file from the last build.  If we don't find one, abort.
    if (!depFile.isPresent()) {
      return Optional.absent();
    }

    // Build the dep-file rule key.  If any inputs are no longer on disk, this means something
    // changed and a dep-file based rule key can't be calculated.

    ImmutableList<DependencyFileEntry> inputs =
        FluentIterable.from(depFile.get()).transform(MoreFunctions.fromJsonFunction(
            objectMapper,
            DependencyFileEntry.class)).toList();
    try {
      return this.ruleKeyFactories.getUnchecked(rule.getProjectFilesystem())
          .depFileRuleKeyBuilderFactory.build(
              rule,
              ((SupportsDependencyFileRuleKey) rule).getPossibleInputSourcePaths(),
              inputs);
    } catch (NoSuchFileException e) {
      if (!allowMissingInputs) {
        throw e;
      }
      return Optional.absent();
    }
  }

  @VisibleForTesting
  protected Path getManifestPath(BuildRule rule) {
    return BuildInfo.getPathToMetadataDirectory(rule.getBuildTarget(), rule.getProjectFilesystem())
        .resolve(BuildInfo.MANIFEST);
  }

  @VisibleForTesting
  protected Optional<RuleKey> getManifestRuleKey(BuildRule rule) throws IOException {
    Optional<Pair<RuleKey, ImmutableSet<SourcePath>>> result =
        ruleKeyFactories.getUnchecked(rule.getProjectFilesystem())
            .depFileRuleKeyBuilderFactory.buildManifestKey(rule);
    if (result.isPresent()) {
      return Optional.of(result.get().getFirst());
    } else {
      return Optional.absent();
    }
  }

  // Update the on-disk manifest with the new dep-file rule key and push it to the cache.
  private void updateAndStoreManifest(
      BuildRule rule,
      RuleKey key,
      ImmutableSet<SourcePath> inputs,
      Pair<RuleKey, ImmutableSet<SourcePath>> manifestKey,
      ArtifactCache cache)
      throws IOException, InterruptedException {

    Preconditions.checkState(useManifestCaching(rule));

    final Path manifestPath = getManifestPath(rule);
    Manifest manifest = new Manifest();

    // If we already have a manifest downloaded, use that.
    if (rule.getProjectFilesystem().exists(manifestPath)) {
      try (InputStream inputStream =
               rule.getProjectFilesystem().newFileInputStream(manifestPath)) {
        manifest = new Manifest(inputStream);
      }
    } else {
      // Ensure the path to manifest exist
      rule.getProjectFilesystem().createParentDirs(manifestPath);
    }

    // If the manifest is larger than the max size, just truncate it.  It might be nice to support
    // some sort of LRU management here to avoid evicting everything, but it'll take some care to do
    // this efficiently and it's not clear how much benefit this will give us.
    if (manifest.size() >= maxDepFileCacheEntries) {
      manifest = new Manifest();
    }

    // Update the manifest with the new output rule key.
    manifest.addEntry(
        fileHashCaches.getUnchecked(rule.getProjectFilesystem()),
        key,
        pathResolver,
        manifestKey.getSecond(),
        inputs);

    // Serialize the manifest to disk.
    try (OutputStream outputStream =
             rule.getProjectFilesystem().newFileOutputStream(manifestPath)) {
      manifest.serialize(outputStream);
    }

    final Path tempFile = Files.createTempFile("buck.", ".manifest");
    // Upload the manifest to the cache.  We stage the manifest into a temp file first since the
    // `ArtifactCache` interface uses raw paths.
    try (InputStream inputStream = rule.getProjectFilesystem().newFileInputStream(manifestPath);
         OutputStream outputStream =
             new GZIPOutputStream(
                 new BufferedOutputStream(Files.newOutputStream(tempFile)))) {
      ByteStreams.copy(inputStream, outputStream);
    }
    cache
        .store(
            ImmutableSet.of(manifestKey.getFirst()),
            ImmutableMap.<String, String>of(),
            BorrowablePath.notBorrowablePath(tempFile))
        .addListener(
            new Runnable() {
          @Override
          public void run() {
                try {
                  Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                  LOG.warn(
                      e,
                      "Error occurred while deleting temporary manifest file for %s",
                      manifestPath);
                }
            }
          },
            MoreExecutors.directExecutor());
  }

  // Fetch an artifact from the cache using manifest-based caching.
  private ListenableFuture<CacheResult> performManifestBasedCacheFetch(
      BuildRule rule,
      BuildContext context,
      Pair<RuleKey, ImmutableSet<SourcePath>> manifestKey,
      BuildInfoRecorder buildInfoRecorder)
      throws IOException, InterruptedException {

    Preconditions.checkState(useManifestCaching(rule));

    LazyPath tempFile = new LazyPath() {
      @Override
      protected Path create() throws IOException {
        return Files.createTempFile("buck.", ".manifest");
      }
    };

    CacheResult manifestResult =
        context.getArtifactCache().fetch(manifestKey.getFirst(), tempFile);
    if (!manifestResult.getType().isSuccess()) {
      return Futures.immediateFuture(CacheResult.miss());

    }

    Path manifestPath = getManifestPath(rule);

    // Clear out any existing manifest.
    rule.getProjectFilesystem().deleteFileAtPathIfExists(manifestPath);

    // Now, fetch an existing manifest from the cache.
    rule.getProjectFilesystem().createParentDirs(manifestPath);

    try (OutputStream outputStream =
             rule.getProjectFilesystem().newFileOutputStream(manifestPath);
         InputStream inputStream =
             new GZIPInputStream(new BufferedInputStream(Files.newInputStream(tempFile.get())))) {
      ByteStreams.copy(inputStream, outputStream);
    }
    Files.delete(tempFile.get());

    // Deserialize the manifest.
    Manifest manifest;
    try (InputStream input =
             rule.getProjectFilesystem().newFileInputStream(manifestPath)) {
      manifest = new Manifest(input);
    }

    // Lookup the rule for the current state of our inputs.
    Optional<RuleKey> ruleKey =
        manifest.lookup(
            fileHashCaches.getUnchecked(rule.getProjectFilesystem()),
            pathResolver,
            manifestKey.getSecond());
    if (!ruleKey.isPresent()) {
      return Futures.immediateFuture(CacheResult.miss());
    }

    // Do another cache fetch using the rule key we found above.
    return
        tryToFetchArtifactFromBuildCacheAndOverlayOnTopOfProjectFilesystem(
            rule,
            ruleKey.get(),
            buildInfoRecorder,
            context.getArtifactCache(),
            // TODO(shs96c): This should be shared between all tests, not one per cell
            rule.getProjectFilesystem(),
            context);
  }

  private static RuleScheduleInfo getRuleScheduleInfo(BuildRule rule) {
    if (rule instanceof OverrideScheduleRule) {
      return ((OverrideScheduleRule) rule).getRuleScheduleInfo();
    }
    return RuleScheduleInfo.DEFAULT;
  }

  /**
   * The mode in which to build rules.
   */
  public enum BuildMode {

    // Perform a shallow build, only locally materializing the bare minimum needed to build the
    // top-level build targets.
    SHALLOW,

    // Perform a deep build, locally materializing all the transitive dependencies of the top-level
    // build targets.
    DEEP,

    // Perform local cache population by only loading all the transitive dependencies of
    // the top-level build targets from the remote cache, without building missing or changed
    // dependencies locally.
    POPULATE_FROM_REMOTE_CACHE,
  }

  /**
   * The order in which to schedule dependency execution.
   */
  public enum DependencySchedulingOrder {

    // Schedule dependencies based on their natural ordering.
    SORTED,

    // Schedule dependencies in random order.
    RANDOM,
  }

  /**
   * Whether to use dependency files or not.
   */
  public enum DepFiles {
    ENABLED,
    DISABLED,
    CACHE,
  }

  // Wrap an async function in rule resume/suspend events.
  private <F, T> AsyncFunction<F, T> ruleAsyncFunction(
      final BuildRule rule,
      final BuildContext context,
      final AsyncFunction<F, T> delegate) {
    return new AsyncFunction<F, T>() {
      @Override
      public ListenableFuture<T> apply(@Nullable F input) throws Exception {
        RuleKeyFactories ruleKeyFactory =
            ruleKeyFactories.getUnchecked(rule.getProjectFilesystem());
        try (BuildRuleEvent.Scope event =
                 BuildRuleEvent.resumeSuspendScope(
                     context.getEventBus(),
                     rule,
                     ruleKeyFactory.defaultRuleKeyBuilderFactory)) {
          return delegate.apply(input);
        }
      }
    };
  }

  @VisibleForTesting
  static class RuleKeyFactories {
    public final RuleKeyBuilderFactory<RuleKey> defaultRuleKeyBuilderFactory;
    public final RuleKeyBuilderFactory<Optional<RuleKey>> inputBasedRuleKeyBuilderFactory;
    public final RuleKeyBuilderFactory<RuleKey> abiRuleKeyBuilderFactory;
    public final DependencyFileRuleKeyBuilderFactory depFileRuleKeyBuilderFactory;

    public static RuleKeyFactories build(
        int seed,
        FileHashCache fileHashCache,
        BuildRuleResolver ruleResolver) {
      SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);
      DefaultRuleKeyBuilderFactory defaultRuleKeyBuilderFactory = new DefaultRuleKeyBuilderFactory(
          seed,
          fileHashCache,
          pathResolver);

      return new RuleKeyFactories(
          defaultRuleKeyBuilderFactory,
          new InputBasedRuleKeyBuilderFactory(
              seed,
              fileHashCache,
              pathResolver),
          new AbiRuleKeyBuilderFactory(
              seed,
              fileHashCache,
              pathResolver,
              defaultRuleKeyBuilderFactory),
          new DefaultDependencyFileRuleKeyBuilderFactory(
              seed,
              fileHashCache,
              pathResolver));
    }

    @VisibleForTesting
    RuleKeyFactories(
        RuleKeyBuilderFactory<RuleKey> defaultRuleKeyBuilderFactory,
        RuleKeyBuilderFactory<Optional<RuleKey>> inputBasedRuleKeyBuilderFactory,
        RuleKeyBuilderFactory<RuleKey> abiRuleKeyBuilderFactory,
        DependencyFileRuleKeyBuilderFactory depFileRuleKeyBuilderFactory) {
      this.defaultRuleKeyBuilderFactory = defaultRuleKeyBuilderFactory;
      this.inputBasedRuleKeyBuilderFactory = inputBasedRuleKeyBuilderFactory;
      this.abiRuleKeyBuilderFactory = abiRuleKeyBuilderFactory;
      this.depFileRuleKeyBuilderFactory = depFileRuleKeyBuilderFactory;
    }
  }
}
