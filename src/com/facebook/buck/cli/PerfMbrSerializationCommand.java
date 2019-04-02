/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.cli;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.Serializer;
import com.facebook.buck.util.CommandLineException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import java.util.ArrayList;
import java.util.List;
import org.kohsuke.args4j.Argument;

/** Tests performance of serializing MBR rules. */
public class PerfMbrSerializationCommand
    extends AbstractPerfCommand<PerfMbrSerializationCommand.PreparedState> {
  private static final Logger LOG = Logger.get(PerfMbrSerializationCommand.class);

  @Argument private List<String> arguments = new ArrayList<>();

  @Override
  protected String getComputationName() {
    return "serializing mbr rules";
  }

  @Override
  PreparedState prepareTest(CommandRunnerParams params) throws Exception {
    // Create a TargetGraph that is composed of the transitive closure of all of the dependent
    // BuildRules for the specified BuildTargetPaths.
    ImmutableSet<BuildTarget> targets = convertArgumentsToBuildTargets(params, arguments);

    if (targets.isEmpty()) {
      throw new CommandLineException("must specify at least one build target");
    }

    TargetGraph targetGraph = getTargetGraph(params, targets);

    // Get a fresh action graph since we might unsafely run init from disks...
    // Also, we don't measure speed of this part.
    ActionGraphBuilder graphBuilder =
        params.getActionGraphProvider().getFreshActionGraph(targetGraph).getActionGraphBuilder();

    ImmutableList<BuildRule> rulesInGraph = getRulesInGraph(graphBuilder, targets);
    return new PreparedState(rulesInGraph, graphBuilder);
  }

  @Override
  void runPerfTest(CommandRunnerParams params, PreparedState state) throws Exception {
    Cell rootCell = params.getCell();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(state.graphBuilder);
    Serializer serializer =
        new Serializer(
            ruleFinder,
            rootCell.getCellPathResolver(),
            (instance, data, children) -> Hashing.md5().newHasher().putBytes(data).hash());
    for (BuildRule buildRule : state.rulesInGraph) {
      if (buildRule instanceof ModernBuildRule) {
        try {
          serializer.serialize(((ModernBuildRule<?>) buildRule).getBuildable());
        } catch (Exception e) {
          // Ignore. Hopefully this is just a serialization failure. In normal builds, those rules
          // just fall back to non-RE.
          LOG.verbose("Ignoring exception %s", e.getMessage());
        }
      }
    }
  }

  @Override
  public String getShortDescription() {
    return "test performance of serializing the build graph (the mbr part at least)";
  }

  /** Preapared state with the action graph created. */
  static class PreparedState {
    private final ImmutableList<BuildRule> rulesInGraph;
    private final BuildRuleResolver graphBuilder;

    public PreparedState(ImmutableList<BuildRule> rulesInGraph, BuildRuleResolver graphBuilder) {
      this.rulesInGraph = rulesInGraph;
      this.graphBuilder = graphBuilder;
    }
  }
}
