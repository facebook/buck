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
import com.facebook.buck.artifact_cache.ArtifactInfo;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.event.ArtifactCompressionEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.RuleKeyCalculationEvent;
import com.facebook.buck.event.ThrowableConsoleEvent;
import com.facebook.buck.io.BorrowablePath;
import com.facebook.buck.io.LazyPath;
import com.facebook.buck.io.MoreFiles;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.keys.DependencyFileEntry;
import com.facebook.buck.rules.keys.RuleKeyAndInputs;
import com.facebook.buck.rules.keys.RuleKeyDiagnostics;
import com.facebook.buck.rules.keys.RuleKeyFactories;
import com.facebook.buck.rules.keys.SizeLimiter;
import com.facebook.buck.rules.keys.StringRuleKeyHasher;
import com.facebook.buck.rules.keys.SupportsDependencyFileRuleKey;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.util.ContextualProcessExecutor;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.MoreFunctions;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.OptionalCompat;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.concurrent.MoreFutures;
import com.facebook.buck.util.concurrent.ResourceAmounts;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.facebook.buck.zip.Unzip;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
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
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A build engine used to build a {@link BuildRule} which also caches the results. If the current
 * {@link RuleKey} of the build rules matches the one on disk, it does not do any work. It also
 * tries to fetch its output from an {@link ArtifactCache} to avoid doing any computation.
 */
public class CachingBuildEngine implements BuildEngine, Closeable {

  private static final Logger LOG = Logger.get(CachingBuildEngine.class);
  public static final ResourceAmounts CACHE_CHECK_RESOURCE_AMOUNTS = ResourceAmounts.of(0, 0, 1, 1);

  public static final ResourceAmounts RULE_KEY_COMPUTATION_RESOURCE_AMOUNTS =
      ResourceAmounts.of(0, 0, 1, 0);
  public static final ResourceAmounts SCHEDULING_MORE_WORK_RESOURCE_AMOUNTS = ResourceAmounts.ZERO;

  private static final String BUILD_RULE_TYPE_CONTEXT_KEY = "build_rule_type";
  private static final String STEP_TYPE_CONTEXT_KEY = "step_type";

  private static enum StepType {
    BUILD_STEP,
    POST_BUILD_STEP,
    ;
  };

  /**
   * These are the values returned by {@link BuildEngine#build(BuildEngineBuildContext,
   * ExecutionContext, BuildRule)}. This must always return the same value for the build of each
   * target.
   */
  private final ConcurrentMap<BuildTarget, ListenableFuture<BuildResult>> results =
      Maps.newConcurrentMap();

  private final ConcurrentMap<BuildTarget, ListenableFuture<RuleKey>> ruleKeys =
      Maps.newConcurrentMap();

  @Nullable private volatile Throwable firstFailure = null;

  private final CachingBuildEngineDelegate cachingBuildEngineDelegate;

  private final WeightedListeningExecutorService service;
  private final WeightedListeningExecutorService cacheActivityService;
  private final StepRunner stepRunner;
  private final BuildMode buildMode;
  private final MetadataStorage metadataStorage;
  private final DepFiles depFiles;
  private final long maxDepFileCacheEntries;
  private final BuildRuleResolver resolver;
  private final SourcePathRuleFinder ruleFinder;
  private final SourcePathResolver pathResolver;
  private final Optional<Long> artifactCacheSizeLimit;
  private final FileHashCache fileHashCache;
  private final RuleKeyFactories ruleKeyFactories;
  private final ResourceAwareSchedulingInfo resourceAwareSchedulingInfo;

  private final RuleDepsCache ruleDeps;
  private final Optional<UnskippedRulesTracker> unskippedRulesTracker;
  private final BuildRuleDurationTracker buildRuleDurationTracker = new BuildRuleDurationTracker();
  private final RuleKeyDiagnostics<RuleKey, String> defaultRuleKeyDiagnostics;

  private final BuildInfoStoreManager buildInfoStoreManager;

  public CachingBuildEngine(
      CachingBuildEngineDelegate cachingBuildEngineDelegate,
      WeightedListeningExecutorService service,
      WeightedListeningExecutorService artifactFetchService,
      StepRunner stepRunner,
      BuildMode buildMode,
      MetadataStorage metadataStorage,
      DepFiles depFiles,
      long maxDepFileCacheEntries,
      Optional<Long> artifactCacheSizeLimit,
      final BuildRuleResolver resolver,
      BuildInfoStoreManager buildInfoStoreManager,
      ResourceAwareSchedulingInfo resourceAwareSchedulingInfo,
      RuleKeyFactories ruleKeyFactories) {
    this.cachingBuildEngineDelegate = cachingBuildEngineDelegate;

    this.service = service;
    this.cacheActivityService = artifactFetchService;
    this.stepRunner = stepRunner;
    this.buildMode = buildMode;
    this.metadataStorage = metadataStorage;
    this.depFiles = depFiles;
    this.maxDepFileCacheEntries = maxDepFileCacheEntries;
    this.artifactCacheSizeLimit = artifactCacheSizeLimit;
    this.resolver = resolver;
    this.ruleFinder = new SourcePathRuleFinder(resolver);
    this.pathResolver = new SourcePathResolver(ruleFinder);
    this.buildInfoStoreManager = buildInfoStoreManager;

    this.fileHashCache = cachingBuildEngineDelegate.getFileHashCache();
    this.ruleKeyFactories = ruleKeyFactories;
    this.resourceAwareSchedulingInfo = resourceAwareSchedulingInfo;

    this.ruleDeps = new RuleDepsCache(resolver);
    this.unskippedRulesTracker = createUnskippedRulesTracker(buildMode, ruleDeps, resolver);
    this.defaultRuleKeyDiagnostics =
        new RuleKeyDiagnostics<>(
            rule ->
                ruleKeyFactories
                    .getDefaultRuleKeyFactory()
                    .buildForDiagnostics(rule, new StringRuleKeyHasher()),
            appendable ->
                ruleKeyFactories
                    .getDefaultRuleKeyFactory()
                    .buildForDiagnostics(appendable, new StringRuleKeyHasher()));
  }

  /** This constructor MUST ONLY BE USED FOR TESTS. */
  @VisibleForTesting
  CachingBuildEngine(
      CachingBuildEngineDelegate cachingBuildEngineDelegate,
      WeightedListeningExecutorService service,
      StepRunner stepRunner,
      BuildMode buildMode,
      MetadataStorage metadataStorage,
      DepFiles depFiles,
      long maxDepFileCacheEntries,
      Optional<Long> artifactCacheSizeLimit,
      BuildRuleResolver resolver,
      BuildInfoStoreManager buildInfoStoreManager,
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver,
      RuleKeyFactories ruleKeyFactories,
      ResourceAwareSchedulingInfo resourceAwareSchedulingInfo) {
    this.cachingBuildEngineDelegate = cachingBuildEngineDelegate;

    this.service = service;
    this.cacheActivityService = service;
    this.stepRunner = stepRunner;
    this.buildMode = buildMode;
    this.metadataStorage = metadataStorage;
    this.depFiles = depFiles;
    this.maxDepFileCacheEntries = maxDepFileCacheEntries;
    this.artifactCacheSizeLimit = artifactCacheSizeLimit;
    this.resolver = resolver;
    this.ruleFinder = ruleFinder;
    this.pathResolver = pathResolver;

    this.fileHashCache = cachingBuildEngineDelegate.getFileHashCache();
    this.ruleKeyFactories = ruleKeyFactories;
    this.resourceAwareSchedulingInfo = resourceAwareSchedulingInfo;
    this.buildInfoStoreManager = buildInfoStoreManager;

    this.ruleDeps = new RuleDepsCache(resolver);
    this.unskippedRulesTracker = createUnskippedRulesTracker(buildMode, ruleDeps, resolver);
    this.defaultRuleKeyDiagnostics = RuleKeyDiagnostics.nop();
  }

  @Override
  public void close() {}

  /**
   * We have a lot of places where tasks are submitted into a service implicitly. There is no way to
   * assign custom weights to such tasks. By creating a temporary service with adjusted weights it
   * is possible to trick the system and tweak the weights.
   */
  private WeightedListeningExecutorService serviceByAdjustingDefaultWeightsTo(
      ResourceAmounts defaultAmounts) {
    if (resourceAwareSchedulingInfo.isResourceAwareSchedulingEnabled()) {
      return service.withDefaultAmounts(defaultAmounts);
    }
    return service;
  }

  private static Optional<UnskippedRulesTracker> createUnskippedRulesTracker(
      BuildMode buildMode, RuleDepsCache ruleDeps, BuildRuleResolver resolver) {
    if (buildMode == BuildMode.DEEP || buildMode == BuildMode.POPULATE_FROM_REMOTE_CACHE) {
      // Those modes never skip rules, there is no need to track unskipped rules.
      return Optional.empty();
    }
    return Optional.of(new UnskippedRulesTracker(ruleDeps, resolver));
  }

  @VisibleForTesting
  void setBuildRuleResult(
      BuildRule buildRule, BuildRuleSuccessType success, CacheResult cacheResult) {
    results.put(
        buildRule.getBuildTarget(),
        Futures.immediateFuture(BuildResult.success(buildRule, success, cacheResult)));
  }

