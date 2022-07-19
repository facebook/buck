/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.features.project.intellij;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.android.AndroidPrebuiltAarBuilder;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.ConfigurationForConfigurationTargets;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.RuleBasedTargetConfiguration;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.features.project.intellij.model.IjLibrary;
import com.facebook.buck.features.project.intellij.model.IjLibraryFactory;
import com.facebook.buck.features.project.intellij.model.IjLibraryFactoryResolver;
import com.facebook.buck.features.project.intellij.model.LibraryBuildContext;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.PrebuiltJarBuilder;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class DefaultIjLibraryFactoryTest {

  private SourcePathResolverAdapter sourcePathResolverAdapter;
  private Path guavaJarPath;
  private TargetNode<?> guava;
  private TargetNode<?> androidSupport;
  private PathSourcePath androidSupportBinaryPath;
  private PathSourcePath androidSupportResClassPath;
  private TargetNode<?> base;
  private PathSourcePath androidSupportBinaryJarPath;
  private PathSourcePath baseOutputPath;
  private IjLibraryFactoryResolver libraryFactoryResolver;
  private IjLibraryFactory factory;
  private TargetGraph targetGraph;
  private boolean isTargetConfigurationInLibrariesEnabled = true;

  @Before
  public void setUp() {
    sourcePathResolverAdapter = new TestActionGraphBuilder().getSourcePathResolver();
    guavaJarPath = Paths.get("third_party/java/guava.jar");
    guava =
        PrebuiltJarBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third_party/java/guava:guava"))
            .setBinaryJar(guavaJarPath)
            .build();

    androidSupportBinaryPath = FakeSourcePath.of("third_party/java/support/support.aar");
    BuildTarget androidSupportTarget =
        BuildTargetFactory.newInstance("//third_party/java/support:support");
    androidSupport =
        AndroidPrebuiltAarBuilder.createBuilder(androidSupportTarget)
            .setBinaryAar(androidSupportBinaryPath)
            .build();

    base =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base:base"))
            .addDep(guava.getBuildTarget())
            .build();

    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    androidSupportBinaryJarPath =
        FakeSourcePath.of(
            BuildTargetPaths.getScratchPath(
                filesystem,
                androidSupportTarget.withAppendedFlavors(InternalFlavor.of("aar")),
                "__unpack_%s_unzip__/classes.jar"));
    androidSupportResClassPath =
        FakeSourcePath.of(
            BuildTargetPaths.getScratchPath(
                filesystem,
                androidSupportTarget.withAppendedFlavors(InternalFlavor.of("aar")),
                "__unpack_%s_unzip__/res"));
    baseOutputPath = FakeSourcePath.of("buck-out/base.jar");

    libraryFactoryResolver =
        new IjLibraryFactoryResolver() {
          @Override
          public Path getPath(SourcePath path) {
            return sourcePathResolverAdapter.getCellUnsafeRelPath(path).getPath();
          }

          @Override
          public Optional<SourcePath> getPathIfJavaLibrary(TargetNode<?> targetNode) {
            if (targetNode.equals(base)) {
              return Optional.of(baseOutputPath);
            }
            if (targetNode.equals(androidSupport)) {
              return Optional.of(androidSupportBinaryJarPath);
            }
            return Optional.empty();
          }
        };

    targetGraph = TargetGraphFactory.newInstance(guava, androidSupport, base);
    factory = getIjLibraryFactory();
  }

  @Test
  public void testPrebuiltJar() {
    LibraryBuildContext guavaLibElement = factory.getLibrary(guava).get();

    IjLibrary guavaLibrary = guavaLibElement.getAggregatedLibrary();

    assertEquals("//third_party/java/guava:guava", guavaLibrary.getName());
    assertEquals(ImmutableSet.of(guavaJarPath), guavaLibrary.getBinaryJars());
    assertEquals(ImmutableSet.of(guava.getBuildTarget()), guavaLibrary.getTargets());
  }

  @Test
  public void testPrebuiltAar() {
    LibraryBuildContext androidSupportLibElement = factory.getLibrary(androidSupport).get();

    IjLibrary androidSupportLibrary = androidSupportLibElement.getAggregatedLibrary();

    assertEquals("//third_party/java/support:support", androidSupportLibrary.getName());
    assertEquals(
        ImmutableSet.of(androidSupportBinaryJarPath.getRelativePath()),
        androidSupportLibrary.getBinaryJars());
    assertEquals(
        ImmutableSet.of(androidSupport.getBuildTarget()), androidSupportLibrary.getTargets());
    assertEquals(
        ImmutableSet.of(androidSupportResClassPath.getRelativePath()),
        androidSupportLibrary.getClassPaths());
  }

  @Test
  public void testLibraryFromOtherTargets() {
    LibraryBuildContext baseLibElement = factory.getLibrary(base).get();

    IjLibrary baseLibrary = baseLibElement.getAggregatedLibrary();

    assertEquals("//java/com/example/base:base", baseLibrary.getName());
    assertEquals(ImmutableSet.of(baseOutputPath.getRelativePath()), baseLibrary.getBinaryJars());
    assertEquals(ImmutableSet.of(base.getBuildTarget()), baseLibrary.getTargets());
  }

  @Test
  public void testLibraryBuildContextWithTargetConfiguration() {
    RuleBasedTargetConfiguration c1Config =
        RuleBasedTargetConfiguration.of(
            BuildTargetFactory.newInstance(
                "config//fake:c1", ConfigurationForConfigurationTargets.INSTANCE));

    Path bazJarPath = Path.of("modules/foo/bar/baz.jar");

    TargetNode<?> nodeWithC1Config =
        PrebuiltJarBuilder.createBuilder(
                BuildTargetFactory.newInstance("//modules/foo/bar:baz", c1Config))
            .setBinaryJar(bazJarPath)
            .build();

    LibraryBuildContext nodeWithC1ConfigLibContext = factory.getLibrary(nodeWithC1Config).get();
    IjLibrary nodeWithC1ConfigLib = nodeWithC1ConfigLibContext.getAggregatedLibrary();

    assertEquals("//modules/foo/bar:baz (config//fake:c1)", nodeWithC1ConfigLib.getName());
    assertEquals(ImmutableSet.of(bazJarPath), nodeWithC1ConfigLib.getBinaryJars());
    assertEquals(
        ImmutableSet.of(nodeWithC1Config.getBuildTarget()), nodeWithC1ConfigLib.getTargets());

    RuleBasedTargetConfiguration c2Config =
        RuleBasedTargetConfiguration.of(
            BuildTargetFactory.newInstance(
                "config//fake:c2", ConfigurationForConfigurationTargets.INSTANCE));

    TargetNode<?> nodeWithC2Config =
        PrebuiltJarBuilder.createBuilder(
                BuildTargetFactory.newInstance("//modules/foo/bar:baz", c2Config))
            .setBinaryJar(bazJarPath)
            .build();

    LibraryBuildContext nodeWithC2ConfigLibContext = factory.getLibrary(nodeWithC2Config).get();
    IjLibrary nodeWithC2ConfigLib = nodeWithC2ConfigLibContext.getAggregatedLibrary();

    assertEquals("//modules/foo/bar:baz (config//fake:c2)", nodeWithC2ConfigLib.getName());
    assertEquals(ImmutableSet.of(bazJarPath), nodeWithC2ConfigLib.getBinaryJars());
    assertEquals(
        ImmutableSet.of(nodeWithC2Config.getBuildTarget()), nodeWithC2ConfigLib.getTargets());
  }

  @Test
  public void testLibraryBuildContextWithoutTargetConfiguration() {
    isTargetConfigurationInLibrariesEnabled = false;
    factory = getIjLibraryFactory();

    RuleBasedTargetConfiguration c1Config =
        RuleBasedTargetConfiguration.of(
            BuildTargetFactory.newInstance(
                "config//fake:c1", ConfigurationForConfigurationTargets.INSTANCE));

    Path bazJarPath = Path.of("modules/foo/bar/baz.jar");

    TargetNode<?> nodeWithC1Config =
        PrebuiltJarBuilder.createBuilder(
                BuildTargetFactory.newInstance("//modules/foo/bar:baz", c1Config))
            .setBinaryJar(bazJarPath)
            .build();

    RuleBasedTargetConfiguration c2Config =
        RuleBasedTargetConfiguration.of(
            BuildTargetFactory.newInstance(
                "config//fake:c2", ConfigurationForConfigurationTargets.INSTANCE));

    TargetNode<?> nodeWithC2Config =
        PrebuiltJarBuilder.createBuilder(
                BuildTargetFactory.newInstance("//modules/foo/bar:baz", c2Config))
            .setBinaryJar(bazJarPath)
            .build();

    LibraryBuildContext nodeWithC1ConfigLibContext = factory.getLibrary(nodeWithC1Config).get();
    LibraryBuildContext nodeWithC2ConfigLibContext = factory.getLibrary(nodeWithC2Config).get();
    IjLibrary nodeWithC2ConfigLib = nodeWithC2ConfigLibContext.getAggregatedLibrary();
    IjLibrary nodeWithC1ConfigLib = nodeWithC1ConfigLibContext.getAggregatedLibrary();

    assertEquals("//modules/foo/bar:baz", nodeWithC1ConfigLib.getName());
    assertEquals(ImmutableSet.of(bazJarPath), nodeWithC1ConfigLib.getBinaryJars());
    assertEquals(
        ImmutableSet.of(nodeWithC1Config.getBuildTarget(), nodeWithC2Config.getBuildTarget()),
        nodeWithC1ConfigLib.getTargets());

    assertEquals("//modules/foo/bar:baz", nodeWithC2ConfigLib.getName());
    assertEquals(ImmutableSet.of(bazJarPath), nodeWithC2ConfigLib.getBinaryJars());
    assertEquals(nodeWithC1ConfigLibContext, nodeWithC2ConfigLibContext);
    assertEquals(nodeWithC1ConfigLib, nodeWithC2ConfigLib);
  }

  private IjLibraryFactory getIjLibraryFactory() {
    return new DefaultIjLibraryFactory(
        targetGraph, libraryFactoryResolver, isTargetConfigurationInLibrariesEnabled);
  }
}
