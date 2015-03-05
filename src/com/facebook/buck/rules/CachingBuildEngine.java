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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.ThrowableConsoleEvent;
import com.facebook.buck.io.MoreFiles;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.util.concurrent.MoreFutures;
import com.facebook.buck.zip.Unzip;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

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
  private final ConcurrentMap<BuildTarget, SettableFuture<BuildRuleSuccess>> results =
      Maps.newConcurrentMap();

  private final ConcurrentMap<BuildTarget, RuleKey> ruleKeys = Maps.newConcurrentMap();

  private final long skipLocalBuildDepth;

  public CachingBuildEngine(long skipLocalBuildDepth) {
    Preconditions.checkArgument(skipLocalBuildDepth >= 0L);
    this.skipLocalBuildDepth = skipLocalBuildDepth;
  }

  @VisibleForTesting
  public CachingBuildEngine() {
    this(0L);
  }

  @VisibleForTesting
  public SettableFuture<BuildRuleSuccess> createFutureFor(BuildTarget buildTarget) {
    SettableFuture<BuildRuleSuccess> newFuture = SettableFuture.create();
    SettableFuture<BuildRuleSuccess> result = results.putIfAbsent(
        buildTarget,
        newFuture);
    return result == null ? newFuture : result;
  }

  @VisibleForTesting
  void setBuildRuleResult(
      BuildTarget buildTarget,
      BuildRuleSuccess success) {
    createFutureFor(buildTarget).set(success);
  }

  @Override
  public boolean isRuleBuilt(BuildTarget buildTarget) throws InterruptedException {
    SettableFuture<BuildRuleSuccess> resultFuture = results.get(buildTarget);
    return resultFuture != null && MoreFutures.isSuccess(resultFuture);
  }

  @Nullable
  @Override
  public RuleKey getRuleKey(BuildTarget buildTarget) {
    return ruleKeys.get(buildTarget);
  }

  private ListenableFuture<BuildRuleSuccess> buildInternal(
      final BuildContext context,
      final BuildRule rule,
      List<ListenableFuture<Void>> asyncJobs) {

    final SettableFuture<BuildRuleSuccess> newFuture = SettableFuture.create();
    SettableFuture<BuildRuleSuccess> existingFuture = results.putIfAbsent(
        rule.getBuildTarget(),
        newFuture);

    // If the future was already in results for this build rule, return what was there.
    if (existingFuture != null) {
      return existingFuture;
    }

    // Build all of the deps first and then schedule a callback for this rule to build itself once
    // all of those rules are done building.
    try {

      // Invoke every dep's build() method and create an uber-ListenableFuture that represents the
      // successful completion of all deps.
      List<ListenableFuture<BuildRuleSuccess>> builtDeps =
          Lists.newArrayListWithCapacity(rule.getDeps().size());
      for (BuildRule dep : rule.getDeps()) {
        builtDeps.add(buildInternal(context, dep, asyncJobs));
      }
      ListenableFuture<List<BuildRuleSuccess>> allBuiltDeps = Futures.allAsList(builtDeps);

      // Schedule this rule to build itself once all of the deps are built.
      ListenableFuture<Void> callbackFuture = context.getStepRunner().addCallback(
          allBuiltDeps,
          new FutureCallback<List<BuildRuleSuccess>>() {

            private final BuckEventBus eventBus = context.getEventBus();

            private final OnDiskBuildInfo onDiskBuildInfo = context.createOnDiskBuildInfoFor(
                rule.getBuildTarget());

            /**
             * It is imperative that:
             * <ol>
             *   <li>The {@link BuildInfoRecorder} is not constructed until all of the
             *       {@link BuildRule}'s {@code deps} are guaranteed to be built. This ensures that
             *       the {@link RuleKey} will be available before the {@link BuildInfoRecorder} is
             *       constructed.
             *       <p>
             *       This is why a {@link Supplier} is used.
             *   <li>Only one {@link BuildInfoRecorder} is created per {@link BuildRule}. This
             *       ensures that all build-related information for a {@link BuildRule} goes though
             *       a single recorder, whose data will be persisted in {@link #onSuccess(List)}.
             *       <p>
             *       This is why {@link Suppliers#memoize(Supplier)} is used.
             * </ol>
             */
            private final Supplier<BuildInfoRecorder> buildInfoRecorder = Suppliers.memoize(
                new Supplier<BuildInfoRecorder>() {
                  @Override
                  public BuildInfoRecorder get() {
                    RuleKey ruleKey;
                    RuleKey ruleKeyWithoutDeps;
                    ruleKey = rule.getRuleKey();
                    ruleKeyWithoutDeps = rule.getRuleKeyWithoutDeps();

                    return context.createBuildInfoRecorder(
                        rule.getBuildTarget(), ruleKey, ruleKeyWithoutDeps);
                  }
                });

            private boolean startOfBuildWasRecordedOnTheEventBus = false;

            @Override
            public void onSuccess(List<BuildRuleSuccess> deps) {
              // Record the start of the build.
              eventBus.logVerboseAndPost(LOG, BuildRuleEvent.started(rule));
              startOfBuildWasRecordedOnTheEventBus = true;

              BuildResult result = null;
              try {
                ruleKeys.putIfAbsent(rule.getBuildTarget(), rule.getRuleKey());
                result = buildOnceDepsAreBuilt(
                    rule,
                    context,
                    onDiskBuildInfo,
                    buildInfoRecorder.get(),
                    shouldTryToFetchFromCache(rule));
                if (result.getStatus() == BuildRuleStatus.SUCCESS) {
                  recordBuildRuleSuccess(result);
                }
              } catch (InterruptedException | RuntimeException e) {
                // StepRunner#addCallback doesn't return a future which means that when we add a
                // callback to the step runner in CachingBuildEngine#build, there is no way to
                // have the exception thrown in the callback to be forwarded to the future we
                // return from it.
                // For now, we'll just catch the RuntimeException.
                // TODO(simons, t5597862): Consider modifying StepRunner#addCallback
                result = new BuildResult(e);
              }
              if (result.getStatus() == BuildRuleStatus.FAIL) {
                recordBuildRuleFailure(result);

                // Reset interrupted flag once failure has been recorded.
                if (result.getFailure() instanceof InterruptedException) {
                  Thread.currentThread().interrupt();
                }
              }
            }

            private void recordBuildRuleSuccess(BuildResult result)
                throws InterruptedException {
              // Make sure that all of the local files have the same values they would as if the
              // rule had been built locally.
              BuildRuleSuccess.Type success = result.getSuccess();
              if (success != null && success.shouldWriteRecordedMetadataToDiskAfterBuilding()) {
                try {
                  boolean clearExistingMetadata = success.shouldClearAndOverwriteMetadataOnDisk();
                  buildInfoRecorder.get().writeMetadataToDisk(clearExistingMetadata);
                } catch (IOException e) {
                  eventBus.post(ThrowableConsoleEvent.create(
                          e,
                          "Failed to write metadata to disk for %s.",
                          rule));
                  onFailure(e);
                }
              }

              doHydrationAfterBuildStepsFinish(rule, result, onDiskBuildInfo);

              // Only now that the rule should be in a completely valid state, resolve the future.
              BuildRuleSuccess buildRuleSuccess = new BuildRuleSuccess(rule, result.getSuccess());
              newFuture.set(buildRuleSuccess);

              // Finally, upload to the artifact cache.
              if (success != null && success.shouldUploadResultingArtifact()) {
                buildInfoRecorder.get().performUploadToArtifactCache(context.getArtifactCache(),
                    eventBus);
              }

              // Post to the event bus that the rule has finished.
              logBuildRuleFinished(result);
            }

            @Override
            public void onFailure(Throwable failure) {
              recordBuildRuleFailure(new BuildResult(failure));
            }

            private void recordBuildRuleFailure(BuildResult result) {
              // TODO(mbolin): Delete all files produced by the rule, as they are not guaranteed to
              // be valid at this point?
              try {
                onDiskBuildInfo.deleteExistingMetadata();
              } catch (IOException e) {
                eventBus.post(ThrowableConsoleEvent.create(
                    e,
                    "Error when deleting metadata for %s.",
                    rule));
              }

              // Note that startOfBuildWasRecordedOnTheEventBus will be false if onSuccess() was
              // never invoked.
              if (startOfBuildWasRecordedOnTheEventBus) {
                logBuildRuleFinished(result);
              }

              // It seems possible (albeit unlikely) that something could go wrong in
              // recordBuildRuleSuccess() after buildRuleResult has been resolved such that Buck
              // would attempt to resolve the future again, which would fail.
              newFuture.setException(Preconditions.checkNotNull(result.getFailure()));
            }

            private void logBuildRuleFinished(BuildResult result) {
              eventBus.logVerboseAndPost(
                  LOG,
                  BuildRuleEvent.finished(
                      rule,
                      result.getStatus(),
                      result.getCacheResult(),
                      Optional.fromNullable(result.getSuccess())));
            }
          });

      // Record the callback future in our async jobs list, so we remember to wait for it at the
      // end.
      asyncJobs.add(callbackFuture);

    } catch (Throwable failure) {
      // This is a defensive catch block: if buildRuleResult is never satisfied, then Buck will
      // hang because a callback that is waiting for this rule's future to complete will never be
      // executed.
      newFuture.setException(failure);
    }

    return newFuture;
  }

  @Override
  public ListenableFuture<BuildRuleSuccess> build(BuildContext context, BuildRule rule) {
    // Keep track of all jobs that run asynchronously with respect to the build dep chain.  We want
    // to make sure we wait for these before calling the build finished.
    List<ListenableFuture<Void>> asyncJobs = Lists.newArrayListWithCapacity(rule.getDeps().size());
    final ListenableFuture<BuildRuleSuccess> result = buildInternal(context, rule, asyncJobs);
    return Futures.transform(
        Futures.allAsList(asyncJobs),
        new AsyncFunction<List<Void>, BuildRuleSuccess>() {
          @Override
          public ListenableFuture<BuildRuleSuccess> apply(List<Void> input) throws Exception {
            return result;
          }
        });
  }

  /**
   * This method is invoked once all of this rule's dependencies are built.
   * <p>
   * This method should be executed on a fresh Runnable in BuildContext's ListeningExecutorService,
   * so there is no reason to schedule new work in a new Runnable.
   * <p>
   * All exit paths through this method should resolve {@link #results} before exiting. To
   * that end, this method should never throw an exception, or else Buck will hang waiting for
   * {@link #results} to be resolved.
   *
   * @param shouldTryToFetchFromCache Making requests to Cassandra can be expensive, so we do not
   *      attempt to fetch from the cache if any of the transitive dependencies gets rebuilt.
   */
  private BuildResult buildOnceDepsAreBuilt(BuildRule rule,
        final BuildContext context,
        OnDiskBuildInfo onDiskBuildInfo,
        BuildInfoRecorder buildInfoRecorder,
        boolean shouldTryToFetchFromCache) {
    // Compute the current RuleKey and compare it to the one stored on disk.
    RuleKey ruleKey = rule.getRuleKey();
    Optional<RuleKey> cachedRuleKey = onDiskBuildInfo.getRuleKey();

    // If the RuleKeys match, then there is nothing to build.
    if (ruleKey.equals(cachedRuleKey.orNull())) {
      return new BuildResult(BuildRuleSuccess.Type.MATCHING_RULE_KEY,
          CacheResult.LOCAL_KEY_UNCHANGED_HIT);
    }

    // Deciding whether we need to rebuild is tricky business. We want to rebuild as little as
    // possible while always being sound.
    //
    // For java_library rules that depend only on their first-order deps,
    // they only need to rebuild themselves if any of the following conditions hold:
    // (1) The definition of the build rule has changed.
    // (2) Any of the input files (which includes resources as well as .java files) have changed.
    // (3) The ABI of any of its dependent java_library rules has changed.
    //
    // For other types of build rules, we have to be more conservative when rebuilding. In those
    // cases, we rebuild if any of the following conditions hold:
    // (1) The definition of the build rule has changed.
    // (2) Any of the input files have changed.
    // (3) Any of the RuleKeys of this rule's deps have changed.
    //
    // Because a RuleKey for a rule will change if any of its transitive deps have changed, that
    // means a change in one of the leaves can result in almost all rules being rebuilt, which is
    // slow. Fortunately, we limit the effects of this when building Java code when checking the ABI
    // of deps instead of the RuleKey for deps.
    AbiRule abiRule = checkIfRuleOrBuildableIsAbiRule(rule);
    if (abiRule != null) {
      RuleKey ruleKeyNoDeps = rule.getRuleKeyWithoutDeps();
      Optional<RuleKey> cachedRuleKeyNoDeps = onDiskBuildInfo.getRuleKeyWithoutDeps();
      if (ruleKeyNoDeps.equals(cachedRuleKeyNoDeps.orNull())) {
        // The RuleKey for the definition of this build rule and its input files has not changed.
        // Therefore, if the ABI of its deps has not changed, there is nothing to rebuild.
        Sha1HashCode abiKeyForDeps = abiRule.getAbiKeyForDeps();
        Optional<Sha1HashCode> cachedAbiKeyForDeps = onDiskBuildInfo.getHash(
            ABI_KEY_FOR_DEPS_ON_DISK_METADATA);
        if (abiKeyForDeps.equals(cachedAbiKeyForDeps.orNull())) {
          return new BuildResult(BuildRuleSuccess.Type.MATCHING_DEPS_ABI_AND_RULE_KEY_NO_DEPS,
              CacheResult.LOCAL_KEY_UNCHANGED_HIT);
        }
      }
    }

    CacheResult cacheResult;
    if (shouldTryToFetchFromCache) {
      // Before deciding to build, check the ArtifactCache.
      // The fetched file is now a ZIP file, so it needs to be unzipped.
      try {
        cacheResult = tryToFetchArtifactFromBuildCacheAndOverlayOnTopOfProjectFilesystem(
            rule,
            buildInfoRecorder,
            context.getArtifactCache(),
            context.getProjectRoot(),
            context);
      } catch (InterruptedException e) {
        return new BuildResult(e);
      }
    } else {
      cacheResult = CacheResult.SKIP;
    }

    // Run the steps to build this rule since it was not found in the cache.
    if (cacheResult.isSuccess()) {
      return new BuildResult(BuildRuleSuccess.Type.FETCHED_FROM_CACHE, cacheResult);
    }

    // The only remaining option is to build locally.
    try {
      executeCommandsNowThatDepsAreBuilt(rule, context, buildInfoRecorder);
    } catch (Exception e) {
      // If the build fails, delete all of the on disk metadata.
      return new BuildResult(e);
    }

    return new BuildResult(BuildRuleSuccess.Type.BUILT_LOCALLY, cacheResult);
  }

  /**
   * @return whether we found a local build chain of the given depth by recursing down the
   *     dependency chain.
   */
  private boolean hasLocalBuildChain(BuildRule rule, long depth) {

    // Look up the success result for this `BuildRule`.
    BuildRuleSuccess success;
    try {
      success = Preconditions.checkNotNull(getBuildRuleResult(rule.getBuildTarget()));
    } catch (InterruptedException | ExecutionException e) {
      // This shouldn't ever happen, as the only way we can get to this point is if the
      // previous build rules in the dep tree generated results.
      throw new IllegalStateException(e);
    }

    // If we built this locally, and caching is enabled for this rule, it means we likely had
    // a cache miss, and may have a local build chain for the given depth.
    if (success.getType() == BuildRuleSuccess.Type.BUILT_LOCALLY &&
        rule.getCacheMode() == CacheMode.ENABLED) {

      // If the given `depth` is zero, we've found our local build chain, so return true.
      if (depth == 0) {
        return true;

      // Otherwise, recurse on our deps looking for the local build chain.
      } else {
        for (BuildRule dep : rule.getDeps()) {
          if (hasLocalBuildChain(dep, depth - 1)) {
            return true;
          }
        }
      }
    }

    return false;
  }

  /**
   * Returns {@code true} if none of the {@link BuildRuleSuccess} objects are built locally.
   */
  private boolean shouldTryToFetchFromCache(BuildRule rule) {

    // If this rule explicitly disables caching, we won't try to fetch.
    if (rule.getCacheMode() == CacheMode.DISABLED) {
      return false;
    }

    // Otherwise, look for a sequence of local builds, which we use as a heuristic to avoid
    // fetching this rule from cache, since this will likely result in a miss.
    if (skipLocalBuildDepth > 0) {
      for (BuildRule dep : rule.getDeps()) {
        if (hasLocalBuildChain(dep, skipLocalBuildDepth - 1)) {
          return false;
        }
      }
    }

    return true;
  }

  private CacheResult tryToFetchArtifactFromBuildCacheAndOverlayOnTopOfProjectFilesystem(
      BuildRule rule,
      BuildInfoRecorder buildInfoRecorder,
      ArtifactCache artifactCache,
      Path projectRoot,
      BuildContext buildContext) throws InterruptedException {
    // Create a temp file whose extension must be ".zip" for Filesystems.newFileSystem() to infer
    // that we are creating a zip-based FileSystem.
    File zipFile;
    try {
      zipFile = File.createTempFile(
          MoreFiles.sanitize(rule.getFullyQualifiedName()),
          ".zip");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    // TODO(mbolin): Change ArtifactCache.fetch() so that it returns a File instead of takes one.
    // Then we could download directly from Cassandra into the on-disk cache and unzip it from
    // there.
    CacheResult cacheResult = buildInfoRecorder.fetchArtifactForBuildable(zipFile, artifactCache);
    if (!cacheResult.isSuccess()) {
      zipFile.delete();
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
            rule.getRuleKey()));
    try {
      Unzip.extractZipFile(zipFile.toPath().toAbsolutePath(),
          projectRoot.toAbsolutePath(),
          /* overwriteExistingFiles */ true);

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
      return CacheResult.MISS;
    } finally {
      buildContext.getEventBus().post(
          ArtifactCacheEvent.finished(
              ArtifactCacheEvent.Operation.DECOMPRESS,
              rule.getRuleKey()));
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
      BuildInfoRecorder buildInfoRecorder)
      throws Exception {
    LOG.debug("Building locally: %s", rule);

    // Get and run all of the commands.
    BuildableContext buildableContext = new DefaultBuildableContext(
        buildInfoRecorder);
    List<Step> steps = rule.getBuildSteps(context, buildableContext);

    AbiRule abiRule = checkIfRuleOrBuildableIsAbiRule(rule);
    if (abiRule != null) {
      buildableContext.addMetadata(
          ABI_KEY_FOR_DEPS_ON_DISK_METADATA,
          abiRule.getAbiKeyForDeps().getHash());
    }

    StepRunner stepRunner = context.getStepRunner();
    for (Step step : steps) {
      stepRunner.runStepForBuildTarget(step, rule.getBuildTarget());

      // Check for interruptions that may have been ignored by step.
      if (Thread.interrupted()) {
        Thread.currentThread().interrupt();
        throw new InterruptedException();
      }
    }

    LOG.debug("Build completed: %s", rule);
  }

  @VisibleForTesting
  public void doHydrationAfterBuildStepsFinish(
      BuildRule rule,
      BuildResult result,
      OnDiskBuildInfo onDiskBuildInfo) {
    // Give the rule a chance to populate its internal data structures now that all of the
    // files should be in a valid state.
    InitializableFromDisk<?> initializable = deriveInitializable(rule);
    if (initializable != null) {
      doInitializeFromDisk(initializable, onDiskBuildInfo);
    }

    // Only now that the rule should be in a completely valid state, resolve the future.
    BuildRuleSuccess buildRuleSuccess = new BuildRuleSuccess(rule, result.getSuccess());
    results.get(rule.getBuildTarget()).set(buildRuleSuccess);
  }

  /**
   * We're moving to a world where BuildRule becomes Buildable. In many cases, that refactoring
   * hasn't happened yet, and in that case "self" and self's Buildable are the same instance. We
   * don't want to call initializeFromDisk more than once, so handle this case gracefully-ish.
   */
   @Nullable
  private InitializableFromDisk<?> deriveInitializable(BuildRule rule) {
    if (rule instanceof InitializableFromDisk) {
      return (InitializableFromDisk<?>) rule;
    }

    return null;
  }

  private <T> void doInitializeFromDisk(InitializableFromDisk<T> initializable,
                                        OnDiskBuildInfo onDiskBuildInfo) {
    BuildOutputInitializer<T> buildOutputInitializer = initializable.getBuildOutputInitializer();
    T buildOutput = buildOutputInitializer.initializeFromDisk(onDiskBuildInfo);
    buildOutputInitializer.setBuildOutput(buildOutput);
  }

  @Nullable
  @Override
  public BuildRuleSuccess getBuildRuleResult(BuildTarget buildTarget)
      throws ExecutionException, InterruptedException {
    SettableFuture<BuildRuleSuccess> result = results.get(buildTarget);
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
}
