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

import com.google.common.annotations.Beta;
import com.google.common.base.Preconditions;

/**
 * A {@link BuildRule} that is constructed from a {@link Description}.
 */
// TODO(simons): Delete once everything has migrated to using Buildables.
@Beta
public class DescribedRule extends AbstractBuildRule implements BuildRule {

  private final BuildRuleType type;
  private final Buildable buildable;

  public DescribedRule(BuildRuleType type, Buildable buildable, BuildRuleParams params) {
    super(params, buildable);

    this.type = Preconditions.checkNotNull(type);
    this.buildable = Preconditions.checkNotNull(buildable);
  }

  @Override
  public BuildRuleType getType() {
    return type;
  }

  @Override
  public Buildable getBuildable() {
    return buildable;
  }

  @Override
  public BuildableProperties getProperties() {
    return buildable.getProperties();
  }
}
