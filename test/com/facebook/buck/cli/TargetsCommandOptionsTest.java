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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TargetsCommandOptionsTest {

  @Test
  public void testGetCanonicalFilesUnderProjectRoot() throws IOException {
    Path projectRoot = Paths.get("").toAbsolutePath().resolve("ProjectRoot");

    ImmutableSet<String> nonCanonicalFilePaths = ImmutableSet.of(
        "ProjectRoot/src/com/facebook/CanonicalRelativePath.java",
        "./ProjectRoot/src/com/otherpackage/.././/facebook/NonCanonicalPath.java",
        projectRoot.normalize() + "/src/com/facebook/AbsolutePath.java",
        projectRoot.normalize() + "/../PathNotUnderProjectRoot.java");

    assertEquals(
        ImmutableSet.of(
            "src/com/facebook/CanonicalRelativePath.java",
            "src/com/facebook/NonCanonicalPath.java",
            "src/com/facebook/AbsolutePath.java"),
        TargetsCommandOptions.getCanonicalFilesUnderProjectRoot(
            projectRoot, nonCanonicalFilePaths));
  }
}
