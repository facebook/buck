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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

public class DefaultWorkerTool extends NoopBuildRuleWithDeclaredAndExtraDeps
    implements HasRuntimeDeps, WorkerTool, InitializableFromDisk<UUID> {

  @AddToRuleKey private final Tool tool;

  private final int maxWorkers;
  private final boolean isPersistent;
  private final BuildOutputInitializer<UUID> buildOutputInitializer;

  protected DefaultWorkerTool(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams ruleParams,
      Tool tool,
      int maxWorkers,
      boolean isPersistent) {
    super(buildTarget, projectFilesystem, ruleParams);
    this.tool = tool;
    this.maxWorkers = maxWorkers;
    this.isPersistent = isPersistent;
    this.buildOutputInitializer = new BuildOutputInitializer<>(getBuildTarget(), this);
  }

  @Override
  public Tool getTool() {
    return tool;
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
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    return getBuildDeps().stream().map(BuildRule::getBuildTarget);
  }

  @Override
  public HashCode getInstanceKey() {
    return HashCode.fromString(buildOutputInitializer.getBuildOutput().toString().replace("-", ""));
  }

  @Override
  public UUID initializeFromDisk() throws IOException {
    return UUID.randomUUID();
  }

  @Override
  public BuildOutputInitializer<UUID> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }
}
