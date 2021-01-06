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

public class WindowsPreprocessorTest {

  private WindowsPreprocessor preprocessor;
  private ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

  @Before
  public void setUp() {
    preprocessor =
        new WindowsPreprocessor(
            new HashedFileTool(
                () ->
                    PathSourcePath.of(
                        projectFilesystem,
                        PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/preprocessor")))));
  }

  @Test
  public void localIncludeArguments() {
    List<String> includeRoots =
        Arrays.asList("/test/dir", "/another/test/dir", "one/more/test/dir");
    List<String> resultLocalIncludes =
        Lists.newArrayList(preprocessor.localIncludeArgs(includeRoots));
    List<String> expected =
        Arrays.asList("/I/test/dir", "/I/another/test/dir", "/Ione/more/test/dir");

    Assert.assertFalse(resultLocalIncludes.isEmpty());
    Assert.assertEquals(resultLocalIncludes, expected);
    Assert.assertEquals(resultLocalIncludes.size(), includeRoots.size());
  }

  @Test
  public void systemIncludeArguments() {
    List<String> includeRoots =
        Arrays.asList("/test/dir", "/another/test/dir", "one/more/test/dir");
    List<String> resultSystemIncludes =
        Lists.newArrayList(preprocessor.systemIncludeArgs(includeRoots));
    List<String> expected =
        Arrays.asList(
            "/experimental:external",
            "/external:I/test/dir",
            "/external:I/another/test/dir",
            "/external:Ione/more/test/dir");

    Assert.assertFalse(resultSystemIncludes.isEmpty());
    Assert.assertEquals(resultSystemIncludes.get(0), "/experimental:external");
    Assert.assertEquals(resultSystemIncludes, expected);
  }
}
