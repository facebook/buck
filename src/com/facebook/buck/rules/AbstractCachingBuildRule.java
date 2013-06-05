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

import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.DirectoryTraverser;
import com.facebook.buck.util.DirectoryTraversers;
import com.facebook.buck.util.MoreFutures;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.Beta;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import javax.annotation.Nullable;

/**
 * Abstract implementation of a {@link BuildRule} that can be cached. If its current {@link RuleKey}
 * matches the one on disk, then it has no work to do. It should also try to fetch its output from
 * an {@link ArtifactCache} to avoid doing any computation.
 * <p>
 * TODO(mbolin, simons): This should be converted from an abstract class that relies on inheritance
 * to a final class that takes an object that implements the following methods:
 * <ul>
 *   <li>getInputsToCompareToOutput()
 *   <li>buildInternal()
 *   <li>appendToRuleKey()
 *   <li>canSkipRebuildIfInterfacesOfDepsAreUnchanged()
 * </ul>
 * Ultimately, we plan to define a BuildRuleDescriptor, from which we will at least be able to
 * provide the implementation of getInputsToCompareToOutput() and appendToRuleKey() automatically.
 * How we plan to generify the ABI logic is up in the air.
 */
@Beta
public abstract class AbstractCachingBuildRule extends AbstractBuildRule implements BuildRule {

  // TODO(mbolin): Make Console and Verbosity accessible through BuildContext and remove this.
  private final static Logger logger = Logger.getLogger(AbstractCachingBuildRule.class.getName());

  // TODO(mbolin): This should be a property of the BuildContext.
  private final ArtifactCache artifactCache;

  /**
   * Lock used to ensure that the logic to kick off a build is performed at most once.
   */
  private final AtomicBoolean hasBuildStarted;

  /**
   * This is the value returned by {@link #build(BuildContext)}.
   * This is initialized by the constructor and marked as final because {@link #build(BuildContext)}
   * must always return the same value.
   */
  private final SettableFuture<BuildRuleSuccess> buildRuleResult;

  /** @see #getInputsToCompareToOutput(BuildContext) */
  private Iterable<InputRule> inputsToCompareToOutputs;

  protected AbstractCachingBuildRule(CachingBuildRuleParams cachingBuildRuleParams) {
      super(cachingBuildRuleParams);
    this.artifactCache = cachingBuildRuleParams.getArtifactCache();
    this.hasBuildStarted = new AtomicBoolean(false);
    this.buildRuleResult = SettableFuture.create();
  }

  /**
   * This rule is designed to be used for precondition checks in subclasses. For example, before
   * running the tests associated with a build rule, it is reasonable to do a sanity check to
   * ensure that the rule has been built.
   */
  protected final synchronized boolean isRuleBuilt() {
    return MoreFutures.isSuccess(buildRuleResult);
  }

  // TODO(mbolin): This method should probably be removed since its meaning is unclear.
  protected boolean isRuleBuiltFromCache() {
    Preconditions.checkArgument(buildRuleResult.isDone(),
        "rule must be built before this method is invoked");
    if (isRuleBuilt()) {
      try {
        return buildRuleResult.get().isFromBuildCache();
      } catch (InterruptedException | ExecutionException ignored) {
        return false;
      }
    } else {
      return false;
    }
  }

  /**
   * Get the set of input files whose contents should be hashed for the purpose of determining
   * whether this rule is cached.
   * <p>
   * Note that the collection of inputs is specified as a list, because for some build rules
   * (such as {@link com.facebook.buck.shell.Genrule}), the order of the inputs is significant. If
   * the order of the inputs is not significant for the build rule, then the list should be
   * alphabetized so that lists with the same elements will be {@code .equals()} to one another.
   */
  abstract protected Iterable<String> getInputsToCompareToOutput(BuildContext context);

  @Override
  public Iterable<InputRule> getInputs() {
    if (inputsToCompareToOutputs == null) {
      List<InputRule> inputs = Lists.newArrayList();
      for (String inputPath : getInputsToCompareToOutput(null /* context */)) {
        inputs.add(new InputRule(inputPath));
      }
      inputsToCompareToOutputs = inputs;
    }
    return inputsToCompareToOutputs;
  }

  @Override
  protected RuleKey.Builder ruleKeyBuilder() {
    // For a rule that lists its inputs via a "srcs" argument, this may seem redundant, but it is
    // not. Here, the inputs are specified as InputRules, which means that the _contents_ of the
    // files will be hashed. In the case of .set("srcs", srcs), the list of strings itself will be
    // hashed. It turns out that we need both of these in order to construct a RuleKey correctly.
    return super.ruleKeyBuilder()
        .setInputs("buck.inputs", getInputs());
  }