  @Override
  public boolean isRuleBuilt(BuildTarget buildTarget) throws InterruptedException {
    ListenableFuture<BuildResult> resultFuture = results.get(buildTarget);
    return resultFuture != null && MoreFutures.isSuccess(resultFuture);
  }

  @Override
  public RuleKey getRuleKey(BuildTarget buildTarget) {
    return Preconditions.checkNotNull(Futures.getUnchecked(ruleKeys.get(buildTarget)));
  }

  // Dispatch and return a future resolving to a list of all results of this rules dependencies.
  private ListenableFuture<List<BuildResult>> getDepResults(
      BuildRule rule,
      BuildEngineBuildContext buildContext,
      ExecutionContext executionContext,
      ConcurrentLinkedQueue<ListenableFuture<Void>> asyncCallbacks) {
    List<ListenableFuture<BuildResult>> depResults =
        Lists.newArrayListWithExpectedSize(rule.getBuildDeps().size());
    for (BuildRule dep : shuffled(rule.getBuildDeps())) {
      depResults.add(
          getBuildRuleResultWithRuntimeDeps(dep, buildContext, executionContext, asyncCallbacks));
    }
    return Futures.allAsList(depResults);
  }

  private static List<BuildRule> shuffled(Iterable<BuildRule> rules) {
    ArrayList<BuildRule> rulesList = Lists.newArrayList(rules);
    Collections.shuffle(rulesList);
    return rulesList;
  }

  private BuildResult buildLocally(
      final BuildRule rule,
      final BuildEngineBuildContext buildContext,
      final ExecutionContext executionContext,
      final BuildableContext buildableContext,
      final CacheResult cacheResult)
      throws StepFailedException, InterruptedException {
    executeCommandsNowThatDepsAreBuilt(rule, buildContext, executionContext, buildableContext);
    return BuildResult.success(rule, BuildRuleSuccessType.BUILT_LOCALLY, cacheResult);
  }

  private void fillMissingBuildMetadataFromCache(
      CacheResult cacheResult, BuildInfoRecorder buildInfoRecorder, String... names) {
    Preconditions.checkState(cacheResult.getType() == CacheResultType.HIT);
    for (String name : names) {
      String value = cacheResult.getMetadata().get(name);
      if (value != null) {
        buildInfoRecorder.addBuildMetadata(name, value);
      }
    }
  }

  // Copy the fetched artifacts build ID to the current builds "origin" build ID.
  private void fillInOriginFromCache(CacheResult cacheResult, BuildInfoRecorder buildInfoRecorder) {
    buildInfoRecorder.addBuildMetadata(
        BuildInfo.MetadataKey.ORIGIN_BUILD_ID,
        Preconditions.checkNotNull(cacheResult.getMetadata().get(BuildInfo.MetadataKey.BUILD_ID)));
  }

  private Optional<BuildResult> checkManifestBasedCaches(
      BuildRule rule, BuildEngineBuildContext context, BuildInfoRecorder buildInfoRecorder)
      throws IOException {
    Optional<RuleKeyAndInputs> manifestKey = calculateManifestKey(rule, context.getEventBus());
    if (manifestKey.isPresent()) {
      buildInfoRecorder.addBuildMetadata(
          BuildInfo.MetadataKey.MANIFEST_KEY, manifestKey.get().getRuleKey().toString());
      try (BuckEvent.Scope scope =
          BuildRuleCacheEvent.startCacheCheckScope(
              context.getEventBus(), rule, BuildRuleCacheEvent.CacheStepType.DEPFILE_BASED)) {
        return performManifestBasedCacheFetch(rule, context, buildInfoRecorder, manifestKey.get());
      }
    }
    return Optional.empty();
  }

