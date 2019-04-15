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

package com.facebook.buck.shell;

import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.shell.FakeWorkerBuilder.FakeWorkerToolRule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.immutables.value.Value;

public class FakeWorkerBuilder
    extends AbstractNodeBuilder<
        FakeWorkerDescriptionArg.Builder,
        FakeWorkerDescriptionArg,
        FakeWorkerBuilder.FakeWorkerDescription,
        FakeWorkerToolRule> {

  public FakeWorkerBuilder(BuildTarget target) {
    super(new FakeWorkerDescription(), target);
  }

  static class FakeWorkerToolRule extends NoopBuildRuleWithDeclaredAndExtraDeps
      implements ProvidesWorkerTool {
    private final FakeWorkerTool fakeWorkerTool = new FakeWorkerTool();

    public FakeWorkerToolRule(
        BuildTarget buildTarget, ProjectFilesystem projectFilesystem, BuildRuleParams params) {
      super(buildTarget, projectFilesystem, params);
    }

    @Override
    public WorkerTool getWorkerTool() {
      return fakeWorkerTool;
    }
  }

  static class FakeWorkerTool implements WorkerTool {

    private final Tool tool = new FakeTool();
    private final HashCode hashCode = HashCode.fromString("0123456789abcdef");

    @Override
    public Tool getTool() {
      return tool;
    }

    @Override
    public Path getTempDir(ProjectFilesystem filesystem) {
      return Paths.get("");
    }

    @Override
    public int getMaxWorkers() {
      return 0;
    }

    @Override
    public boolean isPersistent() {
      return false;
    }

    @Override
    public HashCode getInstanceKey() {
      return hashCode;
    }
  }

  private static class FakeTool implements Tool {
    @Override
    public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
      return ImmutableList.of();
    }

    @Override
    public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
      return ImmutableMap.of();
    }
  }

  public static class FakeWorkerDescription
      implements DescriptionWithTargetGraph<FakeWorkerDescriptionArg> {
    @Override
    public Class<FakeWorkerDescriptionArg> getConstructorArgType() {
      return FakeWorkerDescriptionArg.class;
    }

    @Override
    public BuildRule createBuildRule(
        BuildRuleCreationContextWithTargetGraph context,
        BuildTarget buildTarget,
        BuildRuleParams params,
        FakeWorkerDescriptionArg args) {
      return new FakeWorkerToolRule(buildTarget, context.getProjectFilesystem(), params);
    }

    @BuckStyleImmutable
    @Value.Immutable
    interface AbstractFakeWorkerDescriptionArg extends CommonDescriptionArg {}
  }
}
