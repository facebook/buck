/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.rules.macros;

import com.facebook.buck.model.MacroException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.shell.WorkerTool;
import com.google.common.collect.ImmutableList;

public class WorkerMacroExpander extends ExecutableMacroExpander {

  @Override
  protected Tool getTool(BuildRule rule) throws MacroException {
    if (!(rule instanceof WorkerTool)) {
      throw new MacroException(
          String.format(
              "%s used in worker macro does not correspond to a worker_tool rule",
              rule.getBuildTarget()));
    }
    return ((WorkerTool) rule).getTool();
  }

  @Override
  protected ImmutableList<BuildRule> extractBuildTimeDeps(
      BuildRuleResolver resolver, BuildRule rule) throws MacroException {
    ImmutableList.Builder<BuildRule> deps = ImmutableList.builder();
    deps.add(rule);
    deps.addAll(getTool(rule).getDeps(new SourcePathRuleFinder(resolver)));
    return deps.build();
  }

  @Override
  public String expand(SourcePathResolver resolver, BuildRule rule) throws MacroException {
    return "";
  }
}
