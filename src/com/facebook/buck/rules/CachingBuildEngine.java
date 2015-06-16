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

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.ThrowableConsoleEvent;
import com.facebook.buck.io.MoreFiles;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.util.concurrent.MoreFutures;
import com.facebook.buck.zip.Unzip;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.SettableFuture;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A build engine used to build a {@link BuildRule} which also caches the results. If the current
 * {@link RuleKey} of the build rules matches the one on disk, it does not do any work. It also
 * tries to fetch its output from an {@link ArtifactCache} to avoid doing any computation.
 */
public class CachingBuildEngine implements BuildEngine {

  private static final Logger LOG = Logger.get(CachingBuildEngine.class);

  /**
   * Key for {@link OnDiskBuildInfo} to identify the ABI key for the deps of a build rule.
   */
  @VisibleForTesting
  public static final String ABI_KEY_FOR_DEPS_ON_DISK_METADATA = "ABI_KEY_FOR_DEPS";

  /**
   * These are the values returned by {@link #build(BuildContext, BuildRule)}.
   * This must always return the same value for the build of each target.
   */
  private final ConcurrentMap<BuildTarget, ListenableFuture<BuildResult>> results =
      Maps.newConcurrentMap();

  private final ConcurrentMap<BuildTarget, ListenableFuture<RuleKey>> ruleKeys =
      Maps.newConcurrentMap();

  private final ListeningExecutorService service;
  private final BuildMode buildMode;
  private final RuleKeyBuilderFactory inputBasedRuleKeyBuilderFactory;

  public CachingBuildEngine(
      ListeningExecutorService service,
      BuildMode buildMode,
      RuleKeyBuilderFactory inputBasedRuleKeyBuilderFactory) {
    this.service = service;
    this.buildMode = buildMode;
    this.inputBasedRuleKeyBuilderFactory = inputBasedRuleKeyBuilderFactory;
  }

  @VisibleForTesting
  void setBuildRuleResult(
      BuildRule buildRule,
      BuildRuleSuccessType success,
      CacheResult cacheResult) {
    results.put(
        buildRule.getBuildTarget(),
        Futures.immediateFuture(new BuildResult(buildRule, success, cacheResult)));
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
    for (BuildRule dep : rule.getDeps()) {
      depResults.add(getBuildRuleResultWithRuntimeDeps(dep, context, asyncCallbacks));
    }
    return Futures.allAsList(depResults);
  }

