/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.keys.config.impl;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.model.BuckVersion;
import com.facebook.buck.module.BuckModuleHashStrategy;
import com.facebook.buck.module.BuckModuleManager;
import com.facebook.buck.module.impl.DefaultBuckModuleHashStrategy;
import com.facebook.buck.module.impl.NoOpBuckModuleHashStrategy;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;

/** Creates {@link RuleKeyConfiguration} using information from {@link BuckConfig}. */
public class ConfigRuleKeyConfigurationFactory {

  public static RuleKeyConfiguration create(
      BuckConfig buckConfig, BuckModuleManager moduleManager) {
    long inputKeySizeLimit = buckConfig.getBuildInputRuleKeyFileSizeLimit();
    return RuleKeyConfiguration.builder()
        .setSeed(buckConfig.getKeySeed())
        .setCoreKey(getCoreKey(buckConfig))
        .setBuildInputRuleKeyFileSizeLimit(inputKeySizeLimit)
        .setBuckModuleHashStrategy(createBuckModuleHashStrategy(buckConfig, moduleManager))
        .build();
  }

  private static String getCoreKey(BuckConfig buckConfig) {
    String coreKey;
    if (buckConfig.useBuckBinaryHash()) {
      coreKey = BuckBinaryHashProvider.getBuckBinaryHash();
    } else {
      coreKey = BuckVersion.getVersion();
    }
    return coreKey;
  }

  private static BuckModuleHashStrategy createBuckModuleHashStrategy(
      BuckConfig buckConfig, BuckModuleManager moduleManager) {
    BuckModuleHashStrategy hashStrategy;
    if (buckConfig.useBuckBinaryHash()) {
      hashStrategy = new DefaultBuckModuleHashStrategy(moduleManager);
    } else {
      hashStrategy = new NoOpBuckModuleHashStrategy();
    }
    return hashStrategy;
  }
}
