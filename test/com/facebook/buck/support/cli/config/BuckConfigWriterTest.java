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

package com.facebook.buck.support.cli.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.BuckConfigTestUtils;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;

public class BuckConfigWriterTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void writesConfigToDisk() throws IOException {
    Reader reader = new StringReader("[test]\nexcluded_labels = windows, linux\n[foo]\nbar=baz\n");
    BuckConfig config = BuckConfigTestUtils.createWithDefaultFilesystem(tmp, reader);
    InvocationInfo info =
        InvocationInfo.of(
            new BuildId("1234"),
            false,
            false,
            "build",
            ImmutableList.of("//:target"),
            ImmutableList.of("//:target"),
            Paths.get("buck-out", "log"),
            false,
            "repository",
            "");

    Path expectedPath =
        tmp.getRoot().resolve(info.getLogDirectoryPath()).resolve("buckconfig.json");
    JsonNode expected =
        ObjectMappers.READER.readTree(
            "{\"settings\": {\"test\":{\"excluded_labels\":\"windows, linux\"},\"foo\":{\"bar\":\"baz\"}}}");

    BuckConfigWriter.writeConfig(tmp.getRoot(), info, config);

    assertTrue(Files.exists(expectedPath));
    try (Reader actualReader = Files.newBufferedReader(expectedPath)) {
      assertEquals(expected, ObjectMappers.READER.readTree(actualReader));
    }
  }
}
