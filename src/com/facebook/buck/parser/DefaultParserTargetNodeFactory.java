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

import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.json.JsonObjectHashing;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuckPyFunction;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodeFactory;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.ParamInfoException;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.rules.visibility.VisibilityPattern;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Creates {@link TargetNode} instances from raw data coming in form the {@link
 * ProjectBuildFileParser}.
 */
public class DefaultParserTargetNodeFactory implements ParserTargetNodeFactory<TargetNode<?, ?>> {

  private static final Logger LOG = Logger.get(DefaultParserTargetNodeFactory.class);

  private final ConstructorArgMarshaller marshaller;
  private final Optional<LoadingCache<Cell, BuildFileTree>> buildFileTrees;
  private final TargetNodeListener<TargetNode<?, ?>> nodeListener;
  private final TargetNodeFactory targetNodeFactory;
  private final RuleKeyConfiguration ruleKeyConfiguration;

  private DefaultParserTargetNodeFactory(
      ConstructorArgMarshaller marshaller,
      Optional<LoadingCache<Cell, BuildFileTree>> buildFileTrees,
      TargetNodeListener<TargetNode<?, ?>> nodeListener,
      TargetNodeFactory targetNodeFactory,
      RuleKeyConfiguration ruleKeyConfiguration) {
    this.marshaller = marshaller;
    this.buildFileTrees = buildFileTrees;
    this.nodeListener = nodeListener;
    this.targetNodeFactory = targetNodeFactory;
    this.ruleKeyConfiguration = ruleKeyConfiguration;
  }

  public static ParserTargetNodeFactory<TargetNode<?, ?>> createForParser(
      ConstructorArgMarshaller marshaller,
      LoadingCache<Cell, BuildFileTree> buildFileTrees,
      TargetNodeListener<TargetNode<?, ?>> nodeListener,
      TargetNodeFactory targetNodeFactory,
      RuleKeyConfiguration ruleKeyConfiguration) {
    return new DefaultParserTargetNodeFactory(
        marshaller,
        Optional.of(buildFileTrees),
        nodeListener,
        targetNodeFactory,
        ruleKeyConfiguration);
  }

  public static ParserTargetNodeFactory<TargetNode<?, ?>> createForDistributedBuild(
      ConstructorArgMarshaller marshaller,
      TargetNodeFactory targetNodeFactory,
      RuleKeyConfiguration ruleKeyConfiguration) {
    return new DefaultParserTargetNodeFactory(
        marshaller,
        Optional.empty(),
        (buildFile, node) -> {
          // No-op.
        },
        targetNodeFactory,
        ruleKeyConfiguration);
  }

