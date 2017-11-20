/*
 * Copyright 2017-present Facebook, Inc.
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
import com.facebook.buck.artifact_cache.ArtifactUploader;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.artifact_cache.RuleKeyCacheResult;
import com.facebook.buck.artifact_cache.RuleKeyCacheResultEvent;
import com.facebook.buck.event.ArtifactCompressionEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.LeafEvents;
import com.facebook.buck.event.RuleKeyCalculationEvent;
import com.facebook.buck.event.ThrowableConsoleEvent;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.io.file.MoreFiles;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.keys.DependencyFileEntry;
import com.facebook.buck.rules.keys.RuleKeyAndInputs;
import com.facebook.buck.rules.keys.RuleKeyDiagnostics;
import com.facebook.buck.rules.keys.RuleKeyFactories;
import com.facebook.buck.rules.keys.RuleKeyFactoryWithDiagnostics;
import com.facebook.buck.rules.keys.RuleKeyType;
import com.facebook.buck.rules.keys.SizeLimiter;
import com.facebook.buck.rules.keys.SupportsDependencyFileRuleKey;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.util.ContextualProcessExecutor;
import com.facebook.buck.util.Discardable;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.Threads;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.concurrent.MoreFutures;
import com.facebook.buck.util.concurrent.ResourceAmounts;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.util.zip.Unzip;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
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
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipFile;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

class CachingBuildRuleBuilder {
  private static final Logger LOG = Logger.get(CachingBuildRuleBuilder.class);
  private final BuildRuleBuilderDelegate buildRuleBuilderDelegate;
  private final Optional<Long> artifactCacheSizeLimit;
  private final BuildInfoStoreManager buildInfoStoreManager;
  private final CachingBuildEngine.BuildMode buildMode;
  private final BuildRuleDurationTracker buildRuleDurationTracker;
  private final boolean consoleLogBuildFailuresInline;
  private final RuleKeyDiagnostics<RuleKey, String> defaultRuleKeyDiagnostics;
  private final CachingBuildEngine.DepFiles depFiles;
  private final FileHashCache fileHashCache;
  private final long maxDepFileCacheEntries;
  private final CachingBuildEngine.MetadataStorage metadataStorage;
  private final SourcePathResolver pathResolver;
  private final ResourceAwareSchedulingInfo resourceAwareSchedulingInfo;
  private final RuleKeyFactories ruleKeyFactories;
  private final WeightedListeningExecutorService service;
  private final StepRunner stepRunner;
  private final RuleDepsCache ruleDeps;
  private final BuildRule rule;
  private final ExecutionContext executionContext;
  private final OnDiskBuildInfo onDiskBuildInfo;
  private final Discardable<BuildInfoRecorder> buildInfoRecorder;
  private final BuildableContext buildableContext;
  private final BuildRulePipelinesRunner pipelinesRunner;
  private final BuckEventBus eventBus;
  private final BuildContext buildRuleBuildContext;
  private final ArtifactCache artifactCache;
  private final BuildId buildId;
  private final RemoteBuildRuleCompletionWaiter remoteBuildRuleCompletionWaiter;

  private final BuildRuleScopeManager buildRuleScopeManager;

  private final RuleKey defaultKey;

  private final Supplier<Optional<RuleKey>> inputBasedKey;

  /**
   * This is used to weakly cache the manifest RuleKeyAndInputs. I
   *
   * <p>This is necessary because RuleKeyAndInputs may be very large, and due to the async nature of
   * CachingBuildRuleBuilder, there may be many BuildRules that are in-between two stages that both
   * need the manifest's RuleKeyAndInputs. If we just stored the RuleKeyAndInputs directly, we could
   * use too much memory.
   */
  private final Supplier<Optional<RuleKeyAndInputs>> manifestBasedKeySupplier;

  // These fields contain data that may be computed during a build.

  private volatile ListenableFuture<Void> uploadCompleteFuture = Futures.immediateFuture(null);
  private volatile boolean depsAreAvailable;
  @Nullable private volatile ManifestFetchResult manifestFetchResult = null;
  @Nullable private volatile ManifestStoreResult manifestStoreResult = null;

  public CachingBuildRuleBuilder(
      BuildRuleBuilderDelegate buildRuleBuilderDelegate,
      Optional<Long> artifactCacheSizeLimit,
      BuildInfoStoreManager buildInfoStoreManager,
      CachingBuildEngine.BuildMode buildMode,
      final BuildRuleDurationTracker buildRuleDurationTracker,
      final boolean consoleLogBuildFailuresInline,
      RuleKeyDiagnostics<RuleKey, String> defaultRuleKeyDiagnostics,
      CachingBuildEngine.DepFiles depFiles,
      final FileHashCache fileHashCache,
      long maxDepFileCacheEntries,
      CachingBuildEngine.MetadataStorage metadataStorage,
      SourcePathResolver pathResolver,
      ResourceAwareSchedulingInfo resourceAwareSchedulingInfo,
      final RuleKeyFactories ruleKeyFactories,
      WeightedListeningExecutorService service,
      StepRunner stepRunner,
      RuleDepsCache ruleDeps,
      BuildRule rule,
      BuildEngineBuildContext buildContext,
      ExecutionContext executionContext,
      OnDiskBuildInfo onDiskBuildInfo,
      BuildInfoRecorder buildInfoRecorder,
      BuildableContext buildableContext,
      BuildRulePipelinesRunner pipelinesRunner,
      RemoteBuildRuleCompletionWaiter remoteBuildRuleCompletionWaiter) {
    this.buildRuleBuilderDelegate = buildRuleBuilderDelegate;
    this.artifactCacheSizeLimit = artifactCacheSizeLimit;
    this.buildInfoStoreManager = buildInfoStoreManager;
    this.buildMode = buildMode;
    this.buildRuleDurationTracker = buildRuleDurationTracker;
    this.consoleLogBuildFailuresInline = consoleLogBuildFailuresInline;
    this.defaultRuleKeyDiagnostics = defaultRuleKeyDiagnostics;
    this.depFiles = depFiles;
    this.fileHashCache = fileHashCache;
    this.maxDepFileCacheEntries = maxDepFileCacheEntries;
    this.metadataStorage = metadataStorage;
    this.pathResolver = pathResolver;
    this.resourceAwareSchedulingInfo = resourceAwareSchedulingInfo;
    this.ruleKeyFactories = ruleKeyFactories;
    this.service = service;
    this.stepRunner = stepRunner;
    this.ruleDeps = ruleDeps;
    this.rule = rule;
    this.executionContext = executionContext;
    this.onDiskBuildInfo = onDiskBuildInfo;
    this.buildInfoRecorder = new Discardable<>(buildInfoRecorder);
    this.buildableContext = buildableContext;
    this.pipelinesRunner = pipelinesRunner;
    this.eventBus = buildContext.getEventBus();
    this.buildRuleBuildContext = buildContext.getBuildContext();
    this.artifactCache = buildContext.getArtifactCache();
    this.buildId = buildContext.getBuildId();
    this.remoteBuildRuleCompletionWaiter = remoteBuildRuleCompletionWaiter;
    this.buildRuleScopeManager = new BuildRuleScopeManager();

    this.defaultKey = ruleKeyFactories.getDefaultRuleKeyFactory().build(rule);
    this.inputBasedKey = MoreSuppliers.memoize(this::calculateInputBasedRuleKey);
    this.manifestBasedKeySupplier =
        MoreSuppliers.weakMemoize(
            () -> {
              try (Scope ignored = buildRuleScope()) {
                return calculateManifestKey(eventBus);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
  }

  // Return a `BuildResult.Builder` with rule-specific state pre-filled.
  private BuildResult.Builder buildResultBuilder() {
    BuildResult.Builder builder = BuildResult.builder().setRule(rule);
    if (manifestFetchResult != null) {
      builder.setManifestFetchResult(manifestFetchResult);
    }
    if (manifestStoreResult != null) {
      builder.setManifestStoreResult(manifestStoreResult);
    }
    return builder;
  }

  private BuildResult success(BuildRuleSuccessType successType, CacheResult cacheResult) {
    return buildResultBuilder()
        .setStatus(BuildRuleStatus.SUCCESS)
        .setSuccessOptional(successType)
        .setCacheResult(cacheResult)
        .setUploadCompleteFuture(uploadCompleteFuture)
        .build();
  }

  private BuildResult failure(Throwable thrown) {
    return buildResultBuilder().setStatus(BuildRuleStatus.FAIL).setFailureOptional(thrown).build();
  }

  private BuildResult canceled(Throwable thrown) {
    return buildResultBuilder()
        .setStatus(BuildRuleStatus.CANCELED)
        .setFailureOptional(thrown)
        .build();
  }

  /**
   * We have a lot of places where tasks are submitted into a service implicitly. There is no way to
   * assign custom weights to such tasks. By creating a temporary service with adjusted weights it
   * is possible to trick the system and tweak the weights.
   */
  private WeightedListeningExecutorService serviceByAdjustingDefaultWeightsTo(
      ResourceAmounts defaultAmounts) {
    return CachingBuildEngine.serviceByAdjustingDefaultWeightsTo(
        defaultAmounts, resourceAwareSchedulingInfo, service);
  }

  ListenableFuture<BuildResult> build() {
    final AtomicReference<Long> outputSize = Atomics.newReference();

    ListenableFuture<List<BuildResult>> depResults =
        Futures.immediateFuture(Collections.emptyList());

    // If we're performing a deep build, guarantee that all dependencies will *always* get
    // materialized locally
    if (buildMode == CachingBuildEngine.BuildMode.DEEP
        || buildMode == CachingBuildEngine.BuildMode.POPULATE_FROM_REMOTE_CACHE) {
      depResults = buildRuleBuilderDelegate.getDepResults(rule, executionContext);
    }

    ListenableFuture<BuildResult> buildResult =
        Futures.transformAsync(
            depResults,
            input -> buildOrFetchFromCache(),
            serviceByAdjustingDefaultWeightsTo(
                CachingBuildEngine.SCHEDULING_MORE_WORK_RESOURCE_AMOUNTS));

    // Check immediately (without posting a new task) for a failure so that we can short-circuit
    // pending work. Use .catchingAsync() instead of .catching() so that we can propagate unchecked
    // exceptions.
    buildResult =
        Futures.catchingAsync(
            buildResult,
            Throwable.class,
            throwable -> {
              Preconditions.checkNotNull(throwable);
              buildRuleBuilderDelegate.setFirstFailure(throwable);
              Throwables.throwIfInstanceOf(throwable, Exception.class);
              throw new RuntimeException(throwable);
            });

    buildResult =
        Futures.transform(
            buildResult,
            (result) -> {
              buildRuleBuilderDelegate.markRuleAsUsed(rule, eventBus);
              return result;
            },
            MoreExecutors.directExecutor());

    buildResult =
        Futures.transformAsync(
            buildResult,
            ruleAsyncFunction(result -> finalizeBuildRule(result, outputSize)),
            serviceByAdjustingDefaultWeightsTo(
                CachingBuildEngine.RULE_KEY_COMPUTATION_RESOURCE_AMOUNTS));

    buildResult =
        Futures.catchingAsync(
            buildResult,
            Throwable.class,
            thrown -> {
              LOG.debug(thrown, "Building rule [%s] failed.", rule.getBuildTarget());

              if (consoleLogBuildFailuresInline) {
                eventBus.post(ConsoleEvent.severe(getErrorMessageIncludingBuildRule()));
              }

              thrown = addBuildRuleContextToException(thrown);
              recordFailureAndCleanUp(thrown);

              return Futures.immediateFuture(failure(thrown));
            });

    // Do things that need to happen after either success or failure, but don't block the dependents
    // while doing so:
    buildRuleBuilderDelegate.addAsyncCallback(
        MoreFutures.addListenableCallback(
            buildResult,
            new FutureCallback<BuildResult>() {
              @Override
              public void onSuccess(BuildResult input) {
                handleResult(input);

                // Reset interrupted flag once failure has been recorded.
                if (!input.isSuccess() && input.getFailure() instanceof InterruptedException) {
                  Threads.interruptCurrentThread();
                }
              }

              @Override
              public void onFailure(@Nonnull Throwable thrown) {
                throw new AssertionError("Dead code", thrown);
              }
            },
            serviceByAdjustingDefaultWeightsTo(
                CachingBuildEngine.RULE_KEY_COMPUTATION_RESOURCE_AMOUNTS)));
    return buildResult;
  }

  private void finalizeMatchingKey(BuildRuleSuccessType success)
      throws IOException, StepFailedException, InterruptedException {
    switch (success) {
      case MATCHING_RULE_KEY:
        // No need to record anything for matching rule key.
        return;

      case MATCHING_DEP_FILE_RULE_KEY:
        if (SupportsInputBasedRuleKey.isSupported(rule)) {
          // The input-based key should already be computed.
          inputBasedKey
              .get()
              .ifPresent(
                  key ->
                      getBuildInfoRecorder()
                          .addBuildMetadata(
                              BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY, key.toString()));
        }
        // $FALL-THROUGH$
      case MATCHING_INPUT_BASED_RULE_KEY:
        getBuildInfoRecorder()
            .addBuildMetadata(BuildInfo.MetadataKey.RULE_KEY, defaultKey.toString());
        break;

      case BUILT_LOCALLY:
      case FETCHED_FROM_CACHE:
      case FETCHED_FROM_CACHE_INPUT_BASED:
      case FETCHED_FROM_CACHE_MANIFEST_BASED:
        throw new RuntimeException(String.format("Unexpected success type %s.", success));
    }

    // TODO(cjhopman): input-based/depfile each rewrite the matching key, that's unnecessary.
    // TODO(cjhopman): this writes the current build-id/timestamp/extra-data, that's probably
    // unnecessary.
    getBuildInfoRecorder()
        .assertOnlyHasKeys(
            BuildInfo.MetadataKey.RULE_KEY,
            BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY,
            BuildInfo.MetadataKey.DEP_FILE_RULE_KEY,
            BuildInfo.MetadataKey.BUILD_ID);
    try {
      getBuildInfoRecorder().updateBuildMetadata();
    } catch (IOException e) {
      throw new IOException(String.format("Failed to write metadata to disk for %s.", rule), e);
    }
  }

  private ListenableFuture<BuildResult> finalizeBuildRule(
      BuildResult input, AtomicReference<Long> outputSize)
      throws StepFailedException, InterruptedException, IOException {
    try {
      // If we weren't successful, exit now.
      if (input.getStatus() != BuildRuleStatus.SUCCESS) {
        return Futures.immediateFuture(input);
      }

      try (Scope ignored = LeafEvents.scope(eventBus, "finalizing_build_rule")) {
        // We shouldn't see any build fail result at this point.
        BuildRuleSuccessType success = input.getSuccess();
        switch (success) {
          case BUILT_LOCALLY:
            finalizeBuiltLocally(outputSize);
            break;
          case FETCHED_FROM_CACHE:
          case FETCHED_FROM_CACHE_INPUT_BASED:
          case FETCHED_FROM_CACHE_MANIFEST_BASED:
            finalizeFetchedFromCache(success);
            break;
          case MATCHING_RULE_KEY:
          case MATCHING_INPUT_BASED_RULE_KEY:
          case MATCHING_DEP_FILE_RULE_KEY:
            finalizeMatchingKey(success);
            break;
        }
      } catch (Exception e) {
        throw new BuckUncheckedExecutionException(e, "When finalizing rule.");
      }

      // Give the rule a chance to populate its internal data structures now that all of
      // the files should be in a valid state.
      try {
        if (rule instanceof InitializableFromDisk) {
          doInitializeFromDisk((InitializableFromDisk<?>) rule);
        }
      } catch (IOException e) {
        throw new IOException(
            String.format("Error initializing %s from disk: %s.", rule, e.getMessage()), e);
      }

      return Futures.immediateFuture(input);
    } finally {
      // The BuildInfoRecorder should not be accessed after this point. It does not accurately
      // reflect the state of the buildrule.
      buildInfoRecorder.discard();
    }
  }

  private void finalizeFetchedFromCache(BuildRuleSuccessType success)
      throws StepFailedException, InterruptedException, IOException {
    // For rules fetched from cache, we want to overwrite just the minimum set of things from the
    // cache result. Ensure that nobody has accidentally added unnecessary information to the
    // recorder.
    getBuildInfoRecorder()
        .assertOnlyHasKeys(
            BuildInfo.MetadataKey.BUILD_ID,
            BuildInfo.MetadataKey.RULE_KEY,
            BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY,
            BuildInfo.MetadataKey.DEP_FILE_RULE_KEY,
            BuildInfo.MetadataKey.MANIFEST_KEY);

    // The build has succeeded, whether we've fetched from cache, or built locally.
    // So run the post-build steps.
    if (rule instanceof HasPostBuildSteps) {
      executePostBuildSteps(((HasPostBuildSteps) rule).getPostBuildSteps(buildRuleBuildContext));
    }

    // Invalidate any cached hashes for the output paths, since we've updated them.
    for (Path path : onDiskBuildInfo.getOutputPaths()) {
      fileHashCache.invalidate(rule.getProjectFilesystem().resolve(path));
    }

    // If this rule was fetched from cache, seed the file hash cache with the recorded
    // output hashes from the build metadata.  Skip this if the output size is too big for
    // input-based rule keys.
    long outputSize =
        Long.parseLong(onDiskBuildInfo.getValue(BuildInfo.MetadataKey.OUTPUT_SIZE).get());
    if (shouldWriteOutputHashes(outputSize)) {
      Optional<ImmutableMap<String, String>> hashes =
          onDiskBuildInfo.getMap(BuildInfo.MetadataKey.RECORDED_PATH_HASHES);
      Preconditions.checkState(hashes.isPresent());
      // Seed the cache with the hashes.
      for (Map.Entry<String, String> ent : hashes.get().entrySet()) {
        Path path = rule.getProjectFilesystem().getPath(ent.getKey());
        HashCode hashCode = HashCode.fromString(ent.getValue());
        fileHashCache.set(rule.getProjectFilesystem().resolve(path), hashCode);
      }
    }

    switch (success) {
      case FETCHED_FROM_CACHE:
        break;
      case FETCHED_FROM_CACHE_MANIFEST_BASED:
        if (SupportsInputBasedRuleKey.isSupported(rule)) {
          // The input-based key should already be computed.
          inputBasedKey
              .get()
              .ifPresent(
                  key ->
                      getBuildInfoRecorder()
                          .addBuildMetadata(
                              BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY, key.toString()));
        }
        // $FALL-THROUGH$
      case FETCHED_FROM_CACHE_INPUT_BASED:
        getBuildInfoRecorder()
            .addBuildMetadata(BuildInfo.MetadataKey.RULE_KEY, defaultKey.toString());
        break;

      case BUILT_LOCALLY:

      case MATCHING_RULE_KEY:
      case MATCHING_INPUT_BASED_RULE_KEY:
      case MATCHING_DEP_FILE_RULE_KEY:
        throw new RuntimeException(String.format("Unexpected success type %s.", success));
    }

    // TODO(cjhopman): input-based/depfile each rewrite the matching key, that's unnecessary.
    // TODO(cjhopman): this writes the current build-id/timestamp/extra-data, that's probably
    // unnecessary.
    getBuildInfoRecorder()
        .assertOnlyHasKeys(
            BuildInfo.MetadataKey.RULE_KEY,
            BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY,
            BuildInfo.MetadataKey.DEP_FILE_RULE_KEY,
            BuildInfo.MetadataKey.MANIFEST_KEY,
            BuildInfo.MetadataKey.BUILD_ID);
    try {
      getBuildInfoRecorder().updateBuildMetadata();
    } catch (IOException e) {
      throw new IOException(String.format("Failed to write metadata to disk for %s.", rule), e);
    }
  }

  private void finalizeBuiltLocally(AtomicReference<Long> outputSize)
      throws IOException, StepFailedException, InterruptedException {
    BuildRuleSuccessType success = BuildRuleSuccessType.BUILT_LOCALLY;
    // Try get the output size now that all outputs have been recorded.
    outputSize.set(getBuildInfoRecorder().getOutputSize());
    getBuildInfoRecorder()
        .addMetadata(BuildInfo.MetadataKey.OUTPUT_SIZE, outputSize.get().toString());

    if (rule instanceof HasPostBuildSteps) {
      executePostBuildSteps(((HasPostBuildSteps) rule).getPostBuildSteps(buildRuleBuildContext));
    }

    // Invalidate any cached hashes for the output paths, since we've updated them.
    for (Path path : getBuildInfoRecorder().getRecordedPaths()) {
      fileHashCache.invalidate(rule.getProjectFilesystem().resolve(path));
    }

    // Doing this here is probably not strictly necessary, however in the case of
    // pipelined rules built locally we will never do an input-based cache check.
    // That check would have written the key to metadata, and there are some asserts
    // during cache upload that try to ensure they are present.
    if (SupportsInputBasedRuleKey.isSupported(rule)
        && !getBuildInfoRecorder()
            .getBuildMetadataFor(BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY)
            .isPresent()
        && inputBasedKey.get().isPresent()) {
      getBuildInfoRecorder()
          .addBuildMetadata(
              BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY, inputBasedKey.get().get().toString());
    }

    // If this rule uses dep files, make sure we store the new dep file
    // list and re-calculate the dep file rule key.
    if (useDependencyFileRuleKey()) {

      // Query the rule for the actual inputs it used.
      ImmutableList<SourcePath> inputs =
          ((SupportsDependencyFileRuleKey) rule)
              .getInputsAfterBuildingLocally(
                  buildRuleBuildContext, executionContext.getCellPathResolver());

      // Record the inputs into our metadata for next time.
      // TODO(#9117006): We don't support a way to serlialize `SourcePath`s to the cache,
      // so need to use DependencyFileEntry's instead and recover them on deserialization.
      ImmutableList<String> inputStrings =
          inputs
              .stream()
              .map(inputString -> DependencyFileEntry.fromSourcePath(inputString, pathResolver))
              .map(ObjectMappers.toJsonFunction())
              .collect(MoreCollectors.toImmutableList());
      getBuildInfoRecorder().addMetadata(BuildInfo.MetadataKey.DEP_FILE, inputStrings);

      // Re-calculate and store the depfile rule key for next time.
      Optional<RuleKeyAndInputs> depFileRuleKeyAndInputs =
          calculateDepFileRuleKey(Optional.of(inputStrings), /* allowMissingInputs */ false);
      if (depFileRuleKeyAndInputs.isPresent()) {
        RuleKey depFileRuleKey = depFileRuleKeyAndInputs.get().getRuleKey();
        getBuildInfoRecorder()
            .addBuildMetadata(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY, depFileRuleKey.toString());

        // Push an updated manifest to the cache.
        if (useManifestCaching()) {
          // TODO(cjhopman): This should be able to use manifestKeySupplier.
          Optional<RuleKeyAndInputs> manifestKey = calculateManifestKey(eventBus);
          if (manifestKey.isPresent()) {
            getBuildInfoRecorder()
                .addBuildMetadata(
                    BuildInfo.MetadataKey.MANIFEST_KEY, manifestKey.get().getRuleKey().toString());
            ManifestStoreResult manifestStoreResult =
                updateAndStoreManifest(
                    depFileRuleKeyAndInputs.get().getRuleKey(),
                    depFileRuleKeyAndInputs.get().getInputs(),
                    manifestKey.get(),
                    artifactCache);
            this.manifestStoreResult = manifestStoreResult;
            if (manifestStoreResult.getStoreFuture().isPresent()) {
              uploadCompleteFuture = manifestStoreResult.getStoreFuture().get();
            }
          }
        }
      }
    }

    // Make sure the origin field is filled in.
    getBuildInfoRecorder()
        .addBuildMetadata(BuildInfo.MetadataKey.ORIGIN_BUILD_ID, buildId.toString());
    // Make sure that all of the local files have the same values they would as if the
    // rule had been built locally.
    getBuildInfoRecorder()
        .addBuildMetadata(BuildInfo.MetadataKey.TARGET, rule.getBuildTarget().toString());
    getBuildInfoRecorder()
        .addMetadata(
            BuildInfo.MetadataKey.RECORDED_PATHS,
            getBuildInfoRecorder()
                .getRecordedPaths()
                .stream()
                .map(Object::toString)
                .collect(MoreCollectors.toImmutableList()));
    if (success.shouldWriteRecordedMetadataToDiskAfterBuilding()) {
      try {
        boolean clearExistingMetadata = success.shouldClearAndOverwriteMetadataOnDisk();
        getBuildInfoRecorder().writeMetadataToDisk(clearExistingMetadata);
      } catch (IOException e) {
        throw new IOException(String.format("Failed to write metadata to disk for %s.", rule), e);
      }
    }

    if (shouldWriteOutputHashes(outputSize.get())) {
      onDiskBuildInfo.writeOutputHashes(fileHashCache);
    }
  }

  private boolean shouldWriteOutputHashes(long outputSize) {
    Optional<Long> sizeLimit = ruleKeyFactories.getInputBasedRuleKeyFactory().getInputSizeLimit();
    return !sizeLimit.isPresent() || (outputSize <= sizeLimit.get());
  }

  private BuildInfoRecorder getBuildInfoRecorder() {
    return buildInfoRecorder.get();
  }

  private void uploadToCache(BuildRuleSuccessType success) {
    // Collect up all the rule keys we have index the artifact in the cache with.
    Set<RuleKey> ruleKeys = new HashSet<>();

    // If the rule key has changed (and is not already in the cache), we need to push
    // the artifact to cache using the new key.
    ruleKeys.add(defaultKey);

    // If the input-based rule key has changed, we need to push the artifact to cache
    // using the new key.
    if (SupportsInputBasedRuleKey.isSupported(rule)) {
      Preconditions.checkNotNull(
          inputBasedKey, "input-based key should have been computed already.");
      Optional<RuleKey> calculatedRuleKey = inputBasedKey.get();
      Optional<RuleKey> onDiskRuleKey =
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY);
      Preconditions.checkState(
          calculatedRuleKey.equals(onDiskRuleKey),
          "%s (%s): %s: invalid on-disk input-based rule key: %s != %s",
          rule.getBuildTarget(),
          rule.getType(),
          success,
          calculatedRuleKey,
          onDiskRuleKey);
      if (calculatedRuleKey.isPresent()) {
        ruleKeys.add(calculatedRuleKey.get());
      }
    }

    // If the manifest-based rule key has changed, we need to push the artifact to cache
    // using the new key.
    if (useManifestCaching()) {
      Optional<RuleKey> onDiskRuleKey =
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY);
      if (onDiskRuleKey.isPresent()) {
        ruleKeys.add(onDiskRuleKey.get());
      }
    }

    // Do the actual upload.
    try {
      // Push to cache.
      uploadCompleteFuture =
          ArtifactUploader.performUploadToArtifactCache(
              ImmutableSet.copyOf(ruleKeys),
              artifactCache,
              eventBus,
              onDiskBuildInfo.getMetadataForArtifact(),
              onDiskBuildInfo.getPathsForArtifact(),
              rule.getBuildTarget(),
              rule.getProjectFilesystem());
    } catch (Throwable t) {
      eventBus.post(ThrowableConsoleEvent.create(t, "Error uploading to cache for %s.", rule));
    }
  }

  private void handleResult(BuildResult input) {
    Optional<Long> outputSize = Optional.empty();
    Optional<HashCode> outputHash = Optional.empty();
    Optional<BuildRuleSuccessType> successType = Optional.empty();
    boolean shouldUploadToCache = false;

    try (Scope ignored = buildRuleScope()) {
      if (input.getStatus() == BuildRuleStatus.SUCCESS) {
        BuildRuleSuccessType success = input.getSuccess();
        successType = Optional.of(success);

        // Try get the output size.
        if (success.shouldUploadResultingArtifact()) {
          // All rules should have output_size/output_hash in their artifact metadata.
          outputSize =
              Optional.of(
                  Long.parseLong(
                      onDiskBuildInfo.getValue(BuildInfo.MetadataKey.OUTPUT_SIZE).get()));
          if (shouldWriteOutputHashes(outputSize.get())) {
            String hashString = onDiskBuildInfo.getValue(BuildInfo.MetadataKey.OUTPUT_HASH).get();
            outputHash = Optional.of(HashCode.fromString(hashString));
          }
        }

        // Determine if this is rule is cacheable.
        shouldUploadToCache =
            outputSize.isPresent() && shouldUploadToCache(success, outputSize.get());

        // Upload it to the cache.
        if (shouldUploadToCache) {
          uploadToCache(success);
        }
      }

      buildRuleScopeManager.finished(
          input, outputSize, outputHash, successType, shouldUploadToCache);
    }
  }

  private ListenableFuture<Optional<BuildResult>> buildLocally(
      final CacheResult cacheResult, final ListeningExecutorService service) {
    if (SupportsPipelining.isSupported(rule)
        && ((SupportsPipelining<?>) rule).useRulePipelining()) {
      return pipelinesRunner.runPipelineStartingAt(
          buildRuleBuildContext, (SupportsPipelining<?>) rule, service);
    } else {
      BuildRuleSteps<RulePipelineState> buildRuleSteps = new BuildRuleSteps<>(cacheResult, null);
      service.execute(buildRuleSteps);
      return buildRuleSteps.getFuture();
    }
  }

  private ListenableFuture<Optional<BuildResult>> checkManifestBasedCaches() throws IOException {
    Optional<RuleKeyAndInputs> manifestKeyAndInputs = manifestBasedKeySupplier.get();
    if (!manifestKeyAndInputs.isPresent()) {
      return Futures.immediateFuture(Optional.empty());
    }
    getBuildInfoRecorder()
        .addBuildMetadata(
            BuildInfo.MetadataKey.MANIFEST_KEY, manifestKeyAndInputs.get().getRuleKey().toString());
    try (Scope ignored = LeafEvents.scope(eventBus, "checking_cache_depfile_based")) {
      return Futures.transform(
          performManifestBasedCacheFetch(manifestKeyAndInputs.get()),
          (@Nonnull ManifestFetchResult result) -> {
            this.manifestFetchResult = result;
            if (!result.getRuleCacheResult().isPresent()) {
              return Optional.empty();
            }
            if (!result.getRuleCacheResult().get().getType().isSuccess()) {
              return Optional.empty();
            }
            return Optional.of(
                success(
                    BuildRuleSuccessType.FETCHED_FROM_CACHE_MANIFEST_BASED,
                    result.getRuleCacheResult().get()));
          });
    }
  }

  private Optional<BuildResult> checkMatchingDepfile() throws IOException {
    // Try to get the current dep-file rule key.
    Optional<RuleKeyAndInputs> depFileRuleKeyAndInputs =
        calculateDepFileRuleKey(
            onDiskBuildInfo.getValues(BuildInfo.MetadataKey.DEP_FILE),
            /* allowMissingInputs */ true);
    if (depFileRuleKeyAndInputs.isPresent()) {
      RuleKey depFileRuleKey = depFileRuleKeyAndInputs.get().getRuleKey();
      getBuildInfoRecorder()
          .addBuildMetadata(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY, depFileRuleKey.toString());

      // Check the input-based rule key says we're already built.
      Optional<RuleKey> lastDepFileRuleKey =
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY);
      if (lastDepFileRuleKey.isPresent() && depFileRuleKey.equals(lastDepFileRuleKey.get())) {
        return Optional.of(
            success(
                BuildRuleSuccessType.MATCHING_DEP_FILE_RULE_KEY,
                CacheResult.localKeyUnchangedHit()));
      }
    }
    return Optional.empty();
  }

  private ListenableFuture<Optional<BuildResult>> checkInputBasedCaches() throws IOException {
    Optional<RuleKey> ruleKey;
    try (Scope ignored = buildRuleScope()) {
      // Calculate input-based rule key.
      ruleKey = inputBasedKey.get();
    }
    if (ruleKey.isPresent()) {
      return performInputBasedCacheFetch(ruleKey.get());
    }
    return Futures.immediateFuture(Optional.empty());
  }

  private ListenableFuture<BuildResult> buildOrFetchFromCache() throws IOException {
    // If we've already seen a failure, exit early.
    if (!buildRuleBuilderDelegate.shouldKeepGoing()) {
      return Futures.immediateFuture(canceled(buildRuleBuilderDelegate.getFirstFailure()));
    }

    // 1. Check if it's already built.
    try (Scope ignored = buildRuleScope()) {
      Optional<BuildResult> buildResult = checkMatchingLocalKey();
      if (buildResult.isPresent()) {
        return Futures.immediateFuture(buildResult.get());
      }
    }

    AtomicReference<CacheResult> rulekeyCacheResult = new AtomicReference<>();
    ListenableFuture<Optional<BuildResult>> buildResultFuture;

    // If this is a distributed build, wait for cachable rules to be marked as
    // finished by the remote build before attempting to fetch from cache.
    ListenableFuture<Void> remoteBuildRuleFinishedFuture =
        remoteBuildRuleCompletionWaiter.waitForBuildRuleToFinishRemotely(rule);

    // 2. Rule key cache lookup.
    buildResultFuture =
        // TODO(cjhopman): This should follow the same, simple pattern as everything else. With a
        // large ui.thread_line_limit, SuperConsole tries to redraw more lines than are available.
        // These cache threads make it more likely to hit that problem when SuperConsole is aware
        // of them.
        Futures.transformAsync(
            remoteBuildRuleFinishedFuture,
            (Void v) ->
                Futures.transform(
                    performRuleKeyCacheCheck(),
                    cacheResult -> {
                      rulekeyCacheResult.set(cacheResult);
                      return getBuildResultForRuleKeyCacheResult(cacheResult);
                    }));

    // 3. Build deps.
    buildResultFuture =
        transformBuildResultAsyncIfNotPresent(
            buildResultFuture,
            () -> {
              if (SupportsPipelining.isSupported(rule)) {
                addToPipelinesRunner(
                    (SupportsPipelining<?>) rule,
                    Preconditions.checkNotNull(rulekeyCacheResult.get()));
              }

              return Futures.transformAsync(
                  buildRuleBuilderDelegate.getDepResults(rule, executionContext),
                  (depResults) -> handleDepsResults(depResults),
                  serviceByAdjustingDefaultWeightsTo(
                      CachingBuildEngine.SCHEDULING_MORE_WORK_RESOURCE_AMOUNTS));
            });

    // 4. Return to the current rule and check if it was (or is being) built in a pipeline with
    // one of its dependencies
    if (SupportsPipelining.isSupported(rule)) {
      buildResultFuture =
          transformBuildResultAsyncIfNotPresent(
              buildResultFuture,
              () -> {
                SupportsPipelining<?> pipelinedRule = (SupportsPipelining<?>) rule;
                return pipelinesRunner.runningPipelinesContainRule(pipelinedRule)
                    ? pipelinesRunner.getFuture(pipelinedRule)
                    : Futures.immediateFuture(Optional.empty());
              });
    }

    // 5. Return to the current rule and check caches to see if we can avoid building
    if (SupportsInputBasedRuleKey.isSupported(rule)) {
      buildResultFuture =
          transformBuildResultAsyncIfNotPresent(buildResultFuture, this::checkInputBasedCaches);
    }

    // 6. Then check if the depfile matches.
    if (useDependencyFileRuleKey()) {
      buildResultFuture =
          transformBuildResultIfNotPresent(
              buildResultFuture,
              this::checkMatchingDepfile,
              serviceByAdjustingDefaultWeightsTo(CachingBuildEngine.CACHE_CHECK_RESOURCE_AMOUNTS));
    }

    // 7. Check for a manifest-based cache hit.
    if (useManifestCaching()) {
      buildResultFuture =
          transformBuildResultAsyncIfNotPresent(buildResultFuture, this::checkManifestBasedCaches);
    }

    // 8. Fail if populating the cache and cache lookups failed.
    if (buildMode == CachingBuildEngine.BuildMode.POPULATE_FROM_REMOTE_CACHE) {
      buildResultFuture =
          transformBuildResultIfNotPresent(
              buildResultFuture,
              () -> {
                LOG.info(
                    "Cannot populate cache for " + rule.getBuildTarget().getFullyQualifiedName());
                return Optional.of(
                    canceled(
                        new HumanReadableException(
                            "Skipping %s: in cache population mode local builds are disabled",
                            rule)));
              },
              MoreExecutors.newDirectExecutorService());
    }

    // 9. Build the current rule locally, if we have to.
    buildResultFuture =
        transformBuildResultAsyncIfNotPresent(
            buildResultFuture,
            () ->
                buildLocally(
                    Preconditions.checkNotNull(rulekeyCacheResult.get()),
                    service
                        // This needs to adjust the default amounts even in the non-resource-aware
                        // scheduling case so that RuleScheduleInfo works correctly.
                        .withDefaultAmounts(getRuleResourceAmounts())));

    if (SupportsPipelining.isSupported(rule)) {
      buildResultFuture.addListener(
          () -> pipelinesRunner.removeRule((SupportsPipelining<?>) rule),
          MoreExecutors.directExecutor());
    }

    // Unwrap the result.
    return Futures.transform(buildResultFuture, Optional::get);
  }

  private <T extends RulePipelineState> void addToPipelinesRunner(
      SupportsPipelining<T> rule, CacheResult cacheResult) {
    pipelinesRunner.addRule(rule, pipeline -> new BuildRuleSteps<T>(cacheResult, pipeline));
  }

  private Optional<BuildResult> checkMatchingLocalKey() {
    Optional<RuleKey> cachedRuleKey = onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.RULE_KEY);
    if (defaultKey.equals(cachedRuleKey.orElse(null))) {
      return Optional.of(
          success(BuildRuleSuccessType.MATCHING_RULE_KEY, CacheResult.localKeyUnchangedHit()));
    }
    return Optional.empty();
  }

  private ListenableFuture<CacheResult> performRuleKeyCacheCheck() throws IOException {
    long cacheRequestTimestampMillis = System.currentTimeMillis();
    return Futures.transform(
        tryToFetchArtifactFromBuildCacheAndOverlayOnTopOfProjectFilesystem(
            defaultKey,
            artifactCache,
            // TODO(simons): This should be a shared between all tests, not one per cell
            rule.getProjectFilesystem()),
        cacheResult -> {
          RuleKeyCacheResult ruleKeyCacheResult =
              RuleKeyCacheResult.builder()
                  .setBuildTarget(rule.getFullyQualifiedName())
                  .setRuleKey(defaultKey.toString())
                  .setRuleKeyType(RuleKeyType.DEFAULT)
                  .setCacheResult(cacheResult.getType())
                  .setRequestTimestampMillis(cacheRequestTimestampMillis)
                  .setTwoLevelContentHashKey(cacheResult.twoLevelContentHashKey())
                  .build();
          eventBus.post(new RuleKeyCacheResultEvent(ruleKeyCacheResult));
          return cacheResult;
        });
  }

  private Optional<BuildResult> getBuildResultForRuleKeyCacheResult(CacheResult cacheResult) {
    if (!cacheResult.getType().isSuccess()) {
      return Optional.empty();
    }
    return Optional.of(success(BuildRuleSuccessType.FETCHED_FROM_CACHE, cacheResult));
  }

  private ListenableFuture<Optional<BuildResult>> handleDepsResults(List<BuildResult> depResults) {
    for (BuildResult depResult : depResults) {
      if (buildMode != CachingBuildEngine.BuildMode.POPULATE_FROM_REMOTE_CACHE
          && !depResult.isSuccess()) {
        return Futures.immediateFuture(Optional.of(canceled(depResult.getFailure())));
      }
    }
    depsAreAvailable = true;
    return Futures.immediateFuture(Optional.empty());
  }

  private void recordFailureAndCleanUp(Throwable failure) {
    // Make this failure visible for other rules, so that they can stop early.
    buildRuleBuilderDelegate.setFirstFailure(Preconditions.checkNotNull(failure));

    // If we failed, cleanup the state of this rule.
    // TODO(mbolin): Delete all files produced by the rule, as they are not guaranteed
    // to be valid at this point?
    try {
      onDiskBuildInfo.deleteExistingMetadata();
    } catch (Throwable t) {
      eventBus.post(ThrowableConsoleEvent.create(t, "Error when deleting metadata for %s.", rule));
    }
  }

  private BuildRuleKeys getBuildRuleKeys() {
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
      boolean failureOrBuiltLocally) {
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

  private Throwable addBuildRuleContextToException(@Nonnull Throwable thrown) {
    return new BuckUncheckedExecutionException("", thrown, getErrorMessageIncludingBuildRule());
  }

  private String getErrorMessageIncludingBuildRule() {
    return String.format("When building rule %s.", rule.getBuildTarget());
  }

  private ListenableFuture<CacheResult>
      tryToFetchArtifactFromBuildCacheAndOverlayOnTopOfProjectFilesystem(
          final RuleKey ruleKey,
          final ArtifactCache artifactCache,
          final ProjectFilesystem filesystem) {
    if (!rule.isCacheable()) {
      return Futures.immediateFuture(CacheResult.ignored());
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
    return Futures.transformAsync(
        fetch(artifactCache, ruleKey, lazyZipPath),
        cacheResult -> {
          try (Scope ignored = buildRuleScope()) {
            // Verify that the rule key we used to fetch the artifact is one of the rule keys
            // reported in it's metadata.
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

            return Futures.immediateFuture(
                unzipArtifactFromCacheResult(ruleKey, lazyZipPath, filesystem, cacheResult));
          }
        });
  }

  /**
   * onOutputsWillChange() should be called once we've determined that the outputs are going to
   * change from their previous state (e.g. because we're about to build locally or unzip an
   * artifact from the cache).
   */
  private void onOutputsWillChange() throws IOException {
    if (rule instanceof InitializableFromDisk) {
      ((InitializableFromDisk<?>) rule).getBuildOutputInitializer().invalidate();
    }
    onDiskBuildInfo.deleteExistingMetadata();
    // TODO(cjhopman): Delete old outputs.
  }

  private ListenableFuture<CacheResult> fetch(
      ArtifactCache artifactCache, RuleKey ruleKey, LazyPath outputPath) {
    return Futures.transform(
        artifactCache.fetchAsync(ruleKey, outputPath),
        (CacheResult cacheResult) -> {
          try (Scope ignored = buildRuleScope()) {
            if (cacheResult.getType() != CacheResultType.HIT) {
              return cacheResult;
            }
            for (String ruleKeyName : BuildInfo.RULE_KEY_NAMES) {
              if (!cacheResult.getMetadata().containsKey(ruleKeyName)) {
                continue;
              }
              String ruleKeyValue = cacheResult.getMetadata().get(ruleKeyName);
              try {
                verify(ruleKeyValue);
              } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                    String.format(
                        "Invalid '%s' rule key in metadata for artifact '%s' returned by cache '%s': '%s'",
                        ruleKeyName, ruleKey, artifactCache.getClass(), ruleKeyValue),
                    e);
              }
            }
            return cacheResult;
          }
        },
        serviceByAdjustingDefaultWeightsTo(CachingBuildEngine.CACHE_CHECK_RESOURCE_AMOUNTS));
  }

  /**
   * Checks that passed rule key value is valid and throws an {@link IllegalArgumentException} if it
   * is not.
   *
   * @param ruleKeyValue rule key to verify.
   */
  @SuppressWarnings("CheckReturnValue")
  private void verify(String ruleKeyValue) {
    HashCode.fromString(ruleKeyValue);
  }

  private CacheResult unzipArtifactFromCacheResult(
      RuleKey ruleKey, LazyPath lazyZipPath, ProjectFilesystem filesystem, CacheResult cacheResult)
      throws IOException {

    // We only unpack artifacts from hits.
    if (!cacheResult.getType().isSuccess()) {
      LOG.debug("Cache miss for '%s' with rulekey '%s'", rule, ruleKey);
      return cacheResult;
    }
    onOutputsWillChange();

    Preconditions.checkState(cacheResult.metadata().isPresent());
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
    eventBus.post(started);
    try {
      // First, clear out the pre-existing metadata directory.  We have to do this *before*
      // unpacking the zipped artifact, as it includes files that will be stored in the metadata
      // directory.
      BuildInfoStore buildInfoStore =
          buildInfoStoreManager.get(rule.getProjectFilesystem(), metadataStorage);

      try (ZipFile artifact = new ZipFile(zipPath.toFile())) {
        onDiskBuildInfo.validateArtifact(artifact);
      }

      Preconditions.checkState(
          cacheResult.getMetadata().containsKey(BuildInfo.MetadataKey.ORIGIN_BUILD_ID),
          "Cache artifact for rulekey %s is missing metadata %s.",
          ruleKey,
          BuildInfo.MetadataKey.ORIGIN_BUILD_ID);

      Unzip.extractZipFile(
          zipPath.toAbsolutePath(),
          filesystem,
          Unzip.ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);

      // We only delete the ZIP file when it has been unzipped successfully. Otherwise, we leave it
      // around for debugging purposes.
      Files.delete(zipPath);

      // TODO(cjhopman): This should probably record metadata with the buildInfoRecorder, not
      // directly into the buildInfoStore.
      // Also write out the build metadata.
      buildInfoStore.updateMetadata(rule.getBuildTarget(), cacheResult.getMetadata());
    } finally {
      eventBus.post(ArtifactCompressionEvent.finished(started));
    }

    return cacheResult;
  }

  private void executePostBuildSteps(Iterable<Step> postBuildSteps)
      throws InterruptedException, StepFailedException {

    LOG.debug("Running post-build steps for %s", rule);

    Optional<BuildTarget> optionalTarget = Optional.of(rule.getBuildTarget());
    for (Step step : postBuildSteps) {
      stepRunner.runStepForBuildTarget(
          executionContext.withProcessExecutor(
              new ContextualProcessExecutor(
                  executionContext.getProcessExecutor(),
                  ImmutableMap.of(
                      CachingBuildEngine.BUILD_RULE_TYPE_CONTEXT_KEY,
                      rule.getType(),
                      CachingBuildEngine.STEP_TYPE_CONTEXT_KEY,
                      CachingBuildEngine.StepType.POST_BUILD_STEP.toString()))),
          step,
          optionalTarget);

      // Check for interruptions that may have been ignored by step.
      if (Thread.interrupted()) {
        Threads.interruptCurrentThread();
        throw new InterruptedException();
      }
    }

    LOG.debug("Finished running post-build steps for %s", rule);
  }

  private <T> void doInitializeFromDisk(InitializableFromDisk<T> initializable) throws IOException {
    try (Scope ignored = LeafEvents.scope(eventBus, "initialize_from_disk")) {
      BuildOutputInitializer<T> buildOutputInitializer = initializable.getBuildOutputInitializer();
      buildOutputInitializer.initializeFromDisk();
    }
  }

  /** @return whether we should upload the given rules artifacts to cache. */
  private boolean shouldUploadToCache(BuildRuleSuccessType successType, long outputSize) {

    // The success type must allow cache uploading.
    if (!successType.shouldUploadResultingArtifact()) {
      return false;
    }

    // The cache must be writable.
    if (!artifactCache.getCacheReadMode().isWritable()) {
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

  private boolean useDependencyFileRuleKey() {
    return depFiles != CachingBuildEngine.DepFiles.DISABLED
        && rule instanceof SupportsDependencyFileRuleKey
        && ((SupportsDependencyFileRuleKey) rule).useDependencyFileRuleKeys();
  }

  private boolean useManifestCaching() {
    return depFiles == CachingBuildEngine.DepFiles.CACHE
        && rule instanceof SupportsDependencyFileRuleKey
        && rule.isCacheable()
        && ((SupportsDependencyFileRuleKey) rule).useDependencyFileRuleKeys();
  }

  private Optional<RuleKeyAndInputs> calculateDepFileRuleKey(
      Optional<ImmutableList<String>> depFile, boolean allowMissingInputs) throws IOException {

    Preconditions.checkState(useDependencyFileRuleKey());

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
            .map(ObjectMappers.fromJsonFunction(DependencyFileEntry.class))
            .collect(MoreCollectors.toImmutableList());

    try (Scope ignored =
        RuleKeyCalculationEvent.scope(eventBus, RuleKeyCalculationEvent.Type.DEP_FILE)) {
      return Optional.of(
          ruleKeyFactories
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
  protected static Path getManifestPath(BuildRule rule) {
    return BuildInfo.getPathToOtherMetadataDirectory(
            rule.getBuildTarget(), rule.getProjectFilesystem())
        .resolve(BuildInfo.MANIFEST);
  }

  // Update the on-disk manifest with the new dep-file rule key and push it to the cache.
  private ManifestStoreResult updateAndStoreManifest(
      RuleKey key,
      ImmutableSet<SourcePath> inputs,
      RuleKeyAndInputs manifestKey,
      ArtifactCache cache)
      throws IOException {

    Preconditions.checkState(useManifestCaching());

    ManifestStoreResult.Builder resultBuilder = ManifestStoreResult.builder();

    final Path manifestPath = getManifestPath(rule);
    Manifest manifest = new Manifest(manifestKey.getRuleKey());
    resultBuilder.setDidCreateNewManifest(true);

    // If we already have a manifest downloaded, use that.
    if (rule.getProjectFilesystem().exists(manifestPath)) {
      ManifestLoadResult existingManifest = loadManifest(manifestKey.getRuleKey());
      existingManifest.getError().ifPresent(resultBuilder::setManifestLoadError);
      if (existingManifest.getManifest().isPresent()
          && existingManifest.getManifest().get().getKey().equals(manifestKey.getRuleKey())) {
        manifest = existingManifest.getManifest().get();
        resultBuilder.setDidCreateNewManifest(false);
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
      resultBuilder.setDidCreateNewManifest(true);
    }

    // Update the manifest with the new output rule key.
    manifest.addEntry(fileHashCache, key, pathResolver, manifestKey.getInputs(), inputs);

    // Record the current manifest stats settings now that we've finalized the manifest we're going
    // to store.
    resultBuilder.setManifestStats(manifest.getStats());

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

    // Queue the upload operation and save a future wrapping it.
    resultBuilder.setStoreFuture(
        MoreFutures.addListenableCallback(
            cache.store(
                ArtifactInfo.builder().addRuleKeys(manifestKey.getRuleKey()).build(),
                BorrowablePath.borrowablePath(tempFile)),
            MoreFutures.finallyCallback(
                () -> {
                  try {
                    Files.deleteIfExists(tempFile);
                  } catch (IOException e) {
                    LOG.warn(
                        e,
                        "Error occurred while deleting temporary manifest file for %s",
                        manifestPath);
                  }
                }),
            MoreExecutors.directExecutor()));

    return resultBuilder.build();
  }

  private Optional<RuleKeyAndInputs> calculateManifestKey(BuckEventBus eventBus)
      throws IOException {
    Preconditions.checkState(depsAreAvailable);
    return CachingBuildEngine.calculateManifestKey(
        (SupportsDependencyFileRuleKey) rule, eventBus, ruleKeyFactories);
  }

  private ListenableFuture<CacheResult> fetchManifest(RuleKey key) throws IOException {
    Preconditions.checkState(useManifestCaching());

    Path path = getManifestPath(rule);

    // Use a temp path to store the downloaded artifact.  We'll rename it into place on success to
    // make the process more atomic.
    LazyPath tempPath =
        new LazyPath() {
          @Override
          protected Path create() throws IOException {
            return Files.createTempFile("buck.", ".manifest");
          }
        };

    return Futures.transformAsync(
        fetch(artifactCache, key, tempPath),
        (@Nonnull CacheResult cacheResult) -> {
          if (!cacheResult.getType().isSuccess()) {
            LOG.verbose("%s: cache miss on manifest %s", rule.getBuildTarget(), key);
            return Futures.immediateFuture(cacheResult);
          }

          // Download is successful, so move the manifest into place.
          rule.getProjectFilesystem().createParentDirs(path);
          rule.getProjectFilesystem().deleteFileAtPathIfExists(path);
          rule.getProjectFilesystem().move(tempPath.get(), path);

          LOG.verbose("%s: cache hit on manifest %s", rule.getBuildTarget(), key);

          return Futures.immediateFuture(cacheResult);
        });
  }

  private ManifestLoadResult loadManifest(RuleKey key) {
    Preconditions.checkState(useManifestCaching());

    Path path = getManifestPath(rule);

    // Deserialize the manifest.
    Manifest manifest;
    try (InputStream input =
        new GZIPInputStream(rule.getProjectFilesystem().newFileInputStream(path))) {
      manifest = new Manifest(input);
    } catch (Exception e) {
      LOG.warn(
          e,
          "Failed to deserialize fetched-from-cache manifest for rule %s with key %s",
          rule,
          key);
      return ManifestLoadResult.error("corrupted manifest path");
    }

    return ManifestLoadResult.success(manifest);
  }

  // Fetch an artifact from the cache using manifest-based caching.
  private ListenableFuture<ManifestFetchResult> performManifestBasedCacheFetch(
      RuleKeyAndInputs originalRuleKeyAndInputs) throws IOException {
    Preconditions.checkArgument(useManifestCaching());

    // Explicitly drop the input list from the caller, as holding this in the closure below until
    // the future eventually runs can potentially consume a lot of memory.
    RuleKey manifestRuleKey = originalRuleKeyAndInputs.getRuleKey();
    originalRuleKeyAndInputs = null;

    // Fetch the manifest from the cache.
    return Futures.transformAsync(
        fetchManifest(manifestRuleKey),
        (@Nonnull CacheResult manifestCacheResult) -> {
          ManifestFetchResult.Builder manifestFetchResult = ManifestFetchResult.builder();
          manifestFetchResult.setManifestCacheResult(manifestCacheResult);
          if (!manifestCacheResult.getType().isSuccess()) {
            return Futures.immediateFuture(manifestFetchResult.build());
          }

          // Re-calculate the rule key and the input list.  While we do already have the input list
          // above in `originalRuleKeyAndInputs`, we intentionally don't pass it in and use it here
          // to avoid holding on to significant memory until this future runs.
          RuleKeyAndInputs keyAndInputs =
              manifestBasedKeySupplier.get().orElseThrow(IllegalStateException::new);

          // Load the manifest from disk.
          ManifestLoadResult loadResult = loadManifest(keyAndInputs.getRuleKey());
          if (!loadResult.getManifest().isPresent()) {
            manifestFetchResult.setManifestLoadError(loadResult.getError().get());
            return Futures.immediateFuture(manifestFetchResult.build());
          }
          Manifest manifest = loadResult.getManifest().get();
          Preconditions.checkState(
              manifest.getKey().equals(keyAndInputs.getRuleKey()),
              "%s: found incorrectly keyed manifest: %s != %s",
              rule.getBuildTarget(),
              keyAndInputs.getRuleKey(),
              manifest.getKey());
          manifestFetchResult.setManifestStats(manifest.getStats());

          // Lookup the dep file rule key matching the current state of our inputs.
          Optional<RuleKey> depFileRuleKey =
              manifest.lookup(fileHashCache, pathResolver, keyAndInputs.getInputs());
          if (!depFileRuleKey.isPresent()) {
            return Futures.immediateFuture(manifestFetchResult.build());
          }
          manifestFetchResult.setDepFileRuleKey(depFileRuleKey.get());

          // Fetch the rule outputs from cache using the found dep file rule key.
          return Futures.transform(
              tryToFetchArtifactFromBuildCacheAndOverlayOnTopOfProjectFilesystem(
                  depFileRuleKey.get(), artifactCache, rule.getProjectFilesystem()),
              (@Nonnull CacheResult ruleCacheResult) -> {
                manifestFetchResult.setRuleCacheResult(ruleCacheResult);
                return manifestFetchResult.build();
              });
        });
  }

  private Optional<RuleKey> calculateInputBasedRuleKey() {
    Preconditions.checkState(depsAreAvailable);
    try (Scope ignored =
        RuleKeyCalculationEvent.scope(eventBus, RuleKeyCalculationEvent.Type.INPUT)) {
      return Optional.of(ruleKeyFactories.getInputBasedRuleKeyFactory().build(rule));
    } catch (SizeLimiter.SizeLimitException ex) {
      return Optional.empty();
    }
  }

  private ListenableFuture<Optional<BuildResult>> performInputBasedCacheFetch(RuleKey inputRuleKey)
      throws IOException {
    Preconditions.checkArgument(SupportsInputBasedRuleKey.isSupported(rule));

    getBuildInfoRecorder()
        .addBuildMetadata(BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY, inputRuleKey.toString());

    // Check the input-based rule key says we're already built.
    if (checkMatchingInputBasedKey(inputRuleKey)) {
      return Futures.immediateFuture(
          Optional.of(
              success(
                  BuildRuleSuccessType.MATCHING_INPUT_BASED_RULE_KEY,
                  CacheResult.localKeyUnchangedHit())));
    }

    // Try to fetch the artifact using the input-based rule key.
    return Futures.transform(
        tryToFetchArtifactFromBuildCacheAndOverlayOnTopOfProjectFilesystem(
            inputRuleKey,
            artifactCache,
            // TODO(simons): Share this between all tests, not one per cell.
            rule.getProjectFilesystem()),
        cacheResult -> {
          if (cacheResult.getType().isSuccess()) {
            try (Scope ignored = LeafEvents.scope(eventBus, "handling_cache_result")) {
              return Optional.of(
                  success(BuildRuleSuccessType.FETCHED_FROM_CACHE_INPUT_BASED, cacheResult));
            }
          }
          return Optional.empty();
        });
  }

  private boolean checkMatchingInputBasedKey(RuleKey inputRuleKey) {
    Optional<RuleKey> lastInputRuleKey =
        onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY);
    return inputRuleKey.equals(lastInputRuleKey.orElse(null));
  }

  private ResourceAmounts getRuleResourceAmounts() {
    if (resourceAwareSchedulingInfo.isResourceAwareSchedulingEnabled()) {
      return resourceAwareSchedulingInfo.getResourceAmountsForRule(rule);
    } else {
      return getResourceAmountsForRuleWithCustomScheduleInfo();
    }
  }

  private ResourceAmounts getResourceAmountsForRuleWithCustomScheduleInfo() {
    Preconditions.checkArgument(!resourceAwareSchedulingInfo.isResourceAwareSchedulingEnabled());
    RuleScheduleInfo ruleScheduleInfo;
    if (rule instanceof OverrideScheduleRule) {
      ruleScheduleInfo = ((OverrideScheduleRule) rule).getRuleScheduleInfo();
    } else {
      ruleScheduleInfo = RuleScheduleInfo.DEFAULT;
    }
    return ResourceAmounts.of(ruleScheduleInfo.getJobsMultiplier(), 0, 0, 0);
  }

  private Scope buildRuleScope() {
    return buildRuleScopeManager.scope();
  }

  // Wrap an async function in rule resume/suspend events.
  private <F, T> AsyncFunction<F, T> ruleAsyncFunction(final AsyncFunction<F, T> delegate) {
    return input -> {
      try (Scope ignored = buildRuleScope()) {
        return delegate.apply(input);
      }
    };
  }

  private ListenableFuture<Optional<BuildResult>> transformBuildResultIfNotPresent(
      ListenableFuture<Optional<BuildResult>> future,
      Callable<Optional<BuildResult>> function,
      ListeningExecutorService executor) {
    return transformBuildResultAsyncIfNotPresent(
        future,
        () ->
            executor.submit(
                () -> {
                  if (!buildRuleBuilderDelegate.shouldKeepGoing()) {
                    Preconditions.checkNotNull(buildRuleBuilderDelegate.getFirstFailure());
                    return Optional.of(canceled(buildRuleBuilderDelegate.getFirstFailure()));
                  }
                  try (Scope ignored = buildRuleScope()) {
                    return function.call();
                  }
                }));
  }

  private ListenableFuture<Optional<BuildResult>> transformBuildResultAsyncIfNotPresent(
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
          if (!buildRuleBuilderDelegate.shouldKeepGoing()) {
            Preconditions.checkNotNull(buildRuleBuilderDelegate.getFirstFailure());
            return Futures.immediateFuture(
                Optional.of(canceled(buildRuleBuilderDelegate.getFirstFailure())));
          }
          return function.call();
        },
        MoreExecutors.directExecutor());
  }

  /** Encapsulates the steps involved in building a single {@link BuildRule} locally. */
  private class BuildRuleSteps<T extends RulePipelineState>
      implements RunnableWithFuture<Optional<BuildResult>> {
    private final CacheResult cacheResult;
    private final SettableFuture<Optional<BuildResult>> future = SettableFuture.create();
    @Nullable private final T pipelineState;

    public BuildRuleSteps(CacheResult cacheResult, @Nullable T pipelineState) {
      this.cacheResult = cacheResult;
      this.pipelineState = pipelineState;
    }

    @Override
    public SettableFuture<Optional<BuildResult>> getFuture() {
      return future;
    }

    @Override
    public void run() {
      try {
        if (!buildRuleBuilderDelegate.shouldKeepGoing()) {
          Preconditions.checkNotNull(buildRuleBuilderDelegate.getFirstFailure());
          future.set(Optional.of(canceled(buildRuleBuilderDelegate.getFirstFailure())));
          return;
        }
        try (Scope ignored = buildRuleScope()) {
          executeCommandsNowThatDepsAreBuilt();
        }

        // Set the future outside of the scope, to match the behavior of other steps that use
        // futures provided by the ExecutorService.
        future.set(Optional.of(success(BuildRuleSuccessType.BUILT_LOCALLY, cacheResult)));
      } catch (Throwable t) {
        future.setException(t);
      }
    }

    /**
     * Execute the commands for this build rule. Requires all dependent rules are already built
     * successfully.
     */
    private void executeCommandsNowThatDepsAreBuilt()
        throws InterruptedException, StepFailedException {
      try {
        onOutputsWillChange();
      } catch (IOException e) {
        throw new BuckUncheckedExecutionException(e);
      }

      LOG.debug("Building locally: %s", rule);
      // Attempt to get an approximation of how long it takes to actually run the command.
      @SuppressWarnings("PMD.PrematureDeclaration")
      long start = System.nanoTime();

      eventBus.post(BuildRuleEvent.willBuildLocally(rule));
      buildRuleBuilderDelegate.onRuleAboutToBeBuilt(rule);

      // Get and run all of the commands.
      List<? extends Step> steps;
      try (Scope ignored = LeafEvents.scope(eventBus, "get_build_steps")) {
        if (pipelineState == null) {
          steps = rule.getBuildSteps(buildRuleBuildContext, buildableContext);
        } else {
          @SuppressWarnings("unchecked")
          SupportsPipelining<T> pipelinedRule = (SupportsPipelining<T>) rule;
          steps =
              pipelinedRule.getPipelinedBuildSteps(
                  buildRuleBuildContext, buildableContext, pipelineState);
        }
      }

      Optional<BuildTarget> optionalTarget = Optional.of(rule.getBuildTarget());
      for (Step step : steps) {
        stepRunner.runStepForBuildTarget(
            executionContext.withProcessExecutor(
                new ContextualProcessExecutor(
                    executionContext.getProcessExecutor(),
                    ImmutableMap.of(
                        CachingBuildEngine.BUILD_RULE_TYPE_CONTEXT_KEY,
                        rule.getType(),
                        CachingBuildEngine.STEP_TYPE_CONTEXT_KEY,
                        CachingBuildEngine.StepType.BUILD_STEP.toString()))),
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
          "Build completed: %s %s (%dns)",
          rule.getType(), rule.getFullyQualifiedName(), end - start);
    }
  }

  public interface BuildRuleBuilderDelegate {
    void markRuleAsUsed(BuildRule rule, BuckEventBus eventBus);

    boolean shouldKeepGoing();

    void setFirstFailure(Throwable throwable);

    ListenableFuture<List<BuildResult>> getDepResults(
        BuildRule rule, ExecutionContext executionContext);

    void addAsyncCallback(ListenableFuture<Void> callback);

    @Nullable
    Throwable getFirstFailure();

    void onRuleAboutToBeBuilt(BuildRule rule);
  }

  /**
   * Handles BuildRule resumed/suspended/finished scopes. Only one scope can be active at a time.
   * Scopes nested on the same thread are allowed.
   *
   * <p>Once the rule has been marked as finished, any further scope() calls will fail.
   */
  private class BuildRuleScopeManager {
    private final RuleKeyFactoryWithDiagnostics<RuleKey> ruleKeyFactory;

    private volatile @Nullable Thread currentBuildRuleScopeThread = null;
    private @Nullable FinishedData finishedData = null;

    public BuildRuleScopeManager() {
      ruleKeyFactory = ruleKeyFactories.getDefaultRuleKeyFactory();
    }

    private Scope scope() {
      synchronized (this) {
        Preconditions.checkState(
            finishedData == null, "RuleScope started after rule marked as finished.");
        if (currentBuildRuleScopeThread != null) {
          Preconditions.checkState(Thread.currentThread() == currentBuildRuleScopeThread);
          return () -> {};
        }
        BuildRuleEvent.Resumed resumed = postResumed();
        currentBuildRuleScopeThread = Thread.currentThread();
        return () -> {
          synchronized (this) {
            currentBuildRuleScopeThread = null;
            if (finishedData != null) {
              postFinished(resumed);
            } else {
              postSuspended(resumed);
            }
          }
        };
      }
    }

    public synchronized void finished(
        BuildResult input,
        Optional<Long> outputSize,
        Optional<HashCode> outputHash,
        Optional<BuildRuleSuccessType> successType,
        boolean shouldUploadToCache) {
      Preconditions.checkState(finishedData == null, "Build rule already marked finished.");
      Preconditions.checkState(
          currentBuildRuleScopeThread != null,
          "finished() can only be called within a buildrule scope.");
      Preconditions.checkState(
          currentBuildRuleScopeThread == Thread.currentThread(),
          "finished() should be called from the same thread as the current buildrule scope.");
      finishedData =
          new FinishedData(input, outputSize, outputHash, successType, shouldUploadToCache);
    }

    private void post(BuildRuleEvent event) {
      LOG.verbose(event.toString());
      eventBus.post(event);
    }

    private BuildRuleEvent.Resumed postResumed() {
      BuildRuleEvent.Resumed resumedEvent =
          BuildRuleEvent.resumed(rule, buildRuleDurationTracker, ruleKeyFactory);
      post(resumedEvent);
      return resumedEvent;
    }

    private void postSuspended(BuildRuleEvent.Resumed resumed) {
      post(BuildRuleEvent.suspended(resumed, ruleKeyFactories.getDefaultRuleKeyFactory()));
    }

    private void postFinished(BuildRuleEvent.Resumed resumed) {
      Preconditions.checkNotNull(finishedData);
      post(finishedData.getEvent(resumed));
    }
  }

  private class FinishedData {
    private final BuildResult input;
    private final Optional<Long> outputSize;
    private final Optional<HashCode> outputHash;
    private final Optional<BuildRuleSuccessType> successType;
    private final boolean shouldUploadToCache;

    public FinishedData(
        BuildResult input,
        Optional<Long> outputSize,
        Optional<HashCode> outputHash,
        Optional<BuildRuleSuccessType> successType,
        boolean shouldUploadToCache) {
      this.input = input;
      this.outputSize = outputSize;
      this.outputHash = outputHash;
      this.successType = successType;
      this.shouldUploadToCache = shouldUploadToCache;
    }

    private BuildRuleEvent.Finished getEvent(BuildRuleEvent.Resumed resumedEvent) {
      boolean failureOrBuiltLocally =
          input.getStatus() == BuildRuleStatus.FAIL
              || (input.isSuccess() && input.getSuccess() == BuildRuleSuccessType.BUILT_LOCALLY);
      // Log the result to the event bus.
      BuildRuleEvent.Finished finished =
          BuildRuleEvent.finished(
              resumedEvent,
              getBuildRuleKeys(),
              input.getStatus(),
              input.getCacheResult().orElse(CacheResult.miss()),
              onDiskBuildInfo
                  .getBuildValue(BuildInfo.MetadataKey.ORIGIN_BUILD_ID)
                  .map(BuildId::new),
              successType,
              shouldUploadToCache,
              outputHash,
              outputSize,
              getBuildRuleDiagnosticData(failureOrBuiltLocally),
              Optional.ofNullable(manifestFetchResult),
              Optional.ofNullable(manifestStoreResult));
      return finished;
    }
  }
}
