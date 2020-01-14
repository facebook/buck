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

package com.facebook.buck.rules.keys.config;

import com.facebook.buck.core.module.impl.NoOpBuckModuleHashStrategy;
import com.facebook.buck.rules.keys.config.impl.BuckVersion;

public class TestRuleKeyConfigurationFactory {

  public static RuleKeyConfiguration create() {
    return RuleKeyConfiguration.of(
        0, BuckVersion.getVersion(), Long.MAX_VALUE, new NoOpBuckModuleHashStrategy());
  }

  public static RuleKeyConfiguration createWithSeed(int seed) {
    return RuleKeyConfiguration.of(
        seed, BuckVersion.getVersion(), Long.MAX_VALUE, new NoOpBuckModuleHashStrategy());
  }
}
