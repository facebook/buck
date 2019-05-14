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

import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.util.graph.AcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.core.util.graph.AcyclicDepthFirstPostOrderTraversal.CycleException;
import com.facebook.buck.core.util.graph.GraphTraversable;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Consumer;

/** Serializer for ActionGraph. */
public class ActionGraphSerializer {

  private static final Logger LOG = Logger.get(ActionGraphSerializer.class);

  private final BuildRuleResolver buildRuleResolver;
  private final ImmutableSet<ActionGraphNode> startNodes;
  private final Path outputPath;

  public ActionGraphSerializer(
      BuildRuleResolver buildRuleResolver,
      ImmutableSet<BuildTarget> buildTargets,
      Path outputPath) {
    this.buildRuleResolver = buildRuleResolver;
    this.startNodes = toStartNodes(buildTargets);
    this.outputPath = outputPath;
  }

  private ImmutableSet<ActionGraphNode> toStartNodes(Collection<BuildTarget> buildTargets) {
    return buildTargets.stream()
        .map(buildRuleResolver::getRule)
        .map(this::toActionGraphNode)
        .collect(ImmutableSet.toImmutableSet());
  }

  private ActionGraphNode toActionGraphNode(BuildRule rule) {
    return new ImmutableActionGraphNode(
        rule.getBuildTarget(), rule.getBuildDeps(), getRuntimeDeps(rule));
  }

  private ImmutableSet<BuildRule> getRuntimeDeps(BuildRule rule) {
    if (rule instanceof HasRuntimeDeps) {
      HasRuntimeDeps hasRuntimeDeps = (HasRuntimeDeps) rule;
      return hasRuntimeDeps
          .getRuntimeDeps(buildRuleResolver)
          .map(buildRuleResolver::getRule)
          .collect(ImmutableSet.toImmutableSet());
    }
    return ImmutableSet.of();
  }

  /** Serializes action graph into given {@code outputPath} */
  public void serialize() {
    serialize(this::processNodes);
  }

  @VisibleForTesting
  void serialize(Consumer<Iterable<ActionGraphNode>> consumer) {
    AcyclicDepthFirstPostOrderTraversal<ActionGraphNode> traversal =
        new AcyclicDepthFirstPostOrderTraversal<>(getGraphTraversable());
    Iterable<ActionGraphNode> traverse;
    try {
      traverse = traversal.traverse(startNodes);
    } catch (CycleException e) {
      throw new BuckUncheckedExecutionException(e);
    }

    consumer.accept(traverse);
  }

  private void processNodes(Iterable<ActionGraphNode> nodes) {
    try (BufferedWriter writer = Files.newBufferedWriter(outputPath)) {
      for (ActionGraphNode actionGraphData : nodes) {
        convertToJson(actionGraphData).ifPresent(value -> writeLine(writer, value));
      }
    } catch (IOException e) {
      throw new BuckUncheckedExecutionException(e);
    }
  }

  private GraphTraversable<ActionGraphNode> getGraphTraversable() {
    return actionGraphNode ->
        Iterables.transform(
                Iterables.concat(actionGraphNode.getBuildDeps(), actionGraphNode.getRuntimeDeps()),
                this::toActionGraphNode)
            .iterator();
  }

  private Optional<String> convertToJson(ActionGraphNode actionGraphNode) {
    BuildTarget buildTarget = actionGraphNode.getBuildTarget();
    ActionGraphData actionGraphData =
        new ImmutableActionGraphData(
            toTargetId(buildTarget),
            buildRuleResolver.getRule(buildTarget).getType(),
            convertToStringSet(actionGraphNode.getBuildDeps()),
            convertToStringSet(actionGraphNode.getRuntimeDeps()));

    try {
      return Optional.of(ObjectMappers.WRITER.writeValueAsString(actionGraphData));
    } catch (IOException e) {
      LOG.error(e, "Can't convert %s to JSON", actionGraphData);
    }
    return Optional.empty();
  }

  private void writeLine(BufferedWriter writer, String line) {
    try {
      writer.write(line);
      writer.newLine();
    } catch (IOException e) {
      LOG.error(e, "I/O exception during writing the line: '%s' to the file: %s", line, outputPath);
    }
  }

  private ImmutableSet<String> convertToStringSet(ImmutableSet<BuildRule> rules) {
    return rules.stream()
        .map(BuildRule::getBuildTarget)
        .map(this::toTargetId)
        .collect(ImmutableSet.toImmutableSet());
  }

  private String toTargetId(BuildTarget buildTarget) {
    return buildTarget.getFullyQualifiedName();
  }

  /** Data object that is used to traverse an action graph */
  @BuckStyleValue
  interface ActionGraphNode {

    BuildTarget getBuildTarget();

    ImmutableSet<BuildRule> getBuildDeps();

    ImmutableSet<BuildRule> getRuntimeDeps();
  }

  /** Data object that is used to serialize action graph information into a file */
  @BuckStyleValue
  @JsonSerialize
  @JsonDeserialize
  public interface ActionGraphData {

    String getTargetId();

    String getRuleType();

    ImmutableCollection<String> getBuildDeps();

    ImmutableCollection<String> getRuntimeDeps();
  }
}
