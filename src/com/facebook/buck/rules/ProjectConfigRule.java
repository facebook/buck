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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.Optionals;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;


public class ProjectConfigRule extends AbstractBuildRule implements BuildRule {

  @Nullable
  private final BuildRule srcRule;

  /** Likely empty for a directory that contains only an android_binary() rule. */
  @Nullable
  private final ImmutableList<SourceRoot> srcSourceRoots;

  @Nullable
  private final BuildRule testRule;

  @Nullable
  private final ImmutableList<SourceRoot> testsSourceRoots;

  private final boolean isIntelliJPlugin;

  private final ListenableFuture<BuildRuleSuccess> buildOutput;

  public ProjectConfigRule(BuildRuleParams buildRuleParams,
      @Nullable BuildRule srcRule,
      @Nullable List<String> srcRoots,
      @Nullable BuildRule testRule,
      @Nullable List<String> testRoots,
      boolean isIntelliJPlugin) {
    super(buildRuleParams);
    Preconditions.checkArgument(srcRule != null || testRule != null,
        "At least one of src_target or test_target must be specified in %s.",
        buildRuleParams.getBuildTarget().getFullyQualifiedName());
    Preconditions.checkArgument(testRule == null || testRule.getType().isTestRule(),
        "The test_target for a project_config() must correspond to a test rule, if specified, " +
        "but was %s.",
        testRule);

    this.srcRule = srcRule;
    if (srcRoots != null) {
      this.srcSourceRoots = ImmutableList.copyOf(Iterables.transform(srcRoots,
          new Function<String, SourceRoot>() {
        @Override
        public SourceRoot apply(String srcRoot) {
          return new SourceRoot(srcRoot);
        }
      }));
    } else {
      this.srcSourceRoots = null;
    }

    this.testRule = testRule;
    if (testRoots != null) {
      this.testsSourceRoots = ImmutableList.copyOf(Iterables.transform(testRoots,
          new Function<String, SourceRoot>() {
        @Override
        public SourceRoot apply(String testRoot) {
          return new SourceRoot(testRoot);
        }
      }));
    } else {
      this.testsSourceRoots = null;
    }

    this.isIntelliJPlugin = isIntelliJPlugin;

    BuildRuleSuccess buildRuleSuccess = new BuildRuleSuccess(this, /* isFromBuildCache */ false);
    this.buildOutput = Futures.immediateFuture(buildRuleSuccess);
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.PROJECT_CONFIG;
  }

  /**
   * @return the BuildRule that should determine the type of IDE project to create. This will be
   *     the srcRule, if it is present; otherwise, it will be the test rule.
   */
  public BuildRule getProjectRule() {
    if (getSrcRule() != null) {
      return getSrcRule();
    } else {
      return getTestRule();
    }
  }

  @Nullable
  public BuildRule getSrcRule() {
    return srcRule;
  }

  @Nullable
  public BuildRule getTestRule() {
    return testRule;
  }

  @Nullable
  public ImmutableList<SourceRoot> getSourceRoots() {
    return srcSourceRoots;
  }

  @Nullable
  public ImmutableList<SourceRoot> getTestsSourceRoots() {
    return testsSourceRoots;
  }

  public boolean getIsIntelliJPlugin() {
    return isIntelliJPlugin;
  }

  @Override
  public ListenableFuture<BuildRuleSuccess> build(BuildContext context) {
    return buildOutput;
  }

  @Override
  public final Iterable<InputRule> getInputs() {
    return ImmutableList.of();
  }

  public static Builder newProjectConfigRuleBuilder() {
    return new Builder();
  }

  public static class Builder extends AbstractBuildRuleBuilder {

    private Optional<String> srcTargetId = Optional.absent();
    @Nullable private List<String> srcRoots = null;
    private Optional<String> testTargetId = Optional.absent();
    @Nullable private List<String> testRoots = null;
    private boolean isIntelliJPlugin = false;

    private Builder() {}

    @Override
    public ProjectConfigRule build(final Map<String, BuildRule> buildRuleIndex) {
      BuildRule srcRule = buildRuleIndex.get(srcTargetId.orNull());
      BuildRule testRule = buildRuleIndex.get(testTargetId.orNull());

      return new ProjectConfigRule(createBuildRuleParams(buildRuleIndex),
          srcRule,
          srcRoots,
          testRule,
          testRoots,
          isIntelliJPlugin);
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      super.setBuildTarget(buildTarget);
      return this;
    }

    public Builder setSrcTarget(Optional<String> srcTargetId) {
      this.srcTargetId = Preconditions.checkNotNull(srcTargetId);
      return this;
    }

    public Builder setSrcRoots(@Nullable List<String> srcRoots) {
      this.srcRoots = srcRoots;
      return this;
    }

    public Builder setTestTarget(Optional<String> testTargetId) {
      this.testTargetId = Preconditions.checkNotNull(testTargetId);
      return this;
    }

    public Builder setTestRoots(@Nullable List<String> testRoots) {
      this.testRoots = testRoots;
      return this;
    }

    public Builder setIsIntelliJPlugin(boolean isIntellijPlugin) {
      this.isIntelliJPlugin = isIntellijPlugin;
      return this;
    }

    @Override
    public Set<String> getDeps() {
      ImmutableSet.Builder<String> depsBuilder = ImmutableSet.builder();

      Optionals.addIfPresent(srcTargetId, depsBuilder);
      Optionals.addIfPresent(testTargetId, depsBuilder);

      return depsBuilder.build();
    }
  }

  @Override
  protected RuleKey.Builder ruleKeyBuilder() {
    return super.ruleKeyBuilder()
        .set("srcRule", srcRule)
        .set("srcSourceRoots", srcSourceRoots)
        .set("testRule", testRule)
        .set("testsSourceRoots", testsSourceRoots)
        .set("isIntelliJPlugin", isIntelliJPlugin);
  }
}
