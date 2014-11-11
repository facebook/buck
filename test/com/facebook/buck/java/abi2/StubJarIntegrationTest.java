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

package com.facebook.buck.java.abi2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class StubJarIntegrationTest {

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();
  private Path testDataDir;

  @Before
  public void createWorkspace() throws IOException {
    File dir = TestDataHelper.getTestDataDirectory(this);
    testDataDir = dir.toPath().resolve("sample").toAbsolutePath();
  }

  @Test
  public void shouldBuildAbiJar() throws IOException {
    Path out = temp.newFolder().toPath().resolve("junit-abi.jar");
    Path regularJar = testDataDir.resolve("junit.jar");
    new StubJar(regularJar).writeTo(out);

    // We assume that the lack of an exception indicates that the abi jar is correct. See MirrorTest
    // for why this is so.
    assertTrue(Files.size(out) > 0);
    assertTrue(Files.size(out) < Files.size(regularJar));
  }

  @Test
  public void shouldBuildAbiJarFromAbiJarWeCreated() throws IOException {
    Path outDir = temp.newFolder().toPath();

    Path mid = outDir.resolve("junit-mid.jar");
    Path source = testDataDir.resolve("junit.jar");
    new StubJar(source).writeTo(mid);

    Path out = outDir.resolve("junit-abi.jar");
    new StubJar(mid).writeTo(out);

    assertTrue(Files.size(out) > 0);
    assertEquals(Files.size(mid), Files.size(out));
  }

  @Test
  public void shouldBuildAbiJarFromAThirdPartyStubbedJar() throws IOException {
    Path out = temp.newFolder().toPath().resolve("android-abi.jar");
    Path source = testDataDir.resolve("android.jar");
    new StubJar(source).writeTo(out);

    assertTrue(Files.size(out) > 0);
    assertTrue(Files.size(out) < Files.size(source));
  }

  @Test
  public void shouldBuildAbiJarEvenIfAsmWouldChokeOnAFrame() throws IOException {
    Path out = temp.newFolder().toPath().resolve("unity-abi.jar");
    Path source = testDataDir.resolve("unity.jar");
    new StubJar(source).writeTo(out);

    assertTrue(Files.size(out) > 0);
    assertTrue(Files.size(out) < Files.size(source));
  }
}
