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

package com.facebook.buck.rules.tool.config;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.ConfigView;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.toolchain.toolprovider.impl.BinaryBuildRuleToolProvider;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.rules.BuildRuleResolver;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable(builder = false, copy = false)
@BuckStyleImmutable
abstract class AbstractToolConfig implements ConfigView<BuckConfig> {

  /**
   * @return a {@link Tool} identified by a @{link BuildTarget} or {@link Path} reference by the
   *     given section:field, if set.
   */
  public Optional<ToolProvider> getToolProvider(String section, String field) {
    Optional<String> value = getDelegate().getValue(section, field);
    if (!value.isPresent()) {
      return Optional.empty();
    }
    Optional<BuildTarget> target = getDelegate().getMaybeBuildTarget(section, field);
    if (target.isPresent()) {
      return Optional.of(
          new BinaryBuildRuleToolProvider(target.get(), String.format("[%s] %s", section, field)));
    } else {
      return Optional.of(
          new ConstantToolProvider(
              new HashedFileTool(() -> getDelegate().getPathSourcePath(Paths.get(value.get())))));
    }
  }

  public Optional<Tool> getTool(String section, String field, BuildRuleResolver resolver) {
    Optional<ToolProvider> provider = getToolProvider(section, field);
    if (!provider.isPresent()) {
      return Optional.empty();
    }
    return Optional.of(provider.get().resolve(resolver));
  }

  public Tool getRequiredTool(String section, String field, BuildRuleResolver resolver) {
    Optional<Tool> path = getTool(section, field, resolver);
    return getDelegate().getOrThrow(section, field, path);
  }
}
