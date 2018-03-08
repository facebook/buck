/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.file;

import com.facebook.buck.file.downloader.Downloader;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class HttpFileBinaryTest {

  public @Rule TemporaryPaths temporaryDir = new TemporaryPaths();

  @Test
  public void executableCommandIsCorrect() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ProjectFilesystem filesysten =
        TestProjectFilesystems.createProjectFilesystem(temporaryDir.getRoot());
    BuildRuleParams params =
        new BuildRuleParams(
            ImmutableSortedSet::of, ImmutableSortedSet::of, ImmutableSortedSet.of());
    Downloader downloader =
        (eventBus, uri, output) -> {
          return false;
        };
    ImmutableList<URI> uris = ImmutableList.of(URI.create("https://example.com"));
    HashCode sha256 =
        HashCode.fromString("f2ca1bb6c7e907d06dafe4687e579fce76b37e4e93b7605022da52e6ccc26fd2");
    String out = "foo.exe";

    HttpFileBinary binary =
        new HttpFileBinary(target, filesysten, params, downloader, uris, sha256, out);

    BuildRuleResolver resolver = new TestBuildRuleResolver();
    resolver.addToIndex(binary);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));

    Tool tool = binary.getExecutableCommand();

    Path expectedPath =
        filesysten.resolve(
            filesysten.getBuckPaths().getGenDir().resolve(Paths.get("foo", "bar", "foo.exe")));

    Assert.assertEquals(
        ImmutableList.of(expectedPath.toString()), tool.getCommandPrefix(pathResolver));
  }
}
