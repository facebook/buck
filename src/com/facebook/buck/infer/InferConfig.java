/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.infer;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.tool.config.ToolConfig;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import org.immutables.value.Value;

/** Infer specific buck config */
@BuckStyleValue
public abstract class InferConfig implements ConfigView<BuckConfig> {
  // TODO(arr): change to just "infer" when cxx and java configs are consolidated
  private static final String INFER_CONFIG_SECTION = "infer_java";

  @Override
  public abstract BuckConfig getDelegate();

  @Value.Lazy
  public Optional<ToolProvider> getBinary() {
    return getDelegate().getView(ToolConfig.class).getToolProvider(INFER_CONFIG_SECTION, "binary");
  }

  @Value.Lazy
  public Optional<String> getVersion() {
    return getDelegate().getValue(INFER_CONFIG_SECTION, "version");
  }

  @Value.Lazy
  public Optional<SourcePath> getConfigFile(TargetConfiguration targetConfiguration) {
    return getDelegate().getSourcePath(INFER_CONFIG_SECTION, "config_file", targetConfiguration);
  }

  @Value.Lazy
  @AddToRuleKey
  public ImmutableList<String> getNullsafeArgs() {
    return getDelegate().getListWithoutComments(INFER_CONFIG_SECTION, "nullsafe_args");
  }
}
