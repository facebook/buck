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
import com.facebook.buck.model.BuildTargetPattern;

import java.util.Set;

public interface BuildRuleBuilder<T extends BuildRule> {

  /** @return the build target of this rule */
  public BuildTarget getBuildTarget();

  /** @return a view of the deps of this rule */
  public Set<String> getDeps();

  /** @return a view of the visibility patterns of this rule */
  public Set<BuildTargetPattern> getVisibilityPatterns();

  public T build(BuildRuleBuilderParams buildRuleBuilderParams);

}
