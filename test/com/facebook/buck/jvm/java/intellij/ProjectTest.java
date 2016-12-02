/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.intellij;

import static com.facebook.buck.testutil.MoreAsserts.assertListEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.AndroidBinaryBuilder;
import com.facebook.buck.android.AndroidLibraryBuilder;
import com.facebook.buck.android.AndroidResourceBuilder;
import com.facebook.buck.android.NdkLibrary;
import com.facebook.buck.android.NdkLibraryBuilder;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.PathOrGlobMatcher;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.FakeJavaPackageFinder;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.JavaTestBuilder;
import com.facebook.buck.jvm.java.KeystoreBuilder;
import com.facebook.buck.jvm.java.PrebuiltJarBuilder;
import com.facebook.buck.jvm.java.intellij.SerializableModule.SourceFolder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.InMemoryBuildFileTree;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.ProjectConfig;
import com.facebook.buck.rules.ProjectConfigBuilder;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.ObjectMappers;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import org.easymock.EasyMock;
import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import javax.annotation.Nullable;

public class ProjectTest {

  private static final Path PATH_TO_GUAVA_JAR = Paths.get("third_party/guava/guava-10.0.1.jar");

  @SuppressWarnings("PMD.UnusedPrivateField")
  private BuildRule guava;

  /**
   * Creates an ActionGraph with two android_binary rules, each of which depends on the same
   * android_library. The difference between the two is that one lists Guava in its no_dx list and
   * the other does not.
   * <p>
   * The ActionGraph also includes three project_config rules: one for the android_library, and one
   * for each of the android_binary rules.
   */
  public Pair<ProjectWithModules, BuildRuleResolver> createActionGraphForTesting(
      @Nullable JavaPackageFinder javaPackageFinder) throws Exception {
    ImmutableSet.Builder<TargetNode<?, ?>> nodes = ImmutableSet.builder();

    // prebuilt_jar //third_party/guava:guava
    TargetNode<?, ?> guavaNode = PrebuiltJarBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third_party/guava:guava"))
        .setBinaryJar(PATH_TO_GUAVA_JAR)
        .build();
    nodes.add(guavaNode);

    // android_resouce android_res/base:res
    TargetNode<?, ?> androidResNode =
        AndroidResourceBuilder
            .createBuilder(BuildTargetFactory.newInstance("//android_res/base:res"))
            .setRes(new FakeSourcePath("android_res/base/res"))
            .setRDotJavaPackage("com.facebook")
            .build();
    nodes.add(androidResNode);

    // project_config android_res/base:res
    TargetNode<?, ?> projectConfigForResourceNode = ProjectConfigBuilder
        .createBuilder(
            BuildTargetFactory.newInstance("//android_res/base:project_config"))
        .setSrcRule(androidResNode.getBuildTarget())
        .setSrcRoots(ImmutableList.of("res"))
        .build();
    nodes.add(projectConfigForResourceNode);

    // java_library //java/src/com/facebook/grandchild:grandchild
    BuildTarget grandchildTarget =
        BuildTargetFactory.newInstance("//java/src/com/facebook/grandchild:grandchild");
    TargetNode<?, ?> grandchildNode = JavaLibraryBuilder
        .createBuilder(grandchildTarget)
        .addSrc(Paths.get("Grandchild.java"))
        .build();
    nodes.add(grandchildNode);

    // java_library //java/src/com/facebook/child:child
    TargetNode<?, ?> childNode = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/src/com/facebook/child:child"))
        .addSrc(Paths.get("Child.java"))
        .addDep(grandchildNode.getBuildTarget())
        .build();
    nodes.add(childNode);

    // java_library //java/src/com/facebook/exportlib:exportlib
    TargetNode<?, ?> exportLibNode = JavaLibraryBuilder
        .createBuilder(
            BuildTargetFactory.newInstance("//java/src/com/facebook/exportlib:exportlib"))
        .addSrc(Paths.get("ExportLib.java"))
        .addDep(guavaNode.getBuildTarget())
        .addExportedDep(guavaNode.getBuildTarget())
        .build();
    nodes.add(exportLibNode);

    // android_library //java/src/com/facebook/base:base
    TargetNode<?, ?> baseNode = AndroidLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/src/com/facebook/base:base"))
        .addSrc(Paths.get("Base.java"))
        .addDep(exportLibNode.getBuildTarget())
        .addDep(childNode.getBuildTarget())
        .addDep(androidResNode.getBuildTarget())
        .build();
    nodes.add(baseNode);

    // project_config //java/src/com/facebook/base:project_config
    TargetNode<?, ?> projectConfigForLibraryNode = ProjectConfigBuilder
        .createBuilder(
            BuildTargetFactory.newInstance(
                "//java/src/com/facebook/base:project_config"))
        .setSrcRule(baseNode.getBuildTarget())
        .setSrcRoots(ImmutableList.of("src", "src-gen"))
        .build();
    nodes.add(projectConfigForLibraryNode);

    TargetNode<?, ?> projectConfigForExportLibraryNode = ProjectConfigBuilder
        .createBuilder(
            BuildTargetFactory.newInstance("//java/src/com/facebook/exportlib:project_config"))
        .setSrcRule(exportLibNode.getBuildTarget())
        .setSrcRoots(ImmutableList.of("src"))
        .build();
    nodes.add(projectConfigForExportLibraryNode);

    // keystore //keystore:debug
    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//keystore:debug");
    TargetNode<?, ?> keystoreNode = KeystoreBuilder.createBuilder(keystoreTarget)
        .setStore(new FakeSourcePath("keystore/debug.keystore"))
        .setProperties(new FakeSourcePath("keystore/debug.keystore.properties"))
        .build();
    nodes.add(keystoreNode);

    // android_binary //foo:app
    ImmutableSortedSet<BuildTarget> androidBinaryRuleDepsTarget =
        ImmutableSortedSet.of(baseNode.getBuildTarget());
    TargetNode<?, ?> androidBinaryNode = AndroidBinaryBuilder.createBuilder(
        BuildTargetFactory.newInstance("//foo:app"))
            .setOriginalDeps(androidBinaryRuleDepsTarget)
            .setManifest(new FakeSourcePath("foo/AndroidManifest.xml"))
            .setKeystore(keystoreNode.getBuildTarget())
            .setBuildTargetsToExcludeFromDex(
                ImmutableSet.of(
                    BuildTargetFactory.newInstance("//third_party/guava:guava")))
            .build();
    nodes.add(androidBinaryNode);

    // project_config //foo:project_config
    TargetNode<?, ?> projectConfigUsingNoDxNode = ProjectConfigBuilder
        .createBuilder(BuildTargetFactory.newInstance("//foo:project_config"))
        .setSrcRule(androidBinaryNode.getBuildTarget())
        .build();
    nodes.add(projectConfigUsingNoDxNode);

    // android_binary //bar:app
    ImmutableSortedSet<BuildTarget> barAppBuildRuleDepsTarget =
        ImmutableSortedSet.of(baseNode.getBuildTarget());
    TargetNode<?, ?> barAppBuildNode = AndroidBinaryBuilder.createBuilder(
        BuildTargetFactory.newInstance("//bar:app"))
            .setOriginalDeps(barAppBuildRuleDepsTarget)
            .setManifest(new FakeSourcePath("foo/AndroidManifest.xml"))
            .setKeystore(keystoreNode.getBuildTarget())
            .build();
    nodes.add(barAppBuildNode);

    // project_config //bar:project_config
    TargetNode<?, ?> projectConfigNode = ProjectConfigBuilder
        .createBuilder(BuildTargetFactory.newInstance("//bar:project_config"))
        .setSrcRule(barAppBuildNode.getBuildTarget())
        .build();
    nodes.add(projectConfigNode);

    TargetGraph targetGraph = TargetGraphFactory.newInstance(nodes.build());
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());

