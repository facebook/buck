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
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.DirectoryTraverser;
import com.facebook.buck.util.DirectoryTraversers;
import com.facebook.buck.util.MoreFiles;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.TriState;
import com.google.common.annotations.Beta;
import com.google.common.annotations.VisibleForTesting;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;

@Beta
public abstract class AbstractCachingBuildRule extends AbstractBuildRule implements BuildRule {

  private final static Logger logger = Logger.getLogger(AbstractCachingBuildRule.class.getName());

  /**
   * This field will initially be UNSPECIFIED. Once it has been determined whether this rule is
   * cached, this field will be set accordingly. Thus, the result of {@link #isCached(BuildContext)}
   * itself is cached.
   */
  private TriState isRuleCached;

  /**
   * This field behaves similarly to isRuleCached, but instead tracks whether or not any of this
   * rule's descendants are uncached.
   */
  private TriState hasUncachedDescendants;

  /**
   * This field behaves similarly to isRuleCached, but instead tracks whether or not any of this
   * rule's inputs were uncached.
   */
  private TriState ruleInputsAreCached;

  private ListenableFuture<BuildRuleSuccess> buildRuleResult;

  private Iterable<InputRule> inputsToCompareToOutputs;

  protected AbstractCachingBuildRule(BuildRuleParams buildRuleParams) {
    super(buildRuleParams);
    this.isRuleCached = TriState.UNSPECIFIED;
    this.hasUncachedDescendants = TriState.UNSPECIFIED;
    this.ruleInputsAreCached = TriState.UNSPECIFIED;
  }

  /**
   * This rule is designed to be used for precondition checks in subclasses. For examples, before
   * running the tests associated with a build rule, it is reasonable to do a sanity check to
   * ensure that the rule has been built.
   */
  protected final synchronized boolean isRuleBuilt() {
    if (buildRuleResult == null) {
      return false;
    } else if (buildRuleResult.isDone()) {
      // If the ListenableFuture<BuildRuleSuccess> is complete, the only way to verify that it
      // completed successfully is to try invoking its get() method.
      try {
        buildRuleResult.get();
        return true;
      } catch (ExecutionException e) {
        return false;
      } catch (InterruptedException e) {
        return false;
      }
    } else {
      return false;
    }
  }

  protected boolean isRuleBuiltFromCache() {
    Preconditions.checkArgument(isRuleCached.isSet(),
        "rule must be built before this method is invoked");
    return isRuleCached.asBoolean();
  }

  @Override
  public final boolean isCached(BuildContext context) throws IOException {
    if (isRuleCached.isSet()) {
      return isRuleCached.asBoolean();
    }

    boolean isCached = checkIsCached(context, logger);
    isRuleCached = TriState.forBooleanValue(isCached);
    return isRuleCached.asBoolean();
  }

  @Override
  public boolean hasUncachedDescendants(final BuildContext context) throws IOException {
    if (hasUncachedDescendants.isSet()) {
      return hasUncachedDescendants.asBoolean();
    }

    if (!isCached(context)) {
      hasUncachedDescendants = TriState.TRUE;
    } else {
      boolean depHasUncachedDescendant = false;
      for (BuildRule dep : getDeps()) {
        if (dep.hasUncachedDescendants(context)) {
          depHasUncachedDescendant = true;
          break;
        }
      }
      hasUncachedDescendants = TriState.forBooleanValue(depHasUncachedDescendant);
    }
    return hasUncachedDescendants.asBoolean();
  }

  private Iterable<BuildRule> getRulesToConsiderForCaching() {
    List<BuildRule> rules = Lists.newArrayList();
    // Not possible due to generics limitations:
    //   rules.addAll(getInputs());
    for (InputRule input : getInputs()) {
      rules.add(input);
    }
    rules.add(this);
    return rules;
  }

  @VisibleForTesting
  public void setIsCached(boolean isCached) {
    isRuleCached = TriState.forBooleanValue(isCached);
  }

  private Iterable<String> getSuccessFileStringsForBuildRules(Iterable<BuildRule> buildRules,
      boolean mangleNonIdempotent) {
    List<String> lines = Lists.newArrayList();
    for (BuildRule buildRule : buildRules) {
      if (buildRule.getOutput() != null) {
        lines.add(String.format("%s %s %s",
            buildRule.getOutputKey().toString(mangleNonIdempotent),
            buildRule.getRuleKey().toString(mangleNonIdempotent),
            buildRule.getFullyQualifiedName()));
      }
    }
    return lines;
  }

