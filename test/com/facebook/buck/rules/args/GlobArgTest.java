/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.rules.args;

import static org.junit.Assert.assertThat;

import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;

public class GlobArgTest {

  @Test
  public void emptyDir() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path dir = filesystem.getRootPath().getFileSystem().getPath("foo/bar");
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    filesystem.mkdirs(dir);
    Arg arg = GlobArg.of(pathResolver, new PathSourcePath(filesystem, dir), "**");
    assertThat(
        Arg.stringify(ImmutableList.of(arg)),
        Matchers.empty());
  }

  @Test
  public void filesInSortedOrder() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path dir = filesystem.getRootPath().getFileSystem().getPath("foo/bar");
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    filesystem.mkdirs(dir);
    filesystem.touch(dir.resolve("file2.dat"));
    filesystem.touch(dir.resolve("file1.dat"));
    Arg arg = GlobArg.of(pathResolver, new PathSourcePath(filesystem, dir), "**");
    assertThat(
        Arg.stringify(ImmutableList.of(arg)),
        Matchers.contains(
            filesystem.resolve("foo/bar/file1.dat").toString(),
            filesystem.resolve("foo/bar/file2.dat").toString()));
  }

}