  @Override
  public TargetNode<?, ?> createTargetNode(
      Cell cell,
      KnownBuildRuleTypes knownBuildRuleTypes,
      Path buildFile,
      BuildTarget target,
      Map<String, Object> rawNode,
      Function<PerfEventId, SimplePerfEvent.Scope> perfEventScope) {
    BuildRuleType buildRuleType = parseBuildRuleTypeFromRawRule(knownBuildRuleTypes, rawNode);

    // Because of the way that the parser works, we know this can never return null.
    Description<?> description = knownBuildRuleTypes.getDescription(buildRuleType);

    UnflavoredBuildTarget unflavoredBuildTarget = target.getUnflavoredBuildTarget();
    if (target.isFlavored()) {
      if (description instanceof Flavored) {
        if (!((Flavored) description).hasFlavors(ImmutableSet.copyOf(target.getFlavors()))) {
          throw UnexpectedFlavorException.createWithSuggestions(
              (Flavored) description, cell, target);
        }
      } else {
        LOG.warn(
            "Target %s (type %s) must implement the Flavored interface "
                + "before we can check if it supports flavors: %s",
            unflavoredBuildTarget, buildRuleType, target.getFlavors());
        ImmutableSet<String> invalidFlavorsStr =
            target
                .getFlavors()
                .stream()
                .map(Flavor::toString)
                .collect(ImmutableSet.toImmutableSet());
        String invalidFlavorsDisplayStr = String.join(", ", invalidFlavorsStr);
        throw new HumanReadableException(
            "The following flavor(s) are not supported on target %s:\n"
                + "%s.\n\n"
                + "Please try to remove them when referencing this target.",
            unflavoredBuildTarget, invalidFlavorsDisplayStr);
      }
    }

    UnflavoredBuildTarget unflavoredBuildTargetFromRawData =
        RawNodeParsePipeline.parseBuildTargetFromRawRule(
            cell.getRoot(), cell.getCanonicalName(), rawNode, buildFile);
    if (!unflavoredBuildTarget.equals(unflavoredBuildTargetFromRawData)) {
      throw new IllegalStateException(
          String.format(
              "Inconsistent internal state, target from data: %s, expected: %s, raw data: %s",
              unflavoredBuildTargetFromRawData,
              unflavoredBuildTarget,
              Joiner.on(',').withKeyValueSeparator("->").join(rawNode)));
    }

    Cell targetCell = cell.getCell(target);
    Object constructorArg;
    try {
      ImmutableSet.Builder<BuildTarget> declaredDeps = ImmutableSet.builder();
      ImmutableSet<VisibilityPattern> visibilityPatterns;
      ImmutableSet<VisibilityPattern> withinViewPatterns;
      try (SimplePerfEvent.Scope scope =
          perfEventScope.apply(PerfEventId.of("MarshalledConstructorArg"))) {
        constructorArg =
            marshaller.populate(
                targetCell.getCellPathResolver(),
                targetCell.getFilesystem(),
                target,
                description.getConstructorArgType(),
                declaredDeps,
                rawNode);
        visibilityPatterns =
            ConstructorArgMarshaller.populateVisibilityPatterns(
                targetCell.getCellPathResolver(), "visibility", rawNode.get("visibility"), target);
        withinViewPatterns =
            ConstructorArgMarshaller.populateVisibilityPatterns(
                targetCell.getCellPathResolver(),
                "within_view",
                rawNode.get("within_view"),
                target);
      }
      try (SimplePerfEvent.Scope scope =
          perfEventScope.apply(PerfEventId.of("CreatedTargetNode"))) {
        Hasher hasher = Hashing.sha1().newHasher();
        hasher.putString(ruleKeyConfiguration.getCoreKey(), UTF_8);
        JsonObjectHashing.hashJsonObject(hasher, rawNode);
        TargetNode<?, ?> node =
            targetNodeFactory.createFromObject(
                hasher.hash(),
                description,
                constructorArg,
                targetCell.getFilesystem(),
                target,
                declaredDeps.build(),
                visibilityPatterns,
                withinViewPatterns,
                targetCell.getCellPathResolver());
        if (buildFileTrees.isPresent()
            && cell.isEnforcingBuckPackageBoundaries(target.getBasePath())) {
          enforceBuckPackageBoundaries(
              targetCell, target, buildFileTrees.get().getUnchecked(targetCell), node.getInputs());
        }
        nodeListener.onCreate(buildFile, node);
        return node;
      }
    } catch (NoSuchBuildTargetException e) {
      throw new HumanReadableException(e);
    } catch (ParamInfoException e) {
      throw new HumanReadableException(e, "%s: %s", target, e.getMessage());
    } catch (IOException e) {
      throw new HumanReadableException(e.getMessage(), e);
    }
  }

  protected void enforceBuckPackageBoundaries(
      Cell targetCell, BuildTarget target, BuildFileTree buildFileTree, ImmutableSet<Path> paths) {
    Path basePath = target.getBasePath();

    for (Path path : paths) {
      if (!basePath.toString().isEmpty() && !path.startsWith(basePath)) {
        throw new HumanReadableException(
            "'%s' in '%s' refers to a parent directory.", basePath.relativize(path), target);
      }

      Optional<Path> ancestor = buildFileTree.getBasePathOfAncestorTarget(path);
      // It should not be possible for us to ever get an Optional.empty() for this because that
      // would require one of two conditions:
      // 1) The source path references parent directories, which we check for above.
      // 2) You don't have a build file above this file, which is impossible if it is referenced in
      //    a build file *unless* you happen to be referencing something that is ignored.
      if (!ancestor.isPresent()) {
        throw new IllegalStateException(
            String.format(
                "Target '%s' refers to file '%s', which doesn't belong to any package",
                target, path));
      }
      if (!ancestor.get().equals(basePath)) {
        String buildFileName = targetCell.getBuildFileName();
        Path buckFile = ancestor.get().resolve(buildFileName);
        // TODO(cjhopman): If we want to manually split error message lines ourselves, we should
        // have a utility to do it correctly after formatting instead of doing it manually.
        throw new HumanReadableException(
            "The target '%1$s' tried to reference '%2$s'.\n"
                + "This is not allowed because '%2$s' can only be referenced from '%3$s' \n"
                + "which is it's closest parent '%4$s' file.\n"
                + "\n"
                + "You should find or create the rule in '%3$s' that references\n"
                + "'%2$s' and use that in '%1$s'\n"
                + "instead of directly referencing '%2$s'.\n"
                + "\n"
                + "This may also be due to a bug in buckd's caching.\n"
                + "Please check whether using `buck kill` will resolve it.",
            target, path, buckFile, buildFileName);
      }
    }
  }

  private static BuildRuleType parseBuildRuleTypeFromRawRule(
      KnownBuildRuleTypes knownBuildRuleTypes, Map<String, Object> map) {
    String type = (String) Preconditions.checkNotNull(map.get(BuckPyFunction.TYPE_PROPERTY_NAME));
    return knownBuildRuleTypes.getBuildRuleType(type);
  }
}
