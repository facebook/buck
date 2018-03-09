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

package com.facebook.buck.rules.modern.builders;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleStrategy;
import com.facebook.buck.rules.modern.ModernBuildRule;
import java.io.IOException;

/** Abstract base class for strategies that support ModernBuildRules. */
abstract class AbstractModernBuildRuleStrategy implements BuildRuleStrategy {
  @Override
  public void close() throws IOException {}

  @Override
  public boolean canBuild(BuildRule instance) {
    return instance instanceof ModernBuildRule;
  }
}