  private ListenableFuture<BuildResult> processBuildRule(
      final BuildRule rule,
      final BuildContext context,
      final OnDiskBuildInfo onDiskBuildInfo,
      final BuildInfoRecorder buildInfoRecorder,
      final BuildableContext buildableContext,
      ConcurrentLinkedQueue<ListenableFuture<Void>> asyncCallbacks)
      throws InterruptedException {

    // 1. Check if it's already built.
    Optional<RuleKey> cachedRuleKey =
        onDiskBuildInfo.getRuleKey(BuildInfo.METADATA_KEY_FOR_RULE_KEY);
    if (cachedRuleKey.isPresent() && rule.getRuleKey().equals(cachedRuleKey.get())) {
      return Futures.immediateFuture(
          new BuildResult(
              rule,
              BuildRuleSuccessType.MATCHING_RULE_KEY,
              CacheResult.localKeyUnchangedHit()));
    }

    // 2. Rule key cache lookup.
    final CacheResult cacheResult =
        tryToFetchArtifactFromBuildCacheAndOverlayOnTopOfProjectFilesystem(
            rule,
            rule.getRuleKey(),
            buildInfoRecorder,
            context.getArtifactCache(),
            context.getProjectFilesystem(),
            context);
    if (cacheResult.getType().isSuccess()) {
      return Futures.immediateFuture(
          new BuildResult(rule, BuildRuleSuccessType.FETCHED_FROM_CACHE, cacheResult));
    }

    // Log to the event bus.
    context.getEventBus().logVerboseAndPost(LOG, BuildRuleEvent.suspended(rule));

    // 3. Build deps.
    return Futures.transform(
        getDepResults(rule, context, asyncCallbacks),
        new AsyncFunction<List<BuildResult>, BuildResult>() {
          @Override
          public ListenableFuture<BuildResult> apply(@Nonnull List<BuildResult> depResults)
              throws Exception {

            // Log to the event bus.
            context.getEventBus().logVerboseAndPost(LOG, BuildRuleEvent.resumed(rule));

            // Input-based rule keys.
            if (rule instanceof SupportsInputBasedRuleKey) {

              // Calculate the input-based rule key and record it in the metadata.
              RuleKey inputRuleKey = inputBasedRuleKeyBuilderFactory.newInstance(rule).build();
              buildInfoRecorder.addMetadata(
                  BuildInfo.METADATA_KEY_FOR_INPUT_BASED_RULE_KEY,
                  inputRuleKey.toString());

              // Check the input-based rule key says we're already built.
              Optional<RuleKey> lastInputRuleKey =
                  onDiskBuildInfo.getRuleKey(BuildInfo.METADATA_KEY_FOR_INPUT_BASED_RULE_KEY);
              if (lastInputRuleKey.isPresent() && lastInputRuleKey.get().equals(inputRuleKey)) {
                return Futures.immediateFuture(
                    new BuildResult(
                        rule,
                        BuildRuleSuccessType.MATCHING_INPUT_BASED_RULE_KEY,
                        CacheResult.localKeyUnchangedHit()));
              }

              // Try to fetch the artifact using the input-based rule key.
              CacheResult cacheResult =
                  tryToFetchArtifactFromBuildCacheAndOverlayOnTopOfProjectFilesystem(
                      rule,
                      inputRuleKey,
                      buildInfoRecorder,
                      context.getArtifactCache(),
                      context.getProjectFilesystem(),
                      context);
              if (cacheResult.getType().isSuccess()) {
                return Futures.immediateFuture(
                    new BuildResult(
                        rule,
                        BuildRuleSuccessType.FETCHED_FROM_CACHE_INPUT_BASED,
                        cacheResult));
              }
            }

            // 4. ABI check
            // Deciding whether we need to rebuild is tricky business. We want to rebuild as little
            // as possible while always being sound.
            //
            // For java_library rules that depend only on their first-order deps,
            // they only need to rebuild themselves if any of the following conditions hold:
            // (1) The definition of the build rule has changed.
            // (2) Any of the input files (which includes resources as well as .java files) have
            //     changed.
            // (3) The ABI of any of its dependent java_library rules has changed.
            //
            // For other types of build rules, we have to be more conservative when rebuilding. In
            // those cases, we rebuild if any of the following conditions hold:
            // (1) The definition of the build rule has changed.
            // (2) Any of the input files have changed.
            // (3) Any of the RuleKeys of this rule's deps have changed.
            //
            // Because a RuleKey for a rule will change if any of its transitive deps have changed,
            // that means a change in one of the leaves can result in almost all rules being
            // rebuilt, which is slow. Fortunately, we limit the effects of this when building Java
            // code when checking the ABI of deps instead of the RuleKey for deps.
            AbiRule abiRule = checkIfRuleOrBuildableIsAbiRule(rule);
            if (abiRule != null) {
              RuleKey ruleKeyNoDeps = rule.getRuleKeyWithoutDeps();
              Optional<RuleKey> cachedRuleKeyNoDeps =
                  onDiskBuildInfo.getRuleKey(BuildInfo.METADATA_KEY_FOR_RULE_KEY_WITHOUT_DEPS);
              if (ruleKeyNoDeps.equals(cachedRuleKeyNoDeps.orNull())) {
                // The RuleKey for the definition of this build rule and its input files has not
                // changed.  Therefore, if the ABI of its deps has not changed, there is nothing to
                // rebuild.
                Sha1HashCode abiKeyForDeps = abiRule.getAbiKeyForDeps();
                Optional<Sha1HashCode> cachedAbiKeyForDeps = onDiskBuildInfo.getHash(
                    ABI_KEY_FOR_DEPS_ON_DISK_METADATA);
                if (abiKeyForDeps.equals(cachedAbiKeyForDeps.orNull())) {
                  return Futures.immediateFuture(
                      new BuildResult(
                          rule,
                          BuildRuleSuccessType.MATCHING_DEPS_ABI_AND_RULE_KEY_NO_DEPS,
                          AbstractCacheResult.localKeyUnchangedHit()));
                }
              }
            }

            // 5. build the rule
            executeCommandsNowThatDepsAreBuilt(rule, context, buildableContext);

            return Futures.immediateFuture(
                new BuildResult(rule, BuildRuleSuccessType.BUILT_LOCALLY, cacheResult));
          }
        },
        service);
  }

