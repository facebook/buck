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

package com.facebook.buck.android;

import static org.hamcrest.CoreMatchers.hasItem;
import static org.junit.Assert.assertThat;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AndroidBinaryRDotJavaIntegrationTest {

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            new AndroidBinaryRDotJavaIntegrationTest(), "android_project", tmpFolder);
    workspace.setUp();
    filesystem = TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
  }

  @Test
  public void testResourceSplitting() throws IOException {
    ImmutableMap<String, Path> outputs =
        workspace.buildMultipleAndReturnOutputs(
            "//apps/multidex:disassemble_big_r_dot_java_primary",
            "//apps/multidex:disassemble_big_r_dot_java_classes2",
            "//apps/multidex:disassemble_big_r_dot_java_classes3");

    Set<String> primaryClasses =
        ImmutableSet.copyOf(
            filesystem.readLines(
                outputs.get("//apps/multidex:disassemble_big_r_dot_java_primary")));
    assertThat(primaryClasses, hasItem("Lcom/primary/R$id;"));
    assertThat(primaryClasses, hasItem("Lcom/primary/R$string;"));
    assertThat(primaryClasses, hasItem("Lcom/primary/R$color;"));

    // This is kind of brittle.  We assume that there are exactly 2 secondary dexes.
    // Better would be to use ZipInspector to count the dexes, verify there are at least 2,
    // and use baksmali directly to diassemble them.  The last part turns out to be the trickiest
    // because baksmali is shipped as an uber-jar.
    Set<String> secondary2Classes =
        ImmutableSet.copyOf(
            filesystem.readLines(
                outputs.get("//apps/multidex:disassemble_big_r_dot_java_classes2")));
    Set<String> secondary3Classes =
        ImmutableSet.copyOf(
            filesystem.readLines(
                outputs.get("//apps/multidex:disassemble_big_r_dot_java_classes3")));
    Set<String> secondaryClasses = Sets.union(secondary2Classes, secondary3Classes);
    assertThat(secondaryClasses, hasItem("Lcom/secondary1/R$id;"));
    assertThat(secondaryClasses, hasItem("Lcom/secondary1/R$string;"));
    assertThat(secondaryClasses, hasItem("Lcom/secondary1/R$color;"));
    assertThat(secondaryClasses, hasItem("Lcom/secondary2/R$id;"));
    assertThat(secondaryClasses, hasItem("Lcom/secondary2/R$string;"));
    assertThat(secondaryClasses, hasItem("Lcom/secondary2/R$color;"));
  }
}