  private Optional<BuildResult> checkMatchingDepfile(
      BuildRule rule,
      BuildEngineBuildContext context,
      OnDiskBuildInfo onDiskBuildInfo,
      BuildInfoRecorder buildInfoRecorder)
      throws IOException {
    // Try to get the current dep-file rule key.
    Optional<RuleKeyAndInputs> depFileRuleKeyAndInputs =
        calculateDepFileRuleKey(
            rule,
            context,
            onDiskBuildInfo.getValues(BuildInfo.MetadataKey.DEP_FILE),
            /* allowMissingInputs */ true);
    if (depFileRuleKeyAndInputs.isPresent()) {
      RuleKey depFileRuleKey = depFileRuleKeyAndInputs.get().getRuleKey();
      buildInfoRecorder.addBuildMetadata(
          BuildInfo.MetadataKey.DEP_FILE_RULE_KEY, depFileRuleKey.toString());

      // Check the input-based rule key says we're already built.
      Optional<RuleKey> lastDepFileRuleKey =
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY);
      if (lastDepFileRuleKey.isPresent() && depFileRuleKey.equals(lastDepFileRuleKey.get())) {
        return Optional.of(
            BuildResult.success(
                rule,
                BuildRuleSuccessType.MATCHING_DEP_FILE_RULE_KEY,
                CacheResult.localKeyUnchangedHit()));
      }
    }
    return Optional.empty();
  }

  private Optional<BuildResult> checkInputBasedCaches(
      BuildRule rule,
      BuildEngineBuildContext context,
      OnDiskBuildInfo onDiskBuildInfo,
      BuildInfoRecorder buildInfoRecorder)
      throws IOException {
    // Calculate input-based rule key.
    Optional<RuleKey> inputRuleKey = calculateInputBasedRuleKey(rule, context.getEventBus());
    if (inputRuleKey.isPresent()) {

      // Perform the cache fetch.
      try (BuckEvent.Scope scope =
          BuildRuleCacheEvent.startCacheCheckScope(
              context.getEventBus(), rule, BuildRuleCacheEvent.CacheStepType.INPUT_BASED)) {
        // Input-based rule keys.
        return performInputBasedCacheFetch(
            rule, context, onDiskBuildInfo, buildInfoRecorder, inputRuleKey.get());
      }
    }
    return Optional.empty();
  }

  private ListenableFuture<BuildResult> buildOrFetchFromCache(
      final BuildRule rule,
      final BuildEngineBuildContext buildContext,
      final ExecutionContext executionContext,
      final OnDiskBuildInfo onDiskBuildInfo,
      final BuildInfoRecorder buildInfoRecorder,
      final BuildableContext buildableContext,
      final ConcurrentLinkedQueue<ListenableFuture<Void>> asyncCallbacks) {

    // If we've already seen a failure, exit early.
    if (!buildContext.isKeepGoing() && firstFailure != null) {
      return Futures.immediateFuture(BuildResult.canceled(rule, firstFailure));
    }

    // 1. Check if it's already built.
    try (BuildRuleEvent.Scope scope =
        BuildRuleEvent.resumeSuspendScope(
            buildContext.getEventBus(),
            rule,
            buildRuleDurationTracker,
            ruleKeyFactories.getDefaultRuleKeyFactory())) {
      Optional<BuildResult> buildResult = checkMatchingLocalKey(rule, onDiskBuildInfo);
      if (buildResult.isPresent()) {
        return Futures.immediateFuture(buildResult.get());
      }
    }

    AtomicReference<CacheResult> rulekeyCacheResult = new AtomicReference<>();
    ListenableFuture<Optional<BuildResult>> buildResultFuture =
        Futures.immediateFuture(Optional.empty());

    // 2. Rule key cache lookup.
    buildResultFuture =
        transformBuildResultIfNotPresent(
            rule,
            buildContext,
            buildResultFuture,
            () -> {
              CacheResult cacheResult = performRuleKeyCacheCheck(rule, buildContext);
              rulekeyCacheResult.set(cacheResult);
              return getBuildResultForRuleKeyCacheResult(rule, cacheResult, buildInfoRecorder);
            },
            cacheActivityService.withDefaultAmounts(CACHE_CHECK_RESOURCE_AMOUNTS));

    // 3. Build deps.
    buildResultFuture =
        transformBuildResultAsyncIfNotPresent(
            rule,
            buildContext,
            buildResultFuture,
            () ->
                Futures.transformAsync(
                    getDepResults(rule, buildContext, executionContext, asyncCallbacks),
                    (depResults) -> handleDepsResults(rule, depResults),
                    serviceByAdjustingDefaultWeightsTo(SCHEDULING_MORE_WORK_RESOURCE_AMOUNTS)));

    // 4. Return to the current rule and check caches to see if we can avoid building
    // locally. Start with input-based.
    if (SupportsInputBasedRuleKey.isSupported(rule)) {
      buildResultFuture =
          transformBuildResultIfNotPresent(
              rule,
              buildContext,
              buildResultFuture,
              () -> checkInputBasedCaches(rule, buildContext, onDiskBuildInfo, buildInfoRecorder),
              cacheActivityService.withDefaultAmounts(CACHE_CHECK_RESOURCE_AMOUNTS));
    }

    // 5. Then check if the depfile matches.
    if (useDependencyFileRuleKey(rule)) {
      buildResultFuture =
          transformBuildResultIfNotPresent(
              rule,
              buildContext,
              buildResultFuture,
              () -> checkMatchingDepfile(rule, buildContext, onDiskBuildInfo, buildInfoRecorder),
              serviceByAdjustingDefaultWeightsTo(CACHE_CHECK_RESOURCE_AMOUNTS));
    }

    // 6. Check for a manifest-based cache hit.
    if (useManifestCaching(rule)) {
      buildResultFuture =
          transformBuildResultIfNotPresent(
              rule,
              buildContext,
              buildResultFuture,
              () -> checkManifestBasedCaches(rule, buildContext, buildInfoRecorder),
              serviceByAdjustingDefaultWeightsTo(CACHE_CHECK_RESOURCE_AMOUNTS));
    }

    // 7. Fail if populating the cache and cache lookups failed.
    if (buildMode == BuildMode.POPULATE_FROM_REMOTE_CACHE) {
      buildResultFuture =
          transformBuildResultIfNotPresent(
              rule,
              buildContext,
              buildResultFuture,
              () -> {
                LOG.info(
                    "Cannot populate cache for " + rule.getBuildTarget().getFullyQualifiedName());
                return Optional.of(
                    BuildResult.canceled(
                        rule,
                        new HumanReadableException(
                            "Skipping %s: in cache population mode local builds are disabled",
                            rule)));
              },
              MoreExecutors.newDirectExecutorService());
    }

    // 8. Build the current rule locally, if we have to.
    buildResultFuture =
        transformBuildResultIfNotPresent(
            rule,
            buildContext,
            buildResultFuture,
            () ->
                Optional.of(
                    buildLocally(
                        rule,
                        buildContext,
                        executionContext,
                        buildableContext,
                        Preconditions.checkNotNull(rulekeyCacheResult.get()))),
            // This needs to adjust the default amounts even in the non-resource-aware scheduling
            // case so that RuleScheduleInfo works correctly.
            service.withDefaultAmounts(getRuleResourceAmounts(rule)));

    // Unwrap the result.
    return Futures.transform(buildResultFuture, Optional::get);
  }

  private Optional<BuildResult> checkMatchingLocalKey(
      BuildRule rule, OnDiskBuildInfo onDiskBuildInfo) {
    Optional<RuleKey> cachedRuleKey = onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.RULE_KEY);
    final RuleKey defaultRuleKey = ruleKeyFactories.getDefaultRuleKeyFactory().build(rule);
    if (defaultRuleKey.equals(cachedRuleKey.orElse(null))) {
      return Optional.of(
          BuildResult.success(
              rule, BuildRuleSuccessType.MATCHING_RULE_KEY, CacheResult.localKeyUnchangedHit()));
    }
    return Optional.empty();
  }

  private CacheResult performRuleKeyCacheCheck(BuildRule rule, BuildEngineBuildContext buildContext)
      throws IOException {
    final RuleKey defaultRuleKey = ruleKeyFactories.getDefaultRuleKeyFactory().build(rule);
    return tryToFetchArtifactFromBuildCacheAndOverlayOnTopOfProjectFilesystem(
        rule,
        defaultRuleKey,
        buildContext.getArtifactCache(),
        // TODO(simons): This should be a shared between all tests, not one per cell
        rule.getProjectFilesystem(),
        buildContext);
  }

  private Optional<BuildResult> getBuildResultForRuleKeyCacheResult(
      BuildRule rule, CacheResult cacheResult, BuildInfoRecorder buildInfoRecorder) {
    if (!cacheResult.getType().isSuccess()) {
      return Optional.empty();
    }
    fillInOriginFromCache(cacheResult, buildInfoRecorder);
    fillMissingBuildMetadataFromCache(
        cacheResult,
        buildInfoRecorder,
        BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY,
        BuildInfo.MetadataKey.DEP_FILE_RULE_KEY,
        BuildInfo.MetadataKey.DEP_FILE);
    return Optional.of(
        BuildResult.success(rule, BuildRuleSuccessType.FETCHED_FROM_CACHE, cacheResult));
  }

  private ListenableFuture<Optional<BuildResult>> handleDepsResults(
      BuildRule rule, List<BuildResult> depResults) {
    for (BuildResult depResult : depResults) {
      if (buildMode != BuildMode.POPULATE_FROM_REMOTE_CACHE
          && depResult.getStatus() != BuildRuleStatus.SUCCESS) {
        return Futures.immediateFuture(
            Optional.of(
                BuildResult.canceled(rule, Preconditions.checkNotNull(depResult.getFailure()))));
      }
    }
    return Futures.immediateFuture(Optional.empty());
  }

  private boolean verifyRecordedPathHashes(
      BuildTarget target,
      ProjectFilesystem filesystem,
      ImmutableMap<String, String> recordedPathHashes)
      throws IOException {

    // Create a new `DefaultFileHashCache` to prevent caching from interfering with verification.
    ProjectFileHashCache fileHashCache =
        DefaultFileHashCache.createDefaultFileHashCache(filesystem);

    // Verify each path from the recorded path hashes entry matches the actual on-disk version.
    for (Map.Entry<String, String> ent : recordedPathHashes.entrySet()) {
      Path path = filesystem.getPath(ent.getKey());
      HashCode cachedHashCode = HashCode.fromString(ent.getValue());
      HashCode realHashCode = fileHashCache.get(path);
      if (!realHashCode.equals(cachedHashCode)) {
        LOG.debug(
            "%s: recorded hash for \"%s\" doesn't match actual hash: %s (cached) != %s (real).",
            target, path, cachedHashCode, realHashCode);
        return false;
      }
    }

    return true;
  }

  private boolean verifyRecordedPathHashes(
      BuildTarget target, ProjectFilesystem filesystem, String recordedPathHashesBlob)
      throws IOException {

    // Extract the recorded path hashes map.
    ImmutableMap<String, String> recordedPathHashes =
        ObjectMappers.readValue(
            recordedPathHashesBlob, new TypeReference<ImmutableMap<String, String>>() {});

    return verifyRecordedPathHashes(target, filesystem, recordedPathHashes);
  }

  private ListenableFuture<BuildResult> processBuildRule(
      BuildRule rule,
      BuildEngineBuildContext buildContext,
      ExecutionContext executionContext,
      ConcurrentLinkedQueue<ListenableFuture<Void>> asyncCallbacks) {

    final BuildInfoStore buildInfoStore =
        buildInfoStoreManager.get(rule.getProjectFilesystem(), metadataStorage);
    final OnDiskBuildInfo onDiskBuildInfo =
        buildContext.createOnDiskBuildInfoFor(
            rule.getBuildTarget(), rule.getProjectFilesystem(), buildInfoStore);
    final BuildInfoRecorder buildInfoRecorder =
        buildContext
            .createBuildInfoRecorder(
                rule.getBuildTarget(), rule.getProjectFilesystem(), buildInfoStore)
            .addBuildMetadata(
                BuildInfo.MetadataKey.RULE_KEY,
                ruleKeyFactories.getDefaultRuleKeyFactory().build(rule).toString())
            .addBuildMetadata(BuildInfo.MetadataKey.BUILD_ID, buildContext.getBuildId().toString());
    final BuildableContext buildableContext = new DefaultBuildableContext(buildInfoRecorder);
    final AtomicReference<Long> outputSize = Atomics.newReference();

    ListenableFuture<BuildResult> buildResult =
        buildOrFetchFromCache(
            rule,
            buildContext,
            executionContext,
            onDiskBuildInfo,
            buildInfoRecorder,
            buildableContext,
            asyncCallbacks);

    // Check immediately (without posting a new task) for a failure so that we can short-circuit
    // pending work. Use .catchingAsync() instead of .catching() so that we can propagate unchecked
    // exceptions.
    buildResult =
        Futures.catchingAsync(
            buildResult,
            Throwable.class,
            throwable -> {
              Preconditions.checkNotNull(throwable);
              firstFailure = throwable;
              Throwables.throwIfInstanceOf(throwable, Exception.class);
              throw new RuntimeException(throwable);
            });

    // If we're performing a deep build, guarantee that all dependencies will *always* get
    // materialized locally by chaining up to our result future.
    if (buildMode == BuildMode.DEEP || buildMode == BuildMode.POPULATE_FROM_REMOTE_CACHE) {
      buildResult =
          MoreFutures.chainExceptions(
              getDepResults(rule, buildContext, executionContext, asyncCallbacks), buildResult);
    }

    buildResult =
        Futures.transform(
            buildResult,
            (result) -> {
              markRuleAsUsed(rule, buildContext.getEventBus());
              return result;
            },
            MoreExecutors.directExecutor());

    // Setup a callback to handle either the cached or built locally cases.
    AsyncFunction<BuildResult, BuildResult> callback =
        input -> {

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
                onDiskBuildInfo.getValuesOrThrow(BuildInfo.MetadataKey.RECORDED_PATHS)) {
              buildInfoRecorder.recordArtifact(Paths.get(str));
            }
          }

          // Try get the output size now that all outputs have been recorded.
          if (success == BuildRuleSuccessType.BUILT_LOCALLY) {
            outputSize.set(buildInfoRecorder.getOutputSize());
          }

          // If the success type means the rule has potentially changed it's outputs...
          if (success.outputsHaveChanged()) {

            // The build has succeeded, whether we've fetched from cache, or built locally.
            // So run the post-build steps.
            if (rule instanceof HasPostBuildSteps) {
              executePostBuildSteps(
                  rule,
                  ((HasPostBuildSteps) rule).getPostBuildSteps(buildContext.getBuildContext()),
                  executionContext);
            }

            // Invalidate any cached hashes for the output paths, since we've updated them.
            for (Path path : buildInfoRecorder.getRecordedPaths()) {
              fileHashCache.invalidate(rule.getProjectFilesystem().resolve(path));
            }
          }

          // If this rule uses dep files and we built locally, make sure we store the new dep file
          // list and re-calculate the dep file rule key.
          if (useDependencyFileRuleKey(rule) && success == BuildRuleSuccessType.BUILT_LOCALLY) {

            // Query the rule for the actual inputs it used.
            ImmutableList<SourcePath> inputs =
                ((SupportsDependencyFileRuleKey) rule)
                    .getInputsAfterBuildingLocally(buildContext.getBuildContext());

            // Record the inputs into our metadata for next time.
            // TODO(#9117006): We don't support a way to serlialize `SourcePath`s to the cache,
            // so need to use DependencyFileEntry's instead and recover them on deserialization.
            ImmutableList<String> inputStrings =
                inputs
                    .stream()
                    .map(
                        inputString ->
                            DependencyFileEntry.fromSourcePath(inputString, pathResolver))
                    .map(MoreFunctions.toJsonFunction())
                    .collect(MoreCollectors.toImmutableList());
            buildInfoRecorder.addMetadata(BuildInfo.MetadataKey.DEP_FILE, inputStrings);

            // Re-calculate and store the depfile rule key for next time.
            Optional<RuleKeyAndInputs> depFileRuleKeyAndInputs =
                calculateDepFileRuleKey(
                    rule, buildContext, Optional.of(inputStrings), /* allowMissingInputs */ false);
            if (depFileRuleKeyAndInputs.isPresent()) {
              RuleKey depFileRuleKey = depFileRuleKeyAndInputs.get().getRuleKey();
              buildInfoRecorder.addBuildMetadata(
                  BuildInfo.MetadataKey.DEP_FILE_RULE_KEY, depFileRuleKey.toString());

              // Push an updated manifest to the cache.
              if (useManifestCaching(rule)) {
                Optional<RuleKeyAndInputs> manifestKey =
                    calculateManifestKey(rule, buildContext.getEventBus());
                if (manifestKey.isPresent()) {
                  buildInfoRecorder.addBuildMetadata(
                      BuildInfo.MetadataKey.MANIFEST_KEY,
                      manifestKey.get().getRuleKey().toString());
                  updateAndStoreManifest(
                      rule,
                      depFileRuleKeyAndInputs.get().getRuleKey(),
                      depFileRuleKeyAndInputs.get().getInputs(),
                      manifestKey.get(),
                      buildContext.getArtifactCache());
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
          if (success == BuildRuleSuccessType.BUILT_LOCALLY
              && shouldUploadToCache(
                  buildContext, rule, success, Preconditions.checkNotNull(outputSize.get()))) {
            ImmutableSortedMap.Builder<String, String> outputHashes =
                ImmutableSortedMap.naturalOrder();
            for (Path path : buildInfoRecorder.getOutputPaths()) {
              outputHashes.put(
                  path.toString(),
                  fileHashCache.get(rule.getProjectFilesystem().resolve(path)).toString());
            }
            buildInfoRecorder.addBuildMetadata(
                BuildInfo.MetadataKey.RECORDED_PATH_HASHES, outputHashes.build());
          }

          // If this rule was fetched from cache, seed the file hash cache with the recorded
          // output hashes from the build metadata.  Since outputs which have been changed have
          // already been invalidated above, this is purely a best-effort optimization -- if the
          // the output hashes weren't recorded in the cache we do nothing.
          if (success != BuildRuleSuccessType.BUILT_LOCALLY && success.outputsHaveChanged()) {
            Optional<ImmutableMap<String, String>> hashes =
                onDiskBuildInfo.getBuildMap(BuildInfo.MetadataKey.RECORDED_PATH_HASHES);

            // We only seed after first verifying the recorded path hashes.  This prevents the
            // optimization, but is useful to keep in place for a while to verify this optimization
            // is causing issues.
            if (hashes.isPresent()
                && verifyRecordedPathHashes(
                    rule.getBuildTarget(), rule.getProjectFilesystem(), hashes.get())) {

              // Seed the cache with the hashes.
              for (Map.Entry<String, String> ent : hashes.get().entrySet()) {
                Path path = rule.getProjectFilesystem().getPath(ent.getKey());
                HashCode hashCode = HashCode.fromString(ent.getValue());
                fileHashCache.set(rule.getProjectFilesystem().resolve(path), hashCode);
              }
            }
          }

          // Make sure the origin field is filled in.
          BuildId buildId = buildContext.getBuildId();
          if (success == BuildRuleSuccessType.BUILT_LOCALLY) {
            buildInfoRecorder.addBuildMetadata(
                BuildInfo.MetadataKey.ORIGIN_BUILD_ID, buildId.toString());
          } else if (success.outputsHaveChanged()) {
            Preconditions.checkState(
                buildInfoRecorder
                    .getBuildMetadataFor(BuildInfo.MetadataKey.ORIGIN_BUILD_ID)
                    .isPresent(),
                "Cache hits must populate the %s field (%s)",
                BuildInfo.MetadataKey.ORIGIN_BUILD_ID,
                success);
          }

          // Make sure that all of the local files have the same values they would as if the
          // rule had been built locally.
          buildInfoRecorder.addBuildMetadata(
              BuildInfo.MetadataKey.TARGET, rule.getBuildTarget().toString());
          buildInfoRecorder.addMetadata(
              BuildInfo.MetadataKey.RECORDED_PATHS,
              buildInfoRecorder
                  .getRecordedPaths()
                  .stream()
                  .map(Object::toString)
                  .collect(MoreCollectors.toImmutableList()));
          if (success.shouldWriteRecordedMetadataToDiskAfterBuilding()) {
            try {
              boolean clearExistingMetadata = success.shouldClearAndOverwriteMetadataOnDisk();
              buildInfoRecorder.writeMetadataToDisk(clearExistingMetadata);
            } catch (IOException e) {
              throw new IOException(
                  String.format("Failed to write metadata to disk for %s.", rule), e);
            }
          }

          // Give the rule a chance to populate its internal data structures now that all of
          // the files should be in a valid state.
          try {
            if (rule instanceof InitializableFromDisk) {
              doInitializeFromDisk((InitializableFromDisk<?>) rule, onDiskBuildInfo);
            }
          } catch (IOException e) {
            throw new IOException(
                String.format("Error initializing %s from disk: %s.", rule, e.getMessage()), e);
          }

          return Futures.immediateFuture(input);
        };
    buildResult =
        Futures.transformAsync(
            buildResult,
            ruleAsyncFunction(rule, buildContext.getEventBus(), callback),
            serviceByAdjustingDefaultWeightsTo(RULE_KEY_COMPUTATION_RESOURCE_AMOUNTS));

    // Handle either build success or failure.
    final SettableFuture<BuildResult> result = SettableFuture.create();
    asyncCallbacks.add(
        MoreFutures.addListenableCallback(
            buildResult,
            new FutureCallback<BuildResult>() {

              // TODO(mbolin): Delete all files produced by the rule, as they are not guaranteed
              // to be valid at this point?
              private void cleanupAfterError() {
                try {
                  onDiskBuildInfo.deleteExistingMetadata();
                } catch (Throwable t) {
                  buildContext
                      .getEventBus()
                      .post(
                          ThrowableConsoleEvent.create(
                              t, "Error when deleting metadata for %s.", rule));
                }
              }

              private void uploadToCache(BuildRuleSuccessType success) {

                // Collect up all the rule keys we have index the artifact in the cache with.
                Set<RuleKey> ruleKeys = Sets.newHashSet();

                // If the rule key has changed (and is not already in the cache), we need to push
                // the artifact to cache using the new key.
                ruleKeys.add(ruleKeyFactories.getDefaultRuleKeyFactory().build(rule));

                // If the input-based rule key has changed, we need to push the artifact to cache
                // using the new key.
                if (SupportsInputBasedRuleKey.isSupported(rule)) {
                  Optional<RuleKey> calculatedRuleKey =
                      calculateInputBasedRuleKey(rule, buildContext.getEventBus());
                  Optional<RuleKey> onDiskRuleKey =
                      onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY);
                  Optional<RuleKey> metaDataRuleKey =
                      buildInfoRecorder
                          .getBuildMetadataFor(BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY)
                          .map(RuleKey::new);
                  Preconditions.checkState(
                      calculatedRuleKey.equals(onDiskRuleKey),
                      "%s (%s): %s: invalid on-disk input-based rule key: %s != %s",
                      rule.getBuildTarget(),
                      rule.getType(),
                      success,
                      calculatedRuleKey,
                      onDiskRuleKey);
                  Preconditions.checkState(
                      calculatedRuleKey.equals(metaDataRuleKey),
                      "%s: %s: invalid meta-data input-based rule key: %s != %s",
                      rule.getBuildTarget(),
                      success,
                      calculatedRuleKey,
                      metaDataRuleKey);
                  ruleKeys.addAll(OptionalCompat.asSet(calculatedRuleKey));
                }

                // If the manifest-based rule key has changed, we need to push the artifact to cache
                // using the new key.
                if (useManifestCaching(rule)) {
                  Optional<RuleKey> onDiskRuleKey =
                      onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY);
                  Optional<RuleKey> metaDataRuleKey =
                      buildInfoRecorder
                          .getBuildMetadataFor(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY)
                          .map(RuleKey::new);
                  Preconditions.checkState(
                      onDiskRuleKey.equals(metaDataRuleKey),
                      "%s: %s: inconsistent meta-data and on-disk dep-file rule key: %s != %s",
                      rule.getBuildTarget(),
                      success,
                      onDiskRuleKey,
                      metaDataRuleKey);
                  ruleKeys.addAll(OptionalCompat.asSet(onDiskRuleKey));
                }

                // Do the actual upload.
                try {

                  // Verify that the recorded path hashes are accurate.
                  Optional<String> recordedPathHashes =
                      buildInfoRecorder.getBuildMetadataFor(
                          BuildInfo.MetadataKey.RECORDED_PATH_HASHES);
                  if (recordedPathHashes.isPresent()
                      && !verifyRecordedPathHashes(
                          rule.getBuildTarget(),
                          rule.getProjectFilesystem(),
                          recordedPathHashes.get())) {
                    return;
                  }

                  // Push to cache.
                  buildInfoRecorder.performUploadToArtifactCache(
                      ImmutableSet.copyOf(ruleKeys),
                      buildContext.getArtifactCache(),
                      buildContext.getEventBus());

                } catch (Throwable t) {
                  buildContext
                      .getEventBus()
                      .post(
                          ThrowableConsoleEvent.create(
                              t, "Error uploading to cache for %s.", rule));
                }
              }

              private void handleResult(BuildResult input) {
                Optional<Long> outputSize = Optional.empty();
                Optional<HashCode> outputHash = Optional.empty();
                Optional<BuildRuleSuccessType> successType = Optional.empty();

                BuildRuleEvent.Resumed resumedEvent =
                    BuildRuleEvent.resumed(
                        rule,
                        buildRuleDurationTracker,
                        ruleKeyFactories.getDefaultRuleKeyFactory());
                LOG.verbose(resumedEvent.toString());
                buildContext.getEventBus().post(resumedEvent);

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
                    if (success.shouldUploadResultingArtifact()) {
                      outputSize = Optional.of(buildInfoRecorder.getOutputSize());
                    }
                  } catch (IOException e) {
                    buildContext
                        .getEventBus()
                        .post(
                            ThrowableConsoleEvent.create(
                                e, "Error getting output size for %s.", rule));
                  }

                  // If this rule is cacheable...
                  if (outputSize.isPresent()
                      && shouldUploadToCache(buildContext, rule, success, outputSize.get())) {

                    // Upload it to the cache.
                    uploadToCache(success);

                    // Compute it's output hash for logging/tracing purposes, as this artifact will
                    // be consumed by other builds.
                    try {
                      outputHash = Optional.of(buildInfoRecorder.getOutputHash(fileHashCache));
                    } catch (IOException e) {
                      buildContext
                          .getEventBus()
                          .post(
                              ThrowableConsoleEvent.create(
                                  e, "Error getting output hash for %s.", rule));
                    }
                  }
                }

                boolean failureOrBuiltLocally =
                    input.getStatus() == BuildRuleStatus.FAIL
                        || input.getSuccess() == BuildRuleSuccessType.BUILT_LOCALLY;
                // Log the result to the event bus.
                BuildRuleEvent.Finished finished =
                    BuildRuleEvent.finished(
                        resumedEvent,
                        getBuildRuleKeys(rule, onDiskBuildInfo),
                        input.getStatus(),
                        input.getCacheResult(),
                        onDiskBuildInfo
                            .getBuildValue(BuildInfo.MetadataKey.ORIGIN_BUILD_ID)
                            .map(BuildId::new),
                        successType,
                        outputHash,
                        outputSize,
                        getBuildRuleDiagnosticData(rule, executionContext, failureOrBuiltLocally));
                LOG.verbose(finished.toString());
                buildContext.getEventBus().post(finished);
              }

              @Override
              public void onSuccess(BuildResult input) {
                handleResult(input);
              }

              @Override
              public void onFailure(@Nonnull Throwable thrown) {
                thrown = maybeAttachBuildRuleNameToException(thrown, rule);
                handleResult(BuildResult.failure(rule, thrown));

                // Reset interrupted flag once failure has been recorded.
                if (thrown instanceof InterruptedException) {
                  Thread.currentThread().interrupt();
                }
              }
            }));
    return result;
  }

  private BuildRuleKeys getBuildRuleKeys(BuildRule rule, OnDiskBuildInfo onDiskBuildInfo) {
    RuleKey defaultKey = ruleKeyFactories.getDefaultRuleKeyFactory().build(rule);
    Optional<RuleKey> inputKey =
        onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY);
    Optional<RuleKey> depFileKey =
        onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY);
    Optional<RuleKey> manifestKey = onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.MANIFEST_KEY);
    return BuildRuleKeys.builder()
        .setRuleKey(defaultKey)
        .setInputRuleKey(inputKey)
        .setDepFileRuleKey(depFileKey)
        .setManifestRuleKey(manifestKey)
        .build();
  }

  private Optional<BuildRuleDiagnosticData> getBuildRuleDiagnosticData(
      BuildRule rule, ExecutionContext executionContext, boolean failureOrBuiltLocally) {
    RuleKeyDiagnosticsMode mode = executionContext.getRuleKeyDiagnosticsMode();
    if (mode == RuleKeyDiagnosticsMode.NEVER
        || (mode == RuleKeyDiagnosticsMode.BUILT_LOCALLY && !failureOrBuiltLocally)) {
      return Optional.empty();
    }
    ImmutableList.Builder<RuleKeyDiagnostics.Result<?, ?>> diagnosticKeysBuilder =
        ImmutableList.builder();
    defaultRuleKeyDiagnostics.processRule(rule, diagnosticKeysBuilder::add);
    return Optional.of(
        new BuildRuleDiagnosticData(ruleDeps.get(rule), diagnosticKeysBuilder.build()));
  }

  private static Throwable maybeAttachBuildRuleNameToException(
      @Nonnull Throwable thrown, BuildRule rule) {
    if ((thrown instanceof HumanReadableException) || (thrown instanceof InterruptedException)) {
      return thrown;
    }
    String message = thrown.getMessage();
    if (message != null && message.contains(rule.toString())) {
      return thrown;
    }
    String betterMessage =
        String.format(
            "Building %s failed. Caused by %s",
            rule.getBuildTarget(), thrown.getClass().getSimpleName());
    if (thrown.getMessage() != null) {
      betterMessage += ": " + thrown.getMessage();
    }
    return new RuntimeException(betterMessage, thrown);
  }

  private void registerTopLevelRule(BuildRule rule, BuckEventBus eventBus) {
    unskippedRulesTracker.ifPresent(tracker -> tracker.registerTopLevelRule(rule, eventBus));
  }

  private void markRuleAsUsed(BuildRule rule, BuckEventBus eventBus) {
    unskippedRulesTracker.ifPresent(tracker -> tracker.markRuleAsUsed(rule, eventBus));
  }

  // Provide a future that resolves to the result of executing this rule and its runtime
  // dependencies.
  private ListenableFuture<BuildResult> getBuildRuleResultWithRuntimeDepsUnlocked(
      final BuildRule rule,
      final BuildEngineBuildContext buildContext,
      final ExecutionContext executionContext,
      final ConcurrentLinkedQueue<ListenableFuture<Void>> asyncCallbacks) {

    // If the rule is already executing, return its result future from the cache.
    ListenableFuture<BuildResult> existingResult = results.get(rule.getBuildTarget());
    if (existingResult != null) {
      return existingResult;
    }

    // Get the future holding the result for this rule and, if we have no additional runtime deps
    // to attach, return it.
    ListenableFuture<RuleKey> ruleKey = calculateRuleKey(rule, buildContext);
    ListenableFuture<BuildResult> result =
        Futures.transformAsync(
            ruleKey,
            input -> processBuildRule(rule, buildContext, executionContext, asyncCallbacks),
            serviceByAdjustingDefaultWeightsTo(SCHEDULING_MORE_WORK_RESOURCE_AMOUNTS));
    if (!(rule instanceof HasRuntimeDeps)) {
      results.put(rule.getBuildTarget(), result);
      return result;
    }

    // Collect any runtime deps we have into a list of futures.
    Stream<BuildTarget> runtimeDepPaths = ((HasRuntimeDeps) rule).getRuntimeDeps();
    List<ListenableFuture<BuildResult>> runtimeDepResults = new ArrayList<>();
    ImmutableSet<BuildRule> runtimeDeps =
        resolver.getAllRules(runtimeDepPaths.collect(MoreCollectors.toImmutableSet()));
    for (BuildRule dep : runtimeDeps) {
      runtimeDepResults.add(
          getBuildRuleResultWithRuntimeDepsUnlocked(
              dep, buildContext, executionContext, asyncCallbacks));
    }

    // Create a new combined future, which runs the original rule and all the runtime deps in
    // parallel, but which propagates an error if any one of them fails.
    // It also checks that all runtime deps succeeded.
    ListenableFuture<BuildResult> chainedResult =
        Futures.transformAsync(
            Futures.allAsList(runtimeDepResults),
            results ->
                !buildContext.isKeepGoing() && firstFailure != null
                    ? Futures.immediateFuture(BuildResult.canceled(rule, firstFailure))
                    : result,
            MoreExecutors.directExecutor());
    results.put(rule.getBuildTarget(), chainedResult);
    return chainedResult;
  }

  private ListenableFuture<BuildResult> getBuildRuleResultWithRuntimeDeps(
      BuildRule rule,
      BuildEngineBuildContext buildContext,
      ExecutionContext executionContext,
      ConcurrentLinkedQueue<ListenableFuture<Void>> asyncCallbacks) {

    // If the rule is already executing, return it's result future from the cache without acquiring
    // the lock.
    ListenableFuture<BuildResult> existingResult = results.get(rule.getBuildTarget());
    if (existingResult != null) {
      return existingResult;
    }

    // Otherwise, grab the lock and delegate to the real method,
    synchronized (results) {
      return getBuildRuleResultWithRuntimeDepsUnlocked(
          rule, buildContext, executionContext, asyncCallbacks);
    }
  }

  public ListenableFuture<?> walkRule(BuildRule rule, final Set<BuildRule> seen) {
    return Futures.transformAsync(
        Futures.immediateFuture(ruleDeps.get(rule)),
        deps -> {
          List<ListenableFuture<?>> results1 = Lists.newArrayListWithExpectedSize(deps.size());
          for (BuildRule dep : deps) {
            if (seen.add(dep)) {
              results1.add(walkRule(dep, seen));
            }
          }
          return Futures.allAsList(results1);
        },
        serviceByAdjustingDefaultWeightsTo(SCHEDULING_MORE_WORK_RESOURCE_AMOUNTS));
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
      final BuildRule rule, final BuildEngineBuildContext context) {
    ListenableFuture<RuleKey> fromOurCache = ruleKeys.get(rule.getBuildTarget());
    if (fromOurCache != null) {
      return fromOurCache;
    }

    RuleKey fromInternalCache = ruleKeyFactories.getDefaultRuleKeyFactory().getFromCache(rule);
    if (fromInternalCache != null) {
      ListenableFuture<RuleKey> future = Futures.immediateFuture(fromInternalCache);
      // Record the rule key future.
      ruleKeys.put(rule.getBuildTarget(), future);
      // Because a rule key will be invalidated from the internal cache any time one of its
      // dependents is invalidated, we know that all of our transitive deps are also in cache.
      return future;
    }

    // Grab all the dependency rule key futures.  Since our rule key calculation depends on this
    // one, we need to wait for them to complete.
    ListenableFuture<List<RuleKey>> depKeys =
        Futures.transformAsync(
            Futures.immediateFuture(ruleDeps.get(rule)),
            deps -> {
              List<ListenableFuture<RuleKey>> depKeys1 =
                  Lists.newArrayListWithExpectedSize(rule.getBuildDeps().size());
              for (BuildRule dep : deps) {
                depKeys1.add(calculateRuleKey(dep, context));
              }
              return Futures.allAsList(depKeys1);
            },
            serviceByAdjustingDefaultWeightsTo(RULE_KEY_COMPUTATION_RESOURCE_AMOUNTS));

    // Setup a future to calculate this rule key once the dependencies have been calculated.
    ListenableFuture<RuleKey> calculated =
        Futures.transform(
            depKeys,
            (List<RuleKey> input) -> {
              try (BuildRuleEvent.Scope scope =
                  BuildRuleEvent.ruleKeyCalculationScope(
                      context.getEventBus(),
                      rule,
                      buildRuleDurationTracker,
                      ruleKeyFactories.getDefaultRuleKeyFactory())) {
                return ruleKeyFactories.getDefaultRuleKeyFactory().build(rule);
              }
            },
            serviceByAdjustingDefaultWeightsTo(RULE_KEY_COMPUTATION_RESOURCE_AMOUNTS));

    // Record the rule key future.
    ruleKeys.put(rule.getBuildTarget(), calculated);
    return calculated;
  }

  @Override
  public BuildEngineResult build(
      BuildEngineBuildContext buildContext, ExecutionContext executionContext, BuildRule rule) {
    // Keep track of all jobs that run asynchronously with respect to the build dep chain.  We want
    // to make sure we wait for these before calling yielding the final build result.
    final ConcurrentLinkedQueue<ListenableFuture<Void>> asyncCallbacks =
        new ConcurrentLinkedQueue<>();
    registerTopLevelRule(rule, buildContext.getEventBus());
    ListenableFuture<BuildResult> resultFuture =
        getBuildRuleResultWithRuntimeDeps(rule, buildContext, executionContext, asyncCallbacks);
    return BuildEngineResult.builder()
        .setResult(
            Futures.transformAsync(
                resultFuture,
                result ->
                    Futures.transform(
                        Futures.allAsList(asyncCallbacks), Functions.constant(result)),
                serviceByAdjustingDefaultWeightsTo(SCHEDULING_MORE_WORK_RESOURCE_AMOUNTS)))
        .build();
  }

  private CacheResult tryToFetchArtifactFromBuildCacheAndOverlayOnTopOfProjectFilesystem(
      final BuildRule rule,
      final RuleKey ruleKey,
      final ArtifactCache artifactCache,
      final ProjectFilesystem filesystem,
      final BuildEngineBuildContext buildContext)
      throws IOException {

    if (!rule.isCacheable()) {
      return CacheResult.ignored();
    }

    // Create a temp file whose extension must be ".zip" for Filesystems.newFileSystem() to infer
    // that we are creating a zip-based FileSystem.
    final LazyPath lazyZipPath =
        new LazyPath() {
          @Override
          protected Path create() throws IOException {
            return Files.createTempFile(
                "buck_artifact_" + MoreFiles.sanitize(rule.getBuildTarget().getShortName()),
                ".zip");
          }
        };

    // TODO(mbolin): Change ArtifactCache.fetch() so that it returns a File instead of takes one.
    // Then we could download directly from the remote cache into the on-disk cache and unzip it
    // from there.
    CacheResult cacheResult = artifactCache.fetch(ruleKey, lazyZipPath);

    // Verify that the rule key we used to fetch the artifact is one of the rule keys reported in
    // it's metadata.
    if (cacheResult.getType().isSuccess()) {
      ImmutableSet<RuleKey> ruleKeys =
          RichStream.from(cacheResult.getMetadata().entrySet())
              .filter(e -> BuildInfo.RULE_KEY_NAMES.contains(e.getKey()))
              .map(Map.Entry::getValue)
              .map(RuleKey::new)
              .toImmutableSet();
      if (!ruleKeys.contains(ruleKey)) {
        LOG.warn(
            "%s: rule keys in artifact don't match rule key used to fetch it: %s not in %s",
            rule.getBuildTarget(), ruleKey, ruleKeys);
      }
    }

    return unzipArtifactFromCacheResult(
        rule, ruleKey, lazyZipPath, buildContext, filesystem, cacheResult);
  }

  private CacheResult unzipArtifactFromCacheResult(
      BuildRule rule,
      RuleKey ruleKey,
      LazyPath lazyZipPath,
      BuildEngineBuildContext buildContext,
      ProjectFilesystem filesystem,
      CacheResult cacheResult)
      throws IOException {

    // We only unpack artifacts from hits.
    if (!cacheResult.getType().isSuccess()) {
      LOG.debug("Cache miss for '%s' with rulekey '%s'", rule, ruleKey);
      return cacheResult;
    }
    Preconditions.checkArgument(cacheResult.getType() == CacheResultType.HIT);
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
    ArtifactCompressionEvent.Started started =
        ArtifactCompressionEvent.started(
            ArtifactCompressionEvent.Operation.DECOMPRESS, ImmutableSet.of(ruleKey));
    buildContext.getEventBus().post(started);
    try {

      // First, clear out the pre-existing metadata directory.  We have to do this *before*
      // unpacking the zipped artifact, as it includes files that will be stored in the metadata
      // directory.
      BuildInfoStore buildInfoStore =
          buildInfoStoreManager.get(rule.getProjectFilesystem(), metadataStorage);
      buildInfoStore.deleteMetadata(rule.getBuildTarget());

      // Always remove the on-disk metadata dir, as some pieces of metadata are still stored here
      // (e.g. `DEP_FILE`, manifest).
      Path metadataDir =
          BuildInfo.getPathToMetadataDirectory(rule.getBuildTarget(), rule.getProjectFilesystem());
      rule.getProjectFilesystem().deleteRecursivelyIfExists(metadataDir);

      Unzip.extractZipFile(
          zipPath.toAbsolutePath(),
          filesystem,
          Unzip.ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);

      // We only delete the ZIP file when it has been unzipped successfully. Otherwise, we leave it
      // around for debugging purposes.
      Files.delete(zipPath);

      // Also write out the build metadata.
      buildInfoStore.updateMetadata(rule.getBuildTarget(), cacheResult.getMetadata());
    } finally {
      buildContext.getEventBus().post(ArtifactCompressionEvent.finished(started));
    }

    return cacheResult;
  }

  /**
   * Execute the commands for this build rule. Requires all dependent rules are already built
   * successfully.
   */
  private void executeCommandsNowThatDepsAreBuilt(
      BuildRule rule,
      BuildEngineBuildContext buildContext,
      ExecutionContext executionContext,
      BuildableContext buildableContext)
      throws InterruptedException, StepFailedException {

    LOG.debug("Building locally: %s", rule);
    // Attempt to get an approximation of how long it takes to actually run the command.
    @SuppressWarnings("PMD.PrematureDeclaration")
    long start = System.nanoTime();

    buildContext.getEventBus().post(BuildRuleEvent.willBuildLocally(rule));
    cachingBuildEngineDelegate.onRuleAboutToBeBuilt(rule);

    // Get and run all of the commands.
    List<? extends Step> steps =
        rule.getBuildSteps(buildContext.getBuildContext(), buildableContext);

    Optional<BuildTarget> optionalTarget = Optional.of(rule.getBuildTarget());
    for (Step step : steps) {
      stepRunner.runStepForBuildTarget(
          executionContext.withProcessExecutor(
              new ContextualProcessExecutor(
                  executionContext.getProcessExecutor(),
                  ImmutableMap.of(
                      BUILD_RULE_TYPE_CONTEXT_KEY,
                      rule.getType(),
                      STEP_TYPE_CONTEXT_KEY,
                      StepType.BUILD_STEP.toString()))),
          step,
          optionalTarget);

      // Check for interruptions that may have been ignored by step.
      if (Thread.interrupted()) {
        Thread.currentThread().interrupt();
        throw new InterruptedException();
      }
    }

    long end = System.nanoTime();
    LOG.debug(
        "Build completed: %s %s (%dns)", rule.getType(), rule.getFullyQualifiedName(), end - start);
  }

  private void executePostBuildSteps(
      BuildRule rule, Iterable<Step> postBuildSteps, ExecutionContext context)
      throws InterruptedException, StepFailedException {

    LOG.debug("Running post-build steps for %s", rule);

    Optional<BuildTarget> optionalTarget = Optional.of(rule.getBuildTarget());
    for (Step step : postBuildSteps) {
      stepRunner.runStepForBuildTarget(
          context.withProcessExecutor(
              new ContextualProcessExecutor(
                  context.getProcessExecutor(),
                  ImmutableMap.of(
                      BUILD_RULE_TYPE_CONTEXT_KEY,
                      rule.getType(),
                      STEP_TYPE_CONTEXT_KEY,
                      StepType.POST_BUILD_STEP.toString()))),
          step,
          optionalTarget);

      // Check for interruptions that may have been ignored by step.
      if (Thread.interrupted()) {
        Thread.currentThread().interrupt();
        throw new InterruptedException();
      }
    }

    LOG.debug("Finished running post-build steps for %s", rule);
  }

  private <T> void doInitializeFromDisk(
      InitializableFromDisk<T> initializable, OnDiskBuildInfo onDiskBuildInfo) throws IOException {
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

  /** @return whether we should upload the given rules artifacts to cache. */
  private boolean shouldUploadToCache(
      BuildEngineBuildContext buildContext,
      BuildRule rule,
      BuildRuleSuccessType successType,
      long outputSize) {

    // The success type must allow cache uploading.
    if (!successType.shouldUploadResultingArtifact()) {
      return false;
    }

    // The cache must be writable.
    if (!buildContext.getArtifactCache().getCacheReadMode().isWritable()) {
      return false;
    }

    // If the rule is explicitly marked uncacheable, don't cache it.
    if (!rule.isCacheable()) {
      return false;
    }

    // If the rule's outputs are bigger than the preset size limit, don't cache it.
    if (artifactCacheSizeLimit.isPresent() && outputSize > artifactCacheSizeLimit.get()) {
      return false;
    }

    return true;
  }

  private boolean useDependencyFileRuleKey(BuildRule rule) {
    return depFiles != DepFiles.DISABLED
        && rule instanceof SupportsDependencyFileRuleKey
        && ((SupportsDependencyFileRuleKey) rule).useDependencyFileRuleKeys();
  }

  private boolean useManifestCaching(BuildRule rule) {
    return depFiles == DepFiles.CACHE
        && rule instanceof SupportsDependencyFileRuleKey
        && rule.isCacheable()
        && ((SupportsDependencyFileRuleKey) rule).useDependencyFileRuleKeys();
  }

  private Optional<RuleKeyAndInputs> calculateDepFileRuleKey(
      BuildRule rule,
      BuildEngineBuildContext context,
      Optional<ImmutableList<String>> depFile,
      boolean allowMissingInputs)
      throws IOException {

    Preconditions.checkState(useDependencyFileRuleKey(rule));

    // Extract the dep file from the last build.  If we don't find one, abort.
    if (!depFile.isPresent()) {
      return Optional.empty();
    }

    // Build the dep-file rule key.  If any inputs are no longer on disk, this means something
    // changed and a dep-file based rule key can't be calculated.
    ImmutableList<DependencyFileEntry> inputs =
        depFile
            .get()
            .stream()
            .map(MoreFunctions.fromJsonFunction(DependencyFileEntry.class))
            .collect(MoreCollectors.toImmutableList());

    try (BuckEvent.Scope scope =
        RuleKeyCalculationEvent.scope(
            context.getEventBus(), RuleKeyCalculationEvent.Type.DEP_FILE)) {
      return Optional.of(
          this.ruleKeyFactories
              .getDepFileRuleKeyFactory()
              .build(((SupportsDependencyFileRuleKey) rule), inputs));
    } catch (SizeLimiter.SizeLimitException ex) {
      return Optional.empty();
    } catch (Exception e) {
      // TODO(plamenko): fix exception propagation in RuleKeyBuilder
      if (allowMissingInputs && Throwables.getRootCause(e) instanceof NoSuchFileException) {
        return Optional.empty();
      }
      throw e;
    }
  }

  @VisibleForTesting
  protected Path getManifestPath(BuildRule rule) {
    return BuildInfo.getPathToMetadataDirectory(rule.getBuildTarget(), rule.getProjectFilesystem())
        .resolve(BuildInfo.MANIFEST);
  }

  @VisibleForTesting
  protected Optional<RuleKey> getManifestRuleKey(
      SupportsDependencyFileRuleKey rule, BuckEventBus eventBus) throws IOException {
    return calculateManifestKey(rule, eventBus).map(RuleKeyAndInputs::getRuleKey);
  }

  // Update the on-disk manifest with the new dep-file rule key and push it to the cache.
  private void updateAndStoreManifest(
      BuildRule rule,
      RuleKey key,
      ImmutableSet<SourcePath> inputs,
      RuleKeyAndInputs manifestKey,
      ArtifactCache cache)
      throws IOException {

    Preconditions.checkState(useManifestCaching(rule));

    final Path manifestPath = getManifestPath(rule);
    Manifest manifest = new Manifest(manifestKey.getRuleKey());

    // If we already have a manifest downloaded, use that.
    if (rule.getProjectFilesystem().exists(manifestPath)) {
      try (InputStream inputStream = rule.getProjectFilesystem().newFileInputStream(manifestPath)) {
        Manifest existingManifest = new Manifest(inputStream);
        if (existingManifest.getKey().equals(manifestKey.getRuleKey())) {
          manifest = existingManifest;
        }
      } catch (Exception e) {
        LOG.error(e, "Failed to deserialize on-disk manifest for rule %s.", rule);
      }
    } else {
      // Ensure the path to manifest exist
      rule.getProjectFilesystem().createParentDirs(manifestPath);
    }

    // If the manifest is larger than the max size, just truncate it.  It might be nice to support
    // some sort of LRU management here to avoid evicting everything, but it'll take some care to do
    // this efficiently and it's not clear how much benefit this will give us.
    if (manifest.size() >= maxDepFileCacheEntries) {
      manifest = new Manifest(manifestKey.getRuleKey());
    }

    // Update the manifest with the new output rule key.
    manifest.addEntry(fileHashCache, key, pathResolver, manifestKey.getInputs(), inputs);

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
            new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(tempFile)))) {
      ByteStreams.copy(inputStream, outputStream);
    }
    cache
        .store(
            ArtifactInfo.builder().addRuleKeys(manifestKey.getRuleKey()).build(),
            BorrowablePath.borrowablePath(tempFile))
        .addListener(
            () -> {
              try {
                Files.deleteIfExists(tempFile);
              } catch (IOException e) {
                LOG.warn(
                    e,
                    "Error occurred while deleting temporary manifest file for %s",
                    manifestPath);
              }
            },
            MoreExecutors.directExecutor());
  }

  private Optional<RuleKeyAndInputs> calculateManifestKey(BuildRule rule, BuckEventBus eventBus)
      throws IOException {
    try (BuckEvent.Scope scope =
        RuleKeyCalculationEvent.scope(eventBus, RuleKeyCalculationEvent.Type.MANIFEST)) {
      return Optional.of(
          ruleKeyFactories
              .getDepFileRuleKeyFactory()
              .buildManifestKey((SupportsDependencyFileRuleKey) rule));
    } catch (SizeLimiter.SizeLimitException ex) {
      return Optional.empty();
    }
  }

  // Fetch an artifact from the cache using manifest-based caching.
  private Optional<BuildResult> performManifestBasedCacheFetch(
      final BuildRule rule,
      final BuildEngineBuildContext context,
      BuildInfoRecorder buildInfoRecorder,
      RuleKeyAndInputs manifestKey)
      throws IOException {
    Preconditions.checkArgument(useManifestCaching(rule));

    final LazyPath tempFile =
        new LazyPath() {
          @Override
          protected Path create() throws IOException {
            return Files.createTempFile("buck.", ".manifest");
          }
        };

    CacheResult manifestResult =
        context.getArtifactCache().fetch(manifestKey.getRuleKey(), tempFile);

    if (!manifestResult.getType().isSuccess()) {
      return Optional.empty();
    }

    Path manifestPath = getManifestPath(rule);

    // Clear out any existing manifest.
    rule.getProjectFilesystem().deleteFileAtPathIfExists(manifestPath);

    // Now, fetch an existing manifest from the cache.
    rule.getProjectFilesystem().createParentDirs(manifestPath);

    try (OutputStream outputStream = rule.getProjectFilesystem().newFileOutputStream(manifestPath);
        InputStream inputStream =
            new GZIPInputStream(new BufferedInputStream(Files.newInputStream(tempFile.get())))) {
      ByteStreams.copy(inputStream, outputStream);
    }
    Files.delete(tempFile.get());

    // Deserialize the manifest.
    Manifest manifest;
    try (InputStream input = rule.getProjectFilesystem().newFileInputStream(manifestPath)) {
      manifest = new Manifest(input);
    } catch (Exception e) {
      LOG.error(
          e,
          "Failed to deserialize fetched-from-cache manifest for rule %s with key %s",
          rule,
          manifestKey.getRuleKey());
      return Optional.empty();
    }

    // Verify the manifest.
    Preconditions.checkState(
        manifest.getKey().equals(manifestKey.getRuleKey()),
        "%s: found incorrectly keyed manifest: %s != %s",
        rule.getBuildTarget(),
        manifestKey.getRuleKey(),
        manifest.getKey());

    // Lookup the rule for the current state of our inputs.
    Optional<RuleKey> ruleKey =
        manifest.lookup(fileHashCache, pathResolver, manifestKey.getInputs());
    if (!ruleKey.isPresent()) {
      return Optional.empty();
    }

    CacheResult cacheResult =
        tryToFetchArtifactFromBuildCacheAndOverlayOnTopOfProjectFilesystem(
            rule,
            ruleKey.get(),
            context.getArtifactCache(),
            // TODO(simons): This should be shared between all tests, not one per cell
            rule.getProjectFilesystem(),
            context);

    if (cacheResult.getType().isSuccess()) {
      fillInOriginFromCache(cacheResult, buildInfoRecorder);
      fillMissingBuildMetadataFromCache(
          cacheResult,
          buildInfoRecorder,
          BuildInfo.MetadataKey.DEP_FILE_RULE_KEY,
          BuildInfo.MetadataKey.DEP_FILE);
      return Optional.of(
          BuildResult.success(
              rule, BuildRuleSuccessType.FETCHED_FROM_CACHE_MANIFEST_BASED, cacheResult));
    }
    return Optional.empty();
  }

  private Optional<RuleKey> calculateInputBasedRuleKey(BuildRule rule, BuckEventBus eventBus) {
    try (BuckEvent.Scope scope =
        RuleKeyCalculationEvent.scope(eventBus, RuleKeyCalculationEvent.Type.INPUT)) {
      return Optional.of(ruleKeyFactories.getInputBasedRuleKeyFactory().build(rule));
    } catch (SizeLimiter.SizeLimitException ex) {
      return Optional.empty();
    }
  }

  private Optional<BuildResult> performInputBasedCacheFetch(
      final BuildRule rule,
      final BuildEngineBuildContext context,
      final OnDiskBuildInfo onDiskBuildInfo,
      BuildInfoRecorder buildInfoRecorder,
      RuleKey inputRuleKey)
      throws IOException {
    Preconditions.checkArgument(SupportsInputBasedRuleKey.isSupported(rule));

    buildInfoRecorder.addBuildMetadata(
        BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY, inputRuleKey.toString());

    // Check the input-based rule key says we're already built.
    Optional<RuleKey> lastInputRuleKey =
        onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY);
    if (inputRuleKey.equals(lastInputRuleKey.orElse(null))) {
      return Optional.of(
          BuildResult.success(
              rule,
              BuildRuleSuccessType.MATCHING_INPUT_BASED_RULE_KEY,
              CacheResult.localKeyUnchangedHit()));
    }

    // Try to fetch the artifact using the input-based rule key.
    CacheResult cacheResult =
        tryToFetchArtifactFromBuildCacheAndOverlayOnTopOfProjectFilesystem(
            rule,
            inputRuleKey,
            context.getArtifactCache(),
            // TODO(simons): Share this between all tests, not one per cell.
            rule.getProjectFilesystem(),
            context);

    if (cacheResult.getType().isSuccess()) {
      fillInOriginFromCache(cacheResult, buildInfoRecorder);
      fillMissingBuildMetadataFromCache(
          cacheResult,
          buildInfoRecorder,
          BuildInfo.MetadataKey.DEP_FILE_RULE_KEY,
          BuildInfo.MetadataKey.DEP_FILE);
      return Optional.of(
          BuildResult.success(
              rule, BuildRuleSuccessType.FETCHED_FROM_CACHE_INPUT_BASED, cacheResult));
    }

    return Optional.empty();
  }

  private ResourceAmounts getRuleResourceAmounts(BuildRule rule) {
    if (resourceAwareSchedulingInfo.isResourceAwareSchedulingEnabled()) {
      return resourceAwareSchedulingInfo.getResourceAmountsForRule(rule);
    } else {
      return getResourceAmountsForRuleWithCustomScheduleInfo(rule);
    }
  }

  private ResourceAmounts getResourceAmountsForRuleWithCustomScheduleInfo(BuildRule rule) {
    Preconditions.checkArgument(!resourceAwareSchedulingInfo.isResourceAwareSchedulingEnabled());
    RuleScheduleInfo ruleScheduleInfo;
    if (rule instanceof OverrideScheduleRule) {
      ruleScheduleInfo = ((OverrideScheduleRule) rule).getRuleScheduleInfo();
    } else {
      ruleScheduleInfo = RuleScheduleInfo.DEFAULT;
    }
    return ResourceAmounts.of(ruleScheduleInfo.getJobsMultiplier(), 0, 0, 0);
  }

  /** The mode in which to build rules. */
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

  /** Whether to use dependency files or not. */
  public enum DepFiles {
    ENABLED,
    DISABLED,
    CACHE,
  }

  public enum MetadataStorage {
    FILESYSTEM,
    SQLITE,
    MAPDB,
    ROCKSDB,
  }

  // Wrap an async function in rule resume/suspend events.
  private <F, T> AsyncFunction<F, T> ruleAsyncFunction(
      final BuildRule rule, final BuckEventBus eventBus, final AsyncFunction<F, T> delegate) {
    return input -> {
      try (BuildRuleEvent.Scope event =
          BuildRuleEvent.resumeSuspendScope(
              eventBus,
              rule,
              buildRuleDurationTracker,
              ruleKeyFactories.getDefaultRuleKeyFactory())) {
        return delegate.apply(input);
      }
    };
  }

  private boolean shouldKeepGoing(BuildEngineBuildContext buildContext) {
    return firstFailure == null
        || buildMode == BuildMode.POPULATE_FROM_REMOTE_CACHE
        || buildContext.isKeepGoing();
  }

  private ListenableFuture<Optional<BuildResult>> transformBuildResultIfNotPresent(
      BuildRule rule,
      BuildEngineBuildContext context,
      ListenableFuture<Optional<BuildResult>> future,
      Callable<Optional<BuildResult>> function,
      ListeningExecutorService executor) {
    return transformBuildResultAsyncIfNotPresent(
        rule,
        context,
        future,
        () ->
            executor.submit(
                () -> {
                  if (!shouldKeepGoing(context)) {
                    Preconditions.checkNotNull(firstFailure);
                    return Optional.of(BuildResult.canceled(rule, firstFailure));
                  }
                  try (BuildRuleEvent.Scope scope =
                      BuildRuleEvent.resumeSuspendScope(
                          context.getEventBus(),
                          rule,
                          buildRuleDurationTracker,
                          ruleKeyFactories.getDefaultRuleKeyFactory())) {
                    return function.call();
                  }
                }));
  }

  private ListenableFuture<Optional<BuildResult>> transformBuildResultAsyncIfNotPresent(
      BuildRule rule,
      BuildEngineBuildContext context,
      ListenableFuture<Optional<BuildResult>> future,
      Callable<ListenableFuture<Optional<BuildResult>>> function) {
    // Immediately (i.e. without posting a task), returns the current result if it's already present
    // or a cancelled result if we've already seen a failure.
    return Futures.transformAsync(
        future,
        (result) -> {
          if (result.isPresent()) {
            return Futures.immediateFuture(result);
          }
          if (!shouldKeepGoing(context)) {
            Preconditions.checkNotNull(firstFailure);
            return Futures.immediateFuture(Optional.of(BuildResult.canceled(rule, firstFailure)));
          }
          return function.call();
        },
        MoreExecutors.directExecutor());
  }
}
