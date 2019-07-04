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
import com.facebook.buck.core.model.ConfigurationBuildTargetFactoryForTests;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTargetFactoryForTests;
import com.facebook.buck.core.model.impl.ImmutableDefaultTargetConfiguration;
import com.facebook.buck.core.model.impl.MultiPlatformTargetConfigurationTransformer;
import com.facebook.buck.core.model.platform.TargetPlatformResolver;
import com.facebook.buck.core.model.platform.impl.EmptyPlatform;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.ImmutableRawTargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNode;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.core.rules.platform.ThrowingConstraintResolver;
import com.facebook.buck.core.select.TestSelectable;
import com.facebook.buck.core.select.TestSelectableResolver;
import com.facebook.buck.core.select.impl.DefaultSelectorListResolver;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaLibraryDescriptionArg;
import com.facebook.buck.parser.syntax.ImmutableListWithSelects;
import com.facebook.buck.parser.syntax.ImmutableSelectorValue;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Test;

public class RawTargetNodeToTargetNodeFactoryTest {

  @Test
  public void testTargetNodeCreatedWithAttributes() {
    BuildTarget targetPlatform = BuildTargetFactory.newInstance("//config:platform");
    TargetConfiguration targetConfiguration =
        ImmutableDefaultTargetConfiguration.of(targetPlatform);
    BuildTarget buildTarget =
        UnconfiguredBuildTargetFactoryForTests.newInstance("//a/b:c")
            .configure(targetConfiguration);
    Path basepath = Paths.get("a").resolve("b");
    Cell cell =
        new TestCellBuilder()
            .setFilesystem(
                new FakeProjectFilesystem(
                    Sets.newHashSet(
                        basepath.resolve("src1"),
                        basepath.resolve("src2"),
                        basepath.resolve("BUCK"))))
            .build();
    ImmutableListWithSelects selectorList =
        ImmutableListWithSelects.of(
            ImmutableList.of(
                ImmutableSelectorValue.of(ImmutableMap.of("DEFAULT", "1", "//x:y", "2"), ""),
                ImmutableSelectorValue.of(ImmutableMap.of("DEFAULT", "8", "//x:y", "9"), "")),
            ImmutableMap.class);
    ImmutableMap<String, Object> attributes =
        ImmutableMap.<String, Object>builder()
            .put("name", "c")
            .put("srcs", ImmutableList.of("src1", "src2"))
            .put("source", selectorList)
            .build();
    RawTargetNode node =
        ImmutableRawTargetNode.of(
            buildTarget.getUnconfiguredBuildTargetView().getData(),
            RuleType.of("java_library", RuleType.Kind.BUILD),
            attributes,
            ImmutableSet.of(),
            ImmutableSet.of());
    TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    BuildTarget selectableTarget = ConfigurationBuildTargetFactoryForTests.newInstance("//x:y");
    TargetPlatformResolver targetPlatformResolver = configuration -> EmptyPlatform.INSTANCE;
    RawTargetNodeToTargetNodeFactory factory =
        new RawTargetNodeToTargetNodeFactory(
            typeCoercerFactory,
            TestKnownRuleTypesProvider.create(BuckPluginManagerFactory.createPluginManager()),
            new DefaultConstructorArgMarshaller(typeCoercerFactory),
            new TargetNodeFactory(typeCoercerFactory),
            new NoopPackageBoundaryChecker(),
            (file, targetNode) -> {},
            new DefaultSelectorListResolver(
                new TestSelectableResolver(
                    ImmutableList.of(new TestSelectable(selectableTarget, true)))),
            new ThrowingConstraintResolver(),
            targetPlatformResolver,
            new MultiPlatformTargetConfigurationTransformer(targetPlatformResolver));

    TargetNode<?> targetNode =
        factory.createTargetNode(
            cell,
            Paths.get("a/b/BUCK"),
            buildTarget,
            node,
            id -> SimplePerfEvent.scope(Optional.empty(), null, null));

    assertEquals(JavaLibraryDescription.class, targetNode.getDescription().getClass());
    JavaLibraryDescriptionArg arg = (JavaLibraryDescriptionArg) targetNode.getConstructorArg();
    assertEquals(
        ImmutableSortedSet.of(
            PathSourcePath.of(cell.getFilesystem(), basepath.resolve("src1")),
            PathSourcePath.of(cell.getFilesystem(), basepath.resolve("src2"))),
        arg.getSrcs());
    assertEquals(
        ImmutableSet.of(targetPlatform, selectableTarget), targetNode.getConfigurationDeps());
  }
}
