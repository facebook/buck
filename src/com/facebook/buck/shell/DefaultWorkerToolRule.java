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
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.BuildOutputInitializer;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.attr.InitializableFromDisk;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.DelegatingTool;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.util.SortedSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** BuildRule for worker_tools. */
public class DefaultWorkerToolRule extends WriteFile
    implements HasRuntimeDeps, InitializableFromDisk<UUID>, ProvidesWorkerTool {
  // DefaultWorkerToolRule writes a file just so that consumers can acquire a dependency on the rule
  // via a SourcePath/NonHashableSourcePathContainer. consumers need such a dependency due to the
  // use of the initialized from disk state.

  @AddToRuleKey private final Tool actualTool;
  private final BuildOutputInitializer<UUID> buildOutputInitializer;
  private final Supplier<SortedSet<BuildRule>> depsSupplier;
  private final DefaultWorkerTool workerTool;

  protected DefaultWorkerToolRule(
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
        BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s/worker.file"),
        false);
    this.actualTool = tool;
    this.workerTool =
        new DefaultWorkerTool(
            new DefaultWorkerToolDelegatingTool(tool, getSourcePathToOutput()),
            maxWorkers,
            isPersistent,
            buildTarget,
            generateNewUUID());
    this.buildOutputInitializer = new BuildOutputInitializer<>(buildTarget, this);
    this.depsSupplier = BuildableSupport.buildDepsSupplier(this, ruleFinder);
  }

  /** Impl of DelegatingTool that stores dependency to rule's source path */
  static class DefaultWorkerToolDelegatingTool extends DelegatingTool {

    // This is added to the tool so that users get a dependency on this rule.
    @AddToRuleKey private final SourcePath placeholder;

    public DefaultWorkerToolDelegatingTool(Tool tool, SourcePath placeholder) {
      super(tool);
      this.placeholder = placeholder;
    }
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return depsSupplier.get();
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
  public UUID initializeFromDisk(SourcePathResolver pathResolver) {
    UUID uuid = generateNewUUID();
    workerTool.updateInstanceKey(uuid);
    return uuid;
  }

  private static UUID generateNewUUID() {
    return UUID.randomUUID();
  }

  @Override
  public BuildOutputInitializer<UUID> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  @Override
  public WorkerTool getWorkerTool() {
    return workerTool;
  }

  /** Default implementation of WorkerTool interface */
  static class DefaultWorkerTool implements WorkerTool {

    @AddToRuleKey private final Tool tool;
    @AddToRuleKey private final boolean isPersistent;
    @AddToRuleKey private final BuildTarget buildTarget;

    // Important : Do not add this field into RuleKey
    private final int maxWorkers;

    // Important : Do not add this field into RuleKey
    private String instanceKey;

    DefaultWorkerTool(
        Tool tool, int maxWorkers, boolean isPersistent, BuildTarget buildTarget, UUID uuid) {
      this.tool = tool;
      this.maxWorkers = maxWorkers;
      this.isPersistent = isPersistent;
      this.buildTarget = buildTarget;
      this.instanceKey = calculateInstanceKey(uuid);
    }

    public void updateInstanceKey(UUID uuid) {
      this.instanceKey = calculateInstanceKey(uuid);
    }

    private String calculateInstanceKey(UUID uuid) {
      return uuid.toString().replace("-", "");
    }

    @Override
    public Tool getTool() {
      return tool;
    }

    @Override
    public Path getTempDir(ProjectFilesystem filesystem) {
      return BuildTargetPaths.getScratchPath(filesystem, buildTarget, "%s__worker");
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
    public HashCode getInstanceKey() {
      return HashCode.fromString(instanceKey);
    }
  }
}
