/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.jvm.kotlin;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.util.Optional;
import org.immutables.value.Value;

/** Configuration for Kosabi plugin. */
@BuckStyleValue
public abstract class KosabiConfig implements ConfigView<BuckConfig> {
  public static final String SECTION = "kotlin";
  public static final String PROPERTY_KOSABI_STUBS_GEN_PLUGIN = "kosabi_stubs_gen_plugin";
  public static final String PROPERTY_KOSABI_JVM_ABI_GEN_PLUGIN = "kosabi_jvm_abi_gen_plugin";

  public static KosabiConfig of(BuckConfig delegate) {
    return ImmutableKosabiConfig.ofImpl(delegate);
  }

  /** Return an build target defining Kosabi stub generator plugin. */
  @Value.Lazy
  public Optional<SourcePath> getStubsGenPlugin(TargetConfiguration targetConfiguration) {
    return getDelegate()
        .getSourcePath(SECTION, PROPERTY_KOSABI_STUBS_GEN_PLUGIN, targetConfiguration);
  }

  /** Return an build target defining Kosabi jvm-abi plugin. */
  @Value.Lazy
  public Optional<SourcePath> getJvmAbiGenPlugin(TargetConfiguration targetConfiguration) {
    return getDelegate()
        .getSourcePath(SECTION, PROPERTY_KOSABI_JVM_ABI_GEN_PLUGIN, targetConfiguration);
  }
}
