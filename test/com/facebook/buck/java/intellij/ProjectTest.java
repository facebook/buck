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

package com.facebook.buck.java.intellij;

import static com.facebook.buck.testutil.MoreAsserts.assertListEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.facebook.buck.android.AndroidBinary;
import com.facebook.buck.android.AndroidBinaryBuilder;
import com.facebook.buck.android.AndroidLibraryBuilder;
import com.facebook.buck.android.AndroidResourceRuleBuilder;
import com.facebook.buck.android.NdkLibrary;
import com.facebook.buck.android.NdkLibraryBuilder;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.FakeJavaPackageFinder;
import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.java.JavaPackageFinder;
import com.facebook.buck.java.JavaTestBuilder;
import com.facebook.buck.java.KeystoreBuilder;
import com.facebook.buck.java.PrebuiltJarBuilder;
import com.facebook.buck.java.intellij.Project.SourceFolder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.InMemoryBuildFileTree;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.ProjectConfig;
import com.facebook.buck.rules.ProjectConfigBuilder;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.testutil.BuckTestConstant;
import com.facebook.buck.testutil.RuleMap;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import org.easymock.EasyMock;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
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
  public ProjectWithModules createActionGraphForTesting(
      @Nullable JavaPackageFinder javaPackageFinder) throws IOException {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    // java_library //buck-out/android/com/facebook:R
    BuildRule rRule = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//buck-out/android/com/facebook:R"))
        .addSrc(Paths.get("buck-out/android/com/facebook/R.java"))
        .build(ruleResolver);

    // prebuilt_jar //third_party/guava:guava
    guava = PrebuiltJarBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third_party/guava:guava"))
        .setBinaryJar(PATH_TO_GUAVA_JAR)
        .build(ruleResolver);

    // android_resouce android_res/base:res
    BuildRule androidResRule = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setResolver(new SourcePathResolver(ruleResolver))
            .setBuildTarget(BuildTargetFactory.newInstance("//android_res/base:res"))
            .setRes(Paths.get("android_res/base/res"))
            .setRDotJavaPackage("com.facebook")
            .build());

    // project_config android_res/base:res
    ProjectConfig projectConfigForResource = (ProjectConfig) ProjectConfigBuilder
        .newProjectConfigRuleBuilder(
            BuildTargetFactory.newInstance("//android_res/base:project_config"))
        .setSrcRule(androidResRule.getBuildTarget())
        .setSrcRoots(ImmutableList.of("res"))
        .build(ruleResolver);

    // java_library //java/src/com/facebook/grandchild:grandchild
    BuildTarget grandchildTarget =
        BuildTargetFactory.newInstance("//java/src/com/facebook/grandchild:grandchild");
    BuildRule grandchild = JavaLibraryBuilder
        .createBuilder(grandchildTarget)
        .addSrc(Paths.get("Grandchild.java"))
        .build(ruleResolver);

    // java_library //java/src/com/facebook/child:child
    BuildRule childRule = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/src/com/facebook/child:child"))
        .addSrc(Paths.get("Child.java"))
        .addDep(grandchild.getBuildTarget())
        .build(ruleResolver);

    // java_library //java/src/com/facebook/exportlib:exportlib
    BuildRule exportLib = JavaLibraryBuilder
        .createBuilder(
            BuildTargetFactory.newInstance("//java/src/com/facebook/exportlib:exportlib"))
        .addSrc(Paths.get("ExportLib.java"))
        .addDep(guava.getBuildTarget())
        .addExportedDep(guava.getBuildTarget())
        .build(ruleResolver);

    // android_library //java/src/com/facebook/base:base
    BuildRule baseRule = AndroidLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/src/com/facebook/base:base"))
        .addSrc(Paths.get("Base.java"))
        .addDep(rRule.getBuildTarget())
        .addDep(exportLib.getBuildTarget())
        .addDep(childRule.getBuildTarget())
        .addDep(androidResRule.getBuildTarget())
        .build(ruleResolver);

    // project_config //java/src/com/facebook/base:project_config
    ProjectConfig projectConfigForLibrary = (ProjectConfig) ProjectConfigBuilder
        .newProjectConfigRuleBuilder(
            BuildTargetFactory.newInstance(
                "//java/src/com/facebook/base:project_config"))
        .setSrcRule(baseRule.getBuildTarget())
        .setSrcRoots(ImmutableList.of("src", "src-gen"))
        .build(ruleResolver);

    ProjectConfig projectConfigForExportLibrary = (ProjectConfig) ProjectConfigBuilder
        .newProjectConfigRuleBuilder(
            BuildTargetFactory.newInstance("//java/src/com/facebook/exportlib:project_config"))
        .setSrcRule(exportLib.getBuildTarget())
        .setSrcRoots(ImmutableList.of("src")).build(ruleResolver);

    // keystore //keystore:debug
    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//keystore:debug");
    BuildRule keystore = KeystoreBuilder.createBuilder(keystoreTarget)
        .setStore(Paths.get("keystore/debug.keystore"))
        .setProperties(Paths.get("keystore/debug.keystore.properties"))
        .build(ruleResolver);

    // android_binary //foo:app
    ImmutableSortedSet<BuildTarget> androidBinaryRuleDepsTarget =
        ImmutableSortedSet.of(baseRule.getBuildTarget());
    AndroidBinary androidBinaryRule = (AndroidBinary) AndroidBinaryBuilder.createBuilder(
            BuildTargetFactory.newInstance("//foo:app"))
            .setOriginalDeps(androidBinaryRuleDepsTarget)
            .setManifest(new TestSourcePath("foo/AndroidManifest.xml"))
            .setTarget("Google Inc.:Google APIs:16")
            .setKeystore(keystore.getBuildTarget())
            .setBuildTargetsToExcludeFromDex(
                ImmutableSet.of(
                    BuildTargetFactory.newInstance("//third_party/guava:guava")))
            .build(ruleResolver);

    // project_config //foo:project_config
    ProjectConfig projectConfigUsingNoDx = (ProjectConfig) ProjectConfigBuilder
        .newProjectConfigRuleBuilder(BuildTargetFactory.newInstance("//foo:project_config"))
        .setSrcRule(androidBinaryRule.getBuildTarget())
        .build(ruleResolver);

    // android_binary //bar:app
    ImmutableSortedSet<BuildTarget> barAppBuildRuleDepsTarget =
        ImmutableSortedSet.of(baseRule.getBuildTarget());
    AndroidBinary barAppBuildRule = (AndroidBinary) AndroidBinaryBuilder.createBuilder(
            BuildTargetFactory.newInstance("//bar:app"))
            .setOriginalDeps(barAppBuildRuleDepsTarget)
            .setManifest(new TestSourcePath("foo/AndroidManifest.xml"))
            .setTarget("Google Inc.:Google APIs:16")
            .setKeystore(keystore.getBuildTarget())
            .build(ruleResolver);

    // project_config //bar:project_config
    ProjectConfig projectConfig = (ProjectConfig) ProjectConfigBuilder
        .newProjectConfigRuleBuilder(BuildTargetFactory.newInstance("//bar:project_config"))
        .setSrcRule(barAppBuildRule.getBuildTarget())
        .build(ruleResolver);

    return getModulesForActionGraph(
        ruleResolver,
        ImmutableSortedSet.of(
            projectConfigForExportLibrary,
            projectConfigForLibrary,
            projectConfigForResource,
            projectConfigUsingNoDx,
            projectConfig),
        javaPackageFinder);
  }

  @Test
  public void testGenerateRelativeGenPath() {
    Path basePathOfModule = Paths.get("android_res/com/facebook/gifts/");
    Path expectedRelativePathToGen =
        Paths.get("../../../../buck-out/android/android_res/com/facebook/gifts/gen");
    assertEquals(
        expectedRelativePathToGen, Project.generateRelativeGenPath(basePathOfModule));
  }

  /**
   * This is an important test that verifies that the {@code no_dx} argument for an
   * {@code android_binary} is handled appropriately when generating an IntelliJ project.
   */
  @Test
  public void testProject() throws IOException {
    JavaPackageFinder javaPackageFinder = EasyMock.createMock(JavaPackageFinder.class);
    EasyMock
        .expect(javaPackageFinder.findJavaPackage(Paths.get("foo/module_foo.iml")))
        .andReturn("");
    EasyMock
        .expect(javaPackageFinder.findJavaPackage(Paths.get("bar/module_bar.iml")))
        .andReturn("");
    EasyMock.replay(javaPackageFinder);

    ProjectWithModules projectWithModules = createActionGraphForTesting(javaPackageFinder);
    Project project = projectWithModules.project;
    ActionGraph actionGraph = project.getActionGraph();
    List<Module> modules = projectWithModules.modules;

    assertEquals("Should be one module for the java_library, one for the android_library, " +
                 "one module for the android_resource, and one for each android_binary",
        5,
        modules.size());

    // Check the values of the module that corresponds to the android_library.
    Module javaLibraryModule = modules.get(4);
    assertSame(getRuleById("//java/src/com/facebook/exportlib:exportlib", actionGraph),
        javaLibraryModule.srcRule);
    assertEquals("module_java_src_com_facebook_exportlib", javaLibraryModule.name);
    assertEquals(
        Paths.get("java/src/com/facebook/exportlib/module_java_src_com_facebook_exportlib.iml"),
        javaLibraryModule.pathToImlFile);
    assertListEquals(
        ImmutableList.of(SourceFolder.SRC),
        javaLibraryModule.sourceFolders);

    // Check the dependencies.
    DependentModule inheritedJdk = DependentModule.newInheritedJdk();
    DependentModule guavaAsProvidedDep = DependentModule.newLibrary(
        guava.getBuildTarget(), "third_party_guava_guava_10_0_1_jar");
    guavaAsProvidedDep.scope = "PROVIDED";

    assertListEquals(
        ImmutableList.of(
            DependentModule.newSourceFolder(),
            guavaAsProvidedDep,
            DependentModule.newStandardJdk()),
        javaLibraryModule.dependencies);

    // Check the values of the module that corresponds to the android_library.
    Module androidLibraryModule = modules.get(3);
    assertSame(getRuleById("//java/src/com/facebook/base:base", actionGraph),
        androidLibraryModule.srcRule);
    assertEquals("module_java_src_com_facebook_base", androidLibraryModule.name);
    assertEquals(
        Paths.get("java/src/com/facebook/base/module_java_src_com_facebook_base.iml"),
        androidLibraryModule.pathToImlFile);
    assertListEquals(
        ImmutableList.of(
            SourceFolder.SRC,
            new SourceFolder("file://$MODULE_DIR$/src-gen", false /* isTestSource */),
            SourceFolder.GEN),
        androidLibraryModule.sourceFolders);
    assertEquals(Boolean.TRUE, androidLibraryModule.hasAndroidFacet);
    assertEquals(Boolean.TRUE, androidLibraryModule.isAndroidLibraryProject);
    assertEquals(null, androidLibraryModule.proguardConfigPath);
    assertEquals(null, androidLibraryModule.resFolder);

    // Check the dependencies.
    DependentModule androidResourceAsProvidedDep = DependentModule.newModule(
        BuildTargetFactory.newInstance("//android_res/base:res"),
        "module_android_res_base");

    DependentModule childAsProvidedDep = DependentModule.newModule(
        BuildTargetFactory.newInstance("//java/src/com/facebook/child:child"),
        "module_java_src_com_facebook_child");

    DependentModule exportDepsAsProvidedDep = DependentModule.newModule(
        BuildTargetFactory.newInstance("//java/src/com/facebook/exportlib:exportlib"),
        "module_java_src_com_facebook_exportlib");

    assertListEquals(
        ImmutableList.of(
            DependentModule.newSourceFolder(),
            guavaAsProvidedDep,
            androidResourceAsProvidedDep,
            childAsProvidedDep,
            exportDepsAsProvidedDep,
            inheritedJdk),
        androidLibraryModule.dependencies);

    // Check the values of the module that corresponds to the android_binary that uses no_dx.
    Module androidResourceModule = modules.get(0);
    assertSame(getRuleById("//android_res/base:res", actionGraph), androidResourceModule.srcRule);

    assertEquals(Paths.get("res"), androidResourceModule.resFolder);

    // Check the values of the module that corresponds to the android_binary that uses no_dx.
    Module androidBinaryModuleNoDx = modules.get(2);
    assertSame(getRuleById("//foo:app", actionGraph), androidBinaryModuleNoDx.srcRule);
    assertEquals("module_foo", androidBinaryModuleNoDx.name);
    assertEquals(Paths.get("foo/module_foo.iml"), androidBinaryModuleNoDx.pathToImlFile);

    assertListEquals(ImmutableList.of(SourceFolder.GEN), androidBinaryModuleNoDx.sourceFolders);
    assertEquals(Boolean.TRUE, androidBinaryModuleNoDx.hasAndroidFacet);
    assertEquals(Boolean.FALSE, androidBinaryModuleNoDx.isAndroidLibraryProject);
    assertEquals(null, androidBinaryModuleNoDx.proguardConfigPath);
    assertEquals(null, androidBinaryModuleNoDx.resFolder);
    assertEquals(Paths.get("../keystore/debug.keystore"), androidBinaryModuleNoDx.keystorePath);

    // Check the dependencies.
    DependentModule grandchildAsProvidedDep = DependentModule.newModule(
        BuildTargetFactory.newInstance("//java/src/com/facebook/grandchild:grandchild"),
        "module_java_src_com_facebook_grandchild");

    DependentModule androidLibraryDep = DependentModule.newModule(
        androidLibraryModule.srcRule.getBuildTarget(), "module_java_src_com_facebook_base");
    assertEquals(
        ImmutableList.of(
            DependentModule.newSourceFolder(),
            guavaAsProvidedDep,
            androidLibraryDep,
            androidResourceAsProvidedDep,
            childAsProvidedDep,
            exportDepsAsProvidedDep,
            grandchildAsProvidedDep,
            inheritedJdk),
        androidBinaryModuleNoDx.dependencies);

    // Check the values of the module that corresponds to the android_binary with an empty no_dx.
    Module androidBinaryModuleEmptyNoDx = modules.get(1);
    assertSame(getRuleById("//bar:app", actionGraph), androidBinaryModuleEmptyNoDx.srcRule);
    assertEquals("module_bar", androidBinaryModuleEmptyNoDx.name);
    assertEquals(Paths.get("bar/module_bar.iml"), androidBinaryModuleEmptyNoDx.pathToImlFile);
    assertListEquals(
        ImmutableList.of(SourceFolder.GEN), androidBinaryModuleEmptyNoDx.sourceFolders);
    assertEquals(Boolean.TRUE, androidBinaryModuleEmptyNoDx.hasAndroidFacet);
    assertEquals(Boolean.FALSE, androidBinaryModuleEmptyNoDx.isAndroidLibraryProject);
    assertEquals(null, androidBinaryModuleEmptyNoDx.proguardConfigPath);
    assertEquals(null, androidBinaryModuleEmptyNoDx.resFolder);
    assertEquals(
        Paths.get("../keystore/debug.keystore"),
        androidBinaryModuleEmptyNoDx.keystorePath);

    // Check the dependencies.
    DependentModule guavaAsCompiledDep = DependentModule.newLibrary(
        guava.getBuildTarget(), "third_party_guava_guava_10_0_1_jar");
    assertEquals("Important that Guava is listed as a 'COMPILED' dependency here because it is " +
        "only listed as a 'PROVIDED' dependency earlier.",
        ImmutableList.of(
            DependentModule.newSourceFolder(),
            guavaAsCompiledDep,
            androidLibraryDep,
            androidResourceAsProvidedDep,
            childAsProvidedDep,
            exportDepsAsProvidedDep,
            grandchildAsProvidedDep,
            inheritedJdk),
        androidBinaryModuleEmptyNoDx.dependencies);

    // Check that the correct data was extracted to populate the .idea/libraries directory.
    BuildRule guava = getRuleById("//third_party/guava:guava", actionGraph);
    assertSame(guava, Iterables.getOnlyElement(project.getLibraryJars()));
  }

  @Test
  public void testPrebuiltJarIncludesDeps() throws IOException {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

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
    BuildRule cglib = PrebuiltJarBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third_party/java/easymock:cglib"))
        .setBinaryJar(Paths.get("third_party/java/easymock/cglib.jar"))
        .build(ruleResolver);

    BuildRule objenesis = PrebuiltJarBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third_party/java/easymock:objenesis"))
        .setBinaryJar(Paths.get("third_party/java/easymock/objenesis.jar"))
        .build(ruleResolver);

    BuildRule easymock = PrebuiltJarBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third_party/java/easymock:easymock"))
        .setBinaryJar(Paths.get("third_party/java/easymock/easymock.jar"))
        .addDep(cglib.getBuildTarget())
        .addDep(objenesis.getBuildTarget())
        .build(ruleResolver);

    BuildTarget easyMockExampleTarget = BuildTargetFactory.newInstance(
        "//third_party/java/easymock:example");
    BuildRule mockRule = AndroidLibraryBuilder.createBuilder(easyMockExampleTarget)
        .addDep(easymock.getBuildTarget())
        .build(ruleResolver);

    ProjectConfig projectConfig = (ProjectConfig) ProjectConfigBuilder
        .newProjectConfigRuleBuilder(
            BuildTargetFactory.newInstance("//third_party/java/easymock:project_config"))
        .setSrcRule(mockRule.getBuildTarget())
        .build(ruleResolver);

    ProjectWithModules projectWithModules = getModulesForActionGraph(
        ruleResolver,
        ImmutableSortedSet.of(projectConfig),
        null /* javaPackageFinder */);
    List<Module> modules = projectWithModules.modules;

    // Verify that the single Module that is created transitively includes all JAR files.
    assertEquals("Should be one module for the android_library", 1, modules.size());
    Module androidLibraryModule = Iterables.getOnlyElement(modules);
    assertListEquals(ImmutableList.of(
            DependentModule.newSourceFolder(),
            DependentModule.newLibrary(
                easymock.getBuildTarget(),
                "third_party_java_easymock_easymock_jar"),
            DependentModule.newLibrary(
                cglib.getBuildTarget(),
                "third_party_java_easymock_cglib_jar"),
            DependentModule.newLibrary(
                objenesis.getBuildTarget(),
                "third_party_java_easymock_objenesis_jar"),
            DependentModule.newInheritedJdk()),
        androidLibraryModule.dependencies);
  }

  @Test
  public void testIfModuleIsBothTestAndCompileDepThenTreatAsCompileDep() throws IOException {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    // Create a java_library() and a java_test() that both depend on Guava.
    // When they are part of the same project_config() rule, then the resulting module should
    // include Guava as scope="COMPILE" in IntelliJ.
    BuildRule guava = PrebuiltJarBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third_party/java/guava:guava"))
        .setBinaryJar(Paths.get("third_party/java/guava.jar"))
        .build(ruleResolver);

    BuildRule baseBuildRule = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:base"))
        .addDep(guava.getBuildTarget())
        .build(ruleResolver);

    BuildRule testBuildRule = JavaTestBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:tests"))
        .addDep(guava.getBuildTarget())
        .build(ruleResolver);

    ProjectConfig projectConfig = (ProjectConfig) ProjectConfigBuilder
        .newProjectConfigRuleBuilder(
            BuildTargetFactory.newInstance("//java/com/example/base:project_config"))
        .setSrcRule(baseBuildRule.getBuildTarget())
        .setTestRule(testBuildRule.getBuildTarget())
        .setTestRoots(ImmutableList.of("tests"))
        .build(ruleResolver);

    ProjectWithModules projectWithModules = getModulesForActionGraph(
        ruleResolver,
        ImmutableSortedSet.of(projectConfig),
        null /* javaPackageFinder */);
    List<Module> modules = projectWithModules.modules;
    assertEquals(1, modules.size());
    Module comExampleBaseModule = Iterables.getOnlyElement(modules);

    assertListEquals(ImmutableList.of(
            DependentModule.newSourceFolder(),
            DependentModule.newLibrary(guava.getBuildTarget(), "third_party_java_guava_jar"),
            DependentModule.newStandardJdk()),
        comExampleBaseModule.dependencies);
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
  public void testThatJarsAreListedBeforeModules() throws IOException {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    BuildRule supportV4 = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/android/support/v4:v4"))
        .build(ruleResolver);

    BuildRule httpCore = PrebuiltJarBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third_party/java/httpcore:httpcore"))
        .setBinaryJar(Paths.get("httpcore-4.0.1.jar"))
        .build(ruleResolver);

    // The support-v4 library is loaded as a java_library() rather than a prebuilt_jar() because it
    // contains our local changes to the library.
    BuildTarget robolectricTarget =
        BuildTargetFactory.newInstance("//third_party/java/robolectric:robolectric");
    BuildRule robolectricRule = JavaLibraryBuilder
        .createBuilder(robolectricTarget)
        .addDep(supportV4.getBuildTarget())
        .addDep(httpCore.getBuildTarget())
        .build(ruleResolver);

    ProjectConfig projectConfig = (ProjectConfig) ProjectConfigBuilder
        .newProjectConfigRuleBuilder(
            BuildTargetFactory.newInstance("//third_party/java/robolectric:project_config"))
        .setSrcRule(robolectricRule.getBuildTarget())
        .setSrcRoots(ImmutableList.of("src/main/java"))
        .build(ruleResolver);

    ProjectWithModules projectWithModules = getModulesForActionGraph(
        ruleResolver,
        ImmutableSortedSet.of(projectConfig),
        null /* javaPackageFinder */);
    List<Module> modules = projectWithModules.modules;
    assertEquals("Should be one module for the android_library", 1, modules.size());
    Module robolectricModule = Iterables.getOnlyElement(modules);

    assertListEquals(
        "It is imperative that httpcore-4.0.1.jar be listed before the support v4 library, " +
        "or else when robolectric is listed as a dependency, " +
        "org.apache.http.params.BasicHttpParams will be loaded from android.jar instead of " +
        "httpcore-4.0.1.jar.",
        ImmutableList.of(
            DependentModule.newSourceFolder(),
            DependentModule.newLibrary(httpCore.getBuildTarget(), "httpcore_4_0_1_jar"),
            DependentModule.newModule(
                supportV4.getBuildTarget(), "module_java_com_android_support_v4"),
            DependentModule.newStandardJdk()),
        robolectricModule.dependencies);
  }

  @Test
  public void testCreatePathToProjectDotPropertiesFileForModule() {
    Module rootModule = new Module(null /* buildRule */,
        BuildTargetFactory.newInstance("//:project_config"));
    rootModule.pathToImlFile = Paths.get("fb4a.iml");
    assertEquals("project.properties", Project.createPathToProjectDotPropertiesFileFor(rootModule));

    Module someModule = new Module(null /* buildRule */,
        BuildTargetFactory.newInstance("//java/com/example/base:project_config"));
    someModule.pathToImlFile = Paths.get("java/com/example/base/base.iml");
    assertEquals("java/com/example/base/project.properties",
        Project.createPathToProjectDotPropertiesFileFor(someModule));
  }

  /**
   * A project_config()'s src_roots argument can be {@code None}, {@code []}, or a non-empty array.
   * Each of these should be treated differently.
   */
  @Test
  public void testSrcRoots() throws IOException {
    // Create a project_config() with src_roots=None.
    BuildRuleResolver ruleResolver1 = new BuildRuleResolver();

    BuildRule resBuildRule = ruleResolver1.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setResolver(new SourcePathResolver(ruleResolver1))
            .setBuildTarget(BuildTargetFactory.newInstance("//resources/com/example:res"))
            .build());
    ProjectConfig projectConfigNullSrcRoots = (ProjectConfig) ProjectConfigBuilder
        .newProjectConfigRuleBuilder(
            BuildTargetFactory.newInstance("//resources/com/example:project_config"))
        .setSrcRule(resBuildRule.getBuildTarget())
        .setSrcRoots(null)
        .build(ruleResolver1);
    ProjectWithModules projectWithModules1 = getModulesForActionGraph(
        ruleResolver1,
        ImmutableSortedSet.of(projectConfigNullSrcRoots),
        null /* javaPackageFinder */);

    // Verify that the correct source folders are created.
    assertEquals(1, projectWithModules1.modules.size());
    Module moduleNoJavaSource = projectWithModules1.modules.get(0);
    assertListEquals(
        "Only source tmp should be gen/ when setSrcRoots(null) is specified.",
        ImmutableList.of(SourceFolder.GEN),
        moduleNoJavaSource.sourceFolders);

    // Create a project_config() with src_roots=[].
    BuildRuleResolver ruleResolver2 = new BuildRuleResolver();
    BuildRule baseBuildRule = AndroidLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:base"))
        .build(ruleResolver2);
    ProjectConfig inPackageProjectConfig = (ProjectConfig) ProjectConfigBuilder
        .newProjectConfigRuleBuilder(
            BuildTargetFactory.newInstance("//java/com/example/base:project_config"))
        .setSrcRule(baseBuildRule.getBuildTarget())
        .setSrcRoots(ImmutableList.<String>of())
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
        javaPackageFinder);
    EasyMock.verify(javaPackageFinder);
    assertEquals(1, projectWithModules2.modules.size());
    Module moduleWithPackagePrefix = projectWithModules2.modules.get(0);
    assertListEquals(
        "The current directory should be a source tmp with a package prefix " +
            "as well as the gen/ directory.",
        ImmutableList.of(
            new SourceFolder("file://$MODULE_DIR$", false /* isTestSource */, "com.example.base"),
            SourceFolder.GEN),
        moduleWithPackagePrefix.sourceFolders);

    // Create a project_config() with src_roots=['src'].
    BuildRuleResolver ruleResolver3 = new BuildRuleResolver();
    BuildRule baseBuildRule3 = AndroidLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:base"))
        .build(ruleResolver3);
    ProjectConfig hasSrcFolderProjectConfig = (ProjectConfig) ProjectConfigBuilder
        .newProjectConfigRuleBuilder(
            BuildTargetFactory.newInstance("//java/com/example/base:project_config"))
        .setSrcRule(baseBuildRule3.getBuildTarget())
        .setSrcRoots(ImmutableList.of("src"))
        .build(ruleResolver3);
    ProjectWithModules projectWithModules3 = getModulesForActionGraph(
        ruleResolver3,
        ImmutableSortedSet.of(hasSrcFolderProjectConfig),
        null /* javaPackageFinder */);

    // Verify that the correct source folders are created.
    assertEquals(1, projectWithModules3.modules.size());
    Module moduleHasSrcFolder = projectWithModules3.modules.get(0);
    assertListEquals(
        "Both src/ and gen/ should be source folders.",
        ImmutableList.of(
            new SourceFolder("file://$MODULE_DIR$/src", false /* isTestSource */),
            SourceFolder.GEN),
        moduleHasSrcFolder.sourceFolders);
  }

  private static class ProjectWithModules {
    private final Project project;
    private final ImmutableList<Module> modules;
    private ProjectWithModules(Project project, ImmutableList<Module> modules) {
      this.project = project;
      this.modules = modules;
    }
  }

  private ProjectWithModules getModulesForActionGraph(
      BuildRuleResolver ruleResolver,
      ImmutableSortedSet<ProjectConfig> projectConfigs,
      @Nullable JavaPackageFinder javaPackageFinder) throws IOException {
    if (javaPackageFinder == null) {
      javaPackageFinder = new FakeJavaPackageFinder();
    }

    ActionGraph actionGraph = RuleMap.createGraphFromBuildRules(ruleResolver);

    // Create the Project.
    ExecutionContext executionContext = EasyMock.createMock(ExecutionContext.class);
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);

    Properties keystoreProperties = new Properties();
    keystoreProperties.put("key.alias", "androiddebugkey");
    keystoreProperties.put("key.store.password", "android");
    keystoreProperties.put("key.alias.password", "android");
    EasyMock.expect(projectFilesystem.readPropertiesFile(
        Paths.get("keystore/debug.keystore.properties")))
        .andReturn(keystoreProperties).anyTimes();

    ImmutableMap<Path, String> basePathToAliasMap = ImmutableMap.of();
    Project project = new Project(
        new SourcePathResolver(ruleResolver),
        projectConfigs,
        actionGraph,
        basePathToAliasMap,
        javaPackageFinder,
        executionContext,
        new InMemoryBuildFileTree(
            Iterables.transform(
                actionGraph.getNodes(),
                BuildTarget.TO_TARGET)),
        projectFilesystem,
        /* pathToDefaultAndroidManifest */ Optional.<String>absent(),
        new IntellijConfig(new FakeBuckConfig()),
        /* pathToPostProcessScript */ Optional.<String>absent(),
        BuckTestConstant.PYTHON_INTERPRETER,
        new ObjectMapper(),
        true);

    // Execute Project's business logic.
    EasyMock.replay(executionContext, projectFilesystem);
    List<Module> modules = project.createModulesForProjectConfigs();
    EasyMock.verify(executionContext, projectFilesystem);

    return new ProjectWithModules(project, ImmutableList.copyOf(modules));
  }

  private static BuildRule getRuleById(String id, ActionGraph actionGraph) {
    String[] parts = id.split(":");
    BuildRule rule = actionGraph.findBuildRuleByTarget(
        BuildTarget.builder(parts[0], parts[1]).build());
    Preconditions.checkNotNull(rule, "No rule for %s", id);
    return rule;
  }

  @Test
  public void testNdkLibraryHasCorrectPath() throws IOException {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);

    // Build up a the graph that corresponds to:
    //
    // ndk_library(
    //   name = 'foo-jni'
    // )
    //
    // project_config(
    //   src_target = ':foo-jni',
    // )

    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    BuildTarget fooJni = BuildTargetFactory.newInstance("//third_party/java/foo/jni:foo-jni");
    NdkLibrary ndkLibrary = NdkLibraryBuilder.createNdkLibrary(fooJni,
        pathResolver,
        ruleResolver,
        projectFilesystem)
        .addSrc(Paths.get("Android.mk"))
        .build();

    ruleResolver.addToIndex(ndkLibrary);

    ProjectConfig ndkProjectConfig = (ProjectConfig) ProjectConfigBuilder
        .newProjectConfigRuleBuilder(
            BuildTargetFactory.newInstance(
                "//third_party/java/foo/jni:project_config"))
        .setSrcRule(ndkLibrary.getBuildTarget())
        .build(ruleResolver);

    ProjectWithModules projectWithModules = getModulesForActionGraph(
        ruleResolver,
        ImmutableSortedSet.of(ndkProjectConfig),
        null /* javaPackageFinder */);
    List<Module> modules = projectWithModules.modules;

    assertEquals("Should be one module for the ndk_library.", 1, modules.size());
    Module androidLibraryModule = Iterables.getOnlyElement(modules);
    assertListEquals(ImmutableList.of(
            DependentModule.newSourceFolder(),
            DependentModule.newInheritedJdk()),
        androidLibraryModule.dependencies);
    assertEquals(
        Paths.get(String.format("../../../../%s", ndkLibrary.getLibraryPath())),
        androidLibraryModule.nativeLibs);
  }

  @Test
  public void testDoNotIgnoreAllOfBuckOut() {
    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver());
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    ImmutableSet<Path> ignorePaths = ImmutableSet.of(Paths.get("buck-out"), Paths.get(".git"));
    EasyMock.expect(projectFilesystem.getIgnorePaths()).andReturn(ignorePaths);
    EasyMock.replay(projectFilesystem);

    BuildTarget buildTarget = BuildTarget.builder("//", "base").build();
    BuildRule buildRule = new FakeBuildRule(JavaLibraryDescription.TYPE, buildTarget, resolver);
    Module module = new Module(buildRule, buildTarget);

    Project.addRootExcludes(module, buildRule, projectFilesystem);

    ImmutableSortedSet<SourceFolder> expectedExcludeFolders =
        ImmutableSortedSet.orderedBy(Module.ALPHABETIZER)
        .add(new SourceFolder("file://$MODULE_DIR$/.git", /* isTestSource */ false))
        .add(new SourceFolder("file://$MODULE_DIR$/buck-out/bin", /* isTestSource */ false))
        .add(new SourceFolder("file://$MODULE_DIR$/buck-out/log", /* isTestSource */ false))
        .build();
    assertEquals("Specific subfolders of buck-out should be excluded rather than all of buck-out.",
        expectedExcludeFolders,
        module.excludeFolders);

    EasyMock.verify(projectFilesystem);
  }
}
