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

package com.facebook.buck.skylark;

import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.PrintingEventHandler;
import com.google.devtools.build.lib.syntax.BazelLibrary;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.ParserInputSource;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.IOException;
import java.util.EnumSet;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class GlobTest {

  private Path root;
  private PrintingEventHandler eventHandler;

  @Before
  public void setUp() {
    InMemoryFileSystem fileSystem = new InMemoryFileSystem();
    root = fileSystem.getRootDirectory();
    eventHandler = new PrintingEventHandler(EnumSet.allOf(EventKind.class));
  }

  @Test
  public void testGlobFindsIncludes() throws IOException, InterruptedException {
    FileSystemUtils.createEmptyFile(root.getChild("foo.txt"));
    FileSystemUtils.createEmptyFile(root.getChild("bar.txt"));
    FileSystemUtils.createEmptyFile(root.getChild("bar.jpg"));
    Path buildFile = root.getChild("BUCK");
    FileSystemUtils.writeContentAsLatin1(buildFile, "txts = glob(['*.txt'])");
    assertThat(
        evaluate(buildFile).lookup("txts"),
        equalTo(SkylarkList.createImmutable(ImmutableList.of("bar.txt", "foo.txt"))));
  }

  private Environment evaluate(Path buildFile) throws IOException, InterruptedException {
    try (Mutability mutability = Mutability.create("BUCK")) {
      return evaluate(buildFile, mutability);
    }
  }

  @Test
  public void testGlobExcludedElementsAreNotReturned() throws IOException, InterruptedException {
    FileSystemUtils.createEmptyFile(root.getChild("foo.txt"));
    FileSystemUtils.createEmptyFile(root.getChild("bar.txt"));
    FileSystemUtils.createEmptyFile(root.getChild("bar.jpg"));
    Path buildFile = root.getChild("BUCK");
    FileSystemUtils.writeContentAsLatin1(buildFile, "txts = glob(['*.txt'], excludes=['bar.txt'])");
    assertThat(
        evaluate(buildFile).lookup("txts"),
        equalTo(SkylarkList.createImmutable(ImmutableList.of("foo.txt"))));
  }

  @Test
  public void testMatchingDirectoryIsReturnedWhenDirsAreNotExcluded() throws Exception {
    FileSystemUtils.createDirectoryAndParents(root.getChild("some_dir"));
    Path buildFile = root.getChild("BUCK");
    FileSystemUtils.writeContentAsLatin1(
        buildFile, "txts = glob(['some_dir'], exclude_directories=False)");
    assertThat(
        evaluate(buildFile).lookup("txts"),
        equalTo(SkylarkList.createImmutable(ImmutableList.of("some_dir"))));
  }

  @Test
  public void testMatchingDirectoryIsNotReturnedWhenDirsAreExcluded() throws Exception {
    FileSystemUtils.createDirectoryAndParents(root.getChild("some_dir"));
    Path buildFile = root.getChild("BUCK");
    FileSystemUtils.writeContentAsLatin1(
        buildFile, "txts = glob(['some_dir'], exclude_directories=True)");
    assertThat(
        evaluate(buildFile).lookup("txts"),
        equalTo(SkylarkList.createImmutable(ImmutableList.of())));
  }

  private Environment evaluate(Path buildFile, Mutability mutability)
      throws IOException, InterruptedException {
    BuildFileAST buildFileAst =
        BuildFileAST.parseBuildFile(ParserInputSource.create(buildFile), eventHandler);
    Environment env = Environment.builder(mutability).setGlobals(BazelLibrary.GLOBALS).build();
    env.setup("glob", Glob.create(root));
    boolean exec = buildFileAst.exec(env, eventHandler);
    if (!exec) {
      Assert.fail("Build file evaluation must have succeeded");
    }
    return env;
  }
}