    guava = ruleResolver.requireRule(guavaNode.getBuildTarget());
    ProjectConfig projectConfigForExportLibrary = (ProjectConfig) ruleResolver.requireRule(
        projectConfigForExportLibraryNode.getBuildTarget());
    ProjectConfig projectConfigForLibrary = (ProjectConfig) ruleResolver.requireRule(
        projectConfigForLibraryNode.getBuildTarget());
    ProjectConfig projectConfigForResource = (ProjectConfig) ruleResolver.requireRule(
        projectConfigForResourceNode.getBuildTarget());
    ProjectConfig projectConfigUsingNoDx = (ProjectConfig) ruleResolver.requireRule(
        projectConfigUsingNoDxNode.getBuildTarget());
    ProjectConfig projectConfig = (ProjectConfig) ruleResolver.requireRule(
        projectConfigNode.getBuildTarget());

    return new Pair<>(getModulesForActionGraph(
        ruleResolver,
        ImmutableSortedSet.of(
            projectConfigForExportLibrary,
            projectConfigForLibrary,
            projectConfigForResource,
            projectConfigUsingNoDx,
            projectConfig),
        javaPackageFinder,
        null  /* intellijConfig */),
        ruleResolver);
  }

  @Test
  public void testGenerateRelativeGenPath() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path basePathOfModule = Paths.get("android_res/com/facebook/gifts/");
    Path expectedRelativePathToGen =
        Paths.get("../../../../buck-out/android/android_res/com/facebook/gifts/gen");
    assertEquals(
        expectedRelativePathToGen,
        Project.generateRelativeGenPath(filesystem, basePathOfModule));
  }

  /**
   * This is an important test that verifies that the {@code no_dx} argument for an
   * {@code android_binary} is handled appropriately when generating an IntelliJ project.
   */
  @Test
  public void testProject() throws Exception {
    JavaPackageFinder javaPackageFinder = EasyMock.createMock(JavaPackageFinder.class);
    EasyMock
        .expect(javaPackageFinder.findJavaPackage(Paths.get("foo/module_foo.iml")))
        .andReturn("");
    EasyMock
        .expect(javaPackageFinder.findJavaPackage(Paths.get("bar/module_bar.iml")))
        .andReturn("");
    EasyMock.replay(javaPackageFinder);

    Pair<ProjectWithModules, BuildRuleResolver> projectWithModules =
        createActionGraphForTesting(javaPackageFinder);
    Project project = projectWithModules.getFirst().project;
    BuildRuleResolver resolver = projectWithModules.getSecond();
    List<SerializableModule> modules = projectWithModules.getFirst().modules;

    assertEquals("Should be one module for the java_library, one for the android_library, " +
                 "one module for the android_resource, and one for each android_binary",
        5,
        modules.size());

    // Check the values of the module that corresponds to the android_library.
    SerializableModule javaLibraryModule = modules.get(4);
    assertSame(
        getRuleByBuildTarget("//java/src/com/facebook/exportlib:exportlib", resolver),
        javaLibraryModule.srcRule);
    assertEquals("module_java_src_com_facebook_exportlib", javaLibraryModule.name);
    assertEquals(
        Paths.get("java/src/com/facebook/exportlib/module_java_src_com_facebook_exportlib.iml"),
        javaLibraryModule.pathToImlFile);
    assertListEquals(
        ImmutableList.of(SerializableModule.SourceFolder.SRC),
        javaLibraryModule.sourceFolders);

    // Check the dependencies.
    SerializableDependentModule inheritedJdk = SerializableDependentModule.newInheritedJdk();
    SerializableDependentModule guavaAsProvidedDep = SerializableDependentModule.newLibrary(
        guava.getBuildTarget(), "buck_out_gen_third_party_guava___guava___guava_10_0_1_jar");
    guavaAsProvidedDep.scope = "PROVIDED";

    assertListEquals(
        ImmutableList.of(
            SerializableDependentModule.newSourceFolder(),
            guavaAsProvidedDep,
            SerializableDependentModule.newStandardJdk(
                Optional.empty(),
                Optional.empty())),
        javaLibraryModule.getDependencies());

    // Check the values of the module that corresponds to the android_library.
    SerializableModule androidLibraryModule = modules.get(3);
    assertSame(
        getRuleByBuildTarget("//java/src/com/facebook/base:base", resolver),
        androidLibraryModule.srcRule);
    assertEquals("module_java_src_com_facebook_base", androidLibraryModule.name);
    assertEquals(
        Paths.get("java/src/com/facebook/base/module_java_src_com_facebook_base.iml"),
        androidLibraryModule.pathToImlFile);
    assertListEquals(
        ImmutableList.of(
            SerializableModule.SourceFolder.SRC,
            new SourceFolder("file://$MODULE_DIR$/src-gen", false /* isTestSource */),
            SerializableModule.SourceFolder.GEN),
        androidLibraryModule.sourceFolders);
    assertTrue(androidLibraryModule.hasAndroidFacet);
    assertTrue(androidLibraryModule.isAndroidLibraryProject);
    assertEquals(null, androidLibraryModule.proguardConfigPath);
    assertEquals(null, androidLibraryModule.resFolder);

    // Check the dependencies.
    SerializableDependentModule androidResourceAsProvidedDep =
        SerializableDependentModule.newModule(
            BuildTargetFactory.newInstance("//android_res/base:res"),
            "module_android_res_base");

    SerializableDependentModule childAsProvidedDep = SerializableDependentModule.newModule(
        BuildTargetFactory.newInstance("//java/src/com/facebook/child:child"),
        "module_java_src_com_facebook_child");

    SerializableDependentModule exportDepsAsProvidedDep = SerializableDependentModule.newModule(
        BuildTargetFactory.newInstance("//java/src/com/facebook/exportlib:exportlib"),
        "module_java_src_com_facebook_exportlib");

    assertListEquals(
        ImmutableList.of(
            SerializableDependentModule.newSourceFolder(),
            guavaAsProvidedDep,
            androidResourceAsProvidedDep,
            childAsProvidedDep,
            exportDepsAsProvidedDep,
            inheritedJdk),
        androidLibraryModule.getDependencies());

    // Check the values of the module that corresponds to the android_binary that uses no_dx.
    SerializableModule androidResourceModule = modules.get(0);
    assertSame(
        getRuleByBuildTarget("//android_res/base:res", resolver),
        androidResourceModule.srcRule);

    assertEquals(Paths.get("res"), androidResourceModule.resFolder);

    // Check the values of the module that corresponds to the android_binary that uses no_dx.
    SerializableModule androidBinaryModuleNoDx = modules.get(2);
    assertSame(getRuleByBuildTarget("//foo:app", resolver), androidBinaryModuleNoDx.srcRule);
    assertEquals("module_foo", androidBinaryModuleNoDx.name);
    assertEquals(Paths.get("foo/module_foo.iml"), androidBinaryModuleNoDx.pathToImlFile);

    assertListEquals(
        ImmutableList.of(SerializableModule.SourceFolder.GEN),
        androidBinaryModuleNoDx.sourceFolders);
    assertTrue(androidBinaryModuleNoDx.hasAndroidFacet);
    assertFalse(androidBinaryModuleNoDx.isAndroidLibraryProject);
    assertEquals(null, androidBinaryModuleNoDx.proguardConfigPath);
    assertEquals(null, androidBinaryModuleNoDx.resFolder);
    assertEquals(Paths.get("../keystore/debug.keystore"), androidBinaryModuleNoDx.keystorePath);

    // Check the moduleDependencies.
    SerializableDependentModule grandchildAsProvidedDep = SerializableDependentModule.newModule(
        BuildTargetFactory.newInstance("//java/src/com/facebook/grandchild:grandchild"),
        "module_java_src_com_facebook_grandchild");

    SerializableDependentModule androidLibraryDep = SerializableDependentModule.newModule(
        androidLibraryModule.srcRule.getBuildTarget(), "module_java_src_com_facebook_base");
    assertEquals(
        ImmutableList.of(
            SerializableDependentModule.newSourceFolder(),
            guavaAsProvidedDep,
            androidLibraryDep,
            androidResourceAsProvidedDep,
            childAsProvidedDep,
            exportDepsAsProvidedDep,
            grandchildAsProvidedDep,
            inheritedJdk),
        androidBinaryModuleNoDx.getDependencies());

    // Check the values of the module that corresponds to the android_binary with an empty no_dx.
    SerializableModule androidBinaryModuleEmptyNoDx = modules.get(1);
    assertSame(getRuleByBuildTarget("//bar:app", resolver), androidBinaryModuleEmptyNoDx.srcRule);
    assertEquals("module_bar", androidBinaryModuleEmptyNoDx.name);
    assertEquals(Paths.get("bar/module_bar.iml"), androidBinaryModuleEmptyNoDx.pathToImlFile);
    assertListEquals(
        ImmutableList.of(SerializableModule.SourceFolder.GEN),
        androidBinaryModuleEmptyNoDx.sourceFolders);
    assertTrue(androidBinaryModuleEmptyNoDx.hasAndroidFacet);
    assertFalse(androidBinaryModuleEmptyNoDx.isAndroidLibraryProject);
    assertEquals(null, androidBinaryModuleEmptyNoDx.proguardConfigPath);
    assertEquals(null, androidBinaryModuleEmptyNoDx.resFolder);
    assertEquals(
        Paths.get("../keystore/debug.keystore"),
        androidBinaryModuleEmptyNoDx.keystorePath);

    // Check the moduleDependencies.
    SerializableDependentModule guavaAsCompiledDep = SerializableDependentModule.newLibrary(
        guava.getBuildTarget(), "buck_out_gen_third_party_guava___guava___guava_10_0_1_jar");
    assertEquals("Important that Guava is listed as a 'COMPILED' dependency here because it is " +
        "only listed as a 'PROVIDED' dependency earlier.",
        ImmutableList.of(
            SerializableDependentModule.newSourceFolder(),
            guavaAsCompiledDep,
            androidLibraryDep,
            androidResourceAsProvidedDep,
            childAsProvidedDep,
            exportDepsAsProvidedDep,
            grandchildAsProvidedDep,
            inheritedJdk),
        androidBinaryModuleEmptyNoDx.getDependencies());

    // Check that the correct data was extracted to populate the .idea/libraries directory.
    BuildRule guava = getRuleByBuildTarget("//third_party/guava:guava", resolver);
    assertSame(guava, Iterables.getOnlyElement(project.getLibraryJars()));
  }

  @Test
  public void testPrebuiltJarIncludesDeps() throws Exception {
    // Build up a the graph that corresponds to:
    //
    // android_library(
    //   name = 'example',
    //   deps = [
    //     ':easymock',
    //   ],
    // )
    //
    // prebuilt_jar(
    //   name = 'easymock',
    //   binary_jar = 'easymock.jar',
    //   deps = [
    //     ':cglib',
    //     ':objenesis',
    //   ],
    // )
    //
    // prebuilt_jar(
    //   name = 'cglib',
    //   binary_jar = 'cglib.jar',
    // )
    //
    // prebuilt_jar(
    //   name = 'objenesis',
    //   binary_jar = 'objenesis.jar',
    // )
    //
    // project_config(
    //   src_target = ':example',
    // )
    TargetNode<?, ?> cglibNode = PrebuiltJarBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third_party/java/easymock:cglib"))
        .setBinaryJar(Paths.get("third_party/java/easymock/cglib.jar"))
        .build();

    TargetNode<?, ?> objenesisNode = PrebuiltJarBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third_party/java/easymock:objenesis"))
        .setBinaryJar(Paths.get("third_party/java/easymock/objenesis.jar"))
        .build();

    TargetNode<?, ?> easymockNode = PrebuiltJarBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third_party/java/easymock:easymock"))
        .setBinaryJar(Paths.get("third_party/java/easymock/easymock.jar"))
        .addDep(cglibNode.getBuildTarget())
        .addDep(objenesisNode.getBuildTarget())
        .build();

    BuildTarget easyMockExampleTarget = BuildTargetFactory.newInstance(
        "//third_party/java/easymock:example");
    TargetNode<?, ?> mockNode = AndroidLibraryBuilder.createBuilder(easyMockExampleTarget)
        .addDep(easymockNode.getBuildTarget())
        .build();

    TargetNode<?, ?> projectConfigNode = ProjectConfigBuilder
        .createBuilder(
            BuildTargetFactory.newInstance("//third_party/java/easymock:project_config"))
        .setSrcRule(mockNode.getBuildTarget())
        .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        cglibNode,
        objenesisNode,
        easymockNode,
        mockNode,
        projectConfigNode);
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());

    ProjectConfig projectConfig =
        (ProjectConfig) ruleResolver.requireRule(projectConfigNode.getBuildTarget());

    ProjectWithModules projectWithModules = getModulesForActionGraph(
        ruleResolver,
        ImmutableSortedSet.of(projectConfig),
        null /* javaPackageFinder */,
        null /* intellijConfig */);
    List<SerializableModule> modules = projectWithModules.modules;

    // Verify that the single Module that is created transitively includes all JAR files.
    assertEquals("Should be one module for the android_library", 1, modules.size());
    SerializableModule androidLibraryModule = Iterables.getOnlyElement(modules);
    assertThat(
        androidLibraryModule.getDependencies(),
        Matchers.containsInAnyOrder(
            SerializableDependentModule.newSourceFolder(),
            SerializableDependentModule.newLibrary(
                easymockNode.getBuildTarget(),
                "buck_out_gen_third_party_java_easymock___easymock___easymock_jar"),
            SerializableDependentModule.newLibrary(
                cglibNode.getBuildTarget(),
                "buck_out_gen_third_party_java_easymock___cglib___cglib_jar"),
            SerializableDependentModule.newLibrary(
                objenesisNode.getBuildTarget(),
                "buck_out_gen_third_party_java_easymock___objenesis___objenesis_jar"),
            SerializableDependentModule.newInheritedJdk()));
  }

  @Test
  public void testIfModuleIsBothTestAndCompileDepThenTreatAsCompileDep() throws Exception {
    // Create a java_library() and a java_test() that both depend on Guava.
    // When they are part of the same project_config() rule, then the resulting module should
    // include Guava as scope="COMPILE" in IntelliJ.
    TargetNode<?, ?> guavaNode = PrebuiltJarBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third_party/java/guava:guava"))
        .setBinaryJar(Paths.get("third_party/java/guava.jar"))
        .build();

    TargetNode<?, ?> baseNode = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:base"))
        .addDep(guavaNode.getBuildTarget())
        .build();

    TargetNode<?, ?> testNode = JavaTestBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:tests"))
        .addDep(guavaNode.getBuildTarget())
        .build();

    TargetNode<?, ?> projectConfigNode = ProjectConfigBuilder
        .createBuilder(
            BuildTargetFactory.newInstance("//java/com/example/base:project_config"))
        .setSrcRule(baseNode.getBuildTarget())
        .setTestRule(testNode.getBuildTarget())
        .setTestRoots(ImmutableList.of("tests"))
        .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        guavaNode,
        baseNode,
        testNode,
        projectConfigNode);
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());

    ProjectConfig projectConfig =
        (ProjectConfig) ruleResolver.requireRule(projectConfigNode.getBuildTarget());

    ProjectWithModules projectWithModules = getModulesForActionGraph(
        ruleResolver,
        ImmutableSortedSet.of(projectConfig),
        null /* javaPackageFinder */,
        null /*intellijConfig */);
    List<SerializableModule> modules = projectWithModules.modules;
    assertEquals(1, modules.size());
    SerializableModule comExampleBaseModule = Iterables.getOnlyElement(modules);

    assertListEquals(
        ImmutableList.of(
            SerializableDependentModule.newSourceFolder(),
            SerializableDependentModule.newLibrary(
                guavaNode.getBuildTarget(),
                "buck_out_gen_third_party_java_guava___guava___guava_jar"),
            SerializableDependentModule.newStandardJdk(
                Optional.empty(),
                Optional.empty())),
        comExampleBaseModule.getDependencies());
  }

  /**
   * In the context of Robolectric, httpcore-4.0.1.jar needs to be loaded before the android.jar
   * associated with the Android SDK. Both httpcore-4.0.1.jar and android.jar define
   * org.apache.http.params.BasicHttpParams; however, only httpcore-4.0.1.jar contains a real
   * implementation of BasicHttpParams whereas android.jar contains a stub implementation of
   * BasicHttpParams.
   * <p>
   * One way to fix this problem would be to "tag" httpcore-4.0.1.jar to indicate that it must
   * appear before the Android SDK (or anything that transitively depends on the Android SDK) when
   * listing dependencies for IntelliJ. This would be a giant kludge to the prebuilt_jar rule, so
   * instead we just list jars before modules within an &lt;orderEntry scope="TEST"/> or an
   * &lt;orderEntry scope="COMPILE"/> group.
   */
  @Test
  public void testThatJarsAreListedBeforeModules() throws Exception {
    TargetNode<?, ?> supportV4Node = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/android/support/v4:v4"))
        .build();

    TargetNode<?, ?> httpCoreNode = PrebuiltJarBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third_party/java/httpcore:httpcore"))
        .setBinaryJar(Paths.get("httpcore-4.0.1.jar"))
        .build();

    // The support-v4 library is loaded as a java_library() rather than a prebuilt_jar() because it
    // contains our local changes to the library.
    BuildTarget robolectricTarget =
        BuildTargetFactory.newInstance("//third_party/java/robolectric:robolectric");
    TargetNode<?, ?> robolectricNode = JavaLibraryBuilder
        .createBuilder(robolectricTarget)
        .addDep(supportV4Node.getBuildTarget())
        .addDep(httpCoreNode.getBuildTarget())
        .build();

    TargetNode<?, ?> projectConfigNode = ProjectConfigBuilder
        .createBuilder(
            BuildTargetFactory.newInstance("//third_party/java/robolectric:project_config"))
        .setSrcRule(robolectricNode.getBuildTarget())
        .setSrcRoots(ImmutableList.of("src/main/java"))
        .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        supportV4Node,
        httpCoreNode,
        robolectricNode,
        projectConfigNode);
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());

    ProjectConfig projectConfig =
        (ProjectConfig) ruleResolver.requireRule(projectConfigNode.getBuildTarget());

    ProjectWithModules projectWithModules = getModulesForActionGraph(
        ruleResolver,
        ImmutableSortedSet.of(projectConfig),
        null /* javaPackageFinder */,
        null /* intellijConfig */);
    List<SerializableModule> modules = projectWithModules.modules;
    assertEquals("Should be one module for the android_library", 1, modules.size());
    SerializableModule robolectricModule = Iterables.getOnlyElement(modules);

    assertListEquals(
        "It is imperative that httpcore-4.0.1.jar be listed before the support v4 library, " +
        "or else when robolectric is listed as a dependency, " +
        "org.apache.http.params.BasicHttpParams will be loaded from android.jar instead of " +
        "httpcore-4.0.1.jar.",
        ImmutableList.of(
            SerializableDependentModule.newSourceFolder(),
            SerializableDependentModule.newLibrary(
                httpCoreNode.getBuildTarget(),
                "buck_out_gen_third_party_java_httpcore___httpcore___httpcore_4_0_1_jar"),
            SerializableDependentModule.newModule(
                supportV4Node.getBuildTarget(), "module_java_com_android_support_v4"),
            SerializableDependentModule.newStandardJdk(
                Optional.empty(),
                Optional.empty())),
        robolectricModule.getDependencies());
  }

  @Test
  public void testCreatePathToProjectDotPropertiesFileForModule() {
    SerializableModule rootModule = new SerializableModule(null /* buildRule */,
        BuildTargetFactory.newInstance("//:project_config"));
    rootModule.pathToImlFile = Paths.get("fb4a.iml");
    assertEquals("project.properties", Project.createPathToProjectDotPropertiesFileFor(rootModule));

    SerializableModule someModule = new SerializableModule(null /* buildRule */,
        BuildTargetFactory.newInstance("//java/com/example/base:project_config"));
    someModule.pathToImlFile = Paths.get("java/com/example/base/base.iml");
    assertEquals("java/com/example/base/project.properties",
        Project.createPathToProjectDotPropertiesFileFor(someModule));
  }

  /**
   * A project_config()'s src_roots argument can be {@code []}, or a non-empty array.
   * Each of these should be treated differently.
   */
  @Test
  public void testSrcRoots() throws Exception {
    // Create a project_config() with src_roots=[].
    BuildRuleResolver ruleResolver2 =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildRule baseBuildRule = AndroidLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:base"))
        .build(ruleResolver2);
    ProjectConfig inPackageProjectConfig = (ProjectConfig) ProjectConfigBuilder
        .createBuilder(
            BuildTargetFactory.newInstance("//java/com/example/base:project_config"))
        .setSrcRule(baseBuildRule.getBuildTarget())
        .setSrcRoots(ImmutableList.of())
        .build(ruleResolver2);

    // Verify that the correct source folders are created.
    JavaPackageFinder javaPackageFinder = EasyMock.createMock(JavaPackageFinder.class);
    EasyMock
        .expect(
            javaPackageFinder.findJavaPackage(
                Paths.get("java/com/example/base/module_java_com_example_base.iml")))
        .andReturn("com.example.base");
    EasyMock.replay(javaPackageFinder);
    ProjectWithModules projectWithModules2 = getModulesForActionGraph(
        ruleResolver2,
        ImmutableSortedSet.of(inPackageProjectConfig),
        javaPackageFinder,
        null /* intellijConfig */);
    EasyMock.verify(javaPackageFinder);
    assertEquals(1, projectWithModules2.modules.size());
    SerializableModule moduleWithPackagePrefix = projectWithModules2.modules.get(0);
    assertListEquals(
        "The current directory should be a source tmp with a package prefix " +
            "as well as the gen/ directory.",
        ImmutableList.of(
            new SourceFolder("file://$MODULE_DIR$", false /* isTestSource */, "com.example.base"),
            SerializableModule.SourceFolder.GEN),
        moduleWithPackagePrefix.sourceFolders);

    // Create a project_config() with src_roots=['src'].
    BuildRuleResolver ruleResolver3 =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildRule baseBuildRule3 = AndroidLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:base"))
        .build(ruleResolver3);
    ProjectConfig hasSrcFolderProjectConfig = (ProjectConfig) ProjectConfigBuilder
        .createBuilder(
            BuildTargetFactory.newInstance("//java/com/example/base:project_config"))
        .setSrcRule(baseBuildRule3.getBuildTarget())
        .setSrcRoots(ImmutableList.of("src"))
        .build(ruleResolver3);
    ProjectWithModules projectWithModules3 = getModulesForActionGraph(
        ruleResolver3,
        ImmutableSortedSet.of(hasSrcFolderProjectConfig),
        null /* javaPackageFinder */,
        null /* intellijConfig */);

    // Verify that the correct source folders are created.
    assertEquals(1, projectWithModules3.modules.size());
    SerializableModule moduleHasSrcFolder = projectWithModules3.modules.get(0);
    assertListEquals(
        "Both src/ and gen/ should be source folders.",
        ImmutableList.of(
            new SourceFolder("file://$MODULE_DIR$/src", false /* isTestSource */),
            SerializableModule.SourceFolder.GEN),
        moduleHasSrcFolder.sourceFolders);
  }

  @Test
  public void testIntellijJdkConfig() throws Exception {
    IntellijConfig intellijConfig = new IntellijConfig(
        FakeBuckConfig.builder().setSections(
            ImmutableMap.of("intellij", ImmutableMap.of("jdk_name", "1.8")))
            .build()
    );
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildRule baseBuildRule = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:base"))
        .build(ruleResolver);
    ProjectConfig packageProjectConfig = (ProjectConfig) ProjectConfigBuilder
        .createBuilder(
            BuildTargetFactory.newInstance("//java/com/example/base:project_config"))
        .setSrcRule(baseBuildRule.getBuildTarget())
        .setSrcRoots(ImmutableList.of())
        .build(ruleResolver);

    ProjectWithModules projectWithJdkOverride = getModulesForActionGraph(
        ruleResolver,
        ImmutableSortedSet.of(packageProjectConfig),
        null /* javaPackageFinder */,
        intellijConfig);
    SerializableModule moduleWithJdkOverride = projectWithJdkOverride.modules.get(0);
    assertListEquals(
        ImmutableList.of(
            SerializableDependentModule.newSourceFolder(),
            SerializableDependentModule.newStandardJdk(Optional.of("1.8"), Optional.of("JavaSDK"))),
        moduleWithJdkOverride.getDependencies());

    ProjectWithModules projectWithDefaultJdk = getModulesForActionGraph(
        ruleResolver,
        ImmutableSortedSet.of(packageProjectConfig),
        null /* javaPackageFinder */,
        null /* intellijConfig */);
    SerializableModule moduleWithDefaultJdk = projectWithDefaultJdk.modules.get(0);
    assertListEquals(
        ImmutableList.of(
            SerializableDependentModule.newSourceFolder(),
            SerializableDependentModule.newStandardJdk(Optional.of("1.7"), Optional.of("JavaSDK"))),
        moduleWithDefaultJdk.getDependencies());
  }

  private static class ProjectWithModules {
    private final Project project;
    private final ImmutableList<SerializableModule> modules;
    private ProjectWithModules(Project project, ImmutableList<SerializableModule> modules) {
      this.project = project;
      this.modules = modules;
    }
  }

  private ProjectWithModules getModulesForActionGraph(
      BuildRuleResolver ruleResolver,
      ImmutableSortedSet<ProjectConfig> projectConfigs,
      @Nullable JavaPackageFinder javaPackageFinder,
      @Nullable IntellijConfig intellijConfig) throws IOException {
    if (javaPackageFinder == null) {
      javaPackageFinder = new FakeJavaPackageFinder();
    }
    if (intellijConfig == null) {
      intellijConfig = new IntellijConfig(FakeBuckConfig.builder().build());
    }

    // Create the Project.
    ExecutionContext executionContext = TestExecutionContext.newInstance();
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    Properties keystoreProperties = new Properties();
    keystoreProperties.put("key.alias", "androiddebugkey");
    keystoreProperties.put("key.store.password", "android");
    keystoreProperties.put("key.alias.password", "android");
    try (OutputStream output =
             projectFilesystem.newFileOutputStream(
                 Paths.get("keystore/debug.keystore.properties"))) {
      keystoreProperties.store(output, "");
    }

    ImmutableMap<Path, String> basePathToAliasMap = ImmutableMap.of();
    Project project = new Project(
        new SourcePathResolver(ruleResolver),
        projectConfigs,
        basePathToAliasMap,
        javaPackageFinder,
        executionContext,
        new InMemoryBuildFileTree(
            Iterables.transform(
                ruleResolver.getBuildRules(),
                HasBuildTarget::getBuildTarget)),
        projectFilesystem,
        /* pathToDefaultAndroidManifest */ Optional.empty(),
        intellijConfig,
        /* pathToPostProcessScript */ Optional.empty(),
        "python",
        ObjectMappers.newDefaultInstance(),
        true);

    // Execute Project's business logic.
    List<SerializableModule> modules = new ArrayList<>(project.createModulesForProjectConfigs());

    return new ProjectWithModules(project, ImmutableList.copyOf(modules));
  }

  private static BuildRule getRuleByBuildTarget(String buildTarget, BuildRuleResolver resolver)
      throws NoSuchBuildTargetException {
    BuildRule rule = resolver.requireRule(BuildTargetFactory.newInstance(buildTarget));
    Preconditions.checkNotNull(rule, "No rule for %s", buildTarget);
    return rule;
  }

  @Test
  public void testNdkLibraryHasCorrectPath() throws Exception {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    // Build up a the graph that corresponds to:
    //
    // ndk_library(
    //   name = 'foo-jni'
    // )
    //
    // project_config(
    //   src_target = ':foo-jni',
    // )

    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildTarget fooJni = BuildTargetFactory.newInstance("//third_party/java/foo/jni:foo-jni");
    NdkLibrary ndkLibrary =
        (NdkLibrary) new NdkLibraryBuilder(fooJni)
            .build(ruleResolver, projectFilesystem);

    ProjectConfig ndkProjectConfig = (ProjectConfig) ProjectConfigBuilder
        .createBuilder(
            BuildTargetFactory.newInstance(
                "//third_party/java/foo/jni:project_config"))
        .setSrcRule(ndkLibrary.getBuildTarget())
        .build(ruleResolver);

    ProjectWithModules projectWithModules = getModulesForActionGraph(
        ruleResolver,
        ImmutableSortedSet.of(ndkProjectConfig),
        null /* javaPackageFinder */,
        null /* intellijConfig */);
    List<SerializableModule> modules = projectWithModules.modules;

    assertEquals("Should be one module for the ndk_library.", 1, modules.size());
    SerializableModule androidLibraryModule = Iterables.getOnlyElement(modules);
    assertListEquals(ImmutableList.of(
            SerializableDependentModule.newSourceFolder(),
            SerializableDependentModule.newInheritedJdk()),
        androidLibraryModule.getDependencies());
    assertEquals(
        Paths.get(String.format("../../../../%s", ndkLibrary.getLibraryPath())),
        androidLibraryModule.nativeLibs);
  }

  @Test
  public void testDoNotIgnoreAllOfBuckOut() {
    SourcePathResolver resolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
     );
    ProjectFilesystem projectFilesystem =
        FakeProjectFilesystem.createJavaOnlyFilesystem("/opt/src/buck");

    BuildTarget buildTarget = BuildTarget.builder(
        projectFilesystem.getRootPath(),
        "//",
        "base").build();
    BuildRule buildRule = new FakeBuildRule(buildTarget, resolver);
    SerializableModule module = new SerializableModule(buildRule, buildTarget);

    Project.addRootExcludes(module, buildRule, projectFilesystem);

    ImmutableSortedSet.Builder<SourceFolder> expectedExcludeFolders =
        ImmutableSortedSet.orderedBy(SerializableModule.ALPHABETIZER);
    expectedExcludeFolders.add(
        new SourceFolder("file://$MODULE_DIR$/buck-out/bin", /* isTestSource */ false));
    expectedExcludeFolders.add(
        new SourceFolder("file://$MODULE_DIR$/buck-out/log", /* isTestSource */ false));
    expectedExcludeFolders.add(
        new SourceFolder("file://$MODULE_DIR$/buck-out/tmp", /* isTestSource */ false));
    for (Path ignorePath : FluentIterable.from(projectFilesystem.getIgnorePaths())
        .filter(input -> input.getType() == PathOrGlobMatcher.Type.PATH)
        .transform(PathOrGlobMatcher::getPath)) {
      if (!ignorePath.equals(projectFilesystem.getBuckPaths().getBuckOut()) &&
          !ignorePath.equals(projectFilesystem.getBuckPaths().getGenDir())) {
        expectedExcludeFolders.add(
            new SourceFolder("file://$MODULE_DIR$/" + MorePaths.pathWithUnixSeparators(ignorePath),
                /* isTestSource */ false));
      }
    }
    assertEquals(
        "Specific subfolders of buck-out should be excluded rather than all of buck-out.",
        expectedExcludeFolders.build(),
        module.excludeFolders);
  }
}
