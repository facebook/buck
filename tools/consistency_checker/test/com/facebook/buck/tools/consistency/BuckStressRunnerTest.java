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
import com.facebook.buck.tools.consistency.BuckStressRunner.StressorException;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BuckStressRunnerTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();
  @Rule public ExpectedException expectedException = ExpectedException.none();
  private Path binPath;
  private TestBinWriter binWriter;

  @Before
  public void setUp() throws IOException {
    binPath = temporaryFolder.newFile("test.py");
    binWriter = new TestBinWriter(binPath);
  }

  @Test
  public void abortsIfOneTaskFails() throws IOException, StressorException {
    expectedException.expectMessage(
        "Got non zero output from buck iteration 0: 1"
            + System.lineSeparator()
            + "Buck run 1 was canceled due to a previous error"
            + System.lineSeparator()
            + "Buck run 2 was canceled due to a previous error"
            + System.lineSeparator()
            + "Buck run 3 was canceled due to a previous error"
            + System.lineSeparator()
            + "Buck run 4 was canceled due to a previous error");
    expectedException.expect(StressorException.class);

    binWriter.writeArgEchoer(1);
    List<BuckRunner> runner =
        IntStream.range(0, 5)
            .mapToObj(
                i ->
                    new BuckRunner(
                        Optional.of("python"),
                        binPath.toAbsolutePath().toString(),
                        "targets",
                        ImmutableList.of(),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        Optional.empty(),
                        false))
            .collect(Collectors.toList());
    BuckStressRunner stressRunner = new BuckStressRunner();

    stressRunner.run(runner, temporaryFolder.getRoot(), 1);
  }

  @Test
  public void abortsIfFileCannotBeWrittenTo() throws IOException, StressorException {
    expectedException.expectMessage(
        Matchers.matchesPattern(
            Pattern.compile(
                "Got an error while running buck iteration 0: .*"
                    + System.lineSeparator()
                    + "Buck run 1 was canceled due to a previous error"
                    + System.lineSeparator()
                    + "Buck run 2 was canceled due to a previous error"
                    + System.lineSeparator()
                    + "Buck run 3 was canceled due to a previous error"
                    + System.lineSeparator()
                    + "Buck run 4 was canceled due to a previous error",
                Pattern.MULTILINE)));
    expectedException.expect(StressorException.class);

    binWriter.writeArgEchoer(0);
    Path dummyFile = temporaryFolder.newFile("0.log");
    List<BuckRunner> runners =
        IntStream.range(0, 5)
            .mapToObj(
                i ->
                    new BuckRunner(
                        Optional.of("python"),
                        binPath.toAbsolutePath().toString(),
                        "targets",
                        ImmutableList.of(),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        Optional.empty(),
                        false))
            .collect(Collectors.toList());
    BuckStressRunner stressRunner = new BuckStressRunner();

    stressRunner.run(runners, temporaryFolder.getRoot(), 1);
  }

  @Test
  public void writesOutFileForEachRun() throws IOException, StressorException {
    List<BuckRunner> runners = createRunner("line %s", 5);
    List<String> expectedOutput =
        IntStream.range(0, 5)
            .mapToObj(i -> String.format("line %s%s", i, System.lineSeparator()))
            .collect(Collectors.toList());

    BuckStressRunner stressRunner = new BuckStressRunner();
    List<Path> expectedPaths =
        IntStream.range(0, 5)
            .mapToObj(i -> temporaryFolder.getRoot().resolve(String.format("%s.log", i)))
            .collect(Collectors.toList());

    List<Path> paths = stressRunner.run(runners, temporaryFolder.getRoot(), 1);

    Assert.assertEquals(expectedPaths, paths);
    Assert.assertEquals(expectedOutput.get(0), readFile(paths.get(0)));
    Assert.assertEquals(expectedOutput.get(1), readFile(paths.get(1)));
    Assert.assertEquals(expectedOutput.get(2), readFile(paths.get(2)));
    Assert.assertEquals(expectedOutput.get(3), readFile(paths.get(3)));
    Assert.assertEquals(expectedOutput.get(4), readFile(paths.get(4)));
  }

  private List<BuckRunner> createRunner(String formatString, int size) {
    return IntStream.range(0, size)
        .mapToObj(
            i -> {
              Path tempPath = null;
              try {
                tempPath = temporaryFolder.newFile(Integer.toString(i));
                TestBinWriter writer = new TestBinWriter(tempPath);
                writer.writeLineEchoer(new String[] {String.format(formatString, i)}, 0);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
              return new BuckRunner(
                  Optional.of("python"),
                  tempPath.toAbsolutePath().toString(),
                  "targets",
                  ImmutableList.of(),
                  ImmutableList.of(),
                  ImmutableList.of(),
                  Optional.empty(),
                  false);
            })
        .collect(Collectors.toList());
  }

  private String readFile(Path path) throws IOException {
    byte[] bytes = Files.readAllBytes(path);
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
