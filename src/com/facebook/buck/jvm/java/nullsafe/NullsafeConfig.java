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

package com.facebook.buck.jvm.java.nullsafe;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import org.immutables.value.Value;

/** Configuration for Nullsafe javac plugin. */
@BuckStyleValue
public abstract class NullsafeConfig implements ConfigView<BuckConfig> {
  public static final String SECTION = "nullsafe";
  public static final String PLUGIN_FIELD = "plugin";
  public static final String SIGNATURES_FIELD = "signatures";
  public static final String EXTRA_ARGS_FIELD = "extra_args";

  public static NullsafeConfig of(BuckConfig delegate) {
    return ImmutableNullsafeConfig.ofImpl(delegate);
  }

  /** Same as {@link #getPlugin} but throws if the options is absent. */
  @Value.Lazy
  public BuildTarget requirePlugin(TargetConfiguration targetConfiguration) {
    return getDelegate()
        .getBuildTarget(SECTION, PLUGIN_FIELD, targetConfiguration)
        .orElseThrow(
            () ->
                new HumanReadableException(
                    String.format(
                        "Config option %s.%s should be defined",
                        NullsafeConfig.SECTION, NullsafeConfig.PLUGIN_FIELD)));
  }

  /** Return an build target defining Nullsafe javac plugin. */
  @Value.Lazy
  public Optional<BuildTarget> getPlugin(TargetConfiguration targetConfiguration) {
    return getDelegate().getBuildTarget(SECTION, PLUGIN_FIELD, targetConfiguration);
  }

  /** Return a path to compiled Nullsafe third-party signatures json-file. */
  @Value.Lazy
  public Optional<SourcePath> getSignatures(TargetConfiguration targetConfiguration) {
    return getDelegate().getSourcePath(SECTION, SIGNATURES_FIELD, targetConfiguration);
  }

  @Value.Lazy
  public ImmutableList<String> getExtraArgs() {
    return getDelegate().getListWithoutComments(SECTION, EXTRA_ARGS_FIELD);
  }
}
