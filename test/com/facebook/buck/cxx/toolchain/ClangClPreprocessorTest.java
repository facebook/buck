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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.testutil.PathNormalizer;
import com.google.common.collect.Lists;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ClangClPreprocessorTest {

  private ClangClPreprocessor preprocessor;
  private ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

  @Before
  public void setUp() {
    preprocessor =
        new ClangClPreprocessor(
            new HashedFileTool(
                () ->
                    PathSourcePath.of(
                        projectFilesystem,
                        PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/preprocessor")))));
  }

  @Test
  public void testSystemIncludeArguments() {
    List<String> includeRoots =
        Arrays.asList("/test/dir", "/another/test/dir", "one/more/test/dir");
    List<String> resultSystemIncludes =
        Lists.newArrayList(preprocessor.systemIncludeArgs(includeRoots));
    List<String> expected =
        Arrays.asList(
            "-Xclang",
            "-isystem",
            "-Xclang",
            "/test/dir",
            "-Xclang",
            "-isystem",
            "-Xclang",
            "/another/test/dir",
            "-Xclang",
            "-isystem",
            "-Xclang",
            "one/more/test/dir");

    Assert.assertFalse(resultSystemIncludes.isEmpty());
    Assert.assertEquals(resultSystemIncludes, expected);
  }
}
