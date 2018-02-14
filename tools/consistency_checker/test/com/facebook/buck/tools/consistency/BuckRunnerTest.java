/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.tools.consistency;

import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class BuckRunnerTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();
  private Path binPath;
  private TestPrintStream stream = TestPrintStream.create();
  private TestBinWriter writer;

  @Before
  public void setUp() throws IOException {
    binPath = temporaryFolder.newFile("test.py");
    writer = new TestBinWriter(binPath);
  }

  @Test
  public void sendsStdOutBack() throws IOException, InterruptedException {
    writer.writeArgEchoer(0);

    BuckRunner runner =
        new BuckRunner(
            Optional.of("python"),
            binPath.toAbsolutePath().toString(),
            "build",
            ImmutableList.of("-c", "cxx.cxx=/bin/false"),
            ImmutableList.of("//:main", "//:test"),
            ImmutableList.of(),
            Optional.of(temporaryFolder.getRoot()),
            false);
    int ret = runner.run(stream);

    Assert.assertEquals(0, ret);
    String[] lines = stream.getOutputLines();

    Assert.assertEquals(7, lines.length);
    Assert.assertEquals(temporaryFolder.getRoot().toString(), lines[0]);
    Assert.assertEquals(binPath.toAbsolutePath().toString(), lines[1]);
    Assert.assertEquals("build", lines[2]);
    Assert.assertEquals("-c", lines[3]);
    Assert.assertEquals("cxx.cxx=/bin/false", lines[4]);
    Assert.assertEquals("//:main", lines[5]);
    Assert.assertEquals("//:test", lines[6]);
  }

  @Test
  public void setsReturnCodePropertly() throws IOException, InterruptedException {
    writer.writeArgEchoer(97);

    BuckRunner runner =
        new BuckRunner(
            Optional.of("python"),
            binPath.toAbsolutePath().toString(),
            "build",
            ImmutableList.of("-c", "cxx.cxx=/bin/false"),
            ImmutableList.of("//:main", "//:test"),
            ImmutableList.of(),
            Optional.of(temporaryFolder.getRoot()),
            false);
    int ret = runner.run(stream);

    Assert.assertEquals(97, ret);
    String[] lines = stream.getOutputLines();

    Assert.assertEquals(7, lines.length);
    Assert.assertEquals(temporaryFolder.getRoot().toString(), lines[0]);
    Assert.assertEquals(binPath.toAbsolutePath().toString(), lines[1]);
    Assert.assertEquals("build", lines[2]);
    Assert.assertEquals("-c", lines[3]);
    Assert.assertEquals("cxx.cxx=/bin/false", lines[4]);
    Assert.assertEquals("//:main", lines[5]);
    Assert.assertEquals("//:test", lines[6]);
  }

  @Test
  public void runsInCwdIfRepositoryNotSpecified() throws IOException, InterruptedException {
    writer.writeArgEchoer(0);

    BuckRunner runner =
        new BuckRunner(
            Optional.of("python"),
            binPath.toAbsolutePath().toString(),
            "build",
            ImmutableList.of("-c", "cxx.cxx=/bin/false"),
            ImmutableList.of("//:main", "//:test"),
            ImmutableList.of(),
            Optional.empty(),
            false);
    int ret = runner.run(stream);

    Assert.assertEquals(0, ret);
    String[] lines = stream.getOutputLines();

    Assert.assertEquals(7, lines.length);
    Assert.assertEquals(System.getProperty("user.dir"), lines[0]);
    Assert.assertEquals(binPath.toAbsolutePath().toString(), lines[1]);
    Assert.assertEquals("build", lines[2]);
    Assert.assertEquals("-c", lines[3]);
    Assert.assertEquals("cxx.cxx=/bin/false", lines[4]);
    Assert.assertEquals("//:main", lines[5]);
    Assert.assertEquals("//:test", lines[6]);
  }

  @Test
  public void randomizesPythonSeedIfRandomizeIsTrue() throws IOException, InterruptedException {
    writer.writeArgEchoer(0);

    BuckRunner runner =
        new BuckRunner(
            Optional.of("python"),
            binPath.toAbsolutePath().toString(),
            "build",
            ImmutableList.of("-c", "cxx.cxx=/bin/false"),
            ImmutableList.of("//:main", "//:test"),
            ImmutableList.of(),
            Optional.empty(),
            true);
    int ret = runner.run(stream);

    Assert.assertEquals(0, ret);
    String[] lines = stream.getOutputLines();

    Assert.assertEquals(8, lines.length);
    Assert.assertEquals(System.getProperty("user.dir"), lines[0]);
    Assert.assertEquals(binPath.toAbsolutePath().toString(), lines[1]);
    Assert.assertEquals("build", lines[2]);
    Assert.assertEquals("-c", lines[3]);
    Assert.assertEquals("cxx.cxx=/bin/false", lines[4]);
    Assert.assertEquals("//:main", lines[5]);
    Assert.assertEquals("//:test", lines[6]);
    Assert.assertEquals("Random hashes configured", lines[7]);
  }

  @Test
  public void writesOutArgumentsToATemporaryFileIfProvided()
      throws IOException, InterruptedException {
    writer.writeArgEchoer(0);

    BuckRunner runner =
        new BuckRunner(
            Optional.of("python"),
            binPath.toAbsolutePath().toString(),
            "build",
            ImmutableList.of("-c", "cxx.cxx=/bin/false"),
            ImmutableList.of(),
            ImmutableList.of("//:main", "//:test"),
            Optional.of(temporaryFolder.getRoot()),
            false);
    int ret = runner.run(stream);

    Assert.assertEquals(0, ret);
    String[] lines = stream.getOutputLines();

    Assert.assertEquals(9, lines.length);
    Assert.assertEquals(temporaryFolder.getRoot().toString(), lines[0]);
    Assert.assertEquals(binPath.toAbsolutePath().toString(), lines[1]);
    Assert.assertEquals("build", lines[2]);
    Assert.assertEquals("-c", lines[3]);
    Assert.assertEquals("cxx.cxx=/bin/false", lines[4]);
    Assert.assertTrue(lines[5].startsWith("@"));
    Assert.assertTrue(lines[6].startsWith("Reading arguments from @"));
    Assert.assertEquals("//:main", lines[7]);
    Assert.assertEquals("//:test", lines[8]);
  }
}
