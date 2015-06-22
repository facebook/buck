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

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import java.util.List;

import javax.annotation.Nullable;


public class ProjectConfig extends NoopBuildRule {

  @Nullable
  private final BuildRule srcRule;

  /** Likely empty for a directory that contains only an android_binary() rule. */
  @Nullable
  private final ImmutableList<SourceRoot> srcSourceRoots;

  @Nullable
  private final ImmutableList<SourceRoot> srcResourceRoots;

  @Nullable
  private final BuildRule testRule;

  @Nullable
  private final ImmutableList<SourceRoot> testsSourceRoots;

  @Nullable
  private final ImmutableList<SourceRoot> testsResourceRoots;

  @Nullable
  private final String jdkName;

  @Nullable
  private final String jdkType;

  private final boolean isIntelliJPlugin;

  protected ProjectConfig(
      BuildRuleParams params,
      SourcePathResolver resolver,
      @Nullable BuildRule srcRule,
      @Nullable List<String> srcRoots,
      @Nullable List<String> srcResourceRoots,
      @Nullable BuildRule testRule,
      @Nullable List<String> testRoots,
      @Nullable List<String> testResourceRoots,
      @Nullable String jdkName,
      @Nullable String jdkType,
      boolean isIntelliJPlugin) {
    super(params, resolver);
    Preconditions.checkArgument(srcRule != null || testRule != null,
        "At least one of src_target or test_target must be specified in %s.",
        params.getBuildTarget().getFullyQualifiedName());
    Preconditions.checkArgument(testRule == null || testRule instanceof TestRule,
        "The test_target for a project_config() must correspond to a test rule, if specified, " +
        "but was %s.",
        testRule);

    Function<String, SourceRoot> srcRootsTransform = new Function<String, SourceRoot>() {
      @Override
      public SourceRoot apply(String srcRoot) {
        return new SourceRoot(srcRoot);
      }
    };

    this.srcRule = srcRule;
    if (srcRoots != null) {
      this.srcSourceRoots = ImmutableList.copyOf(Iterables.transform(srcRoots,
            srcRootsTransform));
    } else {
      this.srcSourceRoots = null;
    }

    if (srcResourceRoots != null) {
      this.srcResourceRoots = ImmutableList.copyOf(Iterables.transform(srcResourceRoots,
            srcRootsTransform));
    } else {
      this.srcResourceRoots = null;
    }

    this.testRule = testRule;
    if (testRoots != null) {
      this.testsSourceRoots = ImmutableList.copyOf(Iterables.transform(testRoots,
          srcRootsTransform));
    } else {
      this.testsSourceRoots = null;
    }
    if (testResourceRoots != null) {
      this.testsResourceRoots = ImmutableList.copyOf(Iterables.transform(testResourceRoots,
          srcRootsTransform));
    } else {
      this.testsResourceRoots = null;
    }

    this.jdkName = jdkName;
    this.jdkType = jdkType;
    this.isIntelliJPlugin = isIntelliJPlugin;
  }

  /**
   * @return the BuildRule that should determine the type of IDE project to create. This will be
   *     the srcRule, if it is present; otherwise, it will be the test rule.
   */
  @Nullable
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
  public ImmutableList<SourceRoot> getResourceRoots() {
    return srcResourceRoots;
  }

  @Nullable
  public ImmutableList<SourceRoot> getTestsSourceRoots() {
    return testsSourceRoots;
  }

  @Nullable
  public ImmutableList<SourceRoot> getTestsResourceRoots() {
    return testsResourceRoots;
  }

  public boolean getIsIntelliJPlugin() {
    return isIntelliJPlugin;
  }

  public String getJdkName() {
    return jdkName;
  }

  public String getJdkType() {
    return jdkType;
  }
}
