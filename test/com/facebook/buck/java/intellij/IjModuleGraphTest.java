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
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.AndroidLibraryBuilder;
import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.java.JavaTestBuilder;
import com.facebook.buck.java.KeystoreBuilder;
import com.facebook.buck.java.PrebuiltJarBuilder;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.hamcrest.Matchers;
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
  public void testDoesNotDependOnSelfViaExportedDependencies() {
    TargetNode<?> libraryTypes = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/library:types"))
        .build();

    TargetNode<?> reExporter = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/reexporter:reexporter"))
        .addExportedDep(libraryTypes.getBuildTarget())
        .build();

    TargetNode<?> libraryCore = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/library:core"))
        .addDep(reExporter.getBuildTarget())
        .build();

    IjModuleGraph moduleGraph = createModuleGraph(
        ImmutableSet.of(
            libraryCore,
            reExporter,
            libraryTypes));

    IjModule libraryModule = getModuleForTarget(moduleGraph, libraryCore);
    IjModule reExporterModule = getModuleForTarget(moduleGraph, reExporter);

    assertEquals(
        ImmutableMap.of(reExporterModule, IjModuleGraph.DependencyType.PROD),
        moduleGraph.getDependentModulesFor(libraryModule));
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

  @Test
  public void testCompiledShadow() {
    TargetNode<?> productGenruleTarget = GenruleBuilder
        .newGenruleBuilder(
            BuildTargetFactory.newInstance("//java/src/com/facebook/product:genrule"))
        .build();

    TargetNode<?> libraryJavaTarget = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/src/com/facebook/library:library"))
        .build();

    TargetNode<?> productTarget = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/src/com/facebook/product:product"))
        .addSrc(Paths.get("java/src/com/facebook/File.java"))
        .addSrcTarget(productGenruleTarget.getBuildTarget())
        .addDep(libraryJavaTarget.getBuildTarget())
        .build();

    IjModuleGraph moduleGraph = createModuleGraph(
        ImmutableSet.of(
            productGenruleTarget,
            libraryJavaTarget,
            productTarget),
        ImmutableMap.<TargetNode<?>, Path>of(productTarget, Paths.get("buck-out/product.jar")),
        Functions.constant(Optional.<Path>absent()));

    IjModule libraryModule = getModuleForTarget(moduleGraph, libraryJavaTarget);
    IjModule productModule = getModuleForTarget(moduleGraph, productTarget);
    IjLibrary productLibrary = getLibraryForTarget(moduleGraph, productTarget);

    assertEquals(
        ImmutableMap.of(
            libraryModule, IjModuleGraph.DependencyType.PROD,
            productLibrary, IjModuleGraph.DependencyType.COMPILED_SHADOW),
        moduleGraph.getDepsFor(productModule));
  }

  @Test
  public void testExtraClassPath() {
    final Path rDotJavaClassPath = Paths.get("buck-out/product/rdotjava_classpath");
    final TargetNode<?> productTarget = AndroidLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/src/com/facebook/product:product"))
        .addSrc(Paths.get("java/src/com/facebook/File.java"))
        .build();

    IjModuleGraph moduleGraph = createModuleGraph(
        ImmutableSet.<TargetNode<?>>of(productTarget),
        ImmutableMap.<TargetNode<?>, Path>of(productTarget, Paths.get("buck-out/product.jar")),
        new Function<TargetNode<?>, Optional<Path>>() {
          @Override
          public Optional<Path> apply(TargetNode<?> input) {
            if (input == productTarget) {
              return Optional.of(rDotJavaClassPath);
            }
            return Optional.absent();
          }
        });

    IjModule productModule = getModuleForTarget(moduleGraph, productTarget);
    IjLibrary rDotJavaLibrary = FluentIterable.from(moduleGraph.getNodes())
        .filter(IjLibrary.class)
        .first()
        .get();

    assertEquals(ImmutableSet.of(rDotJavaClassPath), productModule.getExtraClassPathDependencies());
    assertEquals(ImmutableSet.of(rDotJavaClassPath), rDotJavaLibrary.getClassPaths());
    assertEquals(moduleGraph.getDependentLibrariesFor(productModule),
        ImmutableMap.of(rDotJavaLibrary, IjModuleGraph.DependencyType.PROD));
  }


  public void doSingleAggregationModePathFunctionTest(
      IjModuleGraph.AggregationMode aggregationMode,
      int graphSize,
      boolean expectTrimmed) {

    ImmutableList<Path> originalPaths = ImmutableList.of(
        Paths.get("a", "b", "c", "d", "e"),
        Paths.get("a", "b", "c", "d"),
        Paths.get("a", "b", "c"),
        Paths.get("a", "b"),
        Paths.get("a"),
        Paths.get(""));
    ImmutableList<Path> trimmedPaths = ImmutableList.of(
        Paths.get("a", "b", "c"),
        Paths.get("a", "b", "c"),
        Paths.get("a", "b", "c"),
        Paths.get("a", "b"),
        Paths.get("a"),
        Paths.get(""));

    Function<Path, Path> transform = aggregationMode.getBasePathTransform(graphSize);
    assertThat(
        FluentIterable.from(originalPaths).transform(transform).toList(),
        Matchers.equalTo(expectTrimmed ? trimmedPaths : originalPaths));
  }

  @Test
  public void testAggregationModePathFunction() {

    doSingleAggregationModePathFunctionTest(
        IjModuleGraph.AggregationMode.NONE,
        10,
        /* expectTrimmed */ false);
    doSingleAggregationModePathFunctionTest(
        IjModuleGraph.AggregationMode.NONE,
        10000,
        /* expectTrimmed */ false);

    doSingleAggregationModePathFunctionTest(
        IjModuleGraph.AggregationMode.SHALLOW,
        10,
        /* expectTrimmed */ true);
    doSingleAggregationModePathFunctionTest(
        IjModuleGraph.AggregationMode.SHALLOW,
        10000,
        /* expectTrimmed */ true);

    doSingleAggregationModePathFunctionTest(
        IjModuleGraph.AggregationMode.AUTO,
        10,
        /* expectTrimmed */ false);
    doSingleAggregationModePathFunctionTest(
        IjModuleGraph.AggregationMode.AUTO,
        10000,
        /* expectTrimmed */ true);
  }

  @Test
  public void testModuleAggregation() {
    TargetNode<?> guava = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third_party/java/guava:guava"))
        .build();

    TargetNode<?> papaya = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//:papaya"))
        .build();

    TargetNode<?> base = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:base"))
        .addDep(guava.getBuildTarget())
        .build();

    TargetNode<?> core = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/core:core"))
        .addDep(papaya.getBuildTarget())
        .build();

    IjModuleGraph moduleGraph = createModuleGraph(
        ImmutableSet.of(guava, papaya, base, core),
        ImmutableMap.<TargetNode<?>, Path>of(),
        Functions.constant(Optional.<Path>absent()),
        IjModuleGraph.AggregationMode.SHALLOW);

    IjModule guavaModule = getModuleForTarget(moduleGraph, guava);
    IjModule papayaModule = getModuleForTarget(moduleGraph, papaya);
    IjModule baseModule = getModuleForTarget(moduleGraph, base);
    IjModule coreModule = getModuleForTarget(moduleGraph, core);

    assertThat(baseModule, Matchers.sameInstance(coreModule));
    assertThat(
        baseModule.getModuleBasePath(),
        Matchers.equalTo(Paths.get("java", "com", "example")));
    assertThat(
        moduleGraph.getDepsFor(baseModule),
        Matchers.equalTo(ImmutableMap.<IjProjectElement, IjModuleGraph.DependencyType>of(
                papayaModule, IjModuleGraph.DependencyType.PROD,
                guavaModule, IjModuleGraph.DependencyType.PROD)));
  }

  public static IjModuleGraph createModuleGraph(ImmutableSet<TargetNode<?>> targets) {
    return createModuleGraph(targets, ImmutableMap.<TargetNode<?>, Path>of(),
        Functions.constant(Optional.<Path>absent()));
  }

  public static IjModuleGraph createModuleGraph(
      ImmutableSet<TargetNode<?>> targets,
      final ImmutableMap<TargetNode<?>, Path> javaLibraryPaths,
      Function<? super TargetNode<?>, Optional<Path>> rDotJavaClassPathResolver) {
    return createModuleGraph(targets,
        javaLibraryPaths,
        rDotJavaClassPathResolver,
        IjModuleGraph.AggregationMode.AUTO);
  }

  public static IjModuleGraph createModuleGraph(
      ImmutableSet<TargetNode<?>> targets,
      final ImmutableMap<TargetNode<?>, Path> javaLibraryPaths,
      Function<? super TargetNode<?>, Optional<Path>> rDotJavaClassPathResolver,
      IjModuleGraph.AggregationMode aggregationMode) {
    final SourcePathResolver sourcePathResolver = new SourcePathResolver(new BuildRuleResolver());
    DefaultIjLibraryFactory.IjLibraryFactoryResolver sourceOnlyResolver =
        new DefaultIjLibraryFactory.IjLibraryFactoryResolver() {
          @Override
          public Path getPath(SourcePath path) {
            return sourcePathResolver.getPath(path);
          }

          @Override
          public Optional<Path> getPathIfJavaLibrary(TargetNode<?> targetNode) {
            return Optional.fromNullable(javaLibraryPaths.get(targetNode));
          }
        };
    IjModuleFactory moduleFactory = new IjModuleFactory(rDotJavaClassPathResolver);
    IjLibraryFactory libraryFactory = new DefaultIjLibraryFactory(sourceOnlyResolver);
    return IjModuleGraph.from(
        TargetGraphFactory.newInstance(targets),
        libraryFactory,
        moduleFactory,
        aggregationMode);
  }

  public static IjProjectElement getProjectElementForTarget(
      IjModuleGraph graph,
      Class<? extends IjProjectElement> type,
      final TargetNode<?> target) {
    return FluentIterable.from(graph.getNodes())
        .filter(type)
        .firstMatch(
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

  public static IjModule getModuleForTarget(IjModuleGraph graph, final TargetNode<?> target) {
    return (IjModule) getProjectElementForTarget(graph, IjModule.class, target);
  }

  public static IjLibrary getLibraryForTarget(IjModuleGraph graph, final TargetNode<?> target) {
    return (IjLibrary) getProjectElementForTarget(graph, IjLibrary.class, target);
  }
}
