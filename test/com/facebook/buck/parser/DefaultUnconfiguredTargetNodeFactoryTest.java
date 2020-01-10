/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.parser;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.ConfigurationBuildTargetFactoryForTests;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.UnconfiguredBuildTargetFactoryForTests;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.targetgraph.impl.Package;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.core.select.Selector;
import com.facebook.buck.core.select.SelectorKey;
import com.facebook.buck.core.select.SelectorList;
import com.facebook.buck.core.select.impl.SelectorFactory;
import com.facebook.buck.core.select.impl.SelectorListFactory;
import com.facebook.buck.parser.api.ImmutablePackageMetadata;
import com.facebook.buck.parser.syntax.ImmutableListWithSelects;
import com.facebook.buck.parser.syntax.ImmutableSelectorValue;
import com.facebook.buck.rules.coercer.JsonTypeConcatenatingCoercerFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class DefaultUnconfiguredTargetNodeFactoryTest {

  private DefaultUnconfiguredTargetNodeFactory factory;
  private Cell cell;

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() {
    KnownRuleTypesProvider knownRuleTypesProvider =
        TestKnownRuleTypesProvider.create(BuckPluginManagerFactory.createPluginManager());

    cell = new TestCellBuilder().build();

    factory =
        new DefaultUnconfiguredTargetNodeFactory(
            knownRuleTypesProvider,
            new BuiltTargetVerifier(),
            cell.getCellPathResolver(),
            new SelectorListFactory(
                new SelectorFactory(new ParsingUnconfiguredBuildTargetViewFactory())));
  }

  @Test
  public void testCreatePopulatesNode() {
    UnconfiguredBuildTargetView buildTarget =
        UnconfiguredBuildTargetFactoryForTests.newInstance("//a/b:c");

    ImmutableMap<String, Object> inputAttributes =
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

    ImmutableMap<String, Object> expectAttributes =
        ImmutableMap.<String, Object>builder()
            .put("buck.type", "java_library")
            .put("name", "c")
            .put("buck.base_path", "a/b")
            .put("deps", ImmutableList.of("//a/b:d", "//a/b:e"))
            .put(
                "resources",
                new SelectorList<>(
                    JsonTypeConcatenatingCoercerFactory.createForType(List.class),
                    ImmutableList.of(
                        new Selector<>(
                            ImmutableMap.of(
                                new SelectorKey(
                                    ConfigurationBuildTargetFactoryForTests.newInstance("//c:a")),
                                ImmutableList.of("//a/b:file1", "//a/b:file2"),
                                new SelectorKey(
                                    ConfigurationBuildTargetFactoryForTests.newInstance("//c:b")),
                                ImmutableList.of("//a/b:file3", "//a/b:file4")),
                            ImmutableSet.of(),
                            ""))))
            .put("visibility", ImmutableList.of("//a/..."))
            .put("within_view", ImmutableList.of("//b/..."))
            .build();

    UnconfiguredTargetNode unconfiguredTargetNode =
        factory.create(
            cell,
            cell.getRoot().resolve("a/b/BUCK"),
            buildTarget,
            DependencyStack.root(),
            inputAttributes,
            getPackage());

    assertEquals(
        RuleType.of("java_library", RuleType.Kind.BUILD), unconfiguredTargetNode.getRuleType());
    assertEquals(buildTarget.getData(), unconfiguredTargetNode.getBuildTarget());

    assertEquals(expectAttributes, unconfiguredTargetNode.getAttributes());

    assertEquals(
        "//a/...",
        Iterables.getFirst(unconfiguredTargetNode.getVisibilityPatterns(), null)
            .getRepresentation());
    assertEquals(
        "//b/...",
        Iterables.getFirst(unconfiguredTargetNode.getWithinViewPatterns(), null)
            .getRepresentation());
  }

  Package getPackage() {
    ImmutablePackageMetadata pkg =
        ImmutablePackageMetadata.of(ImmutableList.of("//a/..."), ImmutableList.of("//d/..."));

    return PackageFactory.create(cell, cell.getRoot().resolve("a/b/BUCK"), pkg, Optional.empty());
  }
}
