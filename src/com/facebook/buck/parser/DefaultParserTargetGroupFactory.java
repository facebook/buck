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
import com.facebook.buck.groups.TargetGroupDescription;
import com.facebook.buck.json.JsonObjectHashing;
import com.facebook.buck.model.BuckVersion;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.BuckPyFunction;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ParamInfoException;
import com.facebook.buck.rules.TargetGroup;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.VisibilityPattern;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.nio.file.Path;
import java.util.Map;

/**
 * Creates {@link TargetNode} instances from raw data coming in form the
 * {@link com.facebook.buck.json.ProjectBuildFileParser}.
 */
public class DefaultParserTargetGroupFactory implements ParserTargetNodeFactory<TargetGroup> {

  private final ConstructorArgMarshaller marshaller;

  public DefaultParserTargetGroupFactory(ConstructorArgMarshaller marshaller) {
    this.marshaller = marshaller;
  }

  @Override
  public TargetGroup createTargetNode(
      Cell cell,
      Path buildFile,
      BuildTarget target,
      Map<String, Object> rawNode,
      Function<PerfEventId, SimplePerfEvent.Scope> perfEventScope) {
    Preconditions.checkArgument(!target.isFlavored());

    UnflavoredBuildTarget unflavoredBuildTarget = target.withoutCell().getUnflavoredBuildTarget();
    UnflavoredBuildTarget unflavoredBuildTargetFromRawData =
        RawNodeParsePipeline.parseBuildTargetFromRawRule(
            cell.getRoot(),
            rawNode,
            buildFile);
    if (!unflavoredBuildTarget.equals(unflavoredBuildTargetFromRawData)) {
      throw new IllegalStateException(
          String.format(
              "Inconsistent internal state, target from data: %s, expected: %s, raw data: %s",
              unflavoredBuildTargetFromRawData,
              unflavoredBuildTarget,
              Joiner.on(',').withKeyValueSeparator("->").join(rawNode)));
    }

    BuildRuleType buildRuleType = parseBuildRuleTypeFromRawRule(cell, rawNode);

    // Because of the way that the parser works, we know this can never return null.
    Description<?> description = cell.getDescription(buildRuleType);

    Cell targetCell = cell.getCell(target);
    TargetGroupDescription.Arg constructorArg =
        (TargetGroupDescription.Arg) description.createUnpopulatedConstructorArg();
    try {
      ImmutableSet.Builder<BuildTarget> declaredDeps = ImmutableSet.builder();
      ImmutableSet.Builder<VisibilityPattern> visibilityPatterns =
          ImmutableSet.builder();
      try (SimplePerfEvent.Scope scope =
               perfEventScope.apply(PerfEventId.of("MarshalledConstructorArg"))) {
        marshaller.populate(
            targetCell.getCellPathResolver(),
            targetCell.getFilesystem(),
            target,
            constructorArg,
            declaredDeps,
            visibilityPatterns,
            rawNode);
      }
      try (SimplePerfEvent.Scope scope =
               perfEventScope.apply(PerfEventId.of("CreatedTargetNode"))) {
        Hasher hasher = Hashing.sha1().newHasher();
        hasher.putString(BuckVersion.getVersion(), UTF_8);
        JsonObjectHashing.hashJsonObject(hasher, rawNode);
        TargetGroup node = new TargetGroup(
            constructorArg.targets,
            constructorArg.restrictOutboundVisibility,
            target);
        return node;
      }
    } catch (ParamInfoException e) {
      throw new HumanReadableException("%s: %s", target, e.getMessage());
    }
  }

  private static BuildRuleType parseBuildRuleTypeFromRawRule(
      Cell cell,
      Map<String, Object> map) {
    String type = (String) Preconditions.checkNotNull(
        map.get(BuckPyFunction.TYPE_PROPERTY_NAME));
    return cell.getBuildRuleType(type);
  }

}
