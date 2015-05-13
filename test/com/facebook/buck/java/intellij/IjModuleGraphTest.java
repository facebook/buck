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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.java.JavaTestBuilder;
import com.facebook.buck.java.KeystoreBuilder;
import com.facebook.buck.java.PrebuiltJarBuilder;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

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
    TargetNode<?> libraryTarget = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/src/com/library:library"))
        .build();

    TargetNode<?> productTarget = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/src/com/product:product"))
        .addDep(libraryTarget.getBuildTarget())
        .build();

    IjModuleGraph moduleGraph = createModuleGraph(ImmutableSet.of(libraryTarget, productTarget));

    IjModule libraryModule = getModuleForTarget(moduleGraph, libraryTarget);
    IjModule productModule = getModuleForTarget(moduleGraph, productTarget);

    assertEquals(
        ImmutableMap.of(libraryModule, IjModuleGraph.DependencyType.PROD),
        moduleGraph.getDependentModulesFor(productModule));
    assertTrue(moduleGraph.getDependentModulesFor(libraryModule).isEmpty());
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
        ImmutableMap.of(
            junitReflectModule, IjModuleGraph.DependencyType.PROD,
            junitCoreModule, IjModuleGraph.DependencyType.PROD),
        moduleGraph.getDependentModulesFor(junitRuleModule));
  }

  @Test
  public void testTestDependencies() {
    TargetNode<?> junitTargetNode = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third-party/junit:junit"))
        .build();

    TargetNode<?> guavaTargetNode = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third-party/guava:guava"))
        .build();

    TargetNode<?> hamcrestTargetNode = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third-party/hamcrest:hamcrest"))
        .build();

    TargetNode<?> codeTargetNode = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/foo:foo"))
        .addSrc(Paths.get("java/com/foo/src/Foo.java"))
        .addDep(guavaTargetNode.getBuildTarget())
        .build();

    TargetNode<?> inlineTestTargetNode = JavaTestBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/foo:test"))
        .addDep(codeTargetNode.getBuildTarget())
        .addDep(junitTargetNode.getBuildTarget())
        .addSrc(Paths.get("java/com/foo/src/TestFoo.java"))
        .build();

    TargetNode<?> secondInlineTestTargetNode = JavaTestBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/foo:test2"))
        .addDep(hamcrestTargetNode.getBuildTarget())
        .addSrc(Paths.get("java/com/foo/test/TestFoo.java"))
        .build();

    TargetNode<?> testTargetNode = JavaTestBuilder
        .createBuilder(BuildTargetFactory.newInstance("//javatest/com/foo:foo"))
        .addDep(codeTargetNode.getBuildTarget())
        .addDep(junitTargetNode.getBuildTarget())
        .addSrc(Paths.get("javatest/com/foo/Foo.java"))
        .build();

    IjModuleGraph moduleGraph = createModuleGraph(
        ImmutableSet.<TargetNode<?>>of(
            guavaTargetNode,
            hamcrestTargetNode,
            junitTargetNode,
            codeTargetNode,
            inlineTestTargetNode,
            secondInlineTestTargetNode,
            testTargetNode));

    IjModule guavaModule = getModuleForTarget(moduleGraph, guavaTargetNode);
    IjModule hamcrestModule = getModuleForTarget(moduleGraph, hamcrestTargetNode);
    IjModule junitModule = getModuleForTarget(moduleGraph, junitTargetNode);
    IjModule codeModule = getModuleForTarget(moduleGraph, codeTargetNode);
    IjModule testModule = getModuleForTarget(moduleGraph, testTargetNode);

    assertEquals(ImmutableMap.of(),
        moduleGraph.getDependentModulesFor(junitModule));

    assertEquals(ImmutableMap.of(),
        moduleGraph.getDependentModulesFor(guavaModule));

    assertEquals(ImmutableMap.of(
            guavaModule, IjModuleGraph.DependencyType.PROD,
            junitModule, IjModuleGraph.DependencyType.PROD,
            hamcrestModule, IjModuleGraph.DependencyType.TEST),
        moduleGraph.getDependentModulesFor(codeModule));

    assertEquals(ImmutableMap.of(
            codeModule, IjModuleGraph.DependencyType.TEST,
            junitModule, IjModuleGraph.DependencyType.TEST),
        moduleGraph.getDependentModulesFor(testModule));
  }

  @Test
  public void testDependenciesOnPrebuilt() {
    TargetNode<?> guavaTargetNode = PrebuiltJarBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third-party/guava:guava"))
        .setBinaryJar(Paths.get("third-party/guava/guava.jar"))
        .build();

    TargetNode<?> coreTargetNode = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/org/foo/core:core"))
        .addDep(guavaTargetNode.getBuildTarget())
        .build();

    IjModuleGraph moduleGraph = createModuleGraph(ImmutableSet.of(guavaTargetNode, coreTargetNode));

    IjModule coreModule = getModuleForTarget(moduleGraph, coreTargetNode);
    IjLibrary guavaElement = getLibraryForTarget(moduleGraph, guavaTargetNode);

    assertEquals(ImmutableMap.of(guavaElement, IjModuleGraph.DependencyType.PROD),
        moduleGraph.getDepsFor(coreModule));
  }

  @Test
  public void testExportedDependenciesOfTests() {
    TargetNode<?> junitTargetNode = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third-party/junit:junit"))
        .build();

    TargetNode<?> testLibTargetNode = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//javatests/lib:lib"))
        .addExportedDep(junitTargetNode.getBuildTarget())
        .build();

    TargetNode<?> testTargetNode = JavaTestBuilder
        .createBuilder(BuildTargetFactory.newInstance("//javatests/test:test"))
        .addDep(testLibTargetNode.getBuildTarget())
        .build();

    IjModuleGraph moduleGraph = createModuleGraph(
        ImmutableSet.of(
            junitTargetNode,
            testLibTargetNode,
            testTargetNode));

    IjModule junitModule = getModuleForTarget(moduleGraph, junitTargetNode);
    IjModule testLibModule = getModuleForTarget(moduleGraph, testLibTargetNode);
    IjModule testModule = getModuleForTarget(moduleGraph, testTargetNode);

    assertEquals(ImmutableMap.of(
            junitModule, IjModuleGraph.DependencyType.TEST,
            testLibModule, IjModuleGraph.DependencyType.TEST),
        moduleGraph.getDependentModulesFor(testModule));
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
        ImmutableMap.of(
            junitReflectModule, IjModuleGraph.DependencyType.PROD,
            junitCoreModule, IjModuleGraph.DependencyType.PROD),
        moduleGraph.getDependentModulesFor(junitRuleModule));
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
        ImmutableMap.of(junitRuleModule, IjModuleGraph.DependencyType.PROD),
        moduleGraph.getDependentModulesFor(testRuleModule));
  }

  @Test
  public void testDropDependenciesToUnsupportedTargets() {
    TargetNode<?> productKeystoreTarget = KeystoreBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/src/com/facebook/library:keystore"))
        .build();

    TargetNode<?> libraryJavaTarget = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/src/com/facebook/library:library"))
        .build();

    TargetNode<?> productTarget = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/src/com/facebook/product:child"))
        .addDep(productKeystoreTarget.getBuildTarget())
        .addDep(libraryJavaTarget.getBuildTarget())
        .build();

    IjModuleGraph moduleGraph = createModuleGraph(
        ImmutableSet.of(
            libraryJavaTarget,
            productTarget,
            productKeystoreTarget));

    IjModule libraryModule = getModuleForTarget(moduleGraph, libraryJavaTarget);
    IjModule productModule = getModuleForTarget(moduleGraph, productTarget);

    assertEquals(ImmutableMap.of(libraryModule, IjModuleGraph.DependencyType.PROD),
        moduleGraph.getDependentModulesFor(productModule));
    assertEquals(2, moduleGraph.getModuleNodes().size());
  }

  private IjModuleGraph createModuleGraph(ImmutableSet<TargetNode<?>> targets) {
    final SourcePathResolver sourcePathResolver = new SourcePathResolver(new BuildRuleResolver());
    IjLibraryFactory.IjLibraryFactoryResolver sourceOnlyResolver =
        new IjLibraryFactory.IjLibraryFactoryResolver() {
          @Override
          public Path getPath(SourcePath path) {
            return sourcePathResolver.getPath(path);
          }
        };
    return IjModuleGraph.from(TargetGraphFactory.newInstance(targets), sourceOnlyResolver);
  }

  private IjProjectElement getProjectElementForTarget(
      IjModuleGraph graph,
      final TargetNode<?> target) {
    return FluentIterable.from(graph.getNodes()).firstMatch(
        new Predicate<IjProjectElement>() {
          @Override
          public boolean apply(IjProjectElement input) {
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

  private IjModule getModuleForTarget(IjModuleGraph graph, final TargetNode<?> target) {
    return (IjModule) getProjectElementForTarget(graph, target);
  }

  private IjLibrary getLibraryForTarget(IjModuleGraph graph, final TargetNode<?> target) {
    return (IjLibrary) getProjectElementForTarget(graph, target);
  }
}
