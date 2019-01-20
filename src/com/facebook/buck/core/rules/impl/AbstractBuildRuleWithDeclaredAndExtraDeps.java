/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.rules.impl;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.attr.HasDeclaredAndExtraDeps;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableSortedSet;
import java.util.SortedSet;
import java.util.function.Supplier;

public abstract class AbstractBuildRuleWithDeclaredAndExtraDeps extends AbstractBuildRule
    implements HasDeclaredAndExtraDeps {

  private final Supplier<? extends SortedSet<BuildRule>> declaredDeps;
  private final Supplier<? extends SortedSet<BuildRule>> extraDeps;
  private final Supplier<SortedSet<BuildRule>> buildDeps;
  private final ImmutableSortedSet<BuildRule> targetGraphOnlyDeps;

  protected AbstractBuildRuleWithDeclaredAndExtraDeps(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams) {
    super(buildTarget, projectFilesystem);
    this.declaredDeps = buildRuleParams.getDeclaredDeps();
    this.extraDeps = buildRuleParams.getExtraDeps();
    this.buildDeps = () -> buildRuleParams.getBuildDeps();
    this.targetGraphOnlyDeps = buildRuleParams.getTargetGraphOnlyDeps();
  }

  @Override
  public final SortedSet<BuildRule> getBuildDeps() {
    return buildDeps.get();
  }

  @Override
  public final SortedSet<BuildRule> getDeclaredDeps() {
    return declaredDeps.get();
  }

  @Override
  public final SortedSet<BuildRule> deprecatedGetExtraDeps() {
    return extraDeps.get();
  }

  /** See {@link com.facebook.buck.core.model.targetgraph.TargetNode#getTargetGraphOnlyDeps}. */
  @Override
  public final ImmutableSortedSet<BuildRule> getTargetGraphOnlyDeps() {
    return targetGraphOnlyDeps;
  }
}
