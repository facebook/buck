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

package com.facebook.buck.jvm.java.intellij;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.AndroidBinaryBuilder;
import com.facebook.buck.android.AndroidBinaryDescription;
import com.facebook.buck.android.AndroidLibraryBuilder;
import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.cxx.CxxLibraryBuilder;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.JavaTestBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class IjModuleFactoryTest {

  @Test
  public void testModuleDep() {
    IjModuleFactory factory = createIjModuleFactory();

    Path moduleBasePath = Paths.get("java/com/example/base");
    BuildTarget buildTargetGuava = BuildTargetFactory.newInstance("//third-party/guava:guava");

    TargetNode<?> javaLibBase = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:base"))
        .addSrc(moduleBasePath.resolve("File.java"))
        .addDep(buildTargetGuava)
        .build();

    IjModule module = factory.createModule(
        moduleBasePath,
        ImmutableSet.<TargetNode<?>>of(javaLibBase));

    assertEquals(ImmutableMap.of(buildTargetGuava, IjModuleGraph.DependencyType.PROD),
        module.getDependencies());
  }

  @Test
  public void testModuleDepMerge() {
    IjModuleFactory factory = createIjModuleFactory();

    Path moduleBasePath = Paths.get("test/com/example/base");
    BuildTarget buildTargetGuava = BuildTargetFactory.newInstance("//third-party/guava:guava");
    BuildTarget buildTargetJunit = BuildTargetFactory.newInstance("//third-party/junit:junit");

    TargetNode<?> javaLibBase = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//test/com/example/base:base"))
        .addSrc(moduleBasePath.resolve("File.java"))
        .addDep(buildTargetGuava)
        .build();

    TargetNode<?> javaLibExtra = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//test/com/example/base:extra"))
        .addSrc(moduleBasePath.resolve("File2.java"))
        .addDep(javaLibBase.getBuildTarget())
        .addDep(buildTargetJunit)
        .build();

    IjModule module = factory.createModule(
        moduleBasePath,
        ImmutableSet.of(javaLibBase, javaLibExtra));

    assertEquals(ImmutableMap.of(
            buildTargetGuava, IjModuleGraph.DependencyType.PROD,
            buildTargetJunit, IjModuleGraph.DependencyType.PROD),
        module.getDependencies());
  }

  @Test
  public void testModuleTestDep() {
    IjModuleFactory factory = createIjModuleFactory();

    Path moduleBasePath = Paths.get("test/com/example/base");
    BuildTarget buildTargetJunit = BuildTargetFactory.newInstance("//third-party/junit:junit");

    TargetNode<?> javaTestExtra = JavaTestBuilder
        .createBuilder(BuildTargetFactory.newInstance("//test/com/example/base:extra"))
        .addSrc(moduleBasePath.resolve("base/File.java"))
        .addDep(buildTargetJunit)
        .build();

    IjModule module = factory.createModule(
        moduleBasePath,
        ImmutableSet.<TargetNode<?>>of(javaTestExtra));

    assertEquals(ImmutableMap.of(buildTargetJunit, IjModuleGraph.DependencyType.TEST),
        module.getDependencies());
  }

  @Test
  public void testModuleDepTypeResolution() {
    IjModuleFactory factory = createIjModuleFactory();

    Path moduleBasePath = Paths.get("java/com/example");
    BuildTarget buildTargetGuava = BuildTargetFactory.newInstance("//third-party:guava");
    BuildTarget buildTargetJunit = BuildTargetFactory.newInstance("//third-party:junit");

    TargetNode<?> javaLibBase = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:base"))
        .addSrc(moduleBasePath.resolve("base/File.java"))
        .addDep(buildTargetGuava)
        .build();

    TargetNode<?> javaTestBase = JavaTestBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/test:test"))
        .addSrc(moduleBasePath.resolve("test/TestFile.java"))
        .addDep(buildTargetJunit)
        .addDep(buildTargetGuava)
        .build();

    IjModule module = factory.createModule(
        moduleBasePath,
        ImmutableSet.of(javaLibBase, javaTestBase));

    // Guava is a dependency of both a prod and test target therefore it should be kept as a
    // prod dependency.
    assertEquals(ImmutableMap.of(
            buildTargetGuava, IjModuleGraph.DependencyType.PROD,
            buildTargetJunit, IjModuleGraph.DependencyType.TEST),
        module.getDependencies());
  }

  @Test
  public void testModuleDepTypePromotion() {
    IjModuleFactory factory = createIjModuleFactory();

    Path moduleBasePath = Paths.get("java/com/example");
    BuildTarget buildTargetGuava = BuildTargetFactory.newInstance("//third-party:guava");
    BuildTarget buildTargetHamcrest = BuildTargetFactory.newInstance("//third-party:hamcrest");
    BuildTarget buildTargetJunit = BuildTargetFactory.newInstance("//third-party:junit");

    TargetNode<?> javaLibBase = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:base"))
        .addSrc(moduleBasePath.resolve("base/File.java"))
        .addDep(buildTargetGuava)
        .build();

    TargetNode<?> javaTestBase = JavaTestBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:test"))
        .addSrc(moduleBasePath.resolve("base/TestFile.java"))
        .addSrc(moduleBasePath.resolve("test/TestFile.java"))
        .addDep(buildTargetJunit)
        .addDep(buildTargetGuava)
        .build();

    TargetNode<?> javaTest = JavaTestBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/test:test"))
        .addSrc(moduleBasePath.resolve("test/TestFile2.java"))
        .addDep(javaLibBase.getBuildTarget())
        .addDep(buildTargetJunit)
        .addDep(buildTargetHamcrest)
        .build();

    IjModule module = factory.createModule(
        moduleBasePath,
        ImmutableSet.of(javaLibBase, javaTestBase, javaTest));

    // Because both javaLibBase and javaTestBase have source files in the same folder and IntelliJ
    // operates at folder level granularity it is impossible to split the files into separate test
    // and prod sets. Therefore it is necessary to "promote" the test dependencies to prod so that
    // it's possible to compile all of the code in the folder.
    assertEquals(ImmutableMap.of(
            buildTargetGuava, IjModuleGraph.DependencyType.PROD,
            buildTargetJunit, IjModuleGraph.DependencyType.PROD,
            buildTargetHamcrest, IjModuleGraph.DependencyType.TEST),
        module.getDependencies());
  }

  @Test
  public void testDepWhenNoSources() {
    IjModuleFactory factory = createIjModuleFactory();

    Path moduleBasePath = Paths.get("java/com/example");
    BuildTarget buildTargetGuava = BuildTargetFactory.newInstance("//third-party:guava");

    TargetNode<?> javaLibBase = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:base"))
        .addDep(buildTargetGuava)
        .build();

    TargetNode<?> androidBinary = AndroidBinaryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/test:test"))
        .setManifest(new FakeSourcePath("java/com/example/test/AndroidManifest.xml"))
        .setOriginalDeps(ImmutableSortedSet.of(javaLibBase.getBuildTarget()))
        .build();

    IjModule moduleJavaLib = factory.createModule(
        moduleBasePath,
        ImmutableSet.<TargetNode<?>>of(javaLibBase));

    IjModule moduleFromBinary = factory.createModule(
        moduleBasePath,
        ImmutableSet.<TargetNode<?>>of(androidBinary));

    assertEquals(ImmutableMap.of(
            buildTargetGuava, IjModuleGraph.DependencyType.PROD),
        moduleJavaLib.getDependencies());

    assertEquals(ImmutableMap.of(
            javaLibBase.getBuildTarget(), IjModuleGraph.DependencyType.PROD),
        moduleFromBinary.getDependencies());
  }

  @Test
  public void testCompiledShadowDep() {
    IjModuleFactory factory = createIjModuleFactory();

    Path moduleBasePath = Paths.get("java/com/example");
    BuildTarget genruleBuildTarget =
        BuildTargetFactory.newInstance("//java/com/example/base:genrule");

    TargetNode<?> javaLibWithGenrule = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:base_genrule"))
        .addSrcTarget(genruleBuildTarget)
        .build();

    TargetNode<?> javaLibWithAnnotationProcessor = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:base_annotation"))
        .addSrc(Paths.get("java/com/example/base/Base.java"))
        .setAnnotationProcessors(ImmutableSet.of(":annotation_processor"))
        .build();


    IjModule moduleJavaLibWithGenrule = factory.createModule(
        moduleBasePath,
        ImmutableSet.<TargetNode<?>>of(javaLibWithGenrule));

    IjModule moduleJavaLibWithAnnotationProcessor = factory.createModule(
        moduleBasePath,
        ImmutableSet.<TargetNode<?>>of(javaLibWithAnnotationProcessor));

    assertEquals(ImmutableMap.of(
            genruleBuildTarget, IjModuleGraph.DependencyType.PROD,
            javaLibWithGenrule.getBuildTarget(), IjModuleGraph.DependencyType.COMPILED_SHADOW),
        moduleJavaLibWithGenrule.getDependencies());

    assertEquals(ImmutableMap.of(
            javaLibWithAnnotationProcessor.getBuildTarget(),
            IjModuleGraph.DependencyType.COMPILED_SHADOW),
        moduleJavaLibWithAnnotationProcessor.getDependencies());
  }

  @Test
  public void testJavaLibrary() {
    IjModuleFactory factory = createIjModuleFactory();

    TargetNode<?> javaLib = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:base"))
        .addSrc(Paths.get("java/com/example/base/File.java"))
        .build();

    Path moduleBasePath = Paths.get("java/com/example/base");
    IjModule module = factory.createModule(
        moduleBasePath,
        ImmutableSet.<TargetNode<?>>of(javaLib));

    assertEquals(moduleBasePath, module.getModuleBasePath());
    assertFalse(module.getAndroidFacet().isPresent());
    assertEquals(1, module.getFolders().size());
    assertEquals(ImmutableSet.of(javaLib), module.getTargets());

    IjFolder folder = module.getFolders().iterator().next();
    assertEquals(Paths.get("java/com/example/base"), folder.getPath());
    assertFalse(folder.isTest());
    assertTrue(folder.getWantsPackagePrefix());
  }

  @Test
  public void testJavaLibraryInRoot() {
    IjModuleFactory factory = createIjModuleFactory();

    TargetNode<?> javaLib = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//:base"))
        .addSrc(Paths.get("File.java"))
        .build();

    Path moduleBasePath = Paths.get("");
    IjModule module = factory.createModule(
        moduleBasePath,
        ImmutableSet.<TargetNode<?>>of(javaLib));

    assertEquals(moduleBasePath, module.getModuleBasePath());

    assertEquals(1, module.getFolders().size());
    IjFolder folder = module.getFolders().iterator().next();
    assertEquals(Paths.get(""), folder.getPath());
  }

  private ImmutableSet<Path> getFolderPaths(ImmutableSet<IjFolder> folders) {
    return FluentIterable.from(folders)
        .transform(
            new Function<IjFolder, Path>() {
              @Override
              public Path apply(IjFolder input) {
                return input.getPath();
              }
            })
        .toSet();
  }

  @Test
  public void testJavaLibrariesWithParentBasePath() {
    IjModuleFactory factory = createIjModuleFactory();

    TargetNode<?> javaLib1 = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:core"))
        .addSrc(Paths.get("java/com/example/base/src1/File.java"))
        .build();

    TargetNode<?> javaLib2 = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:more"))
        .addSrc(Paths.get("java/com/example/base/src2/File.java"))
        .build();

    IjModule module = factory.createModule(
        Paths.get("java/com/example"),
        ImmutableSet.of(javaLib1, javaLib2));

    ImmutableSet<Path> folderPaths = getFolderPaths(module.getFolders());
    assertEquals(
        ImmutableSet.of(
            Paths.get("java/com/example/base/src1"),
            Paths.get("java/com/example/base/src2")
        ),
        folderPaths);
  }

  @Test
  public void testJavaLibraryAndTestLibraryResultInOnlyOneFolder() {
    IjModuleFactory factory = createIjModuleFactory();

    TargetNode<?> javaLib = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third-party/example:core"))
        .addSrc(Paths.get("third-party/example/File.java"))
        .build();

    TargetNode<?> javaTest = JavaTestBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third-party/example:test"))
        .addSrc(Paths.get("third-party/example/TestFile.java"))
        .addDep(javaLib.getBuildTarget())
        .build();

    IjModule module = factory.createModule(
        Paths.get("third-party/example"),
        ImmutableSet.of(javaLib, javaTest));

    assertEquals(ImmutableSet.of(Paths.get("third-party/example")),
        getFolderPaths(module.getFolders()));
  }

  @Test
  public void testAndroidLibrary() {
    IjModuleFactory factory = createIjModuleFactory();

    TargetNode<?> androidLib = AndroidLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:base"))
        .addSrc(Paths.get("java/com/example/base/File.java"))
        .build();

    Path moduleBasePath = Paths.get("java/com/example/base");
    IjModule module = factory.createModule(
        moduleBasePath,
        ImmutableSet.<TargetNode<?>>of(androidLib));

    assertTrue(module.getAndroidFacet().isPresent());
    assertEquals(ImmutableSet.of(moduleBasePath), getFolderPaths(module.getFolders()));
  }

  @Test
  public void testAndroidLibraries() {
    IjModuleFactory factory = createIjModuleFactory();

    TargetNode<?> javaLib = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:base"))
        .build();

    TargetNode<?> androidLib = AndroidLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:more"))
        .build();

    Path moduleBasePath = Paths.get("java/com/example/base");
    IjModule module = factory.createModule(
        moduleBasePath,
        ImmutableSet.of(androidLib, javaLib));

    assertTrue(module.getAndroidFacet().isPresent());
  }

  @Test
  public void testAndroidBinary() {
    IjModuleFactory factory = createIjModuleFactory();

    String manifestName = "Manifest.xml";
    SourcePath manifestPath = new FakeSourcePath(manifestName);
    TargetNode<?> androidBinary = AndroidBinaryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example:droid"))
        .setManifest(manifestPath)
        .build();

    Path moduleBasePath = Paths.get("java/com/example");
    IjModule module = factory.createModule(
        moduleBasePath,
        ImmutableSet.<TargetNode<?>>of(androidBinary));

    assertTrue(module.getAndroidFacet().isPresent());
    assertEquals(Paths.get(manifestName), module.getAndroidFacet().get().getManifestPath().get());
  }


  private IjModuleFactory createIjModuleFactory() {
    return new IjModuleFactory(
        new IjModuleFactory.IjModuleFactoryResolver() {
          @Override
          public Optional<Path> getDummyRDotJavaPath(TargetNode<?> targetNode) {
            return Optional.absent();
          }

          @Override
          public Path getAndroidManifestPath(
              TargetNode<AndroidBinaryDescription.Arg> targetNode) {
            return ((FakeSourcePath) targetNode.getConstructorArg().manifest).getRelativePath();
          }

          @Override
          public Optional<Path> getProguardConfigPath(
              TargetNode<AndroidBinaryDescription.Arg> targetNode) {
            return Optional.absent();
          }

          @Override
          public Optional<Path> getAndroidResourcePath(
              TargetNode<AndroidResourceDescription.Arg> targetNode) {
            return Optional.absent();
          }

          @Override
          public Optional<Path> getAssetsPath(
              TargetNode<AndroidResourceDescription.Arg> targetNode) {
            return Optional.absent();
          }
        });
  }

  @Test
  public void testCxxLibrary() {
    IjModuleFactory factory = createIjModuleFactory();

    String sourceName = "cpp/lib/foo.cpp";
    TargetNode<?> cxxLibrary = new CxxLibraryBuilder(
        BuildTargetFactory.newInstance("//cpp/lib:foo"))
        .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath(sourceName))))
        .build();

    Path moduleBasePath = Paths.get("java/com/example/base");
    IjModule module = factory.createModule(
        moduleBasePath,
        ImmutableSet.<TargetNode<?>>of(cxxLibrary));

    assertThat(
        module.getFolders(),
        Matchers.contains(
            IjFolder.builder()
                .setPath(Paths.get("cpp/lib"))
                .setType(AbstractIjFolder.Type.SOURCE_FOLDER)
                .setWantsPackagePrefix(false)
                .setInputs(ImmutableSortedSet.of(Paths.get("cpp/lib/foo.cpp")))
                .build())
    );
  }
}
