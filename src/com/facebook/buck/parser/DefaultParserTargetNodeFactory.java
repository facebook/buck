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
import com.facebook.buck.model.BuckVersion;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.BuckPyFunction;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodeFactory;
import com.facebook.buck.rules.VisibilityPattern;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.ParamInfoException;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
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

/**
 * Creates {@link TargetNode} instances from raw data coming in form the {@link
 * com.facebook.buck.json.ProjectBuildFileParser}.
 */
public class DefaultParserTargetNodeFactory implements ParserTargetNodeFactory<TargetNode<?, ?>> {

  private static final Logger LOG = Logger.get(DefaultParserTargetNodeFactory.class);

  private final ConstructorArgMarshaller marshaller;
  private final Optional<LoadingCache<Cell, BuildFileTree>> buildFileTrees;
  private final TargetNodeListener<TargetNode<?, ?>> nodeListener;
  private final TargetNodeFactory targetNodeFactory;

  private DefaultParserTargetNodeFactory(
      ConstructorArgMarshaller marshaller,
      Optional<LoadingCache<Cell, BuildFileTree>> buildFileTrees,
      TargetNodeListener<TargetNode<?, ?>> nodeListener,
      TargetNodeFactory targetNodeFactory) {
    this.marshaller = marshaller;
    this.buildFileTrees = buildFileTrees;
    this.nodeListener = nodeListener;
    this.targetNodeFactory = targetNodeFactory;
  }

  public static ParserTargetNodeFactory<TargetNode<?, ?>> createForParser(
      ConstructorArgMarshaller marshaller,
      LoadingCache<Cell, BuildFileTree> buildFileTrees,
      TargetNodeListener<TargetNode<?, ?>> nodeListener,
      TargetNodeFactory targetNodeFactory) {
    return new DefaultParserTargetNodeFactory(
        marshaller, Optional.of(buildFileTrees), nodeListener, targetNodeFactory);
  }

  public static ParserTargetNodeFactory<TargetNode<?, ?>> createForDistributedBuild(
      ConstructorArgMarshaller marshaller, TargetNodeFactory targetNodeFactory) {
    return new DefaultParserTargetNodeFactory(
        marshaller,
        Optional.empty(),
        (buildFile, node) -> {
          // No-op.
        },
        targetNodeFactory);
  }

  @Override
  public TargetNode<?, ?> createTargetNode(
      Cell cell,
      Path buildFile,
      BuildTarget target,
      Map<String, Object> rawNode,
      Function<PerfEventId, SimplePerfEvent.Scope> perfEventScope) {
    BuildRuleType buildRuleType = parseBuildRuleTypeFromRawRule(cell, rawNode);

    // Because of the way that the parser works, we know this can never return null.
    Description<?> description = cell.getDescription(buildRuleType);

    UnflavoredBuildTarget unflavoredBuildTarget = target.getUnflavoredBuildTarget();
    if (target.isFlavored()) {
      if (description instanceof Flavored) {
        if (!((Flavored) description).hasFlavors(ImmutableSet.copyOf(target.getFlavors()))) {
          throw UnexpectedFlavorException.createWithSuggestions(cell, target);
        }
      } else {
        LOG.warn(
            "Target %s (type %s) must implement the Flavored interface "
                + "before we can check if it supports flavors: %s",
            unflavoredBuildTarget, buildRuleType, target.getFlavors());
        throw new HumanReadableException(
            "Target %s (type %s) does not currently support flavors (tried %s)",
            unflavoredBuildTarget, buildRuleType, target.getFlavors());
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
        hasher.putString(BuckVersion.getVersion(), UTF_8);
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
              target, buildFileTrees.get().getUnchecked(targetCell), node.getInputs());
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
      BuildTarget target, BuildFileTree buildFileTree, ImmutableSet<Path> paths) {
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
        throw new HumanReadableException(
            "'%s' in '%s' crosses a buck package boundary.  This is probably caused by "
                + "specifying one of the folders in '%s' in your .buckconfig under `project.ignore`.",
            path, target, path);
      }
      if (!ancestor.get().equals(basePath)) {
        throw new HumanReadableException(
            "'%s' in '%s' crosses a buck package boundary.  This file is owned by '%s'.  Find "
                + "the owning rule that references '%s', and use a reference to that rule instead "
                + "of referencing the desired file directly.",
            path, target, ancestor.get(), path);
      }
    }
  }

  private static BuildRuleType parseBuildRuleTypeFromRawRule(Cell cell, Map<String, Object> map) {
    String type = (String) Preconditions.checkNotNull(map.get(BuckPyFunction.TYPE_PROPERTY_NAME));
    return cell.getBuildRuleType(type);
  }
}
