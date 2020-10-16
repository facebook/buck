/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.build.engine;

import com.facebook.buck.core.build.action.BuildEngineAction;
import com.facebook.buck.core.rules.BuildRule;

public interface RuleDepsCache {

  /** @return all build and runtime deps for {@code rule}. */
  Iterable<BuildRule> get(BuildRule rule);

  /** @return build deps for {@code rule}. */
  Iterable<BuildRule> getBuildDeps(BuildRule rule);

  /**
   * @param buildEngineAction an action for the build engine that we want the deps for
   * @return the actions the given action depends on for build
   */
  Iterable<BuildEngineAction> get(BuildEngineAction buildEngineAction);
}
