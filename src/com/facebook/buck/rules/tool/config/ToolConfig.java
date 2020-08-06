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

package com.facebook.buck.rules.tool.config;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.toolchain.toolprovider.impl.BinaryBuildRuleToolProvider;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Function;

@BuckStyleValue
public abstract class ToolConfig implements ConfigView<BuckConfig> {

  @Override
  public abstract BuckConfig getDelegate();

  public static ToolConfig of(BuckConfig delegate) {
    return ImmutableToolConfig.of(delegate);
  }

  /**
   * @return a {@link Tool} identified by a @{link BuildTarget} or {@link Path} reference by the
   *     given section:field, if set.
   */
  public Optional<ToolProvider> getToolProvider(String section, String field) {
    return getToolProvider(section, field, Paths::get);
  }

  /**
   * @return a {@link Tool} identified by a @{link BuildTarget} or {@link Path} reference by the
   *     given section:field, if set.
   */
  public Optional<ToolProvider> getToolProvider(
      String section, String field, Function<String, Path> valueToPathMapper) {
    Optional<String> value = getDelegate().getValue(section, field);
    if (!value.isPresent()) {
      return Optional.empty();
    }
    Optional<UnconfiguredBuildTarget> target =
        getDelegate().getMaybeUnconfiguredBuildTarget(section, field);
    if (target.isPresent()) {
      return Optional.of(
          new BinaryBuildRuleToolProvider(target.get(), String.format("[%s] %s", section, field)));
    } else {
      return getPrebuiltTool(section, field, valueToPathMapper).map(ConstantToolProvider::new);
    }
  }

  /**
   * @return a {@link Tool} identified by a {@link Path} reference by the given section:field, if
   *     set. This does not allow the tool to be provided by a @{link BuildTarget}.
   */
  public Optional<Tool> getPrebuiltTool(
      String section, String field, Function<String, Path> valueToPathMapper) {
    return getDelegate()
        .getValue(section, field)
        .map(
            value ->
                new HashedFileTool(
                    () ->
                        getDelegate()
                            .getPathSourcePath(
                                valueToPathMapper.apply(value),
                                String.format("Overridden %s:%s path not found", section, field))));
  }

  public Optional<Tool> getTool(
      String section,
      String field,
      BuildRuleResolver resolver,
      TargetConfiguration targetConfiguration) {
    Optional<ToolProvider> provider = getToolProvider(section, field);
    return provider.map(toolProvider -> toolProvider.resolve(resolver, targetConfiguration));
  }

  public Tool getRequiredTool(
      String section,
      String field,
      BuildRuleResolver resolver,
      TargetConfiguration targetConfiguration) {
    Optional<Tool> path = getTool(section, field, resolver, targetConfiguration);
    return getDelegate().getOrThrow(section, field, path);
  }
}