  private  boolean isMatchingSuccessState(BuildContext context, Iterable<BuildRule> buildRules,
      String pathRelativeToProjectRoot) throws IOException {
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    return projectFilesystem.isMatchingFileContents(
        getSuccessFileStringsForBuildRules(buildRules, true), pathRelativeToProjectRoot);
  }

  protected boolean ruleInputsCached(BuildContext context, Logger logger) throws IOException {
    if (ruleInputsAreCached.isSet()) {
      return ruleInputsAreCached.asBoolean();
    }
    ruleInputsAreCached = TriState.forBooleanValue(checkRuleInputsCached(context, logger));

    return ruleInputsAreCached.asBoolean();
  }

  private boolean checkRuleInputsCached(BuildContext context, Logger logger) throws IOException {
    // If the success file does not exist, then this rule is not cached.
    String pathToSuccessFile = getPathToSuccessFile();
    if (!context.getProjectFilesystem().exists(pathToSuccessFile)) {
      logger.info(String.format("%s not cached because the output file %s is not built",
              this,
              pathToSuccessFile));
      return false;
    }

    // If any of the input files, output files, or the build rule have been modified since the last
    // build, then this rule should not be cached.
    Iterable<BuildRule> rulesToConsiderForCaching = getRulesToConsiderForCaching();
    if (!isMatchingSuccessState(context, rulesToConsiderForCaching, pathToSuccessFile)) {
      logger.info(String.format(
          "%s not cached because the inputs and/or their contents have changed", this));
      return false;
    }
    return true;
  }

  @VisibleForTesting
  boolean checkIsCached(BuildContext context, Logger logger) throws IOException {
    // First, check whether all of the deps are cached.
    // This is checked first since it does not require touching the filesystem.
    // If all of the deps were cached, check if the inputs to this rule were cached.
    return depsCached(context, logger) && ruleInputsCached(context, logger);
  }

  /**
   * Checks to see if all of the dependencies rules are cached.  By default,
   * AbstractCachingBuildRule will consider a rule's deps uncached if any of its descendants were
   * uncached.
   */
  @VisibleForTesting
  boolean depsCached(BuildContext context, Logger logger) throws IOException {
    for (BuildRule dep : getDeps()) {
      if (dep.hasUncachedDescendants(context)) {
        logger.info(String.format("%s not cached because %s has an uncached descendant",
                    this,
                    dep.getFullyQualifiedName()));
        return false;
      }
    }
    return true;
  }

  /**
   * Get the set of input files whose contents should be hashed for the purpose of determining
   * whether this rule is cached.
   * <p>
   * Note that the collection of inputs is specified as a list, because for some build rules
   * (such as {@link Genrule}), the order of the inputs is significant. If the order of the inputs
   * is not significant for the build rule, then the list should be alphabetized so that lists with
   * the same elements will be {@code .equals()} to one another.
   */
  abstract protected Iterable<String> getInputsToCompareToOutput(BuildContext context);

