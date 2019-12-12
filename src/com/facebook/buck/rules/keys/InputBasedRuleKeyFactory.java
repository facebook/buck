/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.rules.keys;

import com.facebook.buck.core.build.action.BuildEngineAction;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.actions.Action;
import com.facebook.buck.core.rules.attr.HasDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.impl.DependencyAggregation;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.thrift.ThriftRuleKeyLogger;
import com.facebook.buck.rules.keys.hasher.RuleKeyHasher;
import com.facebook.buck.util.hashing.FileHashLoader;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * A factory for generating input-based {@link RuleKey}s.
 *
 * @see com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey
 */
public class InputBasedRuleKeyFactory implements RuleKeyFactory<RuleKey> {

  private final RuleKeyFieldLoader ruleKeyFieldLoader;
  private final FileHashLoader fileHashLoader;
  private final SourcePathRuleFinder ruleFinder;
  private final long inputSizeLimit;
  private final Optional<ThriftRuleKeyLogger> ruleKeyLogger;

  private final SingleBuildActionRuleKeyCache<Result<RuleKey>> ruleKeyCache =
      new SingleBuildActionRuleKeyCache<>();

  public InputBasedRuleKeyFactory(
      RuleKeyFieldLoader ruleKeyFieldLoader,
      FileHashLoader hashLoader,
      SourcePathRuleFinder ruleFinder,
      long inputSizeLimit,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger) {
    this.ruleKeyFieldLoader = ruleKeyFieldLoader;
    this.fileHashLoader = hashLoader;
    this.ruleFinder = ruleFinder;
    this.inputSizeLimit = inputSizeLimit;
    this.ruleKeyLogger = ruleKeyLogger;
  }

  @Override
  public Optional<Long> getInputSizeLimit() {
    return Optional.of(inputSizeLimit);
  }

  private Result<RuleKey> calculateBuildRuleKey(BuildEngineAction action) {
    Builder<HashCode> builder = newVerifyingBuilder(action);
    ruleKeyFieldLoader.setFields(builder, action, RuleKeyType.INPUT);
    return builder.buildResult(RuleKey::new);
  }

  private Result<RuleKey> calculateRuleKeyAppendableKey(AddsToRuleKey appendable) {
    Builder<HashCode> subKeyBuilder =
        new Builder<>(RuleKeyBuilder.createDefaultHasher(ruleKeyLogger));
    AlterRuleKeys.amendKey(subKeyBuilder, appendable);
    return subKeyBuilder.buildResult(RuleKey::new);
  }

  @Override
  public RuleKey build(BuildEngineAction action) {
    try {
      return ruleKeyCache.get(action, this::calculateBuildRuleKey).getRuleKey();
    } catch (RuntimeException e) {
      propagateIfSizeLimitException(e);
      throw e;
    }
  }

  private Result<RuleKey> buildAppendableKey(AddsToRuleKey appendable) {
    return ruleKeyCache.get(appendable, this::calculateRuleKeyAppendableKey);
  }

  private void propagateIfSizeLimitException(Throwable throwable) {
    // At the moment, it is difficult to make SizeLimitException be a checked exception. Due to how
    // exceptions are currently handled (e.g. LoadingCache wraps them with ExecutionException),
    // we need to iterate through the cause chain to check if a SizeLimitException is wrapped.
    Throwables.getCausalChain(throwable).stream()
        .filter(t -> t instanceof SizeLimiter.SizeLimitException)
        .findFirst()
        .ifPresent(Throwables::throwIfUnchecked);
  }

  private Builder<HashCode> newVerifyingBuilder(BuildEngineAction action) {
    Set<BuildRule> deps;
    if (action instanceof BuildRule) {
      deps = ((BuildRule) action).getBuildDeps();
    } else if (action instanceof Action) {
      deps =
          ruleFinder.filterBuildRuleInputs(
              Iterables.transform(
                  ((Action) action).getInputs(), artifact -> artifact.asBound().getSourcePath()));
    } else {
      throw new IllegalStateException(
          String.format("Unknown BuildEngineAction type %s", action.getClass()));
    }

    Iterable<DependencyAggregation> aggregatedRules =
        Iterables.filter(deps, DependencyAggregation.class);
    return new Builder<HashCode>(RuleKeyBuilder.createDefaultHasher(ruleKeyLogger)) {
      private boolean hasEffectiveDirectDep(BuildRule dep) {
        for (BuildRule aggregationRule : aggregatedRules) {
          if (aggregationRule.getBuildDeps().contains(dep)) {
            return true;
          }
        }
        return false;
      }

      // Construct the rule key, verifying that all the deps we saw when constructing it
      // are explicit dependencies of the rule.
      @Override
      public <RESULT> Result<RESULT> buildResult(Function<HashCode, RESULT> mapper) {
        Result<RESULT> result = super.buildResult(mapper);
        for (BuildRule usedDep : result.getDeps()) {
          Preconditions.checkState(
              deps.contains(usedDep)
                  || hasEffectiveDirectDep(usedDep)
                  || (action instanceof HasDeclaredAndExtraDeps
                      && ((HasDeclaredAndExtraDeps) action)
                          .getTargetGraphOnlyDeps()
                          .contains(usedDep)),
              "%s: %s not in deps (%s)",
              action.getBuildTarget(),
              usedDep.getBuildTarget(),
              deps);
        }
        return result;
      }
    };
  }

