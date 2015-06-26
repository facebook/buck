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

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;

import com.facebook.buck.java.DefaultJavaPackageFinder;
import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.java.JavaPackageFinder;
import com.facebook.buck.java.JavaTestBuilder;
import com.facebook.buck.java.PrebuiltJarBuilder;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.timing.FakeClock;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class IjProjectDataPreparerTest {

  private FakeProjectFilesystem filesystem;
  private JavaPackageFinder javaPackageFinder;

  @Before
  public void setUp() {
    filesystem = new FakeProjectFilesystem();
    javaPackageFinder = DefaultJavaPackageFinder.createDefaultJavaPackageFinder(
        ImmutableSet.of("/java/", "/javatests/")
    );
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testWriteModule() throws Exception {
    TargetNode<?> guavaTargetNode = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third_party/guava:guava"))
        .addSrc(Paths.get("third_party/guava/src/Collections.java"))
        .build();

    TargetNode<?> baseTargetNode = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:base"))
        .addDep(guavaTargetNode.getBuildTarget())
        .addSrc(Paths.get("java/com/example/base/Base.java"))
        .build();

    IjModuleGraph moduleGraph =
        IjModuleGraphTest.createModuleGraph(ImmutableSet.of(guavaTargetNode, baseTargetNode));
    IjModule baseModule = IjModuleGraphTest.getModuleForTarget(moduleGraph, baseTargetNode);

    IjProjectTemplateDataPreparer dataPreparer =
        new IjProjectTemplateDataPreparer(javaPackageFinder, moduleGraph, filesystem);

    ContentRoot contentRoot = dataPreparer.getContentRoot(baseModule);
    assertEquals("file://$MODULE_DIR$/../../java/com/example/base", contentRoot.getUrl());

    IjSourceFolder baseSourceFolder = contentRoot.getFolders().first();
    assertEquals("sourceFolder", baseSourceFolder.getType());
    assertFalse(baseSourceFolder.getIsTestSource());
    assertEquals("com.example.base", baseSourceFolder.getPackagePrefix());
    assertEquals("file://$MODULE_DIR$/../../java/com/example/base", baseSourceFolder.getUrl());

    assertThat(
        dataPreparer.getDependencies(baseModule),
        contains(
            allOf(
                hasProperty("type", equalTo(IjDependencyListBuilder.Type.MODULE)),
                hasProperty("data", equalTo(Optional.of(
                            DependencyEntryData.builder()
                                .setName("third_party_guava")
                                .setScope(IjDependencyListBuilder.Scope.COMPILE)
                                .setExported(false)
                                .build()
                        )))
            ),
            allOf(
                hasProperty("type", equalTo(IjDependencyListBuilder.Type.SOURCE_FOLDER))
            )
        ));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testDependencies() throws Exception {
    TargetNode<?> hamcrestTargetNode = PrebuiltJarBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third-party/hamcrest:hamcrest"))
        .setBinaryJar(Paths.get("third-party/hamcrest/hamcrest.jar"))
        .build();

    TargetNode<?> guavaTargetNode = PrebuiltJarBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third-party/guava:guava"))
        .setBinaryJar(Paths.get("third-party/guava/guava.jar"))
        .build();

    TargetNode<?> baseTargetNode = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:base"))
        .addDep(guavaTargetNode.getBuildTarget())
        .addSrc(Paths.get("java/com/example/base/Base.java"))
        .build();

    TargetNode<?> baseGenruleTarget = GenruleBuilder
        .newGenruleBuilder(BuildTargetFactory.newInstance("//java/com/example/base:genrule"))
        .build();

    TargetNode<?> baseInlineTestsTargetNode = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:tests"))
        .addDep(hamcrestTargetNode.getBuildTarget())
        .addSrc(Paths.get("java/com/example/base/TestBase.java"))
        .addSrcTarget(baseGenruleTarget.getBuildTarget())
        .build();

    TargetNode<?> baseTestsTargetNode = JavaTestBuilder
        .createBuilder(BuildTargetFactory.newInstance("//javatests/com/example/base:base"))
        .addDep(baseTargetNode.getBuildTarget())
        .addDep(hamcrestTargetNode.getBuildTarget())
        .addSrc(Paths.get("javatests/com/example/base/Base.java"))
        .build();

    IjModuleGraph moduleGraph = IjModuleGraphTest.createModuleGraph(
        ImmutableSet.of(
            hamcrestTargetNode,
            guavaTargetNode,
            baseTargetNode,
            baseGenruleTarget,
            baseInlineTestsTargetNode,
            baseTestsTargetNode),
        ImmutableMap.<TargetNode<?>, Path>of(
            baseInlineTestsTargetNode, Paths.get("buck-out/baseInlineTests.jar")),
        Functions.constant(Optional.<Path>absent()));
    IjLibrary hamcrestLibrary =
        IjModuleGraphTest.getLibraryForTarget(moduleGraph, hamcrestTargetNode);
    IjLibrary guavaLibrary = IjModuleGraphTest.getLibraryForTarget(moduleGraph, guavaTargetNode);
    IjModule baseModule = IjModuleGraphTest.getModuleForTarget(moduleGraph, baseTargetNode);
    IjModule baseTestModule =
        IjModuleGraphTest.getModuleForTarget(moduleGraph, baseTestsTargetNode);

    IjProjectTemplateDataPreparer dataPreparer =
        new IjProjectTemplateDataPreparer(javaPackageFinder, moduleGraph, filesystem);

    assertEquals(
        IjModuleGraphTest.getModuleForTarget(moduleGraph, baseInlineTestsTargetNode),
        IjModuleGraphTest.getModuleForTarget(moduleGraph, baseTargetNode));

    DependencyEntryData.Builder dependencyEntryBuilder = DependencyEntryData.builder()
        .setExported(false);

    assertThat(
        dataPreparer.getDependencies(baseModule),
        contains(
            allOf(
                hasProperty("type", equalTo(IjDependencyListBuilder.Type.LIBRARY)),
                hasProperty("data", equalTo(Optional.of(
                            dependencyEntryBuilder
                                .setName(guavaLibrary.getName())
                                .setScope(IjDependencyListBuilder.Scope.COMPILE)
                                .build()
                        )))
            ),
            allOf(
                hasProperty("type", equalTo(IjDependencyListBuilder.Type.LIBRARY)),
                hasProperty("data", equalTo(Optional.of(
                            dependencyEntryBuilder
                                .setName(hamcrestLibrary.getName())
                                .setScope(IjDependencyListBuilder.Scope.COMPILE)
                                .build()
                        )))
            ),
            allOf(
                hasProperty("type", equalTo(IjDependencyListBuilder.Type.SOURCE_FOLDER))
            ),
            allOf(
                hasProperty("type", equalTo(IjDependencyListBuilder.Type.LIBRARY)),
                hasProperty("data", equalTo(Optional.of(
                            DependencyEntryData.builder()
                                .setExported(true)
                                .setName("library_java_com_example_base_tests")
                                .setScope(IjDependencyListBuilder.Scope.PROVIDED)
                                .build()
                        )))
            )
        ));

    assertThat(
        dataPreparer.getDependencies(baseTestModule),
        contains(
            allOf(
                hasProperty("type", equalTo(IjDependencyListBuilder.Type.MODULE)),
                hasProperty("data", equalTo(Optional.of(
                            dependencyEntryBuilder
                                .setName(baseModule.getName())
                                .setScope(IjDependencyListBuilder.Scope.TEST)
                                .build()
                        )))
            ),
            allOf(
                hasProperty("type", equalTo(IjDependencyListBuilder.Type.LIBRARY)),
                hasProperty("data", equalTo(Optional.of(
                            dependencyEntryBuilder
                                .setName(hamcrestLibrary.getName())
                                .setScope(IjDependencyListBuilder.Scope.TEST)
                                .build()
                        )))
            ),
            allOf(
                hasProperty("type", equalTo(IjDependencyListBuilder.Type.SOURCE_FOLDER))
            )
        ));
  }

  @Test
  public void testEmptyRootModule() throws Exception {

    Path baseTargetSrcFilePath = Paths.get("java/com/example/base/Base.java");
    TargetNode<?> baseTargetNode = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:base"))
        .addSrc(baseTargetSrcFilePath)
        .build();

    IjModuleGraph moduleGraph = IjModuleGraphTest.createModuleGraph(
        ImmutableSet.<TargetNode<?>>of(baseTargetNode));
    IjProjectTemplateDataPreparer dataPreparer =
        new IjProjectTemplateDataPreparer(javaPackageFinder, moduleGraph, filesystem);

    assertThat(
        dataPreparer.getModulesToBeWritten(),
        containsInAnyOrder(
            IjModule.builder()
                .setModuleBasePath(Paths.get("java/com/example/base"))
                .setTargets(ImmutableSet.<TargetNode<?>>of(baseTargetNode))
                .addFolders(
                    IjFolder.builder()
                        .setType(AbstractIjFolder.Type.SOURCE_FOLDER)
                        .setPath(Paths.get("java/com/example/base"))
                        .setInputs(ImmutableSortedSet.<Path>of(baseTargetSrcFilePath))
                        .setWantsPackagePrefix(true)
                        .build())
                .build(),
            IjModule.builder()
                .setModuleBasePath(Paths.get(""))
                .setTargets(ImmutableSet.<TargetNode<?>>of())
                .build()
        )
    );

  }

  @Test
  public void testModuleIndex() throws Exception {
    TargetNode<?> guavaTargetNode = PrebuiltJarBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third-party/guava:guava"))
        .setBinaryJar(Paths.get("third-party/guava/guava.jar"))
        .build();

    TargetNode<?> baseTargetNode = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:base"))
        .addDep(guavaTargetNode.getBuildTarget())
        .addSrc(Paths.get("java/com/example/base/Base.java"))
        .build();

    TargetNode<?> baseTestsTargetNode = JavaTestBuilder
        .createBuilder(BuildTargetFactory.newInstance("//javatests/com/example/base:base"))
        .addDep(baseTargetNode.getBuildTarget())
        .addSrc(Paths.get("javatests/com/example/base/Base.java"))
        .build();

    IjModuleGraph moduleGraph = IjModuleGraphTest.createModuleGraph(
        ImmutableSet.of(
            guavaTargetNode,
            baseTargetNode,
            baseTestsTargetNode));
    IjProjectTemplateDataPreparer dataPreparer =
        new IjProjectTemplateDataPreparer(javaPackageFinder, moduleGraph, filesystem);

    // Libraries don't go into the index.
    assertEquals(
        ImmutableSet.of(
            ModuleIndexEntry.builder()
                .setFileUrl("file://$PROJECT_DIR$/.idea/modules/project_root.iml")
                .setFilePath(Paths.get(".idea/modules/project_root.iml"))
                .build(),
            ModuleIndexEntry.builder()
                .setGroup("modules")
                .setFileUrl("file://$PROJECT_DIR$/.idea/modules/java_com_example_base.iml")
                .setFilePath(Paths.get(".idea/modules/java_com_example_base.iml"))
                .build(),
            ModuleIndexEntry.builder()
                .setGroup("modules")
                .setFileUrl("file://$PROJECT_DIR$/.idea/modules/javatests_com_example_base.iml")
                .setFilePath(Paths.get(".idea/modules/javatests_com_example_base.iml"))
                .build()
        ),
        dataPreparer.getModuleIndexEntries());
  }

  @Test
  public void testExcludePaths() throws Exception {
    /**
     * Fake filesystem structure
     * .idea
     *  |- misc.xml
     * .git
     *  |- HEAD
     * java
     *  |- com
     *     |- BUCK
     *     |- data
     *        |- wsad.txt
     *     |- src
     *        |- BUCK
     *        |- Source.java
     *        |- foo
     *           |- Foo.java
     *     |- src2
     *        |- Code.java
     *  |- org
     *     |- bar
     *        |- Bar.java
     * lib
     *  |- BUCK
     *  |- guava.jar
     */
    ImmutableSet<Path> paths = ImmutableSet.of(
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
        new FakeProjectFilesystem(new FakeClock(0), Paths.get(".").toFile(), paths);

    TargetNode<?> guavaTargetNode = PrebuiltJarBuilder
        .createBuilder(BuildTargetFactory.newInstance("//lib:guava"))
        .setBinaryJar(Paths.get("lib/guava.jar"))
        .build();

    TargetNode<?> srcTargetNode = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/src:src"))
        .addDep(guavaTargetNode.getBuildTarget())
        .addSrc(Paths.get("java/com/src/Source.java"))
        .build();

    TargetNode<?> src2TargetNode = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com:src2"))
        .addDep(guavaTargetNode.getBuildTarget())
        .addSrc(Paths.get("java/com/src2/Code.java"))
        .build();

    TargetNode<?> rootTargetNode = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//:root"))
        .build();

    IjModuleGraph moduleGraph = IjModuleGraphTest.createModuleGraph(
        ImmutableSet.of(guavaTargetNode, srcTargetNode, src2TargetNode, rootTargetNode));
    IjModule srcModule = IjModuleGraphTest.getModuleForTarget(moduleGraph, srcTargetNode);
    IjModule src2Module = IjModuleGraphTest.getModuleForTarget(moduleGraph, src2TargetNode);
    IjModule rootModule = IjModuleGraphTest.getModuleForTarget(moduleGraph, rootTargetNode);

    IjProjectTemplateDataPreparer dataPreparer = new IjProjectTemplateDataPreparer(
        javaPackageFinder,
        moduleGraph,
        filesystemForExcludesTest);

    assertEquals(ImmutableSet.of(Paths.get("java/com/src/foo")),
        distillExcludeFolders(dataPreparer.createExcludes(srcModule)));

    assertEquals(ImmutableSet.of(Paths.get("java/com/data")),
        distillExcludeFolders(dataPreparer.createExcludes(src2Module)));

    // In this case it's fine to exclude "lib" as there is no source code there.
    assertEquals(ImmutableSet.of(Paths.get(".git"), Paths.get("java/org"), Paths.get("lib")),
        distillExcludeFolders(dataPreparer.createExcludes(rootModule)));
  }

  private static ImmutableSet<Path> distillExcludeFolders(ImmutableSet<IjFolder> folders) {
    Preconditions.checkArgument(!FluentIterable.from(folders)
            .anyMatch(
                new Predicate<IjFolder>() {
                  @Override
                  public boolean apply(IjFolder input) {
                    return !input.getType().equals(IjFolder.Type.EXCLUDE_FOLDER);
                  }
                }));
    return FluentIterable.from(folders)
        .uniqueIndex(
            new Function<IjFolder, Path>() {
              @Override
              public Path apply(IjFolder input) {
                return input.getPath();
              }
            })
        .keySet();
  }
}
