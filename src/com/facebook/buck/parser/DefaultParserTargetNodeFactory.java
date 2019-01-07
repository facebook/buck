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

package com.facebook.buck.parser;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildFileTree;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypes;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.json.JsonObjectHashing;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.parser.function.BuckPyFunction;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.ParamInfoException;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.rules.visibility.VisibilityPattern;
import com.facebook.buck.rules.visibility.VisibilityPatterns;
import com.google.common.base.Preconditions;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Creates {@link TargetNode} instances from raw data coming in form the {@link
 * ProjectBuildFileParser}.
 */
public class DefaultParserTargetNodeFactory
    implements ParserTargetNodeFactory<Map<String, Object>> {

  private final KnownRuleTypesProvider knownRuleTypesProvider;
  private final ConstructorArgMarshaller marshaller;
  private final PackageBoundaryChecker packageBoundaryChecker;
  private final TargetNodeListener<TargetNode<?>> nodeListener;
  private final TargetNodeFactory targetNodeFactory;
  private final RuleKeyConfiguration ruleKeyConfiguration;
  private final BuiltTargetVerifier builtTargetVerifier;

  private DefaultParserTargetNodeFactory(
      KnownRuleTypesProvider knownRuleTypesProvider,
      ConstructorArgMarshaller marshaller,
      PackageBoundaryChecker packageBoundaryChecker,
      TargetNodeListener<TargetNode<?>> nodeListener,
      TargetNodeFactory targetNodeFactory,
      RuleKeyConfiguration ruleKeyConfiguration,
      BuiltTargetVerifier builtTargetVerifier) {
    this.knownRuleTypesProvider = knownRuleTypesProvider;
    this.marshaller = marshaller;
    this.packageBoundaryChecker = packageBoundaryChecker;
    this.nodeListener = nodeListener;
    this.targetNodeFactory = targetNodeFactory;
    this.ruleKeyConfiguration = ruleKeyConfiguration;
    this.builtTargetVerifier = builtTargetVerifier;
  }

  public static ParserTargetNodeFactory<Map<String, Object>> createForParser(
      KnownRuleTypesProvider knownRuleTypesProvider,
      ConstructorArgMarshaller marshaller,
      LoadingCache<Cell, BuildFileTree> buildFileTrees,
      TargetNodeListener<TargetNode<?>> nodeListener,
      TargetNodeFactory targetNodeFactory,
      RuleKeyConfiguration ruleKeyConfiguration) {
    return new DefaultParserTargetNodeFactory(
        knownRuleTypesProvider,
        marshaller,
        new ThrowingPackageBoundaryChecker(buildFileTrees),
        nodeListener,
        targetNodeFactory,
        ruleKeyConfiguration,
        new BuiltTargetVerifier());
  }

  public static ParserTargetNodeFactory<Map<String, Object>> createForDistributedBuild(
      KnownRuleTypesProvider knownRuleTypesProvider,
      ConstructorArgMarshaller marshaller,
      TargetNodeFactory targetNodeFactory,
      RuleKeyConfiguration ruleKeyConfiguration) {
    return new DefaultParserTargetNodeFactory(
        knownRuleTypesProvider,
        marshaller,
        new NoopPackageBoundaryChecker(),
        (buildFile, node) -> {
          // No-op.
        },
        targetNodeFactory,
        ruleKeyConfiguration,
        new BuiltTargetVerifier());
  }

  @Override
  public TargetNode<?> createTargetNode(
      Cell cell,
      Path buildFile,
      BuildTarget target,
      Map<String, Object> rawNode,
      Function<PerfEventId, SimplePerfEvent.Scope> perfEventScope) {
    KnownRuleTypes knownRuleTypes = knownRuleTypesProvider.get(cell);
    RuleType buildRuleType = parseBuildRuleTypeFromRawRule(knownRuleTypes, rawNode);

    // Because of the way that the parser works, we know this can never return null.
    BaseDescription<?> description = knownRuleTypes.getDescription(buildRuleType);

    builtTargetVerifier.verifyBuildTarget(
        cell, buildRuleType, buildFile, target, description, rawNode);

    Preconditions.checkState(cell.equals(cell.getCell(target)));
    Object constructorArg;
    try {
      ImmutableSet.Builder<BuildTarget> declaredDeps = ImmutableSet.builder();
      ImmutableSet<VisibilityPattern> visibilityPatterns;
      ImmutableSet<VisibilityPattern> withinViewPatterns;
      try (SimplePerfEvent.Scope scope =
          perfEventScope.apply(PerfEventId.of("MarshalledConstructorArg"))) {
        constructorArg =
            marshaller.populate(
                cell.getCellPathResolver(),
                cell.getFilesystem(),
                target,
                description.getConstructorArgType(),
                declaredDeps,
                rawNode);
        visibilityPatterns =
            VisibilityPatterns.createFromStringList(
                cell.getCellPathResolver(), "visibility", rawNode.get("visibility"), target);
        withinViewPatterns =
            VisibilityPatterns.createFromStringList(
                cell.getCellPathResolver(), "within_view", rawNode.get("within_view"), target);
      }

      return createTargetNodeFromObject(
          cell,
          buildFile,
          target,
          description,
          constructorArg,
          rawNode,
          declaredDeps.build(),
          visibilityPatterns,
          withinViewPatterns,
          perfEventScope);
    } catch (NoSuchBuildTargetException e) {
      throw new HumanReadableException(e);
    } catch (ParamInfoException e) {
      throw new HumanReadableException(e, "%s: %s", target, e.getMessage());
    } catch (IOException e) {
      throw new HumanReadableException(e.getMessage(), e);
    }
  }

  private TargetNode<?> createTargetNodeFromObject(
      Cell cell,
      Path buildFile,
      BuildTarget target,
      BaseDescription<?> description,
      Object constructorArg,
      Map<String, Object> rawNode,
      ImmutableSet<BuildTarget> declaredDeps,
      ImmutableSet<VisibilityPattern> visibilityPatterns,
      ImmutableSet<VisibilityPattern> withinViewPatterns,
      Function<PerfEventId, SimplePerfEvent.Scope> perfEventScope)
      throws IOException {
    try (SimplePerfEvent.Scope scope = perfEventScope.apply(PerfEventId.of("CreatedTargetNode"))) {
      TargetNode<?> node =
          targetNodeFactory.createFromObject(
              hashRawNode(rawNode),
              description,
              constructorArg,
              cell.getFilesystem(),
              target,
              declaredDeps,
              visibilityPatterns,
              withinViewPatterns,
              cell.getCellPathResolver());
      packageBoundaryChecker.enforceBuckPackageBoundaries(cell, target, node.getInputs());
      nodeListener.onCreate(buildFile, node);
      return node;
    }
  }

  private HashCode hashRawNode(Map<String, Object> rawNode) {
    Hasher hasher = Hashing.sha1().newHasher();
    hasher.putString(ruleKeyConfiguration.getCoreKey(), UTF_8);
    JsonObjectHashing.hashJsonObject(hasher, rawNode);
    return hasher.hash();
  }

  private static RuleType parseBuildRuleTypeFromRawRule(
      KnownRuleTypes knownRuleTypes, Map<String, Object> map) {
    String type = (String) Objects.requireNonNull(map.get(BuckPyFunction.TYPE_PROPERTY_NAME));
    return knownRuleTypes.getRuleType(type);
  }
}
