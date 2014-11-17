/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.java.abi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class StubJarIntegrationTest {

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();
  private Path testDataDir;
  private ProjectFilesystem filesystem;

  @Before
  public void createWorkspace() throws IOException {
    File dir = TestDataHelper.getTestDataDirectory(this);
    testDataDir = dir.toPath().resolve("sample").toAbsolutePath();

    filesystem = new ProjectFilesystem(temp.newFolder().toPath());
  }

  @Test
  public void shouldBuildAbiJar() throws IOException {
    Path out = Paths.get("junit-abi.jar");
    Path regularJar = testDataDir.resolve("junit.jar");
    new StubJar(regularJar).writeTo(filesystem, out);

    // We assume that the lack of an exception indicates that the abi jar is correct. See MirrorTest
    // for why this is so.
    assertTrue(filesystem.getFileSize(out) > 0);
    assertTrue(filesystem.getFileSize(out) < filesystem.getFileSize(regularJar));
  }

  @Test
  public void shouldBuildAbiJarFromAbiJarWeCreated() throws IOException {
    Path mid = Paths.get("junit-mid.jar");
    Path source = testDataDir.resolve("junit.jar");
    new StubJar(source).writeTo(filesystem, mid);

    Path out = Paths.get("junit-abi.jar");
    new StubJar(filesystem.resolve(mid)).writeTo(filesystem, out);

    assertTrue(filesystem.getFileSize(out) > 0);
    assertEquals(filesystem.getFileSize(mid), filesystem.getFileSize(out));
  }

  @Test
  public void shouldBuildAbiJarFromAThirdPartyStubbedJar() throws IOException {
    Path out = Paths.get("android-abi.jar");
    Path source = testDataDir.resolve("android.jar");
    new StubJar(source).writeTo(filesystem, out);

    assertTrue(filesystem.getFileSize(out) > 0);
    assertTrue(filesystem.getFileSize(out) < filesystem.getFileSize(source));
  }

  @Test
  public void shouldBuildAbiJarEvenIfAsmWouldChokeOnAFrame() throws IOException {
    Path out = Paths.get("unity-abi.jar");
    Path source = testDataDir.resolve("unity.jar");
    new StubJar(source).writeTo(filesystem, out);

    assertTrue(filesystem.getFileSize(out) > 0);
    assertTrue(filesystem.getFileSize(out) < filesystem.getFileSize(source));
  }
}
