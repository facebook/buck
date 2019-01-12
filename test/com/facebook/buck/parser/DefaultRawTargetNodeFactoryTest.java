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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.targetgraph.RawTargetNode;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.parser.syntax.ImmutableListWithSelects;
import com.facebook.buck.parser.syntax.ImmutableSelectorValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.util.Optional;
import org.junit.Test;

public class DefaultRawTargetNodeFactoryTest {

  @Test
  public void testCreatePopulatesNode() {
    KnownRuleTypesProvider knownRuleTypesProvider =
        TestKnownRuleTypesProvider.create(BuckPluginManagerFactory.createPluginManager());

    DefaultRawTargetNodeFactory factory =
        new DefaultRawTargetNodeFactory(knownRuleTypesProvider, new BuiltTargetVerifier());

    Cell cell = new TestCellBuilder().build();

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//a/b:c");

    ImmutableMap<String, Object> attributes =
        ImmutableMap.<String, Object>builder()
            .put("buck.type", "java_library")
            .put("name", "c")
            .put("buck.base_path", "a/b")
            .put("deps", ImmutableList.of("//a/b:d", "//a/b:e"))
            .put(
                "resources",
                ImmutableListWithSelects.of(
                    ImmutableList.of(
                        ImmutableSelectorValue.of(
                            ImmutableMap.of(
                                "//c:a",
                                ImmutableList.of("//a/b:file1", "//a/b:file2"),
                                "//c:b",
                                ImmutableList.of("//a/b:file3", "//a/b:file4")),
                            "")),
                    ImmutableList.class))
            .put("visibility", ImmutableList.of("//a/..."))
            .put("within_view", ImmutableList.of("//b/..."))
            .build();
    RawTargetNode rawTargetNode =
        factory.create(
            cell,
            cell.getRoot().resolve("a/b/BUCK"),
            buildTarget,
            attributes,
            (id) -> SimplePerfEvent.scope(Optional.empty(), null, null));

    assertEquals(RuleType.of("java_library", RuleType.Kind.BUILD), rawTargetNode.getRuleType());
    assertEquals(buildTarget, rawTargetNode.getBuildTarget());

    assertEquals(attributes, rawTargetNode.getAttributes().getAll());

    assertEquals(
        "//a/...",
        Iterables.getFirst(rawTargetNode.getVisibilityPatterns(), null).getRepresentation());
    assertEquals(
        "//b/...",
        Iterables.getFirst(rawTargetNode.getWithinViewPatterns(), null).getRepresentation());
  }
}
