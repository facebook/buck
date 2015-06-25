/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.collect.ImmutableList;

public class GccCompiler implements Compiler {

  private final Tool tool;

  public GccCompiler(Tool tool) {
    this.tool = tool;
  }

  @Override
  public ImmutableList<String> debugCompilationDirFlags(String debugCompilationDir) {
    return ImmutableList.of();
  }

  @Override
  public ImmutableList<BuildRule> getBuildRules(SourcePathResolver resolver) {
    return tool.getBuildRules(resolver);
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    return tool.getCommandPrefix(resolver);
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    return builder
        .setReflectively("tool", tool)
        .setReflectively("type", getClass().getSimpleName());
  }

}
