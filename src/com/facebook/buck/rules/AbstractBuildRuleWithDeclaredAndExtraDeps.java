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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableSortedSet;
import java.util.SortedSet;

/**
 * Some rules have a legacy behavior of distinguishing between "declared" deps (i.e. the contents of
 * the TargetNode's deps attribute) and "extra" deps (i.e. other deps which were detected somehow
 * else).
 *
 * <p>This class formalizes those concepts.
 *
 * <p>Some rules have switched to have more custom handling of different kinds of deps. Other rules
 * are currently very unclear as to what "extra" means, or when it should be used.
 */
public abstract class AbstractBuildRuleWithDeclaredAndExtraDeps extends AbstractBuildRule {

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

  public final SortedSet<BuildRule> getDeclaredDeps() {
    return declaredDeps.get();
  }

  public final SortedSet<BuildRule> deprecatedGetExtraDeps() {
    return extraDeps.get();
  }

  /** See {@link TargetNode#getTargetGraphOnlyDeps}. */
  public final ImmutableSortedSet<BuildRule> getTargetGraphOnlyDeps() {
    return targetGraphOnlyDeps;
  }
}
