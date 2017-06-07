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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildInfo;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public class DefaultWorkerTool extends NoopBuildRule
    implements HasRuntimeDeps, WorkerTool, InitializableFromDisk<DefaultWorkerTool.Data> {

  @AddToRuleKey private final ImmutableList<Arg> args;

  @AddToRuleKey
  @SuppressWarnings("PMD.UnusedPrivateField")
  private final ImmutableMap<String, Arg> env;

  private final BinaryBuildRule exe;
  private final int maxWorkers;
  private final boolean isPersistent;
  private final BuildOutputInitializer<Data> buildOutputInitializer;
  private final Tool tool;

  protected DefaultWorkerTool(
      BuildRuleParams ruleParams,
      BinaryBuildRule exe,
      ImmutableList<Arg> args,
      ImmutableMap<String, Arg> env,
      int maxWorkers,
      boolean isPersistent) {
    super(ruleParams);
    this.exe = exe;
    this.args = args;
    this.env = env;
    this.maxWorkers = maxWorkers;
    this.isPersistent = isPersistent;
    this.buildOutputInitializer = new BuildOutputInitializer<>(getBuildTarget(), this);
    Tool baseTool = this.exe.getExecutableCommand();
    CommandTool.Builder builder =
        new CommandTool.Builder(baseTool)
            .addInputs(
                this.getBuildDeps()
                    .stream()
                    .map(BuildRule::getSourcePathToOutput)
                    .collect(MoreCollectors.toImmutableList()));
    for (Map.Entry<String, Arg> e : env.entrySet()) {
      builder.addEnv(e.getKey(), e.getValue());
    }
    tool = builder.build();
  }

  @Override
  public Tool getTool() {
    return tool;
  }

  @Override
  public String getArgs(SourcePathResolver pathResolver) {
    ImmutableList.Builder<String> command = ImmutableList.builder();
    for (Arg arg : args) {
      arg.appendToCommandLine(command, pathResolver);
    }
    return Joiner.on(' ').join(command.build());
  }

  @Override
  public Path getTempDir() {
    return BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s__worker");
  }

  @Override
  public int getMaxWorkers() {
    return maxWorkers;
  }

  @Override
  public boolean isPersistent() {
    return isPersistent;
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps() {
    return getBuildDeps().stream().map(BuildRule::getBuildTarget);
  }

  @Override
  public HashCode getInstanceKey() {
    return buildOutputInitializer.getBuildOutput().getRuleKey().getHashCode();
  }

  @Override
  public Data initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) throws IOException {
    Optional<RuleKey> ruleKey = onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.RULE_KEY);
    if (!ruleKey.isPresent()) {
      throw new IllegalStateException(
          String.format(
              "Should not be initializing %s from disk if the rule key is not written.",
              getBuildTarget()));
    }

    return new Data(ruleKey.get());
  }

  @Override
  public BuildOutputInitializer<Data> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  public static class Data {
    private final RuleKey ruleKey;

    public Data(RuleKey ruleKey) {
      this.ruleKey = ruleKey;
    }

    public RuleKey getRuleKey() {
      return ruleKey;
    }
  }
}
