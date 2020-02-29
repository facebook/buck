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

package com.facebook.buck.infer;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.tool.config.ToolConfig;
import com.google.common.collect.ImmutableList;
import java.nio.file.Paths;
import java.util.Optional;
import org.immutables.value.Value;

/** Infer specific buck config */
@BuckStyleValue
public abstract class InferConfig implements ConfigView<BuckConfig> {
  // TODO(arr): change to just "infer" when cxx and java configs are consolidated
  private static final String SECTION = "infer_java";

  private static final String DIST_FIELD = "dist";
  private static final String DEFAULT_DIST_BINARY = "infer";

  @Override
  public abstract BuckConfig getDelegate();

  public static InferConfig of(BuckConfig delegate) {
    return ImmutableInferConfig.of(delegate);
  }

  @Value.Lazy
  public Optional<ToolProvider> getBinary() {
    return getDelegate().getView(ToolConfig.class).getToolProvider(SECTION, "binary");
  }

  /**
   * Depending on the type of dist (plain path vs target) either return a {@link
   * ConstantToolProvider} or {@link InferDistFromTargetProvider} with properly set up parse time
   * deps.
   */
  @Value.Lazy
  public Optional<ToolProvider> getDist() {
    Optional<String> valueOpt = getDelegate().getValue(SECTION, DIST_FIELD);
    if (!valueOpt.isPresent()) {
      return Optional.empty();
    }
    String value = valueOpt.get();

    Optional<UnconfiguredBuildTarget> targetOpt =
        getDelegate().getMaybeUnconfiguredBuildTarget(SECTION, DIST_FIELD);

    ToolProvider toolProvider =
        targetOpt
            .map(this::mkDistProviderFromTarget)
            .orElseGet(() -> this.mkDistProviderFromPath(value));

    return Optional.of(toolProvider);
  }

  @Value.Lazy
  public String getDistBinary() {
    return getDelegate().getValue(SECTION, "dist_binary").orElse(DEFAULT_DIST_BINARY);
  }

  @Value.Lazy
  public Optional<String> getVersion() {
    return getDelegate().getValue(SECTION, "version");
  }

  @Value.Lazy
  public Optional<SourcePath> getConfigFile(TargetConfiguration targetConfiguration) {
    return getDelegate().getSourcePath(SECTION, "config_file", targetConfiguration);
  }

  @Value.Lazy
  public ImmutableList<String> getNullsafeArgs() {
    return getDelegate().getListWithoutComments(SECTION, "nullsafe_args");
  }

  /** Directory with third party signatures for nullsafe. */
  @Value.Lazy
  public Optional<SourcePath> getNullsafeThirdPartySignatures(
      TargetConfiguration targetConfiguration) {
    return getDelegate()
        .getSourcePath(SECTION, "nullsafe_third_party_signatures", targetConfiguration);
  }

  @Value.Lazy
  public Boolean getPrettyPrint() {
    return getDelegate().getBooleanValue(SECTION, "pretty_print", false);
  }

  @Value.Lazy
  public Boolean executeRemotely() {
    return getDelegate().getBooleanValue(SECTION, "execute_remotely", false);
  }

  private ToolProvider mkDistProviderFromTarget(UnconfiguredBuildTarget target) {
    String source = String.format("[%s] %s", SECTION, DIST_FIELD);
    return new InferDistFromTargetProvider(target, getDistBinary(), source);
  }

  private ToolProvider mkDistProviderFromPath(String path) {
    String errorMessage = String.format("%s:%s path not found", SECTION, DIST_FIELD);

    return new ConstantToolProvider(
        new InferDistTool(
            () -> getDelegate().getPathSourcePath(Paths.get(path), errorMessage), getDistBinary()));
  }
}
