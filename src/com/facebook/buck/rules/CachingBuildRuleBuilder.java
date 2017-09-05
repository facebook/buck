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
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.Threads;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.concurrent.MoreFutures;
import com.facebook.buck.util.concurrent.ResourceAmounts;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.facebook.buck.zip.Unzip;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
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
  private final FileHashCacheMode fileHashCacheMode;
  private final RuleDepsCache ruleDeps;
  private final BuildRule rule;
  private final ExecutionContext executionContext;
  private final OnDiskBuildInfo onDiskBuildInfo;
  private final BuildInfoRecorder buildInfoRecorder;
  private final BuildableContext buildableContext;
  private final BuildRulePipelinesRunner pipelinesRunner;
  private final BuckEventBus eventBus;
  private final BuildContext buildRuleBuildContext;
  private final ArtifactCache artifactCache;
  private final BuildId buildId;

  private final BuildRuleScopeManager buildRuleScopeManager;

  // These fields contain data that may be computed during a build.

  /**
   * This is used to weakly cache the manifest RuleKeyAndInputs. It is not set until after the
   * rule's deps are built.
   *
   * <p>This is necessary because RuleKeyAndInputs may be very large, and due to the async nature of
   * CachingBuildRuleBuilder, there may be many BuildRules that are in-between two stages that both
   * need the manifest's RuleKeyAndInputs. If we just stored the RuleKeyAndInputs directly, we could
   * use too much memory.
   */
  @Nullable private Supplier<Optional<RuleKeyAndInputs>> manifestBasedKeySupplier;

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
      FileHashCacheMode fileHashCacheMode,
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
      BuildRulePipelinesRunner pipelinesRunner) {
    this.buildRuleBuilderDelegate = buildRuleBuilderDelegate;
    this.artifactCacheSizeLimit = artifactCacheSizeLimit;
    this.buildInfoStoreManager = buildInfoStoreManager;
    this.buildMode = buildMode;
    this.buildRuleDurationTracker = buildRuleDurationTracker;
    this.consoleLogBuildFailuresInline = consoleLogBuildFailuresInline;
    this.defaultRuleKeyDiagnostics = defaultRuleKeyDiagnostics;
    this.depFiles = depFiles;
    this.fileHashCache = fileHashCache;
    this.fileHashCacheMode = fileHashCacheMode;
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
    this.buildInfoRecorder = buildInfoRecorder;
    this.buildableContext = buildableContext;
    this.pipelinesRunner = pipelinesRunner;
    this.eventBus = buildContext.getEventBus();
    this.buildRuleBuildContext = buildContext.getBuildContext();
    this.artifactCache = buildContext.getArtifactCache();
    this.buildId = buildContext.getBuildId();
    this.buildRuleScopeManager = new BuildRuleScopeManager();
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
                eventBus.post(ConsoleEvent.severe(getErrorMessageIncludingBuildRule(thrown)));
              }

              thrown = maybeAttachBuildRuleNameToException(thrown);
              recordFailureAndCleanUp(thrown);

              return Futures.immediateFuture(BuildResult.failure(rule, thrown));
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
                if (input.getFailure() instanceof InterruptedException) {
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

  private ListenableFuture<BuildResult> finalizeBuildRule(
      BuildResult input, AtomicReference<Long> outputSize)
      throws StepFailedException, InterruptedException, IOException {
    // If we weren't successful, exit now.
    if (input.getStatus() != BuildRuleStatus.SUCCESS) {
      return Futures.immediateFuture(input);
    }

    try (Scope scope = LeafEvents.scope(eventBus, "finalizing_build_rule")) {
      // We shouldn't see any build fail result at this point.
      BuildRuleSuccessType success = Preconditions.checkNotNull(input.getSuccess());

      // If we didn't build the rule locally, reload the recorded paths from the build
      // metadata.
      if (success != BuildRuleSuccessType.BUILT_LOCALLY) {
        try {
          for (String str :
              onDiskBuildInfo.getValuesOrThrow(BuildInfo.MetadataKey.RECORDED_PATHS)) {
            buildInfoRecorder.recordArtifact(Paths.get(str));
          }
        } catch (IOException e) {
          LOG.error(e, "Failed to read RECORDED_PATHS for %s", rule);
          throw e;
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
              ((HasPostBuildSteps) rule).getPostBuildSteps(buildRuleBuildContext));
        }

        // Invalidate any cached hashes for the output paths, since we've updated them.
        for (Path path : buildInfoRecorder.getRecordedPaths()) {
          fileHashCache.invalidate(rule.getProjectFilesystem().resolve(path));
        }
      }

      if (SupportsInputBasedRuleKey.isSupported(rule)
          && success == BuildRuleSuccessType.BUILT_LOCALLY
          && !buildInfoRecorder
              .getBuildMetadataFor(BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY)
              .isPresent()) {
        // Doing this here is probably not strictly necessary, however in the case of
        // pipelined rules built locally we will never do an input-based cache check.
        // That check would have written the key to metadata, and there are some asserts
        // during cache upload that try to ensure they are present.
        Optional<RuleKey> inputRuleKey = calculateInputBasedRuleKey();
        if (inputRuleKey.isPresent()) {
          buildInfoRecorder.addBuildMetadata(
              BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY, inputRuleKey.get().toString());
        }
      }

      // If this rule uses dep files and we built locally, make sure we store the new dep file
      // list and re-calculate the dep file rule key.
      if (useDependencyFileRuleKey() && success == BuildRuleSuccessType.BUILT_LOCALLY) {

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
        buildInfoRecorder.addMetadata(BuildInfo.MetadataKey.DEP_FILE, inputStrings);

        // Re-calculate and store the depfile rule key for next time.
        Optional<RuleKeyAndInputs> depFileRuleKeyAndInputs =
            calculateDepFileRuleKey(Optional.of(inputStrings), /* allowMissingInputs */ false);
        if (depFileRuleKeyAndInputs.isPresent()) {
          RuleKey depFileRuleKey = depFileRuleKeyAndInputs.get().getRuleKey();
          buildInfoRecorder.addBuildMetadata(
              BuildInfo.MetadataKey.DEP_FILE_RULE_KEY, depFileRuleKey.toString());

          // Push an updated manifest to the cache.
          if (useManifestCaching()) {
            // TODO(cjhopman): This should be able to use manifestKeySupplier.
            Optional<RuleKeyAndInputs> manifestKey = calculateManifestKey(eventBus);
            if (manifestKey.isPresent()) {
              buildInfoRecorder.addBuildMetadata(
                  BuildInfo.MetadataKey.MANIFEST_KEY, manifestKey.get().getRuleKey().toString());
              updateAndStoreManifest(
                  depFileRuleKeyAndInputs.get().getRuleKey(),
                  depFileRuleKeyAndInputs.get().getInputs(),
                  manifestKey.get(),
                  artifactCache);
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
          && shouldUploadToCache(success, Preconditions.checkNotNull(outputSize.get()))) {
        ImmutableSortedMap.Builder<String, String> outputHashes = ImmutableSortedMap.naturalOrder();
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
          throw new IOException(String.format("Failed to write metadata to disk for %s.", rule), e);
        }
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
    }

    return Futures.immediateFuture(input);
  }

  private void uploadToCache(BuildRuleSuccessType success) {
    // Collect up all the rule keys we have index the artifact in the cache with.
    Set<RuleKey> ruleKeys = new HashSet<>();

    // If the rule key has changed (and is not already in the cache), we need to push
    // the artifact to cache using the new key.
    ruleKeys.add(ruleKeyFactories.getDefaultRuleKeyFactory().build(rule));

    // If the input-based rule key has changed, we need to push the artifact to cache
    // using the new key.
    if (SupportsInputBasedRuleKey.isSupported(rule)) {
      Optional<RuleKey> calculatedRuleKey = calculateInputBasedRuleKey();
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
      if (calculatedRuleKey.isPresent()) {
        ruleKeys.add(calculatedRuleKey.get());
      }
    }

    // If the manifest-based rule key has changed, we need to push the artifact to cache
    // using the new key.
    if (useManifestCaching()) {
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
      if (onDiskRuleKey.isPresent()) {
        ruleKeys.add(onDiskRuleKey.get());
      }
    }

    // Do the actual upload.
    try {

      // Verify that the recorded path hashes are accurate.
      Optional<String> recordedPathHashes =
          buildInfoRecorder.getBuildMetadataFor(BuildInfo.MetadataKey.RECORDED_PATH_HASHES);
      if (recordedPathHashes.isPresent()
          && !verifyRecordedPathHashes(
              rule.getBuildTarget(), rule.getProjectFilesystem(), recordedPathHashes.get())) {
        return;
      }

      // Push to cache.
      buildInfoRecorder.performUploadToArtifactCache(
          ImmutableSet.copyOf(ruleKeys), artifactCache, eventBus);

    } catch (Throwable t) {
      eventBus.post(ThrowableConsoleEvent.create(t, "Error uploading to cache for %s.", rule));
    }
  }

  private void handleResult(BuildResult input) {
    Optional<Long> outputSize = Optional.empty();
    Optional<HashCode> outputHash = Optional.empty();
    Optional<BuildRuleSuccessType> successType = Optional.empty();
    boolean shouldUploadToCache = false;

    try (Scope scope = buildRuleScope()) {
      if (input.getStatus() == BuildRuleStatus.SUCCESS) {
        BuildRuleSuccessType success = Preconditions.checkNotNull(input.getSuccess());
        successType = Optional.of(success);

        // Try get the output size.
        if (success == BuildRuleSuccessType.BUILT_LOCALLY
            || success.shouldUploadResultingArtifact()) {
          try {
            outputSize = Optional.of(buildInfoRecorder.getOutputSize());
          } catch (IOException e) {
            eventBus.post(
                ThrowableConsoleEvent.create(e, "Error getting output size for %s.", rule));
          }
        }

        // Compute it's output hash for logging/tracing purposes, as this artifact will
        // be consumed by other builds.
        if (outputSize.isPresent() && shouldHashOutputs(success, outputSize.get())) {
          try {
            outputHash = Optional.of(buildInfoRecorder.getOutputHash(fileHashCache));
          } catch (IOException e) {
            eventBus.post(
                ThrowableConsoleEvent.create(e, "Error getting output hash for %s.", rule));
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
    if (SupportsPipelining.isSupported(rule)) {
      return pipelinesRunner.runPipelineStartingAt((SupportsPipelining<?>) rule, service);
    } else {
      BuildRuleSteps<RulePipelineState> buildRuleSteps = new BuildRuleSteps<>(cacheResult, null);
      service.execute(buildRuleSteps);
      return buildRuleSteps.getFuture();
    }
  }

  private void fillMissingBuildMetadataFromCache(CacheResult cacheResult, String... names) {
    Preconditions.checkState(cacheResult.getType() == CacheResultType.HIT);
    for (String name : names) {
      String value = cacheResult.getMetadata().get(name);
      if (value != null) {
        buildInfoRecorder.addBuildMetadata(name, value);
      }
    }
  }

  // Copy the fetched artifacts build ID to the current builds "origin" build ID.
  private void fillInOriginFromCache(CacheResult cacheResult) {
    buildInfoRecorder.addBuildMetadata(
        BuildInfo.MetadataKey.ORIGIN_BUILD_ID,
        Preconditions.checkNotNull(cacheResult.getMetadata().get(BuildInfo.MetadataKey.BUILD_ID)));
  }

  private ListenableFuture<Optional<BuildResult>> checkManifestBasedCaches() throws IOException {
    manifestBasedKeySupplier =
        MoreSuppliers.weakMemoize(
            () -> {
              try (Scope scope = buildRuleScope()) {
                return calculateManifestKey(eventBus);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
    Optional<RuleKey> manifestKey =
        manifestBasedKeySupplier.get().map(RuleKeyAndInputs::getRuleKey);
    if (!manifestKey.isPresent()) {
      return Futures.immediateFuture(Optional.empty());
    }
    buildInfoRecorder.addBuildMetadata(
        BuildInfo.MetadataKey.MANIFEST_KEY, manifestKey.get().toString());
    try (Scope scope = LeafEvents.scope(eventBus, "checking_cache_depfile_based")) {
      return performManifestBasedCacheFetch(manifestKey.get());
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

  private ListenableFuture<Optional<BuildResult>> checkInputBasedCaches() throws IOException {
    Optional<RuleKey> inputRuleKey;
    try (Scope scope = buildRuleScope()) {
      // Calculate input-based rule key.
      inputRuleKey = calculateInputBasedRuleKey();
    }
    if (inputRuleKey.isPresent()) {
      return performInputBasedCacheFetch(inputRuleKey.get());
    }
    return Futures.immediateFuture(Optional.empty());
  }

  private ListenableFuture<BuildResult> buildOrFetchFromCache() throws IOException {
    // If we've already seen a failure, exit early.
    if (!buildRuleBuilderDelegate.shouldKeepGoing()) {
      return Futures.immediateFuture(
          BuildResult.canceled(rule, buildRuleBuilderDelegate.getFirstFailure()));
    }

    // 1. Check if it's already built.
    try (Scope scope = buildRuleScope()) {
      Optional<BuildResult> buildResult = checkMatchingLocalKey();
      if (buildResult.isPresent()) {
        return Futures.immediateFuture(buildResult.get());
      }
    }

    AtomicReference<CacheResult> rulekeyCacheResult = new AtomicReference<>();
    ListenableFuture<Optional<BuildResult>> buildResultFuture;

    // 2. Rule key cache lookup.
    buildResultFuture =
        // TODO(cjhopman): This should follow the same, simple pattern as everything else. With a
        // large ui.thread_line_limit, SuperConsole tries to redraw more lines than are available.
        // These cache threads make it more likely to hit that problem when SuperConsole is aware
        // of them.
        Futures.transform(
            performRuleKeyCacheCheck(),
            cacheResult -> {
              rulekeyCacheResult.set(cacheResult);
              return getBuildResultForRuleKeyCacheResult(cacheResult);
            });

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
                    BuildResult.canceled(
                        rule,
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
                        // This needs to adjust the default amounts even in the non-resource-aware scheduling
                        // case so that RuleScheduleInfo works correctly.
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
    final RuleKey defaultRuleKey = ruleKeyFactories.getDefaultRuleKeyFactory().build(rule);
    if (defaultRuleKey.equals(cachedRuleKey.orElse(null))) {
      return Optional.of(
          BuildResult.success(
              rule, BuildRuleSuccessType.MATCHING_RULE_KEY, CacheResult.localKeyUnchangedHit()));
    }
    return Optional.empty();
  }

  private ListenableFuture<CacheResult> performRuleKeyCacheCheck() throws IOException {
    final RuleKey defaultRuleKey = ruleKeyFactories.getDefaultRuleKeyFactory().build(rule);
    long cacheRequestTimestampMillis = System.currentTimeMillis();
    return Futures.transform(
        tryToFetchArtifactFromBuildCacheAndOverlayOnTopOfProjectFilesystem(
            defaultRuleKey,
            artifactCache,
            // TODO(simons): This should be a shared between all tests, not one per cell
            rule.getProjectFilesystem()),
        cacheResult -> {
          RuleKeyCacheResult ruleKeyCacheResult =
              RuleKeyCacheResult.builder()
                  .setBuildTarget(rule.getFullyQualifiedName())
                  .setRuleKey(defaultRuleKey.toString())
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
    fillInOriginFromCache(cacheResult);
    fillMissingBuildMetadataFromCache(
        cacheResult,
        BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY,
        BuildInfo.MetadataKey.DEP_FILE_RULE_KEY,
        BuildInfo.MetadataKey.DEP_FILE);
    return Optional.of(
        BuildResult.success(rule, BuildRuleSuccessType.FETCHED_FROM_CACHE, cacheResult));
  }

  private ListenableFuture<Optional<BuildResult>> handleDepsResults(List<BuildResult> depResults) {
    for (BuildResult depResult : depResults) {
      if (buildMode != CachingBuildEngine.BuildMode.POPULATE_FROM_REMOTE_CACHE
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
        DefaultFileHashCache.createDefaultFileHashCache(filesystem, fileHashCacheMode);

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

  private Throwable maybeAttachBuildRuleNameToException(@Nonnull Throwable thrown) {
    if ((thrown instanceof HumanReadableException) || (thrown instanceof InterruptedException)) {
      return thrown;
    }
    String message = thrown.getMessage();
    if (message != null && message.contains(rule.toString())) {
      return thrown;
    }
    return new RuntimeException(getErrorMessageIncludingBuildRule(thrown), thrown);
  }

  private String getErrorMessageIncludingBuildRule(@Nonnull Throwable thrown) {
    String betterMessage =
        String.format(
            "Building rule [%s] failed. Caused by [%s]",
            rule.getBuildTarget(), thrown.getClass().getSimpleName());
    if (thrown.getMessage() != null) {
      betterMessage += ":\n" + thrown.getMessage();
    }

    return betterMessage;
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
          try (Scope scope = buildRuleScope()) {
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

            return Futures.immediateFuture(
                unzipArtifactFromCacheResult(ruleKey, lazyZipPath, filesystem, cacheResult));
          }
        });
  }

  private ListenableFuture<CacheResult> fetch(
      ArtifactCache artifactCache, RuleKey ruleKey, LazyPath outputPath) {
    return Futures.transform(
        artifactCache.fetchAsync(ruleKey, outputPath),
        (CacheResult cacheResult) -> {
          try (Scope scope = buildRuleScope()) {
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
    BuildOutputInitializer<T> buildOutputInitializer = initializable.getBuildOutputInitializer();
    T buildOutput = buildOutputInitializer.initializeFromDisk(onDiskBuildInfo);
    buildOutputInitializer.setBuildOutput(buildOutput);
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

  /** @return whether we should hash the outputs of the given rule. */
  private boolean shouldHashOutputs(BuildRuleSuccessType successType, long outputSize) {

    // If the success type would never cache the item, avoid calculating the hash.
    if (!successType.shouldUploadResultingArtifact()) {
      return false;
    }

    // If the rule's outputs are bigger than the preset size limit, don't hash it.
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

    try (Scope scope =
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
    return BuildInfo.getPathToMetadataDirectory(rule.getBuildTarget(), rule.getProjectFilesystem())
        .resolve(BuildInfo.MANIFEST);
  }

  // Update the on-disk manifest with the new dep-file rule key and push it to the cache.
  private void updateAndStoreManifest(
      RuleKey key,
      ImmutableSet<SourcePath> inputs,
      RuleKeyAndInputs manifestKey,
      ArtifactCache cache)
      throws IOException {

    Preconditions.checkState(useManifestCaching());

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

  private Optional<RuleKeyAndInputs> calculateManifestKey(BuckEventBus eventBus)
      throws IOException {
    return CachingBuildEngine.calculateManifestKey(
        (SupportsDependencyFileRuleKey) rule, eventBus, ruleKeyFactories);
  }

  // Fetch an artifact from the cache using manifest-based caching.
  private ListenableFuture<Optional<BuildResult>> performManifestBasedCacheFetch(
      RuleKey manifestCacheKey) {
    Preconditions.checkArgument(useManifestCaching());

    final LazyPath tempFile =
        new LazyPath() {
          @Override
          protected Path create() throws IOException {
            return Files.createTempFile("buck.", ".manifest");
          }
        };

    return Futures.transformAsync(
        fetch(artifactCache, manifestCacheKey, tempFile),
        manifestResult -> {
          if (!manifestResult.getType().isSuccess()) {
            return Futures.immediateFuture(Optional.empty());
          }
          RuleKeyAndInputs manifestKey =
              Preconditions.checkNotNull(manifestBasedKeySupplier).get().get();

          Path manifestPath = getManifestPath(rule);

          // Clear out any existing manifest.
          rule.getProjectFilesystem().deleteFileAtPathIfExists(manifestPath);

          // Now, fetch an existing manifest from the cache.
          rule.getProjectFilesystem().createParentDirs(manifestPath);

          try (OutputStream outputStream =
                  rule.getProjectFilesystem().newFileOutputStream(manifestPath);
              InputStream inputStream =
                  new GZIPInputStream(
                      new BufferedInputStream(Files.newInputStream(tempFile.get())))) {
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
            return Futures.immediateFuture(Optional.empty());
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
            return Futures.immediateFuture(Optional.empty());
          }

          return Futures.transform(
              tryToFetchArtifactFromBuildCacheAndOverlayOnTopOfProjectFilesystem(
                  ruleKey.get(),
                  artifactCache,
                  // TODO(simons): This should be shared between all tests, not one per cell
                  rule.getProjectFilesystem()),
              cacheResult -> {
                if (cacheResult.getType().isSuccess()) {
                  fillInOriginFromCache(cacheResult);
                  fillMissingBuildMetadataFromCache(
                      cacheResult,
                      BuildInfo.MetadataKey.DEP_FILE_RULE_KEY,
                      BuildInfo.MetadataKey.DEP_FILE);
                  return Optional.of(
                      BuildResult.success(
                          rule,
                          BuildRuleSuccessType.FETCHED_FROM_CACHE_MANIFEST_BASED,
                          cacheResult));
                }
                return Optional.<BuildResult>empty();
              });
        });
  }

  private Optional<RuleKey> calculateInputBasedRuleKey() {
    try (Scope scope =
        RuleKeyCalculationEvent.scope(eventBus, RuleKeyCalculationEvent.Type.INPUT)) {
      return Optional.of(ruleKeyFactories.getInputBasedRuleKeyFactory().build(rule));
    } catch (SizeLimiter.SizeLimitException ex) {
      return Optional.empty();
    }
  }

  private ListenableFuture<Optional<BuildResult>> performInputBasedCacheFetch(RuleKey inputRuleKey)
      throws IOException {
    Preconditions.checkArgument(SupportsInputBasedRuleKey.isSupported(rule));

    buildInfoRecorder.addBuildMetadata(
        BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY, inputRuleKey.toString());

    // Check the input-based rule key says we're already built.
    if (checkMatchingInputBasedKey(inputRuleKey)) {
      return Futures.immediateFuture(
          Optional.of(
              BuildResult.success(
                  rule,
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
            try (Scope scope = LeafEvents.scope(eventBus, "handling_cache_result")) {
              fillInOriginFromCache(cacheResult);
              fillMissingBuildMetadataFromCache(
                  cacheResult,
                  BuildInfo.MetadataKey.DEP_FILE_RULE_KEY,
                  BuildInfo.MetadataKey.DEP_FILE);
              return Optional.of(
                  BuildResult.success(
                      rule, BuildRuleSuccessType.FETCHED_FROM_CACHE_INPUT_BASED, cacheResult));
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
      try (Scope scope = buildRuleScope()) {
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
                    return Optional.of(
                        BuildResult.canceled(rule, buildRuleBuilderDelegate.getFirstFailure()));
                  }
                  try (Scope scope = buildRuleScope()) {
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
                Optional.of(
                    BuildResult.canceled(rule, buildRuleBuilderDelegate.getFirstFailure())));
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
          future.set(
              Optional.of(BuildResult.canceled(rule, buildRuleBuilderDelegate.getFirstFailure())));
          return;
        }
        try (Scope scope = buildRuleScope()) {
          executeCommandsNowThatDepsAreBuilt();
        }

        // Set the future outside of the scope, to match the behavior of other steps that use
        // futures provided by the ExecutorService.
        future.set(
            Optional.of(
                BuildResult.success(rule, BuildRuleSuccessType.BUILT_LOCALLY, cacheResult)));
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

      LOG.debug("Building locally: %s", rule);
      // Attempt to get an approximation of how long it takes to actually run the command.
      @SuppressWarnings("PMD.PrematureDeclaration")
      long start = System.nanoTime();

      eventBus.post(BuildRuleEvent.willBuildLocally(rule));
      buildRuleBuilderDelegate.onRuleAboutToBeBuilt(rule);

      // Get and run all of the commands.
      List<? extends Step> steps;
      try (Scope scope = LeafEvents.scope(eventBus, "get_build_steps")) {
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
              || input.getSuccess() == BuildRuleSuccessType.BUILT_LOCALLY;
      // Log the result to the event bus.
      BuildRuleEvent.Finished finished =
          BuildRuleEvent.finished(
              resumedEvent,
              getBuildRuleKeys(),
              input.getStatus(),
              input.getCacheResult(),
              onDiskBuildInfo
                  .getBuildValue(BuildInfo.MetadataKey.ORIGIN_BUILD_ID)
                  .map(BuildId::new),
              successType,
              shouldUploadToCache,
              outputHash,
              outputSize,
              getBuildRuleDiagnosticData(failureOrBuiltLocally));
      return finished;
    }
  }
}
