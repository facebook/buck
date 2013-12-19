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

import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.Path;

import javax.annotation.Nullable;

/**
 * No-op implementations of all methods from {@link Buildable}. To switch to
 * {@link AbstractBuildable}:
 * <ol>
 *   <li>Change the class the rule extends.</li>
 *   <li>Modifying its Builder to extend AbstractBuildable.Builder (this is important!)</li>
 *   <li>Fix compilation errors</li>
 *   <li>Check for usages in the buck code base (particularly instanceof checks and casts)</li>
 *   <li>...</li>
 *   <li>profit</li>
 * </ol>
 *
 */
// This currently extends AbstractCachingBuildRule. The next step is to cut that inheritance.
public abstract class DoNotUseAbstractBuildable extends AbstractCachingBuildRule implements Buildable {

  protected DoNotUseAbstractBuildable(BuildRuleParams buildRuleParams) {
    super(buildRuleParams);
  }

  @Override
  public Buildable getBuildable() {
    return this;
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) throws IOException {
    return builder;
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    return null;
  }

  @Override
  @Nullable
  public ImmutableSortedSet<BuildRule> getEnhancedDeps(BuildRuleResolver ruleResolver) {
    return null;
  }
}
