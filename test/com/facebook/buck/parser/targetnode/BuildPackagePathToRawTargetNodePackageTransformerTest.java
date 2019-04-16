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
package com.facebook.buck.parser.targetnode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.graph.transformation.FakeComputationEnvironment;
import com.facebook.buck.core.model.ImmutableUnconfiguredBuildTarget;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.platform.ConstraintBasedPlatform;
import com.facebook.buck.core.model.targetgraph.RawTargetNodeWithDeps;
import com.facebook.buck.core.model.targetgraph.RawTargetNodeWithDepsPackage;
import com.facebook.buck.core.model.targetgraph.impl.ImmutableRawTargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNode;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.core.rules.platform.ThrowingConstraintResolver;
import com.facebook.buck.core.select.TestSelectableResolver;
import com.facebook.buck.core.select.impl.DefaultSelectorListResolver;
import com.facebook.buck.parser.NoopPackageBoundaryChecker;
import com.facebook.buck.parser.RawTargetNodeToTargetNodeFactory;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import org.junit.Test;

public class BuildPackagePathToRawTargetNodePackageTransformerTest {

  @Test
  public void canParseDeps() {
    Cell cell = new TestCellBuilder().build();

    TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    RawTargetNodeToTargetNodeFactory rawTargetNodeToTargetNodeFactory =
        new RawTargetNodeToTargetNodeFactory(
            TestKnownRuleTypesProvider.create(BuckPluginManagerFactory.createPluginManager()),
            new DefaultConstructorArgMarshaller(typeCoercerFactory),
            new TargetNodeFactory(typeCoercerFactory),
            new NoopPackageBoundaryChecker(),
            (file, targetNode) -> {},
            new DefaultSelectorListResolver(new TestSelectableResolver()),
            new ThrowingConstraintResolver(),
            configuration -> new ConstraintBasedPlatform("", ImmutableSet.of()));

    ImmutableMap<String, Object> rawAttributes1 =
        ImmutableMap.of(
            "name",
            "target1",
            "buck.type",
            "java_library",
            "buck.base_path",
            "",
            "deps",
            ImmutableSortedSet.of(":target2"));
    UnconfiguredBuildTarget unconfiguredBuildTarget1 =
        ImmutableUnconfiguredBuildTarget.of(
            cell.getCanonicalName().orElse(""),
            "//",
            "target1",
            UnconfiguredBuildTarget.NO_FLAVORS);
    RawTargetNode rawTargetNode1 =
        ImmutableRawTargetNode.of(
            unconfiguredBuildTarget1,
            RuleType.of("java_library", RuleType.Kind.BUILD),
            rawAttributes1,
            ImmutableSet.of(),
            ImmutableSet.of());

    ImmutableMap<String, Object> rawAttributes2 =
        ImmutableMap.of("name", "target2", "buck.type", "java_library", "buck.base_path", "");
    UnconfiguredBuildTarget unconfiguredBuildTarget2 =
        ImmutableUnconfiguredBuildTarget.of(
            cell.getCanonicalName().orElse(""),
            "//",
            "target2",
            UnconfiguredBuildTarget.NO_FLAVORS);
    RawTargetNode rawTargetNode2 =
        ImmutableRawTargetNode.of(
            unconfiguredBuildTarget2,
            RuleType.of("java_library", RuleType.Kind.BUILD),
            rawAttributes2,
            ImmutableSet.of(),
            ImmutableSet.of());

    BuildPackagePathToRawTargetNodePackageComputation transformer =
        BuildPackagePathToRawTargetNodePackageComputation.of(
            rawTargetNodeToTargetNodeFactory, cell);
    RawTargetNodeWithDepsPackage rawTargetNodeWithDepsPackage =
        transformer.transform(
            ImmutableBuildPackagePathToRawTargetNodePackageKey.of(Paths.get("")),
            new FakeComputationEnvironment(
                ImmutableMap.of(
                    ImmutableBuildTargetToRawTargetNodeKey.of(unconfiguredBuildTarget1),
                    rawTargetNode1,
                    ImmutableBuildTargetToRawTargetNodeKey.of(unconfiguredBuildTarget2),
                    rawTargetNode2)));

    ImmutableMap<String, RawTargetNodeWithDeps> allTargets =
        rawTargetNodeWithDepsPackage.getRawTargetNodesWithDeps();

    assertEquals(2, allTargets.size());

    RawTargetNodeWithDeps target1 = allTargets.get("target1");
    assertNotNull(target1);

    RawTargetNodeWithDeps target2 = allTargets.get("target2");
    assertNotNull(target2);

    ImmutableSet<UnconfiguredBuildTarget> deps = target1.getDeps();
    assertEquals(1, deps.size());

    UnconfiguredBuildTarget dep = deps.iterator().next();
    assertEquals("//", dep.getBaseName());
    assertEquals("target2", dep.getName());
  }
}
