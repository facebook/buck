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
import com.facebook.buck.core.model.targetgraph.RawAttributes;
import com.facebook.buck.core.model.targetgraph.RawTargetNode;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.core.select.Selector;
import com.facebook.buck.core.select.SelectorKey;
import com.facebook.buck.core.select.SelectorList;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.visibility.BuildTargetVisibilityPattern;
import com.facebook.buck.rules.visibility.VisibilityPatternFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.syntax.SelectorValue;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class DefaultRawTargetNodeFactoryTest {

  @Test
  public void testCreatePopulatesNode() {
    KnownRuleTypesProvider knownRuleTypesProvider =
        TestKnownRuleTypesProvider.create(BuckPluginManagerFactory.createPluginManager());

    DefaultRawTargetNodeFactory factory =
        new DefaultRawTargetNodeFactory(
            knownRuleTypesProvider,
            new ConstructorArgMarshaller(new DefaultTypeCoercerFactory()),
            new VisibilityPatternFactory(),
            new BuiltTargetVerifier());

    Cell cell = new TestCellBuilder().build();

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//a/b:c");

    RawTargetNode rawTargetNode =
        factory.create(
            cell,
            cell.getRoot().resolve("a/b/BUCK"),
            buildTarget,
            ImmutableMap.<String, Object>builder()
                .put("buck.type", "java_library")
                .put("name", "c")
                .put("buck.base_path", "a/b")
                .put("deps", ImmutableList.of("//a/b:d", "//a/b:e"))
                .put(
                    "resources",
                    com.google.devtools.build.lib.syntax.SelectorList.of(
                        new SelectorValue(
                            ImmutableMap.of(
                                "//c:a",
                                ImmutableList.of("//a/b:file1", "//a/b:file2"),
                                "//c:b",
                                ImmutableList.of("//a/b:file3", "//a/b:file4")),
                            "")))
                .put("visibility", ImmutableList.of("//a/..."))
                .put("within_view", ImmutableList.of("//b/..."))
                .build(),
            (id) -> SimplePerfEvent.scope(Optional.empty(), null, null));

    assertEquals(RuleType.of("java_library", RuleType.Kind.BUILD), rawTargetNode.getRuleType());
    assertEquals(buildTarget, rawTargetNode.getBuildTarget());

    RawAttributes attributes = rawTargetNode.getAttributes();
    assertEquals(
        ImmutableList.of(
            BuildTargetFactory.newInstance("//a/b:d"), BuildTargetFactory.newInstance("//a/b:e")),
        attributes.get("deps"));

    SelectorList<List<SourcePath>> resources = attributes.get("resources");
    assertEquals(1, resources.getSelectors().size());
    Selector<List<SourcePath>> selector = resources.getSelectors().get(0);
    assertEquals(
        ImmutableSet.of(
            BuildTargetFactory.newInstance("//c:a"), BuildTargetFactory.newInstance("//c:b")),
        selector
            .getConditions()
            .keySet()
            .stream()
            .map(SelectorKey::getBuildTarget)
            .collect(ImmutableSet.toImmutableSet()));
    assertEquals(
        ImmutableSet.of(
            ImmutableList.of(
                BuildTargetFactory.newInstance("//a/b:file1"),
                BuildTargetFactory.newInstance("//a/b:file2")),
            ImmutableList.of(
                BuildTargetFactory.newInstance("//a/b:file3"),
                BuildTargetFactory.newInstance("//a/b:file4"))),
        selector
            .getConditions()
            .values()
            .stream()
            .map(
                list ->
                    list.stream()
                        .map(BuildTargetSourcePath.class::cast)
                        .map(BuildTargetSourcePath::getTarget)
                        .collect(ImmutableList.toImmutableList()))
            .collect(ImmutableSet.toImmutableSet()));
    assertEquals(
        ImmutableSet.of(
            BuildTargetVisibilityPattern.of(
                SubdirectoryBuildTargetPattern.of(cell.getRoot(), Paths.get("a")))),
        rawTargetNode.getVisibilityPatterns());
    assertEquals(
        ImmutableSet.of(
            BuildTargetVisibilityPattern.of(
                SubdirectoryBuildTargetPattern.of(cell.getRoot(), Paths.get("b")))),
        rawTargetNode.getWithinViewPatterns());
  }
}
