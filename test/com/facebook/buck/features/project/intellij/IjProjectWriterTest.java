/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.features.project.intellij.lang.android.AndroidManifestParser;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.DefaultJavaPackageFinder;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class IjProjectWriterTest {

  private final long TIMESTAMP_A = 12111;
  private final long TIMESTAMP_B = 22122;
  private final Path MODULES_XML = Paths.get(".idea/modules.xml");
  private final Path WORKSPACE_XML = Paths.get(".idea/workspace.xml");

  @Test
  public void testModuleChangeOverwrite() throws IOException {
    FakeDynamicClock fakeClock = new FakeDynamicClock(TIMESTAMP_A);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem(fakeClock);
    getWriterForModuleGraph1(filesystem, filesystem).write();
    assertEquals(TIMESTAMP_A, filesystem.getLastModifiedTime(MODULES_XML).toMillis());
    fakeClock.currentTime = TIMESTAMP_B;
    getWriterForModuleGraph2(filesystem, filesystem).write();
    assertEquals(TIMESTAMP_B, filesystem.getLastModifiedTime(MODULES_XML).toMillis());
  }

  @Test
  public void testNoModuleChangeNoOverwrite() throws IOException {
    FakeDynamicClock fakeClock = new FakeDynamicClock(TIMESTAMP_A);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem(fakeClock);
    getWriterForModuleGraph1(filesystem, filesystem).write();
    assertEquals(TIMESTAMP_A, filesystem.getLastModifiedTime(MODULES_XML).toMillis());
    fakeClock.currentTime = TIMESTAMP_B;
    getWriterForModuleGraph1(filesystem, filesystem).write();
    assertEquals(TIMESTAMP_A, filesystem.getLastModifiedTime(MODULES_XML).toMillis());
  }

  @Test
  public void testOutputDir() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path tmp = Files.createTempDirectory("IjProjectWriterTest");
    FakeProjectFilesystem outFilesystem = new FakeProjectFilesystem(tmp);
    getWriterForModuleGraph1(filesystem, outFilesystem).write();
    assertFalse(filesystem.exists(MODULES_XML));
    assertFalse(filesystem.exists(WORKSPACE_XML));
    assertTrue(outFilesystem.exists(MODULES_XML));
    assertTrue(outFilesystem.exists(WORKSPACE_XML));
  }

  private IjProjectWriter getWriterForModuleGraph1(
      ProjectFilesystem filesystem, ProjectFilesystem outFileSystem) {
    TargetNode<?> guavaTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third_party/guava:guava"))
            .addSrc(Paths.get("third_party/guava/src/Collections.java"))
            .build();

    TargetNode<?> baseTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base:base"))
            .addDep(guavaTargetNode.getBuildTarget())
            .addSrc(Paths.get("java/com/example/base/Base.java"))
            .addAnnotationProcessors("//annotation:processor")
            .build();

    ImmutableSet<TargetNode<?>> targetNodes = ImmutableSet.of(guavaTargetNode, baseTargetNode);
    return writer(filesystem, outFileSystem, IjModuleGraphTest.createModuleGraph(targetNodes));
  }

  private IjProjectWriter getWriterForModuleGraph2(
      ProjectFilesystem filesystem, ProjectFilesystem outFileSystem) {
    TargetNode<?> baseTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base:base"))
            .addSrc(Paths.get("java/com/example/base/BaseChanged.java"))
            .build();

    TargetNode<?> base2TargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base2:base2"))
            .addSrc(Paths.get("java/com/example/base/Base.java"))
            .build();
    return writer(
        filesystem,
        outFileSystem,
        IjModuleGraphTest.createModuleGraph(ImmutableSet.of(baseTargetNode, base2TargetNode)));
  }

  private IjProjectWriter writer(
      ProjectFilesystem filesystem, ProjectFilesystem outFilesystem, IjModuleGraph moduleGraph) {
    IjProjectTemplateDataPreparer dataPreparer = dataPreparer(filesystem, moduleGraph);
    IntellijModulesListParser parser = new IntellijModulesListParser();
    IjProjectConfig config = projectConfig();
    IJProjectCleaner cleaner = new IJProjectCleaner(filesystem);
    return new IjProjectWriter(
        dataPreparer,
        config,
        filesystem,
        parser,
        cleaner,
        outFilesystem,
        new BuckOutPathConverter(config));
  }

  private IjProjectTemplateDataPreparer dataPreparer(
      ProjectFilesystem filesystem, IjModuleGraph moduleGraph) {
    JavaPackageFinder javaPackageFinder =
        DefaultJavaPackageFinder.createDefaultJavaPackageFinder(filesystem, ImmutableSet.of());
    AndroidManifestParser androidManifestParser = new AndroidManifestParser(filesystem);
    return new IjProjectTemplateDataPreparer(
        javaPackageFinder, moduleGraph, filesystem, projectConfig(), androidManifestParser);
  }

  private IjProjectConfig projectConfig() {
    return IjTestProjectConfig.createBuilder(FakeBuckConfig.builder().build()).build();
  }

  // Mutable FakeClock, to provide distinct timestamps to a single FakeProjectFileSystem
  static class FakeDynamicClock extends FakeClock {
    long currentTime;

    public FakeDynamicClock(long currentTime) {
      this.currentTime = currentTime;
    }

    @Override
    public long currentTimeMillis() {
      return currentTime;
    }

    @Override
    public long nanoTime() {
      return 0;
    }
  }
}
