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

package com.facebook.buck.parser.targetnode;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.DefaultCellNameResolverProvider;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.graph.transformation.impl.FakeComputationEnvironment;
import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.core.model.FlavorSet;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.impl.MultiPlatformTargetConfigurationTransformer;
import com.facebook.buck.core.model.platform.TargetPlatformResolver;
import com.facebook.buck.core.model.platform.impl.UnconfiguredPlatform;
import com.facebook.buck.core.model.targetgraph.impl.ImmutableUnconfiguredTargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNodeWithDeps;
import com.facebook.buck.core.parser.buildtargetpattern.UnconfiguredBuildTargetParser;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.core.select.TestSelectableResolver;
import com.facebook.buck.core.select.impl.DefaultSelectorListResolver;
import com.facebook.buck.parser.NoopPackageBoundaryChecker;
import com.facebook.buck.parser.UnconfiguredTargetNodeToTargetNodeFactory;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Test;

public class UnconfiguredTargetNodeToUnconfiguredTargetNodeWithDepsComputationTest {

  @Test
  public void canParseDeps() {
    Cells cell = new TestCellBuilder().build();

    TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    TargetPlatformResolver targetPlatformResolver =
        (configuration, dependencyStack) -> UnconfiguredPlatform.INSTANCE;
    UnconfiguredTargetNodeToTargetNodeFactory unconfiguredTargetNodeToTargetNodeFactory =
        new UnconfiguredTargetNodeToTargetNodeFactory(
            typeCoercerFactory,
            TestKnownRuleTypesProvider.create(BuckPluginManagerFactory.createPluginManager()),
            new DefaultConstructorArgMarshaller(),
            new TargetNodeFactory(typeCoercerFactory, new DefaultCellNameResolverProvider(cell)),
            new NoopPackageBoundaryChecker(),
            (file, targetNode) -> {},
            new DefaultSelectorListResolver(new TestSelectableResolver()),
            targetPlatformResolver,
            new MultiPlatformTargetConfigurationTransformer(targetPlatformResolver),
            UnconfiguredTargetConfiguration.INSTANCE,
            cell.getRootCell().getBuckConfig(),
            Optional.empty());

    ImmutableMap<String, Object> rawAttributes1 =
        ImmutableMap.of(
            "name",
            "target1",
            "buck.type",
            "java_library",
            "buck.base_path",
            "",
            "deps",
            ImmutableList.of(UnconfiguredBuildTargetParser.parse("//:target2")));
    UnconfiguredBuildTarget unconfiguredBuildTarget1 =
        UnconfiguredBuildTarget.of(
            cell.getRootCell().getCanonicalName(),
            BaseName.of("//"),
            "target1",
            FlavorSet.NO_FLAVORS);
    UnconfiguredTargetNode unconfiguredTargetNode1 =
        ImmutableUnconfiguredTargetNode.of(
            unconfiguredBuildTarget1,
            RuleType.of("java_library", RuleType.Kind.BUILD),
            rawAttributes1,
            ImmutableSet.of(),
            ImmutableSet.of(),
            Optional.empty(),
            ImmutableList.of());

    UnconfiguredTargetNodeToUnconfiguredTargetNodeWithDepsComputation computation =
        UnconfiguredTargetNodeToUnconfiguredTargetNodeWithDepsComputation.of(
            unconfiguredTargetNodeToTargetNodeFactory, cell.getRootCell());
    UnconfiguredTargetNodeWithDeps rawTargetNode =
        computation.transform(
            ImmutableUnconfiguredTargetNodeToUnconfiguredTargetNodeWithDepsKey.of(
                unconfiguredTargetNode1, Paths.get("")),
            new FakeComputationEnvironment(
                ImmutableMap.of(
                    ImmutableBuildTargetToUnconfiguredTargetNodeKey.of(
                        unconfiguredBuildTarget1, Paths.get("")),
                    unconfiguredTargetNode1)));

    ImmutableSet<UnconfiguredBuildTarget> deps = rawTargetNode.getDeps();
    assertEquals(1, deps.size());

    UnconfiguredBuildTarget dep = deps.iterator().next();
    assertEquals("//", dep.getBaseName().toString());
    assertEquals("target2", dep.getName());
  }
}
