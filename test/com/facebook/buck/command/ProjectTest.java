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

package com.facebook.buck.command;

import static com.facebook.buck.testutil.MoreAsserts.assertListEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import com.facebook.buck.command.Project.SourceFolder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.model.SingletonBuildTargetPattern;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.parser.PartialGraphFactory;
import com.facebook.buck.rules.AndroidBinaryRule;
import com.facebook.buck.rules.AndroidLibraryRule;
import com.facebook.buck.rules.AndroidResourceRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.DefaultJavaLibraryRule;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.JavaLibraryRule;
import com.facebook.buck.rules.JavaPackageFinder;
import com.facebook.buck.rules.JavaTestRule;
import com.facebook.buck.rules.NdkLibraryRule;
import com.facebook.buck.rules.PrebuiltJarRule;
import com.facebook.buck.rules.ProjectConfigRule;
import com.facebook.buck.shell.ExecutionContext;
import com.facebook.buck.testutil.RuleMap;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import org.easymock.EasyMock;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.annotation.Nullable;

public class ProjectTest {

  private static final String PATH_TO_GUAVA_JAR = "third_party/guava/guava-10.0.1.jar";
  private PrebuiltJarRule guava;

  /**
   * Creates a PartialGraph with two android_binary rules, each of which depends on the same
   * android_library. The difference between the two is that one lists Guava in its no_dx list and
   * the other does not.
   * <p>
   * The PartialGraph also includes three project_config rules: one for the android_library, and one
   * for each of the android_binary rules.
   */
  public ProjectWithModules createPartialGraphForTesting(
      @Nullable JavaPackageFinder javaPackageFinder) throws IOException {
    Map<String, BuildRule> buildRuleIndex = Maps.newHashMap();

    // java_library //buck-android/com/facebook:R
    JavaLibraryRule rDotJavaRule =
        DefaultJavaLibraryRule.newJavaLibraryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//buck-android/com/facebook:R"))
        .addSrc("buck-android/com/facebook/R.java")
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL)
        .build(buildRuleIndex);
    buildRuleIndex.put(rDotJavaRule.getFullyQualifiedName(), rDotJavaRule);

