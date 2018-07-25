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

package com.facebook.buck.features.project.intellij;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.android.AndroidBinaryDescriptionArg;
import com.facebook.buck.android.AndroidLibraryBuilder;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.android.AndroidResourceBuilder;
import com.facebook.buck.android.AndroidResourceDescriptionArg;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.features.project.intellij.aggregation.AggregationMode;
import com.facebook.buck.features.project.intellij.aggregation.DefaultAggregationModuleFactory;
import com.facebook.buck.features.project.intellij.model.DependencyType;
import com.facebook.buck.features.project.intellij.model.IjLibrary;
import com.facebook.buck.features.project.intellij.model.IjLibraryFactory;
import com.facebook.buck.features.project.intellij.model.IjLibraryFactoryResolver;
import com.facebook.buck.features.project.intellij.model.IjModule;
import com.facebook.buck.features.project.intellij.model.IjModuleFactory;
import com.facebook.buck.features.project.intellij.model.IjModuleFactoryResolver;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.features.project.intellij.model.IjProjectElement;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.DefaultJavaPackageFinder;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.JavaTestBuilder;
import com.facebook.buck.jvm.java.JvmLibraryArg;
import com.facebook.buck.jvm.java.KeystoreBuilder;
import com.facebook.buck.jvm.java.PrebuiltJarBuilder;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Functions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Optional;
import java.util.function.Function;
import org.hamcrest.Matchers;
import org.junit.Test;

public class IjModuleGraphTest {

  @Test
  public void testModuleCoalescing() {
    TargetNode<?> guava =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third_party/java/guava:guava"))
            .build();

    TargetNode<?> base =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base:base"))
            .addDep(guava.getBuildTarget())
            .build();

    TargetNode<?> baseTests =
        JavaTestBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base:tests"))
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
    TargetNode<?> libraryTarget =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/src/com/library:library"))
            .build();

    TargetNode<?> productTarget =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/src/com/product:product"))
            .addDep(libraryTarget.getBuildTarget())
            .build();

    IjModuleGraph moduleGraph = createModuleGraph(ImmutableSet.of(libraryTarget, productTarget));

    IjModule libraryModule = getModuleForTarget(moduleGraph, libraryTarget);
    IjModule productModule = getModuleForTarget(moduleGraph, productTarget);

    assertEquals(
        ImmutableMap.of(libraryModule, DependencyType.PROD),
        moduleGraph.getDependentModulesFor(productModule));
    assertTrue(moduleGraph.getDependentModulesFor(libraryModule).isEmpty());
  }

  @Test
  public void testExportedDependencies() {
    TargetNode<?> junitReflect =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third-party/junit/reflect:reflect"))
            .build();

    TargetNode<?> junitCore =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third-party/junit/core:core"))
            .addExportedDep(junitReflect.getBuildTarget())
            .build();

    TargetNode<?> junitRule =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third-party/junit/rule:rule"))
            .addDep(junitCore.getBuildTarget())
            .build();

    IjModuleGraph moduleGraph =
        createModuleGraph(ImmutableSet.of(junitReflect, junitCore, junitRule));

    IjModule junitReflectModule = getModuleForTarget(moduleGraph, junitReflect);
    IjModule junitCoreModule = getModuleForTarget(moduleGraph, junitCore);
    IjModule junitRuleModule = getModuleForTarget(moduleGraph, junitRule);

    assertEquals(
        ImmutableMap.of(
            junitReflectModule, DependencyType.PROD,
            junitCoreModule, DependencyType.PROD),
        moduleGraph.getDependentModulesFor(junitRuleModule));
  }