  private ListenableFuture<BuildResult> processBuildRule(
      final BuildRule rule,
      final BuildContext context,
      ConcurrentLinkedQueue<ListenableFuture<Void>> asyncCallbacks)
      throws InterruptedException {

    // Log to the event bus.
    context.getEventBus().logVerboseAndPost(LOG, BuildRuleEvent.resumed(rule));

    final OnDiskBuildInfo onDiskBuildInfo = context.createOnDiskBuildInfoFor(rule.getBuildTarget());
    final BuildInfoRecorder buildInfoRecorder =
        context.createBuildInfoRecorder(
            rule.getBuildTarget(),
            rule.getRuleKey(),
            rule.getRuleKeyWithoutDeps());
    final BuildableContext buildableContext = new DefaultBuildableContext(buildInfoRecorder);

    // Dispatch the build job for this rule.
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
    if (buildMode == BuildMode.DEEP) {
      buildResult =
          MoreFutures.chainExceptions(
              getDepResults(rule, context, asyncCallbacks),
              buildResult);
    }

    // Setup a callback to handle either the cached or built locally cases.
    AsyncFunction<BuildResult, BuildResult> callback =
        new AsyncFunction<BuildResult, BuildResult>() {
          @Override
          public ListenableFuture<BuildResult> apply(@Nonnull BuildResult input) throws Exception {

            // We shouldn't see any build fail result at this point.
            BuildRuleSuccessType success = Preconditions.checkNotNull(input.getSuccess());

            // The build has succeeded, whether we've fetched from cache, or built locally.
            // So run the post-build steps.
            if (rule instanceof HasPostBuildSteps &&
                (success == BuildRuleSuccessType.BUILT_LOCALLY ||
                 success == BuildRuleSuccessType.FETCHED_FROM_CACHE ||
                 success == BuildRuleSuccessType.FETCHED_FROM_CACHE_INPUT_BASED)) {
              executePostBuildSteps(
                  rule,
                  ((HasPostBuildSteps) rule).getPostBuildSteps(context, buildableContext),
                  context);
            }

            // Make sure that all of the local files have the same values they would as if the
            // rule had been built locally.
            buildInfoRecorder.addMetadata(
                BuildInfo.METADATA_KEY_FOR_TARGET,
                rule.getBuildTarget().toString());
            buildInfoRecorder.addMetadata(
                BuildInfo.METADATA_KEY_FOR_DEPS,
                FluentIterable.from(rule.getDeps())
                    .transform(HasBuildTarget.TO_TARGET)
                    .transform(Functions.toStringFunction()));
            if (success != null && success.shouldWriteRecordedMetadataToDiskAfterBuilding()) {
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
    buildResult = Futures.transform(buildResult, callback);

    // Handle either build success or failure.
    final SettableFuture<BuildResult> result = SettableFuture.create();
    asyncCallbacks.add(
        MoreFutures.addListenableCallback(
            buildResult,
            new FutureCallback<BuildResult>() {
              @Override
              public void onSuccess(BuildResult input) {

                // We should only see build successes at this point.
                BuildRuleSuccessType success = Preconditions.checkNotNull(input.getSuccess());

                // Only now that the rule should be in a completely valid state, resolve the future.
                result.set(input);

                // Collect up all the rule keys we have index the artifact in the cache with.
                Set<RuleKey> ruleKeys = Sets.newHashSet();

                // If the rule key has changed (and is not already in the cache), we need to push
                // the artifact to cache using the new key.
                if (success.shouldUploadResultingArtifact()) {
                  ruleKeys.add(rule.getRuleKey());
                }

                // If the input-based rule key has changed, we need to push the artifact to cache
                // using the new key.
                if (rule instanceof SupportsInputBasedRuleKey &&
                    success.shouldUploadResultingArtifactInputBased()) {
                  ruleKeys.add(
                      onDiskBuildInfo.getRuleKey(
                          BuildInfo.METADATA_KEY_FOR_INPUT_BASED_RULE_KEY).get());
                }

                // If we have any rule keys to push to the cache with, do the upload now.
                if (!ruleKeys.isEmpty()) {
                  try {
                    buildInfoRecorder.performUploadToArtifactCache(
                        ImmutableSet.copyOf(ruleKeys),
                        context.getArtifactCache(),
                        context.getEventBus());
                  } catch (Throwable t) {
                    onFailure(t);
                    return;
                  }
                }

                // Calculate the hash and size of the rule outputs.
                Pair<Long, HashCode> outputHashAndSize;
                try {
                  outputHashAndSize = buildInfoRecorder.getOutputSizeAndHash(Hashing.md5());
                } catch (IOException e) {
                  onFailure(e);
                  return;
                }

                context.getEventBus().logVerboseAndPost(
                    LOG,
                    BuildRuleEvent.finished(
                        rule,
                        BuildRuleStatus.SUCCESS,
                        input.getCacheResult(),
                        Optional.of(input.getSuccess()),
                        Optional.of(outputHashAndSize.getSecond()),
                        Optional.of(outputHashAndSize.getFirst())));
              }

              @Override
              public void onFailure(@Nonnull Throwable thrown) {

                // TODO(mbolin): Delete all files produced by the rule, as they are not guaranteed
                // to be valid at this point?
                try {
                  onDiskBuildInfo.deleteExistingMetadata();
                } catch (Throwable t) {
                  context.getEventBus().post(
                      ThrowableConsoleEvent.create(
                          t,
                          "Error when deleting metadata for %s.",
                          rule));
                }

                // record failure
                result.setException(thrown);

                // Log the error to the event bus.
                context.getEventBus().logVerboseAndPost(
                    LOG,
                    BuildRuleEvent.finished(
                        rule,
                        BuildRuleStatus.FAIL,
                        CacheResult.miss(),
                        Optional.<BuildRuleSuccessType>absent(),
                        Optional.<HashCode>absent(),
                        Optional.<Long>absent()));

                // Reset interrupted flag once failure has been recorded.
                if (thrown instanceof InterruptedException) {
                  Thread.currentThread().interrupt();
                }
              }
            }));
    return result;
  }

  // Provide a future that resolve to the result of executing this rule and its runtime
  // dependencies.
  private ListenableFuture<BuildResult> getBuildRuleResultWithRuntimeDeps(
      final BuildRule rule,
      final BuildContext context,
      final ConcurrentLinkedQueue<ListenableFuture<Void>> asyncCallbacks) {

    // Get the future holding the result for this rule and, if we have no additional runtime deps
    // to attach, return it.
    final ListenableFuture<BuildResult> result = getBuildRuleResult(rule, context, asyncCallbacks);
    if (!(rule instanceof HasRuntimeDeps)) {
      return result;
    }

    // Collect any runtime deps we have into a list of futures.
    ImmutableSortedSet<BuildRule> runtimeDeps = ((HasRuntimeDeps) rule).getRuntimeDeps();
    List<ListenableFuture<BuildResult>> runtimeDepResults =
        Lists.newArrayListWithExpectedSize(runtimeDeps.size());
    for (BuildRule dep : runtimeDeps) {
      runtimeDepResults.add(getBuildRuleResultWithRuntimeDeps(dep, context, asyncCallbacks));
    }

    // Create a new combined future, which runs the original rule and all the runtime deps in
    // parallel, but which propagates an error if any one of them fails.
    return MoreFutures.chainExceptions(
        Futures.allAsList(runtimeDepResults),
        result);
  }

  // Dispatch a job for the given rule (if we haven't already) and return a future tracking it's
  // result.
  private synchronized ListenableFuture<BuildResult> getBuildRuleResult(
      final BuildRule rule,
      final BuildContext context,
      final ConcurrentLinkedQueue<ListenableFuture<Void>> asyncCallbacks) {

    // If the rule is already executing, return it's result future from the cache.
    Optional<ListenableFuture<BuildResult>> existingResult =
        Optional.fromNullable(results.get(rule.getBuildTarget()));
    if (existingResult.isPresent()) {
      return existingResult.get();
    }

    // Otherwise submit a new job for this rule, cache the future, and return it.
    ListenableFuture<RuleKey> ruleKey = calculateRuleKey(rule, context);
    ListenableFuture<BuildResult> result =
        Futures.transform(
            ruleKey,
            new AsyncFunction<RuleKey, BuildResult>() {
              @Override
              public ListenableFuture<BuildResult> apply(@Nonnull RuleKey input) throws Exception {
                return processBuildRule(rule, context, asyncCallbacks);
              }
            },
            service);
    results.put(rule.getBuildTarget(), result);
    return result;
  }

  private synchronized ListenableFuture<RuleKey> calculateRuleKey(
      final BuildRule rule,
      final BuildContext context) {
    ListenableFuture<RuleKey> ruleKey = ruleKeys.get(rule.getBuildTarget());
    if (ruleKey == null) {

      // Grab all the dependency rule key futures.  Since our rule key calculation depends on this
      // one, we need to wait for them to complete.
      List<ListenableFuture<RuleKey>> depKeys =
          Lists.newArrayListWithExpectedSize(rule.getDeps().size());
      for (BuildRule dep : rule.getDeps()) {
        depKeys.add(calculateRuleKey(dep, context));
      }

      // Setup a future to calculate this rule key once the dependencies have been calculated.
      ruleKey = Futures.transform(
          Futures.allAsList(depKeys),
          new Function<List<RuleKey>, RuleKey>() {
            @Override
            public RuleKey apply(List<RuleKey> input) {
              context.getEventBus().logVerboseAndPost(
                  LOG,
                  BuildRuleEvent.started(rule));
              try {
                return rule.getRuleKey();
              } finally {
                context.getEventBus().logVerboseAndPost(
                    LOG,
                    BuildRuleEvent.suspended(rule));
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
    final ListenableFuture<BuildResult> resultFuture =
        getBuildRuleResultWithRuntimeDeps(rule, context, asyncCallbacks);
    return Futures.transform(
        resultFuture,
        new AsyncFunction<BuildResult, BuildResult>() {
          @Override
          public ListenableFuture<BuildResult> apply(@Nonnull BuildResult result)
              throws Exception {
            return Futures.transform(
                Futures.allAsList(asyncCallbacks),
                Functions.constant(result));
          }
        });
  }

  private CacheResult tryToFetchArtifactFromBuildCacheAndOverlayOnTopOfProjectFilesystem(
      BuildRule rule,
      RuleKey ruleKey,
      BuildInfoRecorder buildInfoRecorder,
      ArtifactCache artifactCache,
      ProjectFilesystem filesystem,
      BuildContext buildContext) throws InterruptedException {

    // Create a temp file whose extension must be ".zip" for Filesystems.newFileSystem() to infer
    // that we are creating a zip-based FileSystem.
    File zipFile;
    try {
      zipFile = File.createTempFile(
          "buck_artifact_" + MoreFiles.sanitize(rule.getBuildTarget().getShortName()),
          ".zip");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    // TODO(mbolin): Change ArtifactCache.fetch() so that it returns a File instead of takes one.
    // Then we could download directly from the remote cache into the on-disk cache and unzip it
    // from there.
    CacheResult cacheResult =
        buildInfoRecorder.fetchArtifactForBuildable(ruleKey, zipFile, artifactCache);
    if (!cacheResult.getType().isSuccess()) {
      try {
        Files.delete(zipFile.toPath());
      } catch (IOException e) {
        LOG.warn(e, "failed to delete %s", zipFile);
      }
      return cacheResult;
    }

    // We unzip the file in the root of the project directory.
    // Ideally, the following would work:
    //
    // Path pathToZip = Paths.get(zipFile.getAbsolutePath());
    // FileSystem fs = FileSystems.newFileSystem(pathToZip, /* loader */ null);
    // Path root = Iterables.getOnlyElement(fs.getRootDirectories());
    // MoreFiles.copyRecursively(root, projectRoot);
    //
    // Unfortunately, this does not appear to work, in practice, because MoreFiles fails when trying
    // to resolve a Path for a zip entry against a file Path on disk.
    buildContext.getEventBus().post(
        ArtifactCacheEvent.started(
            ArtifactCacheEvent.Operation.DECOMPRESS,
            ImmutableSet.of(ruleKey)));
    try {
      Unzip.extractZipFile(
          zipFile.toPath().toAbsolutePath(),
          filesystem,
          Unzip.ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);

      // We only delete the ZIP file when it has been unzipped successfully. Otherwise, we leave it
      // around for debugging purposes.
      Files.delete(zipFile.toPath());
    } catch (IOException e) {
      // In the wild, we have seen some inexplicable failures during this step. For now, we try to
      // give the user as much information as we can to debug the issue, but return CacheResult.MISS
      // so that Buck will fall back on doing a local build.
      buildContext.getEventBus().post(ConsoleEvent.warning(
              "Failed to unzip the artifact for %s at %s.\n" +
                  "The rule will be built locally, " +
                  "but here is the stacktrace of the failed unzip call:\n" +
                  rule.getBuildTarget(),
              zipFile.getAbsolutePath(),
              Throwables.getStackTraceAsString(e)));
      return CacheResult.miss();
    } finally {
      buildContext.getEventBus().post(
          ArtifactCacheEvent.finished(
              ArtifactCacheEvent.Operation.DECOMPRESS,
              ImmutableSet.of(rule.getRuleKey())));
    }

    return cacheResult;
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

    AbiRule abiRule = checkIfRuleOrBuildableIsAbiRule(rule);
    if (abiRule != null) {
      buildableContext.addMetadata(
          ABI_KEY_FOR_DEPS_ON_DISK_METADATA,
          abiRule.getAbiKeyForDeps().getHash());
    }

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

  @Nullable
  private AbiRule checkIfRuleOrBuildableIsAbiRule(BuildRule rule) {
    if (rule instanceof AbiRule) {
      return (AbiRule) rule;
    }
    return null;
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
  }

}
