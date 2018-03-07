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

package com.facebook.buck.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;

public class ParserPythonInterpreterProviderTest {
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void whenParserPythonIsExecutableFileThenItIsUsed() throws IOException {
    Path configPythonFile = temporaryFolder.newExecutableFile("python");
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "parser",
                    ImmutableMap.of(
                        "python_interpreter", configPythonFile.toAbsolutePath().toString())))
            .build();
    ParserConfig parserConfig = buckConfig.getView(ParserConfig.class);
    ParserPythonInterpreterProvider provider =
        new ParserPythonInterpreterProvider(parserConfig, new ExecutableFinder());
    assertEquals(
        "Should return path to temp file.",
        configPythonFile.toAbsolutePath().toString(),
        provider.getOrFail());
  }

  @Test
  public void whenParserPythonDoesNotExistThenItIsNotUsed() {
    String invalidPath = temporaryFolder.getRoot().toAbsolutePath() + "DoesNotExist";
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of("parser", ImmutableMap.of("python_interpreter", invalidPath)))
            .build();
    ParserConfig parserConfig = buckConfig.getView(ParserConfig.class);
    ParserPythonInterpreterProvider provider =
        new ParserPythonInterpreterProvider(parserConfig, new ExecutableFinder());
    try {
      provider.getOrFail();
      fail("Should throw exception as python config is invalid.");
    } catch (HumanReadableException e) {
      assertEquals("Not a python executable: " + invalidPath, e.getHumanReadableErrorMessage());
    }
  }
}
