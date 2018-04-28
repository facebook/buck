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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.DelegatingTool;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.util.SortedSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** BuildRule for worker_tools. */
public class DefaultWorkerTool extends WriteFile
    implements HasRuntimeDeps, WorkerTool, InitializableFromDisk<UUID> {
  // DefaultWorkerTool writes a file just so that consumers can acquire a dependency on the rule via
  // a SourcePath/NonHashableSourcePathContainer. consumers need such a dependency due to the use of
  // the initialized from disk state.

  @AddToRuleKey private final Tool actualTool;
  private final Tool tool;

  private final int maxWorkers;
  private final boolean isPersistent;
  private final BuildOutputInitializer<UUID> buildOutputInitializer;
  private final Supplier<SortedSet<BuildRule>> depsSupplier;

  protected DefaultWorkerTool(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      Tool tool,
      int maxWorkers,
      boolean isPersistent) {
    super(
        buildTarget,
        projectFilesystem,
        "",
        BuildTargets.getGenPath(projectFilesystem, buildTarget, "%s/worker.file"),
        false);
    this.actualTool = tool;
    this.tool =
        new DelegatingTool(tool) {
          // This is added to the tool so that users get a dependency on this rule.
          @AddToRuleKey SourcePath placeholder = getSourcePathToOutput();
        };
    this.maxWorkers = maxWorkers;
    this.isPersistent = isPersistent;
    this.buildOutputInitializer = new BuildOutputInitializer<>(getBuildTarget(), this);
    this.depsSupplier = BuildableSupport.buildDepsSupplier(this, ruleFinder);
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return depsSupplier.get();
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
  public boolean isCacheable() {
    return false;
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    // TODO(cjhopman): This should probably just be the deps of the underlying tool.
    return getBuildDeps().stream().map(BuildRule::getBuildTarget);
  }

  @Override
  public HashCode getInstanceKey() {
    return HashCode.fromString(buildOutputInitializer.getBuildOutput().toString().replace("-", ""));
  }

  @Override
  public UUID initializeFromDisk() {
    return UUID.randomUUID();
  }

  @Override
  public BuildOutputInitializer<UUID> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }
}