  @Override
  public final ListenableFuture<BuildRuleSuccess> build(final BuildContext context) {
    // We use hasBuildStarted as a lock so that we can minimize how much we need to synchronize.
    synchronized(hasBuildStarted) {
      if (hasBuildStarted.get()) {
        return buildRuleResult;
      } else {
        hasBuildStarted.set(true);
      }
    }

    // Build all of the deps first and then schedule a callback for this rule to build itself once
    // all of those rules are done building.
    try {
      // Invoke every dep's build() method and create an uber-ListenableFuture that represents the
      // successful completion of all deps.
      List<ListenableFuture<BuildRuleSuccess>> builtDeps =
          Lists.newArrayListWithCapacity(getDeps().size());
      for (BuildRule dep : getDeps()) {
        builtDeps.add(dep.build(context));
      }
      ListenableFuture<List<BuildRuleSuccess>> allBuiltDeps = Futures.allAsList(builtDeps);

      // Schedule this rule to build itself once all of the deps are built.
      Futures.addCallback(allBuiltDeps,
          new FutureCallback<List<BuildRuleSuccess>>() {

            @Override
            public void onSuccess(List<BuildRuleSuccess> deps) {
              buildOnceDepsAreBuilt(context);
            }

            @Override
            public void onFailure(Throwable failure) {
              buildRuleResult.setException(failure);
            }
          },
          context.getExecutor());
    } catch (Throwable throwable) {
      // This is a defensive catch block: if buildRuleResult is never satisfied, then Buck will
      // hang because a callback that is waiting for this rule's future to complete will never be
      // executed.
      buildRuleResult.setException(throwable);
    }

    return buildRuleResult;
  }

  /**
   * This method is invoked once all of this rule's dependencies are built.
   * <p>
   * This method should be executed on a fresh Runnable in BuildContext's ListeningExecutorService,
   * so there is no reason to schedule new work in a new Runnable.
   * <p>
   * All exit paths through this method should resolve {@link #buildRuleResult} before exiting. To
   * that end, this method should never throw an exception, or else Buck will hang waiting for
   * {@link #buildRuleResult} to be resolved.
   */
  private void buildOnceDepsAreBuilt(final BuildContext context) {
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
    //
    // TODO(mbolin): It appears that we need to be able to compute a RuleKey with and without its
    // deps. Right now, it looks like a RuleKey includes its deps via
    // RuleKey.Builder.builder() when it creates the Builder. We almost always want to do it with
    // deps, with the exception of java_library() when we want to test (1) and (2) but not (3).
    RuleKey ruleKey;
    if (canSkipRebuildIfInterfacesOfDepsAreUnchanged()) {
      // TODO(mbolin): Implement this case.

      // Compute the current ruleKey only if necessary.
      ruleKey = null;
    } else {
      // Compute the current RuleKey and compare it to the one stored on disk.
      ruleKey = getRuleKey();
      Optional<RuleKey> cachedRuleKey = getRuleKeyOnDisk(context.getProjectFilesystem());

      // If the RuleKeys match, then there is nothing to build.
      if (cachedRuleKey.isPresent() && ruleKey.equals(cachedRuleKey.get())) {
        logger.info(String.format("[UNCHANGED %s]", getFullyQualifiedName()));
        buildRuleResult.set(new BuildRuleSuccess(this, /* isFromBuildCache */ false));
        return;
      }
    }

    Preconditions.checkNotNull(ruleKey, "ruleKey must be set by the preceding codepath.");

    // Record the start of the build.
    context.getEventBus().post(BuildEvents.started(this));

    // Before deciding to build, check the ArtifactCache.
    File output = getOutput();
    boolean fromCache = (output != null && artifactCache.fetch(getRuleKey(), output));
    CacheResult cacheResult = fromCache ? CacheResult.HIT : CacheResult.MISS;

    // Run the steps to build this rule since it was not found in the cache.
    if (!fromCache) {
      try {
        executeCommandsNowThatDepsAreBuilt(context);
      } catch (IOException|StepFailedException e) {
        buildRuleResult.setException(e);
        BuildEvents.finished(this, BuildRuleStatus.FAIL, cacheResult);
        return;
      }
    }

    // Record that the build rule has built successfully.
    try {
      recordBuildRuleCompleted(context.getProjectFilesystem(), fromCache);
    } catch (IOException e) {
      // If we failed to record the success, then we are in a potentially bad state where we have a
      // new output but an old RuleKey record.
      // TODO(mbolin): Make a final attempt to clear the invalid RuleKey record.
      buildRuleResult.setException(e);
      BuildEvents.finished(this, BuildRuleStatus.FAIL, cacheResult);
      return;
    }

    // We made it to the end of the method! Record our success.
    buildRuleResult.set(new BuildRuleSuccess(this, /* isFromBuildCache */ fromCache));
    context.getEventBus().post(
        BuildEvents.finished(this, BuildRuleStatus.SUCCESS, cacheResult));
    return;
  }

