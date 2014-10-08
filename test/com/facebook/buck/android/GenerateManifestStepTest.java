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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class GenerateManifestStepTest {

  private Path skeletonPath;
  private Path manifestPath;

  @Before
  public void setUp() {
    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver());
    manifestPath = resolver.getPath(testDataPath("AndroidManifest.xml"));
    skeletonPath = resolver.getPath(testDataPath("AndroidManifestSkeleton.xml"));
  }

  @After
  public void tearDown() {
    manifestPath.toFile().delete();
  }

  @Test
  public void testManifestGeneration() throws IOException {
    String expectedOutputPath = testDataPath("AndroidManifest.expected.xml").toString();
    SourcePath libraryManifestA = testDataPath("AndroidManifestA.xml");
    SourcePath libraryManifestB = testDataPath("AndroidManifestB.xml");
    SourcePath libraryManifestC = testDataPath("AndroidManifestC.xml");
    ImmutableSet.Builder<SourcePath> libraryManifestFiles = ImmutableSet.builder();
    libraryManifestFiles.add(libraryManifestA);
    libraryManifestFiles.add(libraryManifestB);
    libraryManifestFiles.add(libraryManifestC);

    ExecutionContext context = TestExecutionContext.newInstance();

    GenerateManifestStep manifestCommand = new GenerateManifestStep(
        skeletonPath,
        ImmutableSet.copyOf(new SourcePathResolver(new BuildRuleResolver()).getAllPaths(
                libraryManifestFiles.build())),
        manifestPath);
    int result = manifestCommand.execute(context);

    assertEquals(0, result);

    String expected = Files.toString(new File(expectedOutputPath), Charsets.UTF_8);
    String output = Files.toString(manifestPath.toFile(), Charsets.UTF_8);

    assertEquals(expected, output);
  }

  private SourcePath testDataPath(String fileName) {
    return new PathSourcePath(Paths.get("testdata/com/facebook/buck/shell", fileName));
  }
}
