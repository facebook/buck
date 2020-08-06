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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

/**
 * A {@link ToolProvider} which provides {@link InferDistTool} referenced by a given {@link
 * BuildTarget}.
 */
public class InferDistFromTargetProvider implements ToolProvider {

  private final UnconfiguredBuildTarget target;
  private final String binary;
  private final String source;

  public InferDistFromTargetProvider(UnconfiguredBuildTarget target, String binary, String source) {
    this.target = target;
    this.binary = binary;
    this.source = source;
  }

  @Override
  public Tool resolve(BuildRuleResolver resolver, TargetConfiguration targetConfiguration) {
    Optional<BuildRule> rule = resolver.getRuleOptional(target.configure(targetConfiguration));
    if (!rule.isPresent()) {
      throw new HumanReadableException("%s: no rule found for %s", source, target);
    }

    return new InferDistTool(rule.get().getSourcePathToOutput(), binary);
  }

  @Override
  public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {
    return ImmutableList.of(target.configure(targetConfiguration));
  }
}
