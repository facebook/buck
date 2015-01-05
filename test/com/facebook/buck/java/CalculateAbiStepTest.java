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

package com.facebook.buck.java;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.AbiRule;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.Zip;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableMap;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CalculateAbiStepTest {

  @Rule
  public DebuggableTemporaryFolder temp = new DebuggableTemporaryFolder();

  @Test
  public void shouldCalculateAbiFromAStubJar() throws IOException {
    Path outDir = temp.newFolder().toPath().toAbsolutePath();
    ProjectFilesystem filesystem = new ProjectFilesystem(outDir);

    Path directory = TestDataHelper.getTestDataDirectory(this);
    Path source = directory.resolve("prebuilt/junit.jar");
    Path binJar = Paths.get("source.jar");
    Files.copy(source, outDir.resolve(binJar));

    Path abiJar = outDir.resolve("abi.jar");

    ExecutionContext executionContext = TestExecutionContext.newBuilder()
        .setProjectFilesystem(filesystem)
        .build();

    FakeBuildableContext context = new FakeBuildableContext();
    new CalculateAbiStep(context, binJar, abiJar).execute(executionContext);

    String expectedHash = filesystem.computeSha1(Paths.get("abi.jar"));
    ImmutableMap<String, Object> metadata = context.getRecordedMetadata();
    Object seenHash = metadata.get(AbiRule.ABI_KEY_ON_DISK_METADATA);

    assertEquals(expectedHash, seenHash);
  }

  @Test
  public void fallsBackToCalculatingAbiFromInputJarIfClassFileIsMalformed() throws IOException {
    Path outDir = temp.newFolder().toPath().toAbsolutePath();
    ProjectFilesystem filesystem = new ProjectFilesystem(outDir);

    Path binJar = outDir.resolve("bad.jar");
    try (Zip zip = new Zip(binJar.toFile(), true)){
      zip.add("Broken.class", "cafebabe bacon and cheese".getBytes(UTF_8));
    }
    String expectedHash = filesystem.computeSha1(binJar);

    Path abiJar = outDir.resolve("abi.jar");

    ExecutionContext executionContext = TestExecutionContext.newBuilder()
        .setProjectFilesystem(filesystem)
        .build();

    FakeBuildableContext context = new FakeBuildableContext();
    new CalculateAbiStep(context, binJar, abiJar).execute(executionContext);

    ImmutableMap<String, Object> metadata = context.getRecordedMetadata();
    Object seenHash = metadata.get(AbiRule.ABI_KEY_ON_DISK_METADATA);

    assertEquals(expectedHash, seenHash);
  }
}
