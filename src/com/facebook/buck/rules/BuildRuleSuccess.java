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

import com.google.common.base.Preconditions;

/**
 * Token provided by the result of {@link BuildRule#build(BuildContext)}, demonstrating that the
 * associated {@link BuildRule} was built successfully.
 */
public class BuildRuleSuccess {

  private final BuildRule rule;
  private final boolean isFromBuildCache;

  public BuildRuleSuccess(BuildRule rule, boolean isFromBuildCache) {
    this.rule = Preconditions.checkNotNull(rule);
    this.isFromBuildCache = isFromBuildCache;
  }

  public boolean isFromBuildCache() {
    return isFromBuildCache;
  }

  @Override
  public String toString() {
    return rule.getFullyQualifiedName();
  }
}
