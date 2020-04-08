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

package com.facebook.buck.skylark.function;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.starlark.compatible.BuckStarlark;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.filesystem.skylark.SkylarkFilesystem;
import com.facebook.buck.skylark.io.impl.NativeGlobber;
import com.facebook.buck.skylark.packages.PackageContext;
import com.facebook.buck.skylark.parser.context.ParseContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventCollector;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.packages.BazelLibrary;
import com.google.devtools.build.lib.syntax.CallUtils;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.EvalUtils;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.ParserInput;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.StarlarkFile;
import com.google.devtools.build.lib.syntax.StarlarkThread;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.EnumSet;
import org.junit.Before;
import org.junit.Test;

public class GlobTest {

  private Path root;
  private EventCollector eventHandler;

  @Before
  public void setUp() {
    ProjectFilesystem projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    SkylarkFilesystem fileSystem = SkylarkFilesystem.using(projectFilesystem);
    root = fileSystem.getPath(projectFilesystem.getRootPath().toString());
    eventHandler = new EventCollector(EnumSet.allOf(EventKind.class));
  }

  @Test
  public void testGlobFindsIncludedFiles() throws IOException, InterruptedException, EvalException {
    FileSystemUtils.createEmptyFile(root.getChild("foo.txt"));
    FileSystemUtils.createEmptyFile(root.getChild("bar.txt"));
    FileSystemUtils.createEmptyFile(root.getChild("bar.jpg"));
    Path buildFile = root.getChild("BUCK");
    FileSystemUtils.writeContentAsLatin1(buildFile, "txts = glob(['*.txt'])");
    assertThat(
        assertEvaluate(buildFile).moduleLookup("txts"),
        equalTo(SkylarkList.createImmutable(ImmutableList.of("bar.txt", "foo.txt"))));
  }

  @Test
  public void testGlobFindsIncludedFilesUsingKeyword()
      throws IOException, InterruptedException, EvalException {
    FileSystemUtils.createEmptyFile(root.getChild("foo.txt"));
    FileSystemUtils.createEmptyFile(root.getChild("bar.txt"));
    FileSystemUtils.createEmptyFile(root.getChild("bar.jpg"));
    Path buildFile = root.getChild("BUCK");
    FileSystemUtils.writeContentAsLatin1(buildFile, "txts = glob(include=['*.txt'])");
    assertThat(
        assertEvaluate(buildFile).moduleLookup("txts"),
        equalTo(SkylarkList.createImmutable(ImmutableList.of("bar.txt", "foo.txt"))));
  }

  @Test
  public void testGlobExcludedElementsAreNotReturned()
      throws IOException, InterruptedException, EvalException {
    FileSystemUtils.createEmptyFile(root.getChild("foo.txt"));
    FileSystemUtils.createEmptyFile(root.getChild("bar.txt"));
    FileSystemUtils.createEmptyFile(root.getChild("bar.jpg"));
    Path buildFile = root.getChild("BUCK");
    FileSystemUtils.writeContentAsLatin1(buildFile, "txts = glob(['*.txt'], exclude=['bar.txt'])");
    assertThat(
        assertEvaluate(buildFile).moduleLookup("txts"),
        equalTo(SkylarkList.createImmutable(ImmutableList.of("foo.txt"))));
  }

  @Test
  public void testMatchingDirectoryIsReturnedWhenDirsAreNotExcluded() throws Exception {
    FileSystemUtils.createDirectoryAndParents(root.getChild("some_dir"));
    Path buildFile = root.getChild("BUCK");
    FileSystemUtils.writeContentAsLatin1(
        buildFile, "txts = glob(['some_dir'], exclude_directories=False)");
    assertThat(
        assertEvaluate(buildFile).moduleLookup("txts"),
        equalTo(SkylarkList.createImmutable(ImmutableList.of("some_dir"))));
  }

  @Test
  public void testMatchingDirectoryIsNotReturnedWhenDirsAreExcluded() throws Exception {
    FileSystemUtils.createDirectoryAndParents(root.getChild("some_dir"));
    Path buildFile = root.getChild("BUCK");
    FileSystemUtils.writeContentAsLatin1(
        buildFile, "txts = glob(['some_dir'], exclude_directories=True)");
    assertThat(
        assertEvaluate(buildFile).moduleLookup("txts"),
        equalTo(SkylarkList.createImmutable(ImmutableList.of())));
  }

  @Test
  public void testMatchingDirectoryIsNotReturnedWhenDirExclusionIsNotSpecified() throws Exception {
    FileSystemUtils.createDirectoryAndParents(root.getChild("some_dir"));
    Path buildFile = root.getChild("BUCK");
    FileSystemUtils.writeContentAsLatin1(buildFile, "txts = glob(['some_dir'])");
    assertThat(
        assertEvaluate(buildFile).moduleLookup("txts"),
        equalTo(SkylarkList.createImmutable(ImmutableList.of())));
  }

  @Test
  public void emptyIncludeListIsReportedAsAWarning() throws Exception {
    Path buildFile = root.getChild("BUCK");
    FileSystemUtils.writeContentAsLatin1(buildFile, "txts = glob([])");
    assertThat(
        assertEvaluate(buildFile).moduleLookup("txts"),
        equalTo(SkylarkList.createImmutable(ImmutableList.of())));
    Event event = Iterables.getOnlyElement(eventHandler);
    assertThat(event.getKind(), is(EventKind.WARNING));
    assertThat(event.getLocation(), notNullValue());
    assertThat(
        event.getMessage(),
        is(
            "glob's 'include' attribute is empty. "
                + "Such calls are expensive and unnecessary. "
                + "Please use an empty list ([]) instead."));
  }

  private StarlarkThread assertEvaluate(Path buildFile)
      throws IOException, InterruptedException, EvalException {
    try (Mutability mutability = Mutability.create("BUCK")) {
      return assertEvaluate(buildFile, mutability);
    }
  }

  private StarlarkThread assertEvaluate(Path buildFile, Mutability mutability)
      throws IOException, InterruptedException, EvalException {
    byte[] buildFileContent =
        FileSystemUtils.readWithKnownFileSize(buildFile, buildFile.getFileSize());
    StarlarkFile buildFileAst =
        StarlarkFile.parse(ParserInput.create(buildFileContent, buildFile.asFragment()));
    StarlarkThread env =
        StarlarkThread.builder(mutability)
            .setGlobals(BazelLibrary.GLOBALS)
            .setSemantics(BuckStarlark.BUCK_STARLARK_SEMANTICS)
            .build();
    new ParseContext(
            PackageContext.of(
                NativeGlobber.create(root),
                ImmutableMap.of(),
                PackageIdentifier.create(RepositoryName.DEFAULT, PathFragment.create("pkg")),
                eventHandler,
                ImmutableMap.of()))
        .setup(env);
    env.setup("glob", CallUtils.getBuiltinCallable(SkylarkBuildModule.BUILD_MODULE, "glob"));

    EvalUtils.exec(buildFileAst, env);

    return env;
  }
}