    // prebuilt_jar //third_party/guava:guava
    guava = PrebuiltJarRule.newPrebuiltJarRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//third_party/guava:guava"))
        .setBinaryJar(PATH_TO_GUAVA_JAR)
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL)
        .build(buildRuleIndex);
    buildRuleIndex.put(guava.getFullyQualifiedName(), guava);

    // android_resouce android_res/base:res
    AndroidResourceRule androidResourceRule = AndroidResourceRule.newAndroidResourceRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//android_res/base:res"))
        .setRes("android_res/base/res")
        .setRDotJavaPackage("com.facebook")
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL)
        .build(buildRuleIndex);
    buildRuleIndex.put(androidResourceRule.getFullyQualifiedName(), androidResourceRule);

    // project_config android_res/base:res
    ProjectConfigRule projectConfigRuleForResource =
          ProjectConfigRule.newProjectConfigRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//android_res/base:project_config"))
        .setSrcTarget("//android_res/base:res")
        .setSrcRoots(ImmutableList.of("res"))
        .build(buildRuleIndex);
    buildRuleIndex.put(projectConfigRuleForResource.getFullyQualifiedName(),
        projectConfigRuleForResource);

    // android_library //java/src/com/facebook/base:base
    AndroidLibraryRule androidLibraryRule = AndroidLibraryRule.newAndroidLibraryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//java/src/com/facebook/base:base"))
        .addSrc("Base.java")
        .addDep("//buck-android/com/facebook:R")
        .addDep("//third_party/guava:guava")
        .addDep("//android_res/base:res")
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL)
        .build(buildRuleIndex);
    buildRuleIndex.put(androidLibraryRule.getFullyQualifiedName(), androidLibraryRule);

    // project_config //java/src/com/facebook/base:project_config
    ProjectConfigRule projectConfigRuleForLibrary = ProjectConfigRule.newProjectConfigRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance(
            "//java/src/com/facebook/base:project_config"))
        .setSrcTarget("//java/src/com/facebook/base:base")
        .setSrcRoots(ImmutableList.of("src", "src-gen"))
        .build(buildRuleIndex);
    buildRuleIndex.put(projectConfigRuleForLibrary.getFullyQualifiedName(),
        projectConfigRuleForLibrary);

    // android_binary //foo:app
    AndroidBinaryRule androidBinaryRuleUsingNoDx = AndroidBinaryRule.newAndroidBinaryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//foo:app"))
        .addDep("//java/src/com/facebook/base:base")
        .setManifest("foo/AndroidManifest.xml")
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystorePropertiesPath("foo/../keystore.properties")
        .addBuildRuleToExcludeFromDex("//third_party/guava:guava")
        .build(buildRuleIndex);
    buildRuleIndex.put(androidBinaryRuleUsingNoDx.getFullyQualifiedName(),
        androidBinaryRuleUsingNoDx);

    // project_config //foo:project_config
    ProjectConfigRule projectConfigRuleUsingNoDx = ProjectConfigRule.newProjectConfigRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//foo:project_config"))
        .setSrcTarget("//foo:app")
        .build(buildRuleIndex);
    buildRuleIndex.put(projectConfigRuleUsingNoDx.getFullyQualifiedName(),
        projectConfigRuleUsingNoDx);

    // android_binary //bar:app
    AndroidBinaryRule androidBinaryRule = AndroidBinaryRule.newAndroidBinaryRuleBuilder()
    .setBuildTarget(BuildTargetFactory.newInstance("//bar:app"))
    .addDep("//java/src/com/facebook/base:base")
        .setManifest("foo/AndroidManifest.xml")
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystorePropertiesPath("bar/../keystore.properties")
        .build(buildRuleIndex);
    buildRuleIndex.put(androidBinaryRule.getFullyQualifiedName(),
        androidBinaryRule);

    // project_config //bar:project_config
    ProjectConfigRule projectConfigRule = ProjectConfigRule.newProjectConfigRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//bar:project_config"))
        .setSrcTarget("//bar:app")
        .build(buildRuleIndex);
    buildRuleIndex.put(projectConfigRule.getFullyQualifiedName(), projectConfigRule);

    return getModulesForPartialGraph(buildRuleIndex,
        ImmutableList.of(
            projectConfigRuleForLibrary,
            projectConfigRuleUsingNoDx,
            projectConfigRule,
            projectConfigRuleForResource),
        javaPackageFinder);
  }

  @Test
  public void testGenerateRelativeGenPath() {
    String basePathOfModuleWithSlash = "android_res/com/facebook/gifts/";
    String expectedRelativePathToGen =
        "/../../../../buck-android/android_res/com/facebook/gifts/gen";
    assertEquals(
        expectedRelativePathToGen, Project.generateRelativeGenPath(basePathOfModuleWithSlash));
  }

  /**
   * This is an important test that verifies that the {@code no_dx} argument for an
   * {@code android_binary} is handled appropriately when generating an IntelliJ project.
   */
  @Test
  public void testProject() throws IOException {
    JavaPackageFinder javaPackageFinder = EasyMock.createMock(JavaPackageFinder.class);
    EasyMock.expect(javaPackageFinder.findJavaPackageForPath("foo/module_foo.iml")).andReturn("");
    EasyMock.expect(javaPackageFinder.findJavaPackageForPath("bar/module_bar.iml")).andReturn("");
    EasyMock.replay(javaPackageFinder);

    ProjectWithModules projectWithModules = createPartialGraphForTesting(javaPackageFinder);
    Project project = projectWithModules.project;
    PartialGraph partialGraph = project.getPartialGraph();
    List<Module> modules = projectWithModules.modules;

    assertEquals("Should be one module for the android_library, one for the android_resource, " +
                 "and one for each android_binary",
        4,
        modules.size());

    // Check the values of the module that corresponds to the android_library.
    Module androidLibraryModule = modules.get(0);
    assertSame(getRuleById("//java/src/com/facebook/base:base", partialGraph),
        androidLibraryModule.srcRule);
    assertEquals("module_java_src_com_facebook_base", androidLibraryModule.name);
    assertEquals("java/src/com/facebook/base/module_java_src_com_facebook_base.iml",
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
    DependentModule inheritedJdk = DependentModule.newInheritedJdk();
    DependentModule guavaAsProvidedDep = DependentModule.newLibrary(
        guava.getBuildTarget(), "guava_10_0_1");
    guavaAsProvidedDep.scope = "PROVIDED";
    DependentModule androidResourceAsProvidedDep = DependentModule.newModule(
        BuildTargetFactory.newInstance("//android_res/base:res"),
        "module_android_res_base");

    assertListEquals(
        ImmutableList.of(
            DependentModule.newSourceFolder(),
            guavaAsProvidedDep,
            androidResourceAsProvidedDep,
            inheritedJdk),
        androidLibraryModule.dependencies);

    // Check the values of the module that corresponds to the android_binary that uses no_dx.
    Module androidResourceModule = modules.get(3);
    assertSame(getRuleById("//android_res/base:res", partialGraph), androidResourceModule.srcRule);

    assertEquals("/res", androidResourceModule.resFolder);

    // Check the values of the module that corresponds to the android_binary that uses no_dx.
    Module androidBinaryModuleNoDx = modules.get(1);
    assertSame(getRuleById("//foo:app", partialGraph), androidBinaryModuleNoDx.srcRule);
    assertEquals("module_foo", androidBinaryModuleNoDx.name);
    assertEquals("foo/module_foo.iml", androidBinaryModuleNoDx.pathToImlFile);

    assertListEquals(ImmutableList.of(SourceFolder.GEN), androidBinaryModuleNoDx.sourceFolders);
    assertEquals(Boolean.TRUE, androidBinaryModuleNoDx.hasAndroidFacet);
    assertEquals(Boolean.FALSE, androidBinaryModuleNoDx.isAndroidLibraryProject);
    assertEquals(null, androidBinaryModuleNoDx.proguardConfigPath);
    assertEquals(null, androidBinaryModuleNoDx.resFolder);
    assertEquals("../debug.keystore", androidBinaryModuleNoDx.keystorePath);

    // Check the dependencies.
    DependentModule androidLibraryDep = DependentModule.newModule(
        androidLibraryModule.srcRule.getBuildTarget(), "module_java_src_com_facebook_base");
    assertEquals(
        ImmutableList.of(
            DependentModule.newSourceFolder(),
            guavaAsProvidedDep,
            androidLibraryDep,
            androidResourceAsProvidedDep,
            inheritedJdk),
        androidBinaryModuleNoDx.dependencies);

    // Check the values of the module that corresponds to the android_binary with an empty no_dx.
    Module androidBinaryModuleEmptyNoDx = modules.get(2);
    assertSame(getRuleById("//bar:app", partialGraph), androidBinaryModuleEmptyNoDx.srcRule);
    assertEquals("module_bar", androidBinaryModuleEmptyNoDx.name);
    assertEquals("bar/module_bar.iml", androidBinaryModuleEmptyNoDx.pathToImlFile);
    assertListEquals(
        ImmutableList.of(SourceFolder.GEN), androidBinaryModuleEmptyNoDx.sourceFolders);
    assertEquals(Boolean.TRUE, androidBinaryModuleEmptyNoDx.hasAndroidFacet);
    assertEquals(Boolean.FALSE, androidBinaryModuleEmptyNoDx.isAndroidLibraryProject);
    assertEquals(null, androidBinaryModuleEmptyNoDx.proguardConfigPath);
    assertEquals(null, androidBinaryModuleEmptyNoDx.resFolder);
    assertEquals("../debug.keystore", androidBinaryModuleEmptyNoDx.keystorePath);

    // Check the dependencies.
    DependentModule guavaAsCompiledDep = DependentModule.newLibrary(
        guava.getBuildTarget(), "guava_10_0_1");
    assertEquals("Important that Guava is listed as a 'COMPILED' dependency here because it is "
        + "only listed as a 'PROVIDED' dependency earlier.",
        ImmutableList.of(
            DependentModule.newSourceFolder(),
            guavaAsCompiledDep,
            androidLibraryDep,
            androidResourceAsProvidedDep,
            inheritedJdk),
        androidBinaryModuleEmptyNoDx.dependencies);

    // Check that the correct data was extracted to populate the .idea/libraries directory.
    BuildRule guava = getRuleById("//third_party/guava:guava", partialGraph);
    assertSame(guava, Iterables.getOnlyElement(project.getLibraryJars()));
  }

  @Test
  public void testPrebuiltJarIncludesDeps() throws IOException {
    Map<String, BuildRule> buildRuleIndex = Maps.newHashMap();

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
    PrebuiltJarRule cglib = PrebuiltJarRule.newPrebuiltJarRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//third_party/java/easymock:cglib"))
        .setBinaryJar("third_party/java/easymock/cglib.jar")
        .build(buildRuleIndex);
    buildRuleIndex.put(cglib.getFullyQualifiedName(), cglib);

    PrebuiltJarRule objenesis = PrebuiltJarRule.newPrebuiltJarRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//third_party/java/easymock:objenesis"))
        .setBinaryJar("third_party/java/easymock/objenesis.jar")
        .build(buildRuleIndex);
    buildRuleIndex.put(objenesis.getFullyQualifiedName(), objenesis);

    PrebuiltJarRule easymock = PrebuiltJarRule.newPrebuiltJarRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//third_party/java/easymock:easymock"))
        .setBinaryJar("third_party/java/easymock/easymock.jar")
        .addDep("//third_party/java/easymock:cglib")
        .addDep("//third_party/java/easymock:objenesis")
        .build(buildRuleIndex);
    buildRuleIndex.put(easymock.getFullyQualifiedName(), easymock);

    AndroidLibraryRule androidLibrary = AndroidLibraryRule.newAndroidLibraryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//third_party/java/easymock:example"))
        .addDep("//third_party/java/easymock:easymock")
        .build(buildRuleIndex);
    buildRuleIndex.put(androidLibrary.getFullyQualifiedName(), androidLibrary);

    ProjectConfigRule projectConfig = ProjectConfigRule.newProjectConfigRuleBuilder()
        .setBuildTarget(
            BuildTargetFactory.newInstance("//third_party/java/easymock:project_config"))
        .setSrcTarget("//third_party/java/easymock:example")
        .build(buildRuleIndex);
    buildRuleIndex.put(projectConfig.getFullyQualifiedName(), projectConfig);

    ProjectWithModules projectWithModules = getModulesForPartialGraph(buildRuleIndex,
        ImmutableList.of(projectConfig),
        null /* javaPackageFinder */);
    List<Module> modules = projectWithModules.modules;

    // Verify that the single Module that is created transitively includes all JAR files.
    assertEquals("Should be one module for the android_library", 1, modules.size());
    Module androidLibraryModule = Iterables.getOnlyElement(modules);
    assertListEquals(ImmutableList.of(
            DependentModule.newSourceFolder(),
            DependentModule.newLibrary(easymock.getBuildTarget(), "easymock"),
            DependentModule.newLibrary(cglib.getBuildTarget(), "cglib"),
            DependentModule.newLibrary(objenesis.getBuildTarget(), "objenesis"),
            DependentModule.newInheritedJdk()),
        androidLibraryModule.dependencies);
  }

  @Test
  public void testIfModuleIsBothTestAndCompileDepThenTreatAsCompileDep() throws IOException {
    Map<String, BuildRule> buildRuleIndex = Maps.newHashMap();

    // Create a java_library() and a java_test() that both depend on Guava.
    // When they are part of the same project_config() rule, then the resulting module should
    // include Guava as scope="COMPILE" in IntelliJ.
    PrebuiltJarRule guava = PrebuiltJarRule.newPrebuiltJarRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//third_party/java/guava:guava"))
        .setBinaryJar("third_party/java/guava.jar")
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL)
        .build(buildRuleIndex);
    buildRuleIndex.put(guava.getFullyQualifiedName(), guava);

    JavaLibraryRule javaLib = DefaultJavaLibraryRule.newJavaLibraryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//java/com/example/base:base"))
        .addDep("//third_party/java/guava:guava")
        .build(buildRuleIndex);
    buildRuleIndex.put(javaLib.getFullyQualifiedName(), javaLib);

    JavaTestRule javaTest = JavaTestRule.newJavaTestRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//java/com/example/base:tests"))
        .addDep("//third_party/java/guava:guava")
        .build(buildRuleIndex);
    buildRuleIndex.put(javaTest.getFullyQualifiedName(), javaTest);

    ProjectConfigRule projectConfig = ProjectConfigRule.newProjectConfigRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//java/com/example/base:project_config"))
        .setSrcTarget("//java/com/example/base:base")
        .setTestTarget("//java/com/example/base:tests")
        .setTestRoots(ImmutableList.of("tests"))
        .build(buildRuleIndex);
    buildRuleIndex.put(projectConfig.getFullyQualifiedName(), projectConfig);

    ProjectWithModules projectWithModules = getModulesForPartialGraph(buildRuleIndex,
        ImmutableList.of(projectConfig),
        null /* javaPackageFinder */);
    List<Module> modules = projectWithModules.modules;
    assertEquals(1, modules.size());
    Module comExampleBaseModule = Iterables.getOnlyElement(modules);

    assertListEquals(ImmutableList.of(
            DependentModule.newSourceFolder(),
            DependentModule.newLibrary(guava.getBuildTarget(), "guava"),
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
    Map<String, BuildRule> buildRuleIndex = Maps.newHashMap();

    JavaLibraryRule supportV4 = DefaultJavaLibraryRule.newJavaLibraryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//java/com/android/support/v4:v4"))
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL)
        .build(buildRuleIndex);
    buildRuleIndex.put(supportV4.getFullyQualifiedName(), supportV4);

    PrebuiltJarRule httpCore = PrebuiltJarRule.newPrebuiltJarRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//third_party/java/httpcore:httpcore"))
        .setBinaryJar("httpcore-4.0.1.jar")
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL)
        .build(buildRuleIndex);
    buildRuleIndex.put(httpCore.getFullyQualifiedName(), httpCore);

    // The support-v4 library is loaded as a java_library() rather than a prebuilt_jar() because it
    // contains our local changes to the library.
    JavaLibraryRule robolectric = DefaultJavaLibraryRule.newJavaLibraryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance(
            "//third_party/java/robolectric:robolectric"))
        .addDep("//java/com/android/support/v4:v4")
        .addDep("//third_party/java/httpcore:httpcore")
        .build(buildRuleIndex);
    buildRuleIndex.put(robolectric.getFullyQualifiedName(), robolectric);

    ProjectConfigRule projectConfig = ProjectConfigRule.newProjectConfigRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance(
            "//third_party/java/robolectric:project_config"))
        .setSrcTarget("//third_party/java/robolectric:robolectric")
        .setSrcRoots(ImmutableList.of("src/main/java"))
        .build(buildRuleIndex);
    buildRuleIndex.put(projectConfig.getFullyQualifiedName(), projectConfig);

    ProjectWithModules projectWithModules = getModulesForPartialGraph(buildRuleIndex,
        ImmutableList.of(projectConfig),
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
            DependentModule.newLibrary(httpCore.getBuildTarget(), "httpcore_4_0_1"),
            DependentModule.newModule(
                supportV4.getBuildTarget(), "module_java_com_android_support_v4"),
            DependentModule.newStandardJdk()),
        robolectricModule.dependencies);
  }

  @Test
  public void testCreatePathToProjectDotPropertiesFileForModule() {
    Module rootModule = new Module(null /* buildRule */,
        BuildTargetFactory.newInstance("//:project_config"));
    rootModule.pathToImlFile = "fb4a.iml";
    assertEquals("project.properties", Project.createPathToProjectDotPropertiesFileFor(rootModule));

    Module someModule = new Module(null /* buildRule */,
        BuildTargetFactory.newInstance("//java/com/example/base:project_config"));
    someModule.pathToImlFile = "java/com/example/base/base.iml";
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
    Map<String, BuildRule> buildRuleIndex1 = Maps.newHashMap();
    AndroidResourceRule androidResourceRule = AndroidResourceRule
        .newAndroidResourceRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//resources/com/example:res"))
        .build(buildRuleIndex1);
    buildRuleIndex1.put(androidResourceRule.getFullyQualifiedName(),
        androidResourceRule);
    ProjectConfigRule projectConfigNullSrcRoots = ProjectConfigRule.newProjectConfigRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//resources/com/example:project_config"))
        .setSrcTarget("//resources/com/example:res")
        .setSrcRoots(null)
        .build(buildRuleIndex1);
    buildRuleIndex1.put(projectConfigNullSrcRoots.getFullyQualifiedName(),
        projectConfigNullSrcRoots);
    ProjectWithModules projectWithModules1 = getModulesForPartialGraph(buildRuleIndex1,
        ImmutableList.of(projectConfigNullSrcRoots),
        null /* javaPackageFinder */);

    // Verify that the correct source folders are created.
    assertEquals(1, projectWithModules1.modules.size());
    Module moduleNoJavaSource = projectWithModules1.modules.get(0);
    assertListEquals(
        "Only source folder should be gen/ when setSrcRoots(null) is specified.",
        ImmutableList.of(SourceFolder.GEN),
        moduleNoJavaSource.sourceFolders);

    // Create a project_config() with src_roots=[].
    Map<String, BuildRule> buildRuleIndex2 = Maps.newHashMap();
    AndroidLibraryRule inPackageJavaLibraryRule = AndroidLibraryRule
        .newAndroidLibraryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//java/com/example/base:base"))
        .build(buildRuleIndex2);
    buildRuleIndex2.put(inPackageJavaLibraryRule.getFullyQualifiedName(),
        inPackageJavaLibraryRule);
    ProjectConfigRule inPackageProjectConfig = ProjectConfigRule.newProjectConfigRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//java/com/example/base:project_config"))
        .setSrcTarget("//java/com/example/base:base")
        .setSrcRoots(ImmutableList.<String>of())
        .build(buildRuleIndex2);
    buildRuleIndex2.put(inPackageProjectConfig.getFullyQualifiedName(), inPackageProjectConfig);

    // Verify that the correct source folders are created.
    JavaPackageFinder javaPackageFinder = EasyMock.createMock(JavaPackageFinder.class);
    EasyMock.expect(javaPackageFinder.findJavaPackageForPath(
        "java/com/example/base/module_java_com_example_base.iml")).andReturn("com.example.base");
    EasyMock.replay(javaPackageFinder);
    ProjectWithModules projectWithModules2 = getModulesForPartialGraph(buildRuleIndex2,
        ImmutableList.of(inPackageProjectConfig),
        javaPackageFinder);
    EasyMock.verify(javaPackageFinder);
    assertEquals(1, projectWithModules2.modules.size());
    Module moduleWithPackagePrefix = projectWithModules2.modules.get(0);
    assertListEquals(
        "The current directory should be a source folder with a package prefix " +
            "as well as the gen/ directory.",
        ImmutableList.of(
            new SourceFolder("file://$MODULE_DIR$", false /* isTestSource */, "com.example.base"),
            SourceFolder.GEN),
        moduleWithPackagePrefix.sourceFolders);

    // Create a project_config() with src_roots=['src'].
    Map<String, BuildRule> buildRuleIndex3 = Maps.newHashMap();
    AndroidLibraryRule hasSrcFolderAndroidLibraryRule = AndroidLibraryRule
        .newAndroidLibraryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//java/com/example/base:base"))
        .build(buildRuleIndex3);
    buildRuleIndex3.put(hasSrcFolderAndroidLibraryRule.getFullyQualifiedName(),
        hasSrcFolderAndroidLibraryRule);
    ProjectConfigRule hasSrcFolderProjectConfig = ProjectConfigRule.newProjectConfigRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//java/com/example/base:project_config"))
        .setSrcTarget("//java/com/example/base:base")
        .setSrcRoots(ImmutableList.of("src"))
        .build(buildRuleIndex3);
    buildRuleIndex3.put(hasSrcFolderProjectConfig.getFullyQualifiedName(),
        hasSrcFolderProjectConfig);
    ProjectWithModules projectWithModules3 = getModulesForPartialGraph(buildRuleIndex3,
        ImmutableList.of(hasSrcFolderProjectConfig),
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

  private ProjectWithModules getModulesForPartialGraph(Map<String, BuildRule> buildRuleIndex,
      ImmutableList<ProjectConfigRule> projectConfigs,
      @Nullable JavaPackageFinder javaPackageFinder) throws IOException {
    if (javaPackageFinder == null) {
      javaPackageFinder = EasyMock.createMock(JavaPackageFinder.class);
    }

    DependencyGraph graph = RuleMap.createGraphFromBuildRules(buildRuleIndex);
    List<BuildTarget> targets = ImmutableList.copyOf(Iterables.transform(projectConfigs,
        new Function<ProjectConfigRule, BuildTarget>() {

      @Override
      public BuildTarget apply(ProjectConfigRule rule) {
        return rule.getBuildTarget();
      }
    }));
    PartialGraph partialGraph = PartialGraphFactory.newInstance(graph, targets);

    // Create the Project.
    ExecutionContext executionContext = EasyMock.createMock(ExecutionContext.class);
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);

    Properties keystoreProperties = new Properties();
    keystoreProperties.put("key.store", "debug.keystore");
    keystoreProperties.put("key.alias", "androiddebugkey");
    keystoreProperties.put("key.store.password", "android");
    keystoreProperties.put("key.alias.password", "android");
    EasyMock.expect(projectFilesystem.readPropertiesFile(
        "foo/../keystore.properties"))
        .andReturn(keystoreProperties).anyTimes();
    EasyMock.expect(projectFilesystem.readPropertiesFile(
        "bar/../keystore.properties"))
        .andReturn(keystoreProperties).anyTimes();

    ImmutableMap<String, String> basePathToAliasMap = ImmutableMap.of();
    Project project = new Project(
        partialGraph,
        basePathToAliasMap,
        javaPackageFinder,
        executionContext,
        projectFilesystem,
        Optional.<String>absent() /* pathToDefaultAndroidManifest */);

    // Execute Project's business logic.
    EasyMock.replay(executionContext, projectFilesystem);
    List<Module> modules = project.createModulesForProjectConfigs();
    EasyMock.verify(executionContext, projectFilesystem);

    return new ProjectWithModules(project, ImmutableList.copyOf(modules));
  }

  private static BuildRule getRuleById(String id, PartialGraph graph) {
    String[] parts = id.split(":");
    BuildRule rule = graph.getDependencyGraph().findBuildRuleByTarget(
        BuildTargetFactory.newInstance(parts[0], parts[1]));
    Preconditions.checkNotNull(rule, "No rule for %s", id);
    return rule;
  }

  @Test
  public void testNdkLibraryHasCorrectPath() throws IOException {
    Map<String, BuildRule> buildRuleIndex = Maps.newHashMap();

    // Build up a the graph that corresponds to:
    //
    // ndk_library(
    //   name = 'foo-jni'
    // )
    //
    // project_config(
    //   src_target = ':foo-jni',
    // )

    NdkLibraryRule ndkLibrary = NdkLibraryRule.newNdkLibraryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//third_party/java/foo/jni:foo-jni"))
        .addSrc("Android.mk")
        .addVisibilityPattern(new SingletonBuildTargetPattern("//third_party/java/foo:foo"))
        .build(buildRuleIndex);
    buildRuleIndex.put(ndkLibrary.getFullyQualifiedName(), ndkLibrary);

    ProjectConfigRule ndkProjectConfig = ProjectConfigRule.newProjectConfigRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//third_party/java/foo/jni:project_config"))
        .setSrcTarget("//third_party/java/foo/jni:foo-jni")
        .build(buildRuleIndex);
    buildRuleIndex.put(ndkProjectConfig.getFullyQualifiedName(), ndkProjectConfig);

    ProjectWithModules projectWithModules = getModulesForPartialGraph(buildRuleIndex,
        ImmutableList.of(ndkProjectConfig),
        null /* javaPackageFinder */);
    List<Module> modules = projectWithModules.modules;

    assertEquals("Should be one module for the ndk_library.", 1, modules.size());
    Module androidLibraryModule = Iterables.getOnlyElement(modules);
    assertListEquals(ImmutableList.of(
            DependentModule.newSourceFolder(),
            DependentModule.newInheritedJdk()),
        androidLibraryModule.dependencies);
    assertEquals(
        String.format("../../../../%s", ndkLibrary.getLibraryPath()),
        androidLibraryModule.nativeLibs);
  }

  @Test
  public void shouldThrowAnExceptionIfAModuleIsMissingADependencyWhenGeneratingProjectFiles()
      throws IOException {
    Map<String, BuildRule> buildRuleIndex = Maps.newHashMap();

    DefaultJavaLibraryRule ex1 = DefaultJavaLibraryRule.newJavaLibraryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//example/parent:ex1"))
        .addSrc("DoesNotExist.java")
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL)
        .build(buildRuleIndex);
    buildRuleIndex.put(ex1.getFullyQualifiedName(), ex1);

    DefaultJavaLibraryRule ex2 = DefaultJavaLibraryRule.newJavaLibraryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//example/child:ex2"))
        .addSrc("AlsoDoesNotExist.java")
        .addDep(ex1.getFullyQualifiedName())
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL)
        .build(buildRuleIndex);
    buildRuleIndex.put(ex2.getFullyQualifiedName(), ex2);

    DefaultJavaLibraryRule tests = JavaTestRule.newJavaTestRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//example/child:tests"))
        .addSrc("SomeTestFile.java")
        .addDep(ex2.getFullyQualifiedName())
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL)
        .build(buildRuleIndex);
    buildRuleIndex.put(tests.getFullyQualifiedName(), tests);

    ProjectConfigRule config = ProjectConfigRule.newProjectConfigRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//example/child:config"))
        .setSrcTarget(ex2.getFullyQualifiedName())
        .setTestTarget(tests.getFullyQualifiedName())
        .build(buildRuleIndex);
    buildRuleIndex.put(config.getFullyQualifiedName(), config);

    ProjectWithModules projectWithModules = getModulesForPartialGraph(
        buildRuleIndex, ImmutableList.of(config), null);

    Module module = Iterables.getOnlyElement(projectWithModules.modules);
    List<Module> modules = projectWithModules.project.createModulesForProjectConfigs();
    Map<String, Module> map = projectWithModules.project.buildNameToModuleMap(modules);

    try {
      projectWithModules.project.writeProjectDotPropertiesFile(module, map);
      fail("Should have thrown a HumanReadableException");
    } catch (HumanReadableException e) {
      assertEquals("You must define a project_config() in example/child/BUCK containing " +
          "//example/parent:ex1. The project_config() in //example/child:config transitively " +
          "depends on it.",
          e.getHumanReadableErrorMessage());
    }
  }
}