  protected boolean canSkipRebuildIfInterfacesOfDepsAreUnchanged() {
    // TODO(mbolin): Override this to return true for java_library() and friends.
    return false;
  }

  /**
   * Return this rule's RuleKey from the previous run if it is available on disk.
   * <p>
   * Any sort of internal IOException will be masked via {@link Optional#absent()}.
   */
  @VisibleForTesting
  Optional<RuleKey> getRuleKeyOnDisk(ProjectFilesystem projectFilesystem) {
    Optional<File> successFile = projectFilesystem.getFileIfExists(getPathToSuccessFile());
    if (successFile.isPresent()) {
      try {
        String ruleKeyHash = Files.readFirstLine(successFile.get(), Charsets.US_ASCII);
        return Optional.of(new RuleKey(ruleKeyHash));
      } catch (IOException|NumberFormatException|NullPointerException e) {
        // As we transition into a new format for a .success file, old versions of .success files
        // may be lying around, which could throw any of these exceptions. When this happens, we
        // treat the .success file the same as if it were missing.
        return Optional.absent();
      }
    } else {
      return Optional.absent();
    }
  }

  /**
   * Execute the commands for this build rule. Requires all dependent rules are already built
   * successfully.
   */
  private void executeCommandsNowThatDepsAreBuilt(BuildContext context)
      throws IOException, StepFailedException {
    logger.info(String.format("[BUILDING %s]", getFullyQualifiedName()));

    // Get and run all of the commands.
    List<Step> steps = buildInternal(context);
    StepRunner stepRunner = context.getCommandRunner();
    for (Step step : steps) {
      stepRunner.runStepForBuildTarget(step, getBuildTarget());
    }
  }

  /**
   * When this method is invoked, all of its dependencies will have been built.
   */
  abstract protected List<Step> buildInternal(BuildContext context) throws IOException;

  /**
   * Record that the outputs for the build rule have been written. They may have been written by
   * either:
   * <ol>
   *   <li>The build rule executing all of its steps successfully.
   *   <li>The build rule pulling down the output artifacts via the ArtifactCache.
   * </ol>
   */
  private void recordBuildRuleCompleted(ProjectFilesystem projectFilesystem, boolean fromCache)
      throws IOException {
    // Drop our cached output key, since it probably changed.
    resetOutputKey();

    // Write the success file.
    writeSuccessFile(projectFilesystem);

    // Store output to cache.
    File output = getOutput();
    if (output != null && !fromCache) {
      artifactCache.store(getRuleKey(), output);
    }
  }

  /**
   * Write out a file that represents that this build rule succeeded, as well as the inputs that
   * were used. The last-modified time of this file, and its contents, will be used to determine
   * whether this rule should be cached.
   * @throws IOException
   */
  private void writeSuccessFile(ProjectFilesystem projectFilesystem) throws IOException {
    String path = getPathToSuccessFile();
    projectFilesystem.createParentDirs(new File(path));
    Iterable<String> lines = getSuccessFileStringsForBuildRules(/* mangleNonIdempotent */ false);
    projectFilesystem.writeLinesToPath(lines, path);
  }

  private Iterable<String> getSuccessFileStringsForBuildRules(
      boolean mangleNonIdempotent) {
    List<String> lines = Lists.newArrayList();

    // The first line should always be the RuleKey.
    lines.add(getRuleKey().toString());

    // These lines are still written for now for debugging purposes, but may ultimately be removed.
    for (BuildRule buildRule : getInputs()) {
      if (buildRule.getOutput() != null) {
        lines.add(String.format("%s %s %s",
            buildRule.getOutputKey().toString(mangleNonIdempotent),
            buildRule.getRuleKey().toString(mangleNonIdempotent),
            buildRule.getFullyQualifiedName()));
      }
    }
    return lines;
  }

  @VisibleForTesting
  String getPathToSuccessFile() {
    return String.format("%s/%s/.success/%s",
        BuckConstant.BIN_DIR,
        getBuildTarget().getBasePath(),
        getBuildTarget().getShortName());
  }

  /**
   * Helper function for subclasses to create their lists of files for caching.
   */
  protected static void addInputsToSortedSet(@Nullable String pathToDirectory,
      ImmutableSortedSet.Builder<String> inputsToConsiderForCachingPurposes,
      DirectoryTraverser traverser) {
    if (pathToDirectory == null) {
      return;
    }
    Set<String> files = DirectoryTraversers.getInstance().findFiles(
        ImmutableSet.of(pathToDirectory), traverser);
    inputsToConsiderForCachingPurposes.addAll(files);
  }
}