  @Override
  public final Iterable<InputRule> getInputs() {
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
  public final synchronized ListenableFuture<BuildRuleSuccess> build(final BuildContext context) {
    // If buildRuleResult is non-null, then this method has already been invoked. Because this
    // method must be idempotent, return the existing future.
    if (buildRuleResult != null) {
      // Not posting an event because the start has already been fired.
      return buildRuleResult;
    }

    context.getEventBus().post(BuildEvents.started(this));

    // If this build rule is cached, then a result can be returned immediately.
    boolean isCached;
    try {
      isCached = isCached(context);
    } catch (IOException e) {
      // A failure while determining whether this build rule is cached should be treated as if this
      // rule failed to build.
      buildRuleResult = Futures.immediateFailedFuture(e);
      // Assume a cache result is a miss: "Here's your cached result" should not be exceptional.
      context.getEventBus().post(
          BuildEvents.finished(this, BuildRuleStatus.FAIL, CacheResult.MISS));
      return buildRuleResult;
    }
    if (isCached) {
      logger.log(Level.INFO, String.format("[FROM CACHE %s]", getFullyQualifiedName()));
      buildRuleResult = Futures.immediateFuture(new BuildRuleSuccess(this));
      context.getEventBus().post(
          BuildEvents.finished(this, BuildRuleStatus.SUCCESS, CacheResult.HIT));
      return buildRuleResult;
    }

    // This rule is not cached, so it needs to be built. Ultimately, buildRuleResult will be set
    // with a BuildRuleResult (indicating success) or set with a Throwable (indicating a failure).
    logger.log(Level.INFO, String.format("[BUILDING %s]", getFullyQualifiedName()));
    buildRuleResult = SettableFuture.create();

    // Create a single future that represents the result of building all of the dependencies.
    ListenableFuture<List<BuildRuleSuccess>> builtDeps = Builder.getInstance().buildRules(
        getDeps(), context);

    // Once all of the dependencies have been built, schedule this rule to build itself on the
    // Executor associated with the BuildContext.
    OnDepsBuiltCallback onDepsBuiltCallback = new OnDepsBuiltCallback(context);
    Futures.addCallback(builtDeps, onDepsBuiltCallback, context.getExecutor());

    Futures.addCallback(buildRuleResult, new FutureCallback<BuildRuleSuccess>() {
      @Override
      public void onSuccess(BuildRuleSuccess buildRuleSuccess) {
        context.getEventBus().post(BuildEvents.finished(
            AbstractCachingBuildRule.this, BuildRuleStatus.SUCCESS, CacheResult.MISS));
      }

      @Override
      public void onFailure(Throwable throwable) {
        context.getEventBus().post(BuildEvents.finished(
            AbstractCachingBuildRule.this, BuildRuleStatus.FAIL, CacheResult.MISS));
      }
    }, context.getExecutor());


    return buildRuleResult;
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

  private class OnDepsBuiltCallback implements FutureCallback<List<BuildRuleSuccess>> {

    private final BuildContext context;

    private OnDepsBuiltCallback(BuildContext context) {
      this.context = context;
    }

    @Override
    public void onSuccess(List<BuildRuleSuccess> results) {
      final SettableFuture<BuildRuleSuccess> result =
          (SettableFuture<BuildRuleSuccess>)buildRuleResult;

      ListenableFuture<BuildRuleSuccess> future = executeCommandsNowThatDepsAreBuilt(context);
      Futures.addCallback(
          future,
          new FutureCallback<BuildRuleSuccess>() {
            @Override
            public void onSuccess(BuildRuleSuccess buildRuleSuccess) {
              result.set(buildRuleSuccess);
            }

            @Override
            public void onFailure(Throwable throwable) {
              result.setException(throwable);
            }
          },
          context.getExecutor()
      );
    }

    @Override
    public void onFailure(Throwable throwable) {
      ((SettableFuture<BuildRuleSuccess>)buildRuleResult).setException(throwable);
    }
  }

  /**
   * Execute the commands for this build rule. Requires all dependent rules are already built
   * successfully.
   */
  private ListenableFuture<BuildRuleSuccess> executeCommandsNowThatDepsAreBuilt(
      final BuildContext context) {

    // Do the work to build this rule in a Callable so it can be scheduled on an Executor.
    Callable<BuildRuleSuccess> callable = new Callable<BuildRuleSuccess>() {
      @Override
      public BuildRuleSuccess call() throws Exception {
        AbstractCachingBuildRule buildRule = AbstractCachingBuildRule.this;

        // Get and run all of the commands.
        List<Step> steps = buildInternal(context);
        StepRunner stepRunner = context.getCommandRunner();
        for (Step command : steps) {
          stepRunner.runStep(command);
        }
        // Drop our cached output key, since it probably changed.
        resetOutputKey();

        // Write the success file.
        buildRule.writeSuccessFile();

        // Return the object to represent the success of the build rule.
        return new BuildRuleSuccess(buildRule);
      }
    };

    return context.getCommandRunner().getListeningExecutorService().submit(callable);
  }

  @Override
  protected RuleKey.Builder ruleKeyBuilder() {
    return super.ruleKeyBuilder()
        .set("inputs", getInputs());
  }

  private String getPathToSuccessFile() {
    return String.format("%s/%s/.success/%s",
        BuckConstant.BIN_DIR,
        getBuildTarget().getBasePath(),
        getBuildTarget().getShortName());
  }

  /**
   * Write out a file that represents that this build rule succeeded, as well as the inputs that
   * were used. The last-modified time of this file, and its contents, will be used to determine
   * whether this rule should be cached.
   * @throws IOException
   */
  private void writeSuccessFile() throws IOException {
    String path = getPathToSuccessFile();
    Files.createParentDirs(new File(path));
    MoreFiles.writeLinesToFile(getSuccessFileStringsForBuildRules(
        getRulesToConsiderForCaching(), false), path);
  }

  /**
   * When this method is run, all of its dependencies will have been built.
   */
  abstract protected List<Step> buildInternal(BuildContext context) throws IOException;
}
