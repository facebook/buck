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

package com.facebook.buck.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.io.Files;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class BuildRuleFactoryParamsTest {

  @ClassRule public static TemporaryFolder folder = new TemporaryFolder();
  private static ProjectFilesystem filesystem;
  private BuildTargetParser parser;

  @BeforeClass
  public static void layOutExampleProject() throws IOException {
    File root = folder.getRoot();
    filesystem = new ProjectFilesystem(root.toPath());

    File deepest = new File(root, "src/com/facebook/demo");
    assertTrue("Unable to create test project layout", deepest.mkdirs());

    File lacksBuildFile = new File(root, "src/com/facebook/nobuild");
    assertTrue("Unable to create test project layout", lacksBuildFile.mkdirs());

    // The files just need to exist. We never actually read their contents.
    Files.touch(new File(root, "src/com/facebook/BUCK"));
    Files.touch(new File(root, "src/com/facebook/A.java"));
    Files.touch(new File(root, "src/com/facebook/demo/BUCK"));
    Files.touch(new File(root, "src/com/facebook/demo/B.java"));
    Files.touch(new File(root, "src/com/facebook/nobuild/C.java"));
  }

  @AfterClass
  public static void deleteExampleProject() {
    folder.delete();
  }

  @Before
  public void prepareParser() throws IOException {
    parser = new BuildTargetParser();
  }

  @Test
  public void testResolveFilePathRelativeToBuildFileDirectoryInRootDirectory() throws IOException {

    Files.touch(filesystem.getRootPath().resolve("build.xml").toFile());

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:wakizashi");
    BuildRuleFactoryParams params = new BuildRuleFactoryParams(
        filesystem,
        parser,
        buildTarget,
        new FakeRuleKeyBuilderFactory());

    assertEquals(Paths.get("build.xml"),
        params.resolveFilePathRelativeToBuildFileDirectory("build.xml"));
  }

  @Test
  public void testResolveFilePathRelativeToBuildFileDirectoryInSubDirectory() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//src/com/facebook:Main");

    BuildRuleFactoryParams params = new BuildRuleFactoryParams(
        filesystem,
        parser,
        buildTarget,
        new FakeRuleKeyBuilderFactory());
    assertEquals(Paths.get("src/com/facebook/A.java"),
        params.resolveFilePathRelativeToBuildFileDirectory("A.java"));
  }

  @Test
  public void testShouldAllowChildPathsIfTargetBuildFileIsClosest() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//src/com/facebook:boundary");

    BuildRuleFactoryParams params = new BuildRuleFactoryParams(
        filesystem,
        parser,
        buildTarget,
        new FakeRuleKeyBuilderFactory());
    // File exists, is in a subdir but does not cross a buck package boundary
    Path relativePath = params.resolveFilePathRelativeToBuildFileDirectory("nobuild/C.java");
    assertEquals("src/com/facebook/nobuild/C.java", relativePath.toString());
  }
}
