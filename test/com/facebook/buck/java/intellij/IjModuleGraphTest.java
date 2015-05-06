/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.java.intellij;

import static com.facebook.buck.testutil.MoreAsserts.assertContainsOne;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.java.JavaTestBuilder;
import com.facebook.buck.java.KeystoreBuilder;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

public class IjModuleGraphTest {

  @Test
  public void testModuleCoalescing() {
    TargetNode<?> guava = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third_party/java/guava:guava"))
        .build();

    TargetNode<?> base = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:base"))
        .addDep(guava.getBuildTarget())
        .build();

    TargetNode<?> baseTests = JavaTestBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:tests"))
        .addDep(guava.getBuildTarget())
        .build();

    IjModuleGraph moduleGraph = createModuleGraph(ImmutableSet.of(guava, base, baseTests));

    IjModule guavaModule = getModuleForTarget(moduleGraph, guava);
    IjModule baseModule = getModuleForTarget(moduleGraph, base);
    IjModule baseTestModule = getModuleForTarget(moduleGraph, baseTests);

    assertNotEquals(guavaModule, baseModule);
    assertEquals(baseTestModule, baseModule);
  }

  @Test
  public void testSimpleDependencies() {
    TargetNode<?> parentTarget = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/src/com/facebook/parent:parent"))
        .build();

    TargetNode<?> childTarget = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/src/com/facebook/child:child"))
        .addDep(parentTarget.getBuildTarget())
        .build();

    IjModuleGraph moduleGraph = createModuleGraph(ImmutableSet.of(parentTarget, childTarget));

    IjModule parentModule = getModuleForTarget(moduleGraph, parentTarget);
    IjModule childModule = getModuleForTarget(moduleGraph, childTarget);

    assertContainsOne(moduleGraph.getIncomingNodesFor(parentModule), childModule);
    assertTrue(moduleGraph.getIncomingNodesFor(childModule).isEmpty());
  }

  @Test
  public void testExportedDependencies() {
    TargetNode<?> junitReflect = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third-party/junit/reflect:reflect"))
        .build();

    TargetNode<?> junitCore = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third-party/junit/core:core"))
        .addExportedDep(junitReflect.getBuildTarget())
        .build();

    TargetNode<?> junitRule = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third-party/junit/rule:rule"))
        .addDep(junitCore.getBuildTarget())
        .build();

    IjModuleGraph moduleGraph = createModuleGraph(
        ImmutableSet.of(
            junitReflect,
            junitCore,
            junitRule));

    IjModule junitReflectModule = getModuleForTarget(moduleGraph, junitReflect);
    IjModule junitCoreModule = getModuleForTarget(moduleGraph, junitCore);
    IjModule junitRuleModule = getModuleForTarget(moduleGraph, junitRule);

    assertEquals(
        ImmutableSet.of(junitReflectModule, junitCoreModule),
        moduleGraph.getOutgoingNodesFor(junitRuleModule));
  }

  @Test
  public void testExportedDependenciesDependsOnDepOfExportedDep() {
    TargetNode<?> junitReflect = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third-party/junit/reflect:reflect"))
        .build();

    TargetNode<?> junitCore = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third-party/junit/core:core"))
        .addExportedDep(junitReflect.getBuildTarget())
        .build();

    TargetNode<?> junitRule = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third-party/junit/rule:rule"))
        .addExportedDep(junitCore.getBuildTarget())
        .build();

    IjModuleGraph moduleGraph = createModuleGraph(
        ImmutableSet.of(
            junitReflect,
            junitCore,
            junitRule));

    IjModule junitReflectModule = getModuleForTarget(moduleGraph, junitReflect);
    IjModule junitCoreModule = getModuleForTarget(moduleGraph, junitCore);
    IjModule junitRuleModule = getModuleForTarget(moduleGraph, junitRule);

    assertEquals(
        ImmutableSet.of(junitReflectModule, junitCoreModule),
        moduleGraph.getOutgoingNodesFor(junitRuleModule));
  }

  @Test
  public void testExportedDependenciesDontLeak() {

    TargetNode<?> junitReflect = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third-party/junit/reflect:reflect"))
        .build();

    TargetNode<?> junitCore = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third-party/junit/core:core"))
        .addExportedDep(junitReflect.getBuildTarget())
        .build();

    TargetNode<?> junitRule = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third-party/junit/rule:rule"))
        .addDep(junitCore.getBuildTarget())
        .build();

    TargetNode<?> testRule = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//tests/feature:tests"))
        .addDep(junitRule.getBuildTarget())
        .build();

    IjModuleGraph moduleGraph = createModuleGraph(
        ImmutableSet.of(
            junitReflect,
            junitCore,
            junitRule,
            testRule));

    IjModule junitRuleModule = getModuleForTarget(moduleGraph, junitRule);
    IjModule testRuleModule = getModuleForTarget(moduleGraph, testRule);

    assertEquals(
        ImmutableSet.of(junitRuleModule),
        moduleGraph.getOutgoingNodesFor(testRuleModule));
  }

  @Test
  public void testDropDependenciesToUnsupportedTargets() {
    TargetNode<?> parentKeystoreTarget = KeystoreBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/src/com/facebook/parent:keystore"))
        .build();

    TargetNode<?> parentJavaTarget = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/src/com/facebook/parent:parent"))
        .build();

    TargetNode<?> childTarget = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/src/com/facebook/child:child"))
        .addDep(parentKeystoreTarget.getBuildTarget())
        .build();

    IjModuleGraph moduleGraph = createModuleGraph(ImmutableSet.of(
            parentKeystoreTarget,
            parentJavaTarget,
            childTarget));

    IjModule parentModule = getModuleForTarget(moduleGraph, parentJavaTarget);
    IjModule childModule = getModuleForTarget(moduleGraph, childTarget);

    assertTrue(moduleGraph.getIncomingNodesFor(parentModule).isEmpty());
    assertTrue(moduleGraph.getIncomingNodesFor(childModule).isEmpty());
  }

  private IjModuleGraph createModuleGraph(ImmutableSet<TargetNode<?>> targets) {
    return IjModuleGraph.from(TargetGraphFactory.newInstance(targets), null);
  }

  private IjModule getModuleForTarget(IjModuleGraph graph, final TargetNode<?> target) {
    return FluentIterable.from(graph.getNodes()).firstMatch(
        new Predicate<IjModule>() {
          @Override
          public boolean apply(IjModule input) {
            return FluentIterable.from(input.getTargets()).anyMatch(
                new Predicate<TargetNode<?>>() {
                  @Override
                  public boolean apply(TargetNode<?> input) {
                    return input.getBuildTarget().equals(target.getBuildTarget());
                  }
                });
          }
        }).get();
  }
}