  /* package */ class Builder<RULE_KEY> extends RuleKeyBuilder<RULE_KEY> {

    private final ImmutableList.Builder<Iterable<BuildRule>> deps = ImmutableList.builder();
    private final SizeLimiter sizeLimiter = new SizeLimiter(inputSizeLimit);

    public Builder(RuleKeyHasher<RULE_KEY> hasher) {
      super(ruleFinder, fileHashLoader, hasher);
    }

    @Override
    protected Builder<RULE_KEY> setAddsToRuleKey(AddsToRuleKey appendable) {
      Result<RuleKey> result = InputBasedRuleKeyFactory.this.buildAppendableKey(appendable);
      deps.add(result.getDeps());
      setAddsToRuleKey(result.getRuleKey());
      return this;
    }

    @Override
    public Builder<RULE_KEY> setPath(Path absolutePath, Path ideallyRelative) throws IOException {
      // TODO(plamenko): this check should not be necessary, but otherwise some tests fail due to
      // FileHashLoader throwing NoSuchFileException which doesn't get correctly propagated.
      if (inputSizeLimit != Long.MAX_VALUE) {
        sizeLimiter.add(fileHashLoader.getSize(absolutePath));
      }
      super.setPath(absolutePath, ideallyRelative);
      return this;
    }

    @Override
    protected Builder<RULE_KEY> setPath(ProjectFilesystem filesystem, Path relativePath)
        throws IOException {
      // TODO(plamenko): this check should not be necessary, but otherwise some tests fail due to
      // FileHashLoader throwing NoSuchFileException which doesn't get correctly propagated.
      if (inputSizeLimit != Long.MAX_VALUE) {
        sizeLimiter.add(fileHashLoader.getSize(filesystem, relativePath));
      }
      super.setPath(filesystem, relativePath);
      return this;
    }

    @Override
    protected Builder<RULE_KEY> setNonHashingSourcePath(SourcePath sourcePath) {
      setNonHashingSourcePathDirectly(sourcePath);
      return this;
    }

    // Input-based rule keys are evaluated after all dependencies for a rule are available on
    // disk, and so we can always resolve the `Path` packaged in a `SourcePath`.  We hash this,
    // rather than the rule key from it's `BuildRule`.
    @Override
    protected Builder<RULE_KEY> setSourcePath(SourcePath sourcePath) throws IOException {
      if (sourcePath instanceof BuildTargetSourcePath) {
        deps.add(ImmutableSet.of(ruleFinder.getRule((BuildTargetSourcePath) sourcePath)));
        // fall through and call setSourcePathDirectly as well
      }
      setSourcePathDirectly(sourcePath);
      return this;
    }

    @Override
    protected AbstractRuleKeyBuilder<RULE_KEY> setAction(Action action) {
      // called reflectively via InputBasedRuleKeyFactory.build(BuildRule), so we still need to
      // handle it.
      return setActionRuleKey(InputBasedRuleKeyFactory.this.build(action));
    }

    // Rules supporting input-based rule keys should be described entirely by their `SourcePath`
    // inputs.  If we see a `BuildRule` when generating the rule key, this is likely a break in
    // that contract, so check for that.
    @Override
    protected Builder<RULE_KEY> setBuildRule(BuildRule rule) {
      throw new IllegalStateException(
          String.format(
              "Input-based rule key builders cannot process build rules. "
                  + "Was given %s to add to rule key.",
              rule));
    }

    public <RESULT> Result<RESULT> buildResult(Function<RULE_KEY, RESULT> mapper) {
      return new Result<>(this.build(mapper), Iterables.concat(deps.build()));
    }
  }

  protected static class Result<RULE_KEY> {

    private final RULE_KEY ruleKey;
    private final Iterable<BuildRule> deps;

    public Result(RULE_KEY ruleKey, Iterable<BuildRule> deps) {
      this.ruleKey = ruleKey;
      this.deps = deps;
    }

    public RULE_KEY getRuleKey() {
      return ruleKey;
    }

    public Iterable<BuildRule> getDeps() {
      return deps;
    }
  }
}
