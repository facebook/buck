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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.features.project.intellij.lang.android.AndroidManifestParser;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.DefaultJavaPackageFinder;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.timing.AbstractFakeClock;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class IjProjectWriterTest {

  private final long TIMESTAMP_A = 12111;
  private final long TIMESTAMP_B = 22122;
  private final Path PROJECT_ROOT = Paths.get("projectRoot");
  private final Path MODULES_XML = PROJECT_ROOT.resolve(".idea/modules.xml");
  private final Path WORKSPACE_XML = PROJECT_ROOT.resolve(".idea/workspace.xml");

  @Test
  public void testModuleChangeOverwrite() throws IOException {
    FakeDynamicClock fakeClock = new FakeDynamicClock(TIMESTAMP_A);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem(fakeClock);
    write(filesystem, filesystem, moduleGraph1());
    assertEquals(TIMESTAMP_A, filesystem.getLastModifiedTime(MODULES_XML).toMillis());
    fakeClock.currentTime = TIMESTAMP_B;
    write(filesystem, filesystem, moduleGraph2());
    assertEquals(TIMESTAMP_B, filesystem.getLastModifiedTime(MODULES_XML).toMillis());
  }

  @Test
  public void testNoModuleChangeNoOverwrite() throws IOException {
    FakeDynamicClock fakeClock = new FakeDynamicClock(TIMESTAMP_A);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem(fakeClock);
    write(filesystem, filesystem, moduleGraph1());
    assertEquals(TIMESTAMP_A, filesystem.getLastModifiedTime(MODULES_XML).toMillis());
    fakeClock.currentTime = TIMESTAMP_B;
    write(filesystem, filesystem, moduleGraph1());
    assertEquals(TIMESTAMP_A, filesystem.getLastModifiedTime(MODULES_XML).toMillis());
  }

  @Test
  public void testOutputDir() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path tmp = Files.createTempDirectory("IjProjectWriterTest");
    FakeProjectFilesystem outFilesystem = new FakeProjectFilesystem(tmp);
    write(filesystem, outFilesystem, moduleGraph1());
    assertFalse(filesystem.exists(MODULES_XML));
    assertFalse(filesystem.exists(WORKSPACE_XML));
    assertTrue(outFilesystem.exists(MODULES_XML));
    assertTrue(outFilesystem.exists(WORKSPACE_XML));
  }

  private IjModuleGraph moduleGraph1() {
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
            .build();

    return IjModuleGraphTest.createModuleGraph(ImmutableSet.of(guavaTargetNode, baseTargetNode));
  }

  private IjModuleGraph moduleGraph2() {
    TargetNode<?> baseTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base:base"))
            .addSrc(Paths.get("java/com/example/base/Base.java"))
            .build();

    return IjModuleGraphTest.createModuleGraph(ImmutableSet.of(baseTargetNode));
  }

  private void write(
      ProjectFilesystem filesystem, ProjectFilesystem outFilesystem, IjModuleGraph moduleGraph)
      throws IOException {
    IjProjectTemplateDataPreparer dataPreparer = dataPreparer(filesystem, moduleGraph);
    IntellijModulesListParser parser = new IntellijModulesListParser();
    IjProjectConfig config = projectConfig();
    IJProjectCleaner cleaner = new IJProjectCleaner(filesystem);
    IjProjectWriter writer =
        new IjProjectWriter(dataPreparer, config, filesystem, parser, cleaner, outFilesystem);
    writer.write();
  }

  private IjProjectTemplateDataPreparer dataPreparer(
      ProjectFilesystem filesystem, IjModuleGraph moduleGraph) {
    JavaPackageFinder javaPackageFinder =
        DefaultJavaPackageFinder.createDefaultJavaPackageFinder(ImmutableSet.of());
    AndroidManifestParser androidManifestParser = new AndroidManifestParser(filesystem);
    return new IjProjectTemplateDataPreparer(
        javaPackageFinder, moduleGraph, filesystem, projectConfig(), androidManifestParser);
  }

  private IjProjectConfig projectConfig() {
    return IjProjectBuckConfig.create(
        FakeBuckConfig.builder().build(),
        null,
        null,
        PROJECT_ROOT.toString(),
        "modules",
        false,
        false,
        true,
        false,
        true);
  }

  // Mutable FakeClock, to provide distinct timestamps to a single FakeProjectFileSystem
  class FakeDynamicClock extends AbstractFakeClock {
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
