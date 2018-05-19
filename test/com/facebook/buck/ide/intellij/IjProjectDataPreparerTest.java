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

package com.facebook.buck.ide.intellij;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.ide.intellij.lang.android.AndroidManifestParser;
import com.facebook.buck.ide.intellij.model.ContentRoot;
import com.facebook.buck.ide.intellij.model.IjLibrary;
import com.facebook.buck.ide.intellij.model.IjModule;
import com.facebook.buck.ide.intellij.model.IjModuleType;
import com.facebook.buck.ide.intellij.model.ModuleIndexEntry;
import com.facebook.buck.ide.intellij.model.folders.ExcludeFolder;
import com.facebook.buck.ide.intellij.model.folders.IjFolder;
import com.facebook.buck.ide.intellij.model.folders.IjSourceFolder;
import com.facebook.buck.ide.intellij.model.folders.SourceFolder;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.DefaultJavaPackageFinder;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.JavaTestBuilder;
import com.facebook.buck.jvm.java.PrebuiltJarBuilder;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class IjProjectDataPreparerTest {

  private FakeProjectFilesystem filesystem;
  private JavaPackageFinder javaPackageFinder;
  private AndroidManifestParser androidManifestParser;

  @Before
  public void setUp() {
    filesystem = new FakeProjectFilesystem();
    javaPackageFinder =
        DefaultJavaPackageFinder.createDefaultJavaPackageFinder(
            ImmutableSet.of("/java/", "/javatests/"));
    androidManifestParser = new AndroidManifestParser(new FakeProjectFilesystem());
  }

  @Test
  public void testWriteModule() throws Exception {
    TargetNode<?, ?> guavaTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third_party/guava:guava"))
            .addSrc(Paths.get("third_party/guava/src/Collections.java"))
            .build();

    TargetNode<?, ?> baseTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base:base"))
            .addDep(guavaTargetNode.getBuildTarget())
            .addSrc(Paths.get("java/com/example/base/Base.java"))
            .build();

    IjModuleGraph moduleGraph =
        IjModuleGraphTest.createModuleGraph(ImmutableSet.of(guavaTargetNode, baseTargetNode));
    IjModule baseModule = IjModuleGraphTest.getModuleForTarget(moduleGraph, baseTargetNode);

    IjProjectTemplateDataPreparer dataPreparer =
        new IjProjectTemplateDataPreparer(
            javaPackageFinder,
            moduleGraph,
            filesystem,
            IjTestProjectConfig.create(),
            androidManifestParser);

    ContentRoot contentRoot = dataPreparer.getContentRoots(baseModule).asList().get(0);
    assertEquals("file://$MODULE_DIR$", contentRoot.getUrl());

    IjSourceFolder baseSourceFolder = contentRoot.getFolders().iterator().next();
    assertEquals("sourceFolder", baseSourceFolder.getType());
    assertFalse(baseSourceFolder.getIsTestSource());
    assertEquals("com.example.base", baseSourceFolder.getPackagePrefix());
    assertEquals("file://$MODULE_DIR$", baseSourceFolder.getUrl());

    assertThat(
        dataPreparer.getDependencies(baseModule),
        contains(
            allOf(
                hasProperty("type", equalTo(IjDependencyListBuilder.Type.MODULE)),
                hasProperty(
                    "data",
                    equalTo(
                        Optional.of(
                            DependencyEntryData.builder()
                                .setName("third_party_guava")
                                .setScope(IjDependencyListBuilder.Scope.COMPILE)
                                .setExported(false)
                                .build()))))));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testDependencies() {
    TargetNode<?, ?> hamcrestTargetNode =
        PrebuiltJarBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third-party/hamcrest:hamcrest"))
            .setBinaryJar(Paths.get("third-party/hamcrest/hamcrest.jar"))
            .build();

    TargetNode<?, ?> guavaTargetNode =
        PrebuiltJarBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third-party/guava:guava"))
            .setBinaryJar(Paths.get("third-party/guava/guava.jar"))
            .build();

    TargetNode<?, ?> baseTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base:base"))
            .addDep(guavaTargetNode.getBuildTarget())
            .addSrc(Paths.get("java/com/example/base/Base.java"))
            .build();

    TargetNode<?, ?> baseGenruleTarget =
        GenruleBuilder.newGenruleBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base:genrule"))
            .setOut("out")
            .build();

    TargetNode<?, ?> baseInlineTestsTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base:tests"))
            .addDep(hamcrestTargetNode.getBuildTarget())
            .addSrc(Paths.get("java/com/example/base/TestBase.java"))
            .addSrcTarget(baseGenruleTarget.getBuildTarget())
            .build();

    TargetNode<?, ?> baseTestsTargetNode =
        JavaTestBuilder.createBuilder(
                BuildTargetFactory.newInstance("//javatests/com/example/base:base"))
            .addDep(baseTargetNode.getBuildTarget())
            .addDep(hamcrestTargetNode.getBuildTarget())
            .addSrc(Paths.get("javatests/com/example/base/Base.java"))
            .build();

    IjModuleGraph moduleGraph =
        IjModuleGraphTest.createModuleGraph(
            ImmutableSet.of(
                hamcrestTargetNode,
                guavaTargetNode,
                baseTargetNode,
                baseGenruleTarget,
                baseInlineTestsTargetNode,
                baseTestsTargetNode),
            ImmutableMap.of(
                baseInlineTestsTargetNode, FakeSourcePath.of("buck-out/baseInlineTests.jar")),
            Functions.constant(Optional.empty()));
    IjLibrary hamcrestLibrary =
        IjModuleGraphTest.getLibraryForTarget(moduleGraph, hamcrestTargetNode);
    IjLibrary guavaLibrary = IjModuleGraphTest.getLibraryForTarget(moduleGraph, guavaTargetNode);
    IjModule baseModule = IjModuleGraphTest.getModuleForTarget(moduleGraph, baseTargetNode);
    IjModule baseTestModule =
        IjModuleGraphTest.getModuleForTarget(moduleGraph, baseTestsTargetNode);

    IjProjectTemplateDataPreparer dataPreparer =
        new IjProjectTemplateDataPreparer(
            javaPackageFinder,
            moduleGraph,
            filesystem,
            IjTestProjectConfig.create(),
            androidManifestParser);

    assertEquals(
        IjModuleGraphTest.getModuleForTarget(moduleGraph, baseInlineTestsTargetNode),
        IjModuleGraphTest.getModuleForTarget(moduleGraph, baseTargetNode));

    DependencyEntryData.Builder dependencyEntryBuilder =
        DependencyEntryData.builder().setExported(false);

    assertThat(
        dataPreparer.getDependencies(baseModule),
        contains(
            allOf(
                hasProperty("type", equalTo(IjDependencyListBuilder.Type.LIBRARY)),
                hasProperty(
                    "data",
                    equalTo(
                        Optional.of(
                            DependencyEntryData.builder()
                                .setExported(true)
                                .setName("//java/com/example/base:tests")
                                .setScope(IjDependencyListBuilder.Scope.PROVIDED)
                                .build())))),
            allOf(
                hasProperty("type", equalTo(IjDependencyListBuilder.Type.LIBRARY)),
                hasProperty(
                    "data",
                    equalTo(
                        Optional.of(
                            dependencyEntryBuilder
                                .setName(guavaLibrary.getName())
                                .setScope(IjDependencyListBuilder.Scope.COMPILE)
                                .build())))),
            allOf(
                hasProperty("type", equalTo(IjDependencyListBuilder.Type.LIBRARY)),
                hasProperty(
                    "data",
                    equalTo(
                        Optional.of(
                            dependencyEntryBuilder
                                .setName(hamcrestLibrary.getName())
                                .setScope(IjDependencyListBuilder.Scope.COMPILE)
                                .build()))))));

    assertThat(
        dataPreparer.getDependencies(baseTestModule),
        contains(
            allOf(
                hasProperty("type", equalTo(IjDependencyListBuilder.Type.MODULE)),
                hasProperty(
                    "data",
                    equalTo(
                        Optional.of(
                            dependencyEntryBuilder
                                .setName(baseModule.getName())
                                .setScope(IjDependencyListBuilder.Scope.TEST)
                                .build())))),
            allOf(
                hasProperty("type", equalTo(IjDependencyListBuilder.Type.LIBRARY)),
                hasProperty(
                    "data",
                    equalTo(
                        Optional.of(
                            dependencyEntryBuilder
                                .setName(hamcrestLibrary.getName())
                                .setScope(IjDependencyListBuilder.Scope.TEST)
                                .build()))))));
  }

  @Test
  public void testEmptyRootModule() {

    Path baseTargetSrcFilePath = Paths.get("java/com/example/base/Base.java");
    TargetNode<?, ?> baseTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base:base"))
            .addSrc(baseTargetSrcFilePath)
            .build();

    IjModuleGraph moduleGraph =
        IjModuleGraphTest.createModuleGraph(ImmutableSet.of(baseTargetNode));
    IjProjectTemplateDataPreparer dataPreparer =
        new IjProjectTemplateDataPreparer(
            javaPackageFinder,
            moduleGraph,
            filesystem,
            IjTestProjectConfig.create(),
            androidManifestParser);

    assertThat(
        dataPreparer.getModulesToBeWritten(),
        containsInAnyOrder(
            IjModule.builder()
                .setModuleBasePath(Paths.get("java/com/example/base"))
                .setTargets(ImmutableSet.of(baseTargetNode.getBuildTarget()))
                .addFolders(
                    new SourceFolder(
                        Paths.get("java/com/example/base"),
                        true,
                        ImmutableSortedSet.of(baseTargetSrcFilePath)))
                .setModuleType(IjModuleType.JAVA_MODULE)
                .build(),
            IjModule.builder()
                .setModuleBasePath(Paths.get(""))
                .setTargets(ImmutableSet.of())
                .setModuleType(IjModuleType.UNKNOWN_MODULE)
                .build()));
  }

  @Test
  public void testModuleIndex() {
    TargetNode<?, ?> guavaTargetNode =
        PrebuiltJarBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third-party/guava:guava"))
            .setBinaryJar(Paths.get("third-party/guava/guava.jar"))
            .build();

    TargetNode<?, ?> baseTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base:base"))
            .addDep(guavaTargetNode.getBuildTarget())
            .addSrc(Paths.get("java/com/example/base/Base.java"))
            .build();

    TargetNode<?, ?> baseTestsTargetNode =
        JavaTestBuilder.createBuilder(
                BuildTargetFactory.newInstance("//javatests/com/example/base:base"))
            .addDep(baseTargetNode.getBuildTarget())
            .addSrc(Paths.get("javatests/com/example/base/Base.java"))
            .build();

    IjModuleGraph moduleGraph =
        IjModuleGraphTest.createModuleGraph(
            ImmutableSet.of(guavaTargetNode, baseTargetNode, baseTestsTargetNode));
    IjProjectTemplateDataPreparer dataPreparer =
        new IjProjectTemplateDataPreparer(
            javaPackageFinder,
            moduleGraph,
            filesystem,
            IjTestProjectConfig.create(),
            androidManifestParser);

    // Libraries don't go into the index.
    assertEquals(
        ImmutableSet.of(
            ModuleIndexEntry.builder()
                .setFileUrl("file://$PROJECT_DIR$/project_root.iml")
                .setFilePath(Paths.get("project_root.iml"))
                .build(),
            ModuleIndexEntry.builder()
                .setGroup("modules")
                .setFileUrl("file://$PROJECT_DIR$/java/com/example/base/java_com_example_base.iml")
                .setFilePath(Paths.get("java/com/example/base/java_com_example_base.iml"))
                .build(),
            ModuleIndexEntry.builder()
                .setGroup("modules")
                .setFileUrl(
                    "file://$PROJECT_DIR$/javatests/com/example/base/javatests_com_example_base.iml")
                .setFilePath(Paths.get("javatests/com/example/base/javatests_com_example_base.iml"))
                .build()),
        dataPreparer.getModuleIndexEntries());
  }

  @Test
  public void testExcludePaths() throws Exception {
    /**
     * Fake filesystem structure .idea |- misc.xml .git |- HEAD java |- com |- BUCK |- data |-
     * wsad.txt |- src |- BUCK |- Source.java |- foo |- Foo.java |- src2 |- Code.java |- org |- bar
     * |- Bar.java lib |- BUCK |- guava.jar
     */
    ImmutableSet<Path> paths =
        ImmutableSet.of(
            Paths.get(".idea/misc.xml"),
            Paths.get(".git/HEAD"),
            Paths.get("java/com/BUCK"),
            Paths.get("java/com/data/wsad.txt"),
            Paths.get("java/com/src/BUCK"),
            Paths.get("java/com/src/Source.java"),
            Paths.get("java/com/src/foo/Foo.java"),
            Paths.get("java/org/bar/Bar.java"),
            Paths.get("lib/BUCK"),
            Paths.get("lib/guava.jar"));

    FakeProjectFilesystem filesystemForExcludesTest =
        new FakeProjectFilesystem(FakeClock.doNotCare(), Paths.get(".").toAbsolutePath(), paths);

    TargetNode<?, ?> guavaTargetNode =
        PrebuiltJarBuilder.createBuilder(BuildTargetFactory.newInstance("//lib:guava"))
            .setBinaryJar(Paths.get("lib/guava.jar"))
            .build();

    TargetNode<?, ?> srcTargetNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//java/com/src:src"))
            .addDep(guavaTargetNode.getBuildTarget())
            .addSrc(Paths.get("java/com/src/Source.java"))
            .build();

    TargetNode<?, ?> src2TargetNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//java/com:src2"))
            .addDep(guavaTargetNode.getBuildTarget())
            .addSrc(Paths.get("java/com/src2/Code.java"))
            .build();

    TargetNode<?, ?> rootTargetNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:root")).build();

    IjModuleGraph moduleGraph =
        IjModuleGraphTest.createModuleGraph(
            ImmutableSet.of(guavaTargetNode, srcTargetNode, src2TargetNode, rootTargetNode));
    IjModule srcModule = IjModuleGraphTest.getModuleForTarget(moduleGraph, srcTargetNode);
    IjModule src2Module = IjModuleGraphTest.getModuleForTarget(moduleGraph, src2TargetNode);
    IjModule rootModule = IjModuleGraphTest.getModuleForTarget(moduleGraph, rootTargetNode);

    IjProjectTemplateDataPreparer dataPreparer =
        new IjProjectTemplateDataPreparer(
            javaPackageFinder,
            moduleGraph,
            filesystemForExcludesTest,
            IjTestProjectConfig.create(),
            androidManifestParser);

    assertEquals(
        ImmutableSet.of(Paths.get("java/com/src/foo")),
        distillExcludeFolders(dataPreparer.createExcludes(srcModule)));

    assertEquals(
        ImmutableSet.of(Paths.get("java/com/data")),
        distillExcludeFolders(dataPreparer.createExcludes(src2Module)));

    // In this case it's fine to exclude "lib" as there is no source code there.
    assertEquals(
        ImmutableSet.of(Paths.get(".git"), Paths.get("java/org"), Paths.get("lib")),
        distillExcludeFolders(dataPreparer.createExcludes(rootModule)));
  }

  @Test
  public void testCreatePackageLookupPahtSet() {
    TargetNode<?, ?> guavaTargetNode =
        PrebuiltJarBuilder.createBuilder(BuildTargetFactory.newInstance("//lib:guava"))
            .setBinaryJar(Paths.get("lib/guava.jar"))
            .build();

    Path sourcePath = Paths.get("java/com/src/Source.java");
    Path subSourcePath = Paths.get("java/com/src/subpackage/SubSource.java");
    TargetNode<?, ?> srcTargetNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//java/com/src:src"))
            .addDep(guavaTargetNode.getBuildTarget())
            .addSrc(sourcePath)
            .addSrc(subSourcePath)
            .build();

    IjModuleGraph moduleGraph =
        IjModuleGraphTest.createModuleGraph(ImmutableSet.of(guavaTargetNode, srcTargetNode));

    assertThat(
        IjProjectTemplateDataPreparer.createPackageLookupPathSet(moduleGraph),
        containsInAnyOrder(subSourcePath, sourcePath));
  }

  private static ImmutableSet<Path> distillExcludeFolders(ImmutableCollection<IjFolder> folders) {
    Preconditions.checkArgument(
        !FluentIterable.from(folders).anyMatch(input -> !(input instanceof ExcludeFolder)));
    return FluentIterable.from(folders).uniqueIndex(IjFolder::getPath).keySet();
  }
}
