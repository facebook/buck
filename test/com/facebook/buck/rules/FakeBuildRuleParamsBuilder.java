/*
 * Copyright 2013-present Facebook, Inc.
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
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSortedSet;

public class FakeBuildRuleParamsBuilder {

  private final BuildTarget buildTarget;
  private ImmutableSortedSet<BuildRule> declaredDeps = ImmutableSortedSet.of();
  private ImmutableSortedSet<BuildRule> extraDeps = ImmutableSortedSet.of();
  private ProjectFilesystem filesystem = new FakeProjectFilesystem();

  public FakeBuildRuleParamsBuilder(BuildTarget buildTarget) {
    this.buildTarget = Preconditions.checkNotNull(buildTarget);
  }

  public FakeBuildRuleParamsBuilder(String buildTarget) {
    this(BuildTargetFactory.newInstance(Preconditions.checkNotNull(buildTarget)));
  }

  public FakeBuildRuleParamsBuilder setDeclaredDeps(ImmutableSortedSet<BuildRule> declaredDeps) {
    this.declaredDeps = declaredDeps;
    return this;
  }

  public FakeBuildRuleParamsBuilder setExtraDeps(ImmutableSortedSet<BuildRule> extraDeps) {
    this.extraDeps = extraDeps;
    return this;
  }

  public FakeBuildRuleParamsBuilder setProjectFilesystem(ProjectFilesystem filesystem) {
    this.filesystem = filesystem;
    return this;
  }

  public BuildRuleParams build() {
    return new BuildRuleParams(
        buildTarget,
        Suppliers.ofInstance(declaredDeps),
        Suppliers.ofInstance(extraDeps),
        ImmutableSortedSet.of(),
        filesystem);
  }
}
