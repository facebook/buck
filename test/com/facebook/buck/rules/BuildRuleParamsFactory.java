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

import com.facebook.buck.model.BuildTarget;
import com.google.common.base.Preconditions;

public class BuildRuleParamsFactory {

  /** Utility class: do not instantiate. */
  private BuildRuleParamsFactory() {}

  /**
   * @return a {@link BuildRuleParams} with no deps or visibility patterns, and a pathRelativizer
   *     that returns the parameter it receives verbatim.
   */
  public static BuildRuleParams createTrivialBuildRuleParams(BuildTarget buildTarget) {
    return new FakeBuildRuleParamsBuilder(Preconditions.checkNotNull(buildTarget)).build();
  }
}
