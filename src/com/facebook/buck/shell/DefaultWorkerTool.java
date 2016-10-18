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

package com.facebook.buck.shell;

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.Tool;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Map;

public class DefaultWorkerTool extends NoopBuildRule implements HasRuntimeDeps, WorkerTool {

  private final BinaryBuildRule exe;
  private final String args;
  private final ImmutableMap<String, String> env;
  private final int maxWorkers;

  protected DefaultWorkerTool(
      BuildRuleParams ruleParams,
      SourcePathResolver resolver,
      BinaryBuildRule exe,
      String args,
      ImmutableMap<String, String> env,
      int maxWorkers) {
    super(ruleParams, resolver);
    this.exe = exe;
    this.args = args;
    this.env = env;
    this.maxWorkers = maxWorkers;
  }

  @Override
  public Tool getTool() {
    Tool baseTool = this.exe.getExecutableCommand();
    CommandTool.Builder builder = new CommandTool.Builder(baseTool)
        .addInputs(
            FluentIterable.from(this.getDeps())
                .transform(SourcePaths.getToBuildTargetSourcePath())
                .toList());
    for (Map.Entry<String, String> e : env.entrySet()) {
      builder.addEnv(e.getKey(), e.getValue());
    }
    return builder.build();
  }

  @Override
  public String getArgs() {
    return this.args;
  }

  @Override
  public Path getTempDir() {
    return BuildTargets.getScratchPath(
        getProjectFilesystem(), getBuildTarget(), "%s__worker");
  }

  @Override
  public int getMaxWorkers() {
    return maxWorkers;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getRuntimeDeps() {
    return getDeps();
  }
}
