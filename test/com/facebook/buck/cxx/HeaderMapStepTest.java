/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.apple.clang.HeaderMap;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class HeaderMapStepTest {

  @Rule
  public final TemporaryFolder tmpDir = new TemporaryFolder();

  @Test
  public void testHeaderMap() throws IOException {

    ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmpDir.getRoot().toPath());

    ExecutionContext context = TestExecutionContext.newBuilder()
        .setProjectFilesystem(projectFilesystem)
        .build();

    Path output = Paths.get("headers.hmap");
    ImmutableMap<Path, Path> entries = ImmutableMap.of(
        Paths.get("file1.h"), Paths.get("/some/absolute/path.h"),
        Paths.get("file2.h"), Paths.get("/other/absolute/path.h"),
        Paths.get("prefix/file1.h"), Paths.get("/some/absolute/path.h"));

    HeaderMapStep step = new HeaderMapStep(output, entries);

    step.execute(context);

    assertTrue(projectFilesystem.exists(output));

    byte[] headerMapBytes = ByteStreams.toByteArray(projectFilesystem.newFileInputStream(output));
    HeaderMap headerMap = HeaderMap.deserialize(headerMapBytes);
    assertNotNull(headerMap);
    assertThat(headerMap.getNumEntries(), equalTo(entries.size()));
    for (Map.Entry<Path, Path> entry : entries.entrySet()) {
      assertThat(
          headerMap.lookup(entry.getKey().toString()),
          equalTo(entry.getValue().toString()));
    }
  }

}
