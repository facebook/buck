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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.AndroidBinaryBuilder;
import com.facebook.buck.android.AndroidLibraryBuilder;
import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.java.PrebuiltJarBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.ProjectConfigBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TestSourcePath;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class IjModuleFactoryTest {

  private static final IjLibraryFactory NO_OP_LIBRARY_FACTORY = new IjLibraryFactory() {

    @Override
    public Optional<IjLibrary> getLibrary(BuildTarget target) {
      return Optional.absent();
    }
  };

  @Test
  public void testIjLibraryResolution() {
    final IjLibrary testLibrary = IjLibrary.builder()
        .setName("library_test_library")
        .setBinaryJar(Paths.get("test.jar"))
        .build();

    final TargetNode<?> prebuiltJar = PrebuiltJarBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third-party:prebuilt"))
        .build();

    TargetNode<?> javaLib = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example/base:base"))
        .addSrc(Paths.get("java/com/example/base/File.java"))
        .addDep(prebuiltJar.getBuildTarget())
        .build();

    IjLibraryFactory testLibraryFactory = new IjLibraryFactory() {

      @Override
      public Optional<IjLibrary> getLibrary(BuildTarget target) {
        if (target.equals(prebuiltJar.getBuildTarget())) {
          return Optional.of(testLibrary);
        }
        return Optional.absent();
      }
    };

    IjModuleFactory factory = new IjModuleFactory(testLibraryFactory);
    Path moduleBasePath = Paths.get("java/com/example/base");
    IjModule module = factory.createModule(
        moduleBasePath,
        ImmutableSet.<TargetNode<?>>of(javaLib));

    assertEquals(ImmutableSet.of(testLibrary), module.getLibraries());
  }

  @Test
  public void testJavaLibrary() {
    IjModuleFactory factory = new IjModuleFactory(NO_OP_LIBRARY_FACTORY);

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

    IjFolder folder = module.getFolders().iterator().next();
    assertEquals(Paths.get("java/com/example/base"), folder.getPath());
    assertFalse(folder.isTest());
    assertTrue(folder.getWantsPackagePrefix());
  }

  @Test
  public void testJavaLibraryInRoot() {
    IjModuleFactory factory = new IjModuleFactory(NO_OP_LIBRARY_FACTORY);

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
    IjModuleFactory factory = new IjModuleFactory(NO_OP_LIBRARY_FACTORY);

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
  public void testAndroidLibrary() {
    IjModuleFactory factory = new IjModuleFactory(NO_OP_LIBRARY_FACTORY);

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
    IjModuleFactory factory = new IjModuleFactory(NO_OP_LIBRARY_FACTORY);

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
    IjModuleFactory factory = new IjModuleFactory(NO_OP_LIBRARY_FACTORY);

    SourcePath manifestPath = new TestSourcePath("AndroidManifest.xml");
    TargetNode<?> androidBinary = AndroidBinaryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example:droid"))
        .setManifest(manifestPath)
        .build();

    Path moduleBasePath = Paths.get("java/com/example");
    IjModule module = factory.createModule(
        moduleBasePath,
        ImmutableSet.<TargetNode<?>>of(androidBinary));

    assertTrue(module.getAndroidFacet().isPresent());
    assertEquals(manifestPath, module.getAndroidFacet().get().getManifestPath().get());
  }

  @Test
  public void testProjectConfig() {
    IjModuleFactory factory = new IjModuleFactory(NO_OP_LIBRARY_FACTORY);

    TargetNode<?> javaLib = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third-party/lib:lib"))
        .addSrc(Paths.get("third-party/lib/1.0/org/lib/File.java"))
        .build();

    TargetNode<?> projectConfig = ProjectConfigBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third-party/lib:project_config"))
        .setSrcRule(javaLib.getBuildTarget())
        .setSrcRoots(ImmutableList.<String>of("1.0"))
        .build();

    Path moduleBasePath = Paths.get("third-party/lib");
    IjModule module = factory.createModule(
        moduleBasePath,
        ImmutableSet.of(javaLib, projectConfig));

    assertEquals(ImmutableSet.of(Paths.get("third-party/lib/1.0")),
        getFolderPaths(module.getFolders()));
  }

  @Test
  public void testProjectConfigExclusiveRuleResolution() {
    IjModuleFactory factory = new IjModuleFactory(NO_OP_LIBRARY_FACTORY);

    SourcePath blessedManifestPath = new TestSourcePath("AndroidManifest.xml");
    TargetNode<?> blessedAndroidBinary = AndroidBinaryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example:droid_blessed"))
        .setManifest(blessedManifestPath)
        .build();

    TargetNode<?> otherAndroidBinary = AndroidBinaryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example:droid_other"))
        .setManifest(new TestSourcePath("OtherAndroidManifest.xml"))
        .build();

    TargetNode<?> projectConfig = ProjectConfigBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/example:project_config"))
        .setSrcRule(blessedAndroidBinary.getBuildTarget())
        .build();

    Path moduleBasePath = Paths.get("java/com/example");
    IjModule module = factory.createModule(
        moduleBasePath,
        ImmutableSet.of(blessedAndroidBinary, otherAndroidBinary, projectConfig));

    assertTrue(module.getAndroidFacet().isPresent());
    assertEquals(blessedManifestPath, module.getAndroidFacet().get().getManifestPath().get());
  }

}
