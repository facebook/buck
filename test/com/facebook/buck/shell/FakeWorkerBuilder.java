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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Function;
import org.immutables.value.Value;

public class FakeWorkerBuilder
    extends AbstractNodeBuilder<
        FakeWorkerDescriptionArg.Builder, FakeWorkerDescriptionArg,
        FakeWorkerBuilder.FakeWorkerDescription, FakeWorkerBuilder.FakeWorkerTool> {

  public FakeWorkerBuilder(BuildTarget target) {
    this(target, FakeWorkerTool::new);
  }

  public FakeWorkerBuilder(BuildTarget target, Function<BuildRuleParams, BuildRule> create) {
    super(new FakeWorkerDescription(create), target);
  }

  public static class FakeWorkerTool extends NoopBuildRule implements WorkerTool {
    private final Tool tool = new FakeTool();
    private final HashCode hashCode = HashCode.fromString("0123456789abcdef");

    public FakeWorkerTool(BuildRuleParams params) {
      super(params);
    }

    @Override
    public Tool getTool() {
      return tool;
    }

    @Override
    public String getArgs(SourcePathResolver pathResolver) {
      return "";
    }

    @Override
    public Path getTempDir() {
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
    public void appendToRuleKey(RuleKeyObjectSink sink) {
      // Do nothing
    }

    @Override
    public ImmutableCollection<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
      return ImmutableList.of();
    }

    @Override
    public ImmutableCollection<SourcePath> getInputs() {
      return ImmutableList.of();
    }

    @Override
    public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
      return ImmutableList.of();
    }

    @Override
    public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
      return ImmutableMap.of();
    }
  }

  public static class FakeWorkerDescription implements Description<FakeWorkerDescriptionArg> {
    private final Function<BuildRuleParams, BuildRule> create;

    private FakeWorkerDescription(Function<BuildRuleParams, BuildRule> create) {
      this.create = create;
    }

    @Override
    public Class<FakeWorkerDescriptionArg> getConstructorArgType() {
      return FakeWorkerDescriptionArg.class;
    }

    @Override
    public BuildRule createBuildRule(
        TargetGraph targetGraph,
        BuildRuleParams params,
        BuildRuleResolver resolver,
        CellPathResolver cellRoots,
        FakeWorkerDescriptionArg args)
        throws NoSuchBuildTargetException {
      return create.apply(params);
    }

    @BuckStyleImmutable
    @Value.Immutable
    interface AbstractFakeWorkerDescriptionArg extends CommonDescriptionArg {}
  }
}
