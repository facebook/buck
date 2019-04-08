/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNode;
import com.facebook.buck.core.select.SelectorList;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent.Scope;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

/**
 * Creates {@link TargetNode} from {@link RawTargetNode} without resolving configurable attributes.
 *
 * <p>This is used to create {@link TargetNode} for targets that represent configuration rules.
 */
public class NonResolvingRawTargetNodeToTargetNodeFactory
    implements ParserTargetNodeFactory<RawTargetNode> {

  private final ParserTargetNodeFactory<Map<String, Object>> parserTargetNodeFactory;

  public NonResolvingRawTargetNodeToTargetNodeFactory(
      ParserTargetNodeFactory<Map<String, Object>> parserTargetNodeFactory) {
    this.parserTargetNodeFactory = parserTargetNodeFactory;
  }

  @Override
  public TargetNode<?> createTargetNode(
      Cell cell,
      Path buildFile,
      BuildTarget target,
      RawTargetNode rawTargetNode,
      Function<PerfEventId, Scope> perfEventScope) {

    return parserTargetNodeFactory.createTargetNode(
        cell,
        cell.getBuckConfigView(ParserConfig.class)
            .getAbsolutePathToBuildFile(cell, target.getUnconfiguredBuildTargetView()),
        target,
        assertRawTargetNodeAttributesNotConfigurable(target, rawTargetNode.getAttributes()),
        perfEventScope);
  }

  private ImmutableMap<String, Object> assertRawTargetNodeAttributesNotConfigurable(
      BuildTarget buildTarget, ImmutableMap<String, Object> rawTargetNodeAttributes) {
    for (Map.Entry<String, ?> entry : rawTargetNodeAttributes.entrySet()) {
      Preconditions.checkState(
          !(entry.getValue() instanceof SelectorList),
          "Attribute %s cannot be configurable in %s",
          entry.getKey(),
          buildTarget);
    }
    return rawTargetNodeAttributes;
  }
}
