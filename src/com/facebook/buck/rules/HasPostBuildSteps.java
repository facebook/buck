/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;

/**
 * An interface used by {@link BuildRule}s which perform operations after the build.
 */
public interface HasPostBuildSteps {

  /**
   * @return a list of {@link Step}s that run after the build regardless of whether this build rule
   *     actually ran or hit in the cache.
   */
  ImmutableList<Step> getPostBuildSteps(BuildContext context, BuildableContext buildableContext);

}