  @Test
  public void testDoesNotDependOnSelfViaExportedDependencies() {
    TargetNode<?> libraryTypes =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//java/library:types"))
            .build();

    TargetNode<?> reExporter =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/reexporter:reexporter"))
            .addExportedDep(libraryTypes.getBuildTarget())
            .build();

    TargetNode<?> libraryCore =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//java/library:core"))
            .addDep(reExporter.getBuildTarget())
            .build();

    IjModuleGraph moduleGraph =
        createModuleGraph(ImmutableSet.of(libraryCore, reExporter, libraryTypes));

    IjModule libraryModule = getModuleForTarget(moduleGraph, libraryCore);
    IjModule reExporterModule = getModuleForTarget(moduleGraph, reExporter);

    assertEquals(
        ImmutableMap.of(reExporterModule, DependencyType.PROD),
        moduleGraph.getDependentModulesFor(libraryModule));
  }

  @Test
  public void testTestDependencies() {
    TargetNode<?> junitTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third-party/junit:junit"))
            .build();

    TargetNode<?> guavaTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third-party/guava:guava"))
            .build();

    TargetNode<?> hamcrestTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third-party/hamcrest:hamcrest"))
            .build();

    TargetNode<?> codeTargetNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//java/com/foo:foo"))
            .addSrc(Paths.get("java/com/foo/src/Foo.java"))
            .addDep(guavaTargetNode.getBuildTarget())
            .build();

    TargetNode<?> inlineTestTargetNode =
        JavaTestBuilder.createBuilder(BuildTargetFactory.newInstance("//java/com/foo:test"))
            .addDep(codeTargetNode.getBuildTarget())
            .addDep(junitTargetNode.getBuildTarget())
            .addSrc(Paths.get("java/com/foo/src/TestFoo.java"))
            .build();

    TargetNode<?> secondInlineTestTargetNode =
        JavaTestBuilder.createBuilder(BuildTargetFactory.newInstance("//java/com/foo:test2"))
            .addDep(hamcrestTargetNode.getBuildTarget())
            .addSrc(Paths.get("java/com/foo/test/TestFoo.java"))
            .build();

    TargetNode<?> testTargetNode =
        JavaTestBuilder.createBuilder(BuildTargetFactory.newInstance("//javatest/com/foo:foo"))
            .addDep(codeTargetNode.getBuildTarget())
            .addDep(junitTargetNode.getBuildTarget())
            .addSrc(Paths.get("javatest/com/foo/Foo.java"))
            .build();

    IjModuleGraph moduleGraph =
        createModuleGraph(
            ImmutableSet.of(
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

    assertEquals(ImmutableMap.of(), moduleGraph.getDependentModulesFor(junitModule));

    assertEquals(ImmutableMap.of(), moduleGraph.getDependentModulesFor(guavaModule));

    assertEquals(
        ImmutableMap.of(
            guavaModule, DependencyType.PROD,
            junitModule, DependencyType.PROD,
            hamcrestModule, DependencyType.TEST),
        moduleGraph.getDependentModulesFor(codeModule));

    assertEquals(
        ImmutableMap.of(
            codeModule, DependencyType.TEST,
            junitModule, DependencyType.TEST),
        moduleGraph.getDependentModulesFor(testModule));
  }

  @Test
  public void testDependenciesOnPrebuilt() {
    TargetNode<?> guavaTargetNode =
        PrebuiltJarBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third-party/guava:guava"))
            .setBinaryJar(Paths.get("third-party/guava/guava.jar"))
            .build();

    TargetNode<?> coreTargetNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//java/org/foo/core:core"))
            .addDep(guavaTargetNode.getBuildTarget())
            .build();

    IjModuleGraph moduleGraph = createModuleGraph(ImmutableSet.of(guavaTargetNode, coreTargetNode));

    IjModule coreModule = getModuleForTarget(moduleGraph, coreTargetNode);
    IjLibrary guavaElement = getLibraryForTarget(moduleGraph, guavaTargetNode);

    assertEquals(
        ImmutableMap.of(guavaElement, DependencyType.PROD), moduleGraph.getDepsFor(coreModule));
  }

  @Test
  public void testExportedDependenciesOfTests() {
    TargetNode<?> junitTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third-party/junit:junit"))
            .build();

    TargetNode<?> testLibTargetNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//javatests/lib:lib"))
            .addExportedDep(junitTargetNode.getBuildTarget())
            .build();

    TargetNode<?> testTargetNode =
        JavaTestBuilder.createBuilder(BuildTargetFactory.newInstance("//javatests/test:test"))
            .addDep(testLibTargetNode.getBuildTarget())
            .build();

    IjModuleGraph moduleGraph =
        createModuleGraph(ImmutableSet.of(junitTargetNode, testLibTargetNode, testTargetNode));

    IjModule junitModule = getModuleForTarget(moduleGraph, junitTargetNode);
    IjModule testLibModule = getModuleForTarget(moduleGraph, testLibTargetNode);
    IjModule testModule = getModuleForTarget(moduleGraph, testTargetNode);

    assertEquals(
        ImmutableMap.of(
            junitModule, DependencyType.TEST,
            testLibModule, DependencyType.TEST),
        moduleGraph.getDependentModulesFor(testModule));
  }

  @Test
  public void testExportedDependenciesDependsOnDepOfExportedDep() {
    TargetNode<?> junitReflect =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third-party/junit/reflect:reflect"))
            .build();

    TargetNode<?> junitCore =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third-party/junit/core:core"))
            .addExportedDep(junitReflect.getBuildTarget())
            .build();

    TargetNode<?> junitRule =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third-party/junit/rule:rule"))
            .addExportedDep(junitCore.getBuildTarget())
            .build();

    IjModuleGraph moduleGraph =
        createModuleGraph(ImmutableSet.of(junitReflect, junitCore, junitRule));

    IjModule junitReflectModule = getModuleForTarget(moduleGraph, junitReflect);
    IjModule junitCoreModule = getModuleForTarget(moduleGraph, junitCore);
    IjModule junitRuleModule = getModuleForTarget(moduleGraph, junitRule);

    assertEquals(
        ImmutableMap.of(
            junitReflectModule, DependencyType.PROD,
            junitCoreModule, DependencyType.PROD),
        moduleGraph.getDependentModulesFor(junitRuleModule));
  }

  @Test
  public void testExportedDependenciesDontLeak() {

    TargetNode<?> junitReflect =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third-party/junit/reflect:reflect"))
            .build();

    TargetNode<?> junitCore =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third-party/junit/core:core"))
            .addExportedDep(junitReflect.getBuildTarget())
            .build();

    TargetNode<?> junitRule =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third-party/junit/rule:rule"))
            .addDep(junitCore.getBuildTarget())
            .build();

    TargetNode<?> testRule =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//tests/feature:tests"))
            .addDep(junitRule.getBuildTarget())
            .build();

    IjModuleGraph moduleGraph =
        createModuleGraph(ImmutableSet.of(junitReflect, junitCore, junitRule, testRule));

    IjModule junitRuleModule = getModuleForTarget(moduleGraph, junitRule);
    IjModule testRuleModule = getModuleForTarget(moduleGraph, testRule);

    assertEquals(
        ImmutableMap.of(junitRuleModule, DependencyType.PROD),
        moduleGraph.getDependentModulesFor(testRuleModule));
  }

  @Test
  public void testDropDependenciesToUnsupportedTargets() {
    TargetNode<?> productKeystoreTarget =
        KeystoreBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/src/com/facebook/library:keystore"))
            .setStore(FakeSourcePath.of("store"))
            .setProperties(FakeSourcePath.of("properties"))
            .build();

    TargetNode<?> libraryJavaTarget =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/src/com/facebook/library:library"))
            .build();

    TargetNode<?> productTarget =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/src/com/facebook/product:child"))
            .addDep(productKeystoreTarget.getBuildTarget())
            .addDep(libraryJavaTarget.getBuildTarget())
            .build();

    IjModuleGraph moduleGraph =
        createModuleGraph(ImmutableSet.of(libraryJavaTarget, productTarget, productKeystoreTarget));

    IjModule libraryModule = getModuleForTarget(moduleGraph, libraryJavaTarget);
    IjModule productModule = getModuleForTarget(moduleGraph, productTarget);

    assertEquals(
        ImmutableMap.of(libraryModule, DependencyType.PROD),
        moduleGraph.getDependentModulesFor(productModule));
    assertEquals(2, moduleGraph.getModules().size());
  }

  @Test
  public void testCompiledShadow() {
    TargetNode<?> productGenruleTarget =
        GenruleBuilder.newGenruleBuilder(
                BuildTargetFactory.newInstance("//java/src/com/facebook/product:genrule"))
            .setOut("out")
            .build();

    TargetNode<?> libraryJavaTarget =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/src/com/facebook/library:library"))
            .build();

    TargetNode<?> productTarget =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/src/com/facebook/product:product"))
            .addSrc(Paths.get("java/src/com/facebook/File.java"))
            .addSrcTarget(productGenruleTarget.getBuildTarget())
            .addDep(libraryJavaTarget.getBuildTarget())
            .build();

    IjModuleGraph moduleGraph =
        createModuleGraph(
            ImmutableSet.of(productGenruleTarget, libraryJavaTarget, productTarget),
            ImmutableMap.of(productTarget, FakeSourcePath.of("buck-out/product.jar")),
            Functions.constant(Optional.empty()));

    IjModule libraryModule = getModuleForTarget(moduleGraph, libraryJavaTarget);
    IjModule productModule = getModuleForTarget(moduleGraph, productTarget);
    IjLibrary productLibrary = getLibraryForTarget(moduleGraph, productTarget);

    assertEquals(
        ImmutableMap.of(
            libraryModule, DependencyType.PROD,
            productLibrary, DependencyType.COMPILED_SHADOW),
        moduleGraph.getDepsFor(productModule));
  }

  @Test
  public void testExtraClassPath() {
    Path rDotJavaClassPath = Paths.get("buck-out/product/rdotjava_classpath");
    TargetNode<?> productTarget =
        AndroidLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/src/com/facebook/product:product"))
            .addSrc(Paths.get("java/src/com/facebook/File.java"))
            .build();

    IjModuleGraph moduleGraph =
        createModuleGraph(
            ImmutableSet.of(productTarget),
            ImmutableMap.of(productTarget, FakeSourcePath.of("buck-out/product.jar")),
            input -> {
              if (input == productTarget) {
                return Optional.of(rDotJavaClassPath);
              }
              return Optional.empty();
            });

    IjModule productModule = getModuleForTarget(moduleGraph, productTarget);
    IjLibrary rDotJavaLibrary =
        FluentIterable.from(moduleGraph.getNodes()).filter(IjLibrary.class).first().get();

    assertEquals(ImmutableSet.of(rDotJavaClassPath), productModule.getExtraClassPathDependencies());
    assertEquals(ImmutableSet.of(rDotJavaClassPath), rDotJavaLibrary.getClassPaths());
    assertEquals(
        moduleGraph.getDependentLibrariesFor(productModule),
        ImmutableMap.of(rDotJavaLibrary, DependencyType.PROD));
  }

  @Test(expected = HumanReadableException.class)
  public void testCustomAggregationModeAtZero() {
    AggregationMode.fromString("0");
    fail("Should not be able to construct an aggregator with zero minimum depth.");
  }

  @Test
  public void testModuleAggregation() {
    TargetNode<?> guava =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third_party/java/guava:guava"))
            .build();

    TargetNode<?> papaya =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:papaya")).build();

    TargetNode<?> base =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base:base"))
            .addDep(guava.getBuildTarget())
            .build();

    TargetNode<?> core =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/core:core"))
            .addDep(papaya.getBuildTarget())
            .build();

    IjModuleGraph moduleGraph =
        createModuleGraph(
            ImmutableSet.of(guava, papaya, base, core),
            ImmutableMap.of(),
            Functions.constant(Optional.empty()),
            AggregationMode.SHALLOW);

    IjModule guavaModule = getModuleForTarget(moduleGraph, guava);
    IjModule papayaModule = getModuleForTarget(moduleGraph, papaya);
    IjModule baseModule = getModuleForTarget(moduleGraph, base);
    IjModule coreModule = getModuleForTarget(moduleGraph, core);

    assertThat(baseModule, Matchers.sameInstance(coreModule));
    assertThat(
        baseModule.getModuleBasePath(), Matchers.equalTo(Paths.get("java", "com", "example")));
    assertThat(
        moduleGraph.getDepsFor(baseModule),
        Matchers.equalTo(
            ImmutableMap.<IjProjectElement, DependencyType>of(
                papayaModule, DependencyType.PROD,
                guavaModule, DependencyType.PROD)));
  }

  @Test
  public void testModuleAggregationDoesNotCoalesceAndroidResources() {
    TargetNode<?> blah1 =
        AndroidResourceBuilder.createBuilder(
                BuildTargetFactory.newInstance("//android_res/com/example/blah/blah/blah:res"))
            .build();

    TargetNode<?> blah2 =
        AndroidResourceBuilder.createBuilder(
                BuildTargetFactory.newInstance("//android_res/com/example/blah/blah/blah2:res"))
            .build();

    TargetNode<?> commonApp =
        AndroidLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base:base"))
            .addDep(blah1.getBuildTarget())
            .addDep(blah2.getBuildTarget())
            .build();

    IjModuleGraph moduleGraph =
        createModuleGraph(
            ImmutableSet.of(blah1, blah2, commonApp),
            ImmutableMap.of(),
            Functions.constant(Optional.empty()),
            AggregationMode.SHALLOW);

    IjModule blah1Module = getModuleForTarget(moduleGraph, blah1);
    IjModule blah2Module = getModuleForTarget(moduleGraph, blah2);

    assertThat(blah1Module, Matchers.not(Matchers.equalTo(blah2Module)));
    assertThat(
        blah1Module.getModuleBasePath(),
        Matchers.not(Matchers.equalTo(blah2Module.getModuleBasePath())));
  }

  @Test
  public void testModuleAggregationDoesNotCoalesceJava8() {
    TargetNode<?> blah1 =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/blah/blah:blah"))
            .setSourceLevel("1.8")
            .build();

    TargetNode<?> blah2 =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/blah/blah2:blah2"))
            .setTargetLevel("1.8")
            .build();

    TargetNode<?> commonApp =
        AndroidLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base:base"))
            .addDep(blah1.getBuildTarget())
            .addDep(blah2.getBuildTarget())
            .build();

    IjModuleGraph moduleGraph =
        createModuleGraph(
            ImmutableSet.of(blah1, blah2, commonApp),
            ImmutableMap.of(),
            Functions.constant(Optional.empty()),
            AggregationMode.SHALLOW);

    IjModule blah1Module = getModuleForTarget(moduleGraph, blah1);
    IjModule blah2Module = getModuleForTarget(moduleGraph, blah2);

    assertThat(blah1Module, Matchers.not(Matchers.equalTo(blah2Module)));
    assertThat(
        blah1Module.getModuleBasePath(),
        Matchers.not(Matchers.equalTo(blah2Module.getModuleBasePath())));
  }

  public static IjModuleGraph createModuleGraph(ImmutableSet<TargetNode<?>> targets) {
    return createModuleGraph(targets, ImmutableMap.of(), Functions.constant(Optional.empty()));
  }

  public static IjModuleGraph createModuleGraph(
      ImmutableSet<TargetNode<?>> targets,
      ImmutableMap<TargetNode<?>, SourcePath> javaLibraryPaths,
      Function<? super TargetNode<?>, Optional<Path>> rDotJavaClassPathResolver) {
    return createModuleGraph(
        targets, javaLibraryPaths, rDotJavaClassPathResolver, AggregationMode.AUTO);
  }

  public static IjModuleGraph createModuleGraph(
      ImmutableSet<TargetNode<?>> targets,
      ImmutableMap<TargetNode<?>, SourcePath> javaLibraryPaths,
      Function<? super TargetNode<?>, Optional<Path>> rDotJavaClassPathResolver,
      AggregationMode aggregationMode) {
    SourcePathResolver sourcePathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder()));
    IjLibraryFactoryResolver sourceOnlyResolver =
        new IjLibraryFactoryResolver() {
          @Override
          public Path getPath(SourcePath path) {
            return sourcePathResolver.getAbsolutePath(path);
          }

          @Override
          public Optional<SourcePath> getPathIfJavaLibrary(TargetNode<?> targetNode) {
            return Optional.ofNullable(javaLibraryPaths.get(targetNode));
          }
        };
    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    IjProjectConfig projectConfig =
        IjProjectBuckConfig.create(
            buckConfig, aggregationMode, null, "", "", false, false, false, false, true);
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    JavaPackageFinder packageFinder =
        (buckConfig == null)
            ? DefaultJavaPackageFinder.createDefaultJavaPackageFinder(Collections.emptyList())
            : buckConfig.getView(JavaBuckConfig.class).createDefaultJavaPackageFinder();
    SupportedTargetTypeRegistry typeRegistry =
        new SupportedTargetTypeRegistry(
            filesystem,
            new IjModuleFactoryResolver() {
              @Override
              public Optional<Path> getDummyRDotJavaPath(TargetNode<?> targetNode) {
                return rDotJavaClassPathResolver.apply(targetNode);
              }

              @Override
              public Path getAndroidManifestPath(
                  TargetNode<AndroidBinaryDescriptionArg> targetNode) {
                return Paths.get("TestAndroidManifest.xml");
              }

              @Override
              public Optional<Path> getLibraryAndroidManifestPath(
                  TargetNode<AndroidLibraryDescription.CoreArg> targetNode) {
                return Optional.empty();
              }

              @Override
              public Optional<Path> getProguardConfigPath(
                  TargetNode<AndroidBinaryDescriptionArg> targetNode) {
                return Optional.empty();
              }

              @Override
              public Optional<Path> getAndroidResourcePath(
                  TargetNode<AndroidResourceDescriptionArg> targetNode) {
                return Optional.empty();
              }

              @Override
              public Optional<Path> getAssetsPath(
                  TargetNode<AndroidResourceDescriptionArg> targetNode) {
                return Optional.empty();
              }

              @Override
              public Optional<Path> getAnnotationOutputPath(
                  TargetNode<? extends JvmLibraryArg> targetNode) {
                return Optional.empty();
              }

              @Override
              public Optional<Path> getCompilerOutputPath(
                  TargetNode<? extends JvmLibraryArg> targetNode) {
                return Optional.empty();
              }
            },
            projectConfig,
            packageFinder);
    IjModuleFactory moduleFactory = new DefaultIjModuleFactory(filesystem, typeRegistry);
    IjLibraryFactory libraryFactory = new DefaultIjLibraryFactory(sourceOnlyResolver);
    return IjModuleGraphFactory.from(
        filesystem,
        projectConfig,
        TargetGraphFactory.newInstance(targets),
        libraryFactory,
        moduleFactory,
        new DefaultAggregationModuleFactory(typeRegistry));
  }

  public static IjProjectElement getProjectElementForTarget(
      IjModuleGraph graph, Class<? extends IjProjectElement> type, TargetNode<?> target) {
    return FluentIterable.from(graph.getNodes())
        .filter(type)
        .firstMatch(
            input ->
                FluentIterable.from(input.getTargets())
                    .anyMatch(input1 -> input1.equals(target.getBuildTarget())))
        .get();
  }

  public static IjModule getModuleForTarget(IjModuleGraph graph, TargetNode<?> target) {
    return (IjModule) getProjectElementForTarget(graph, IjModule.class, target);
  }

  public static IjLibrary getLibraryForTarget(IjModuleGraph graph, TargetNode<?> target) {
    return (IjLibrary) getProjectElementForTarget(graph, IjLibrary.class, target);
  }
}
