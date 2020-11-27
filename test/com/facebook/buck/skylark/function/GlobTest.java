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

import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.label.PackageIdentifier;
import com.facebook.buck.core.model.label.PathFragment;
import com.facebook.buck.core.model.label.RepositoryName;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.starlark.compatible.BuckStarlark;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.skylark.io.impl.NativeGlobber;
import com.facebook.buck.skylark.packages.PackageContext;
import com.facebook.buck.skylark.parser.context.ParseContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventCollector;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.EvalUtils;
import com.google.devtools.build.lib.syntax.Module;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.ParserInput;
import com.google.devtools.build.lib.syntax.Resolver;
import com.google.devtools.build.lib.syntax.Starlark;
import com.google.devtools.build.lib.syntax.StarlarkFile;
import com.google.devtools.build.lib.syntax.StarlarkList;
import com.google.devtools.build.lib.syntax.StarlarkThread;
import com.google.devtools.build.lib.syntax.SyntaxError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumSet;
import org.junit.Before;
import org.junit.Test;

public class GlobTest {

  private AbsPath root;
  private EventCollector eventHandler;

  @Before
  public void setUp() {
    ProjectFilesystem projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    root = projectFilesystem.getRootPath();
    eventHandler = new EventCollector(EnumSet.allOf(EventKind.class));
  }

  @Test
  public void testGlobFindsIncludedFiles() throws IOException, InterruptedException, EvalException {
    Files.write(root.resolve("foo.txt").getPath(), new byte[0]);
    Files.write(root.resolve("bar.txt").getPath(), new byte[0]);
    Files.write(root.resolve("bar.jpg").getPath(), new byte[0]);
    AbsPath buildFile = root.resolve("BUCK");
    Files.write(buildFile.getPath(), ImmutableList.of("txts = glob(['*.txt'])"));
    assertThat(
        assertEvaluate(buildFile).getGlobals().get("txts"),
        equalTo(StarlarkList.immutableCopyOf(ImmutableList.of("bar.txt", "foo.txt"))));
  }

  @Test
  public void testGlobFindsIncludedFilesUsingKeyword()
      throws IOException, InterruptedException, EvalException {
    Files.write(root.resolve("foo.txt").getPath(), new byte[0]);
    Files.write(root.resolve("bar.txt").getPath(), new byte[0]);
    Files.write(root.resolve("bar.jpg").getPath(), new byte[0]);
    AbsPath buildFile = root.resolve("BUCK");
    Files.write(buildFile.getPath(), ImmutableList.of("txts = glob(include=['*.txt'])"));
    assertThat(
        assertEvaluate(buildFile).getGlobals().get("txts"),
        equalTo(StarlarkList.immutableCopyOf(ImmutableList.of("bar.txt", "foo.txt"))));
  }

  @Test
  public void testGlobExcludedElementsAreNotReturned()
      throws IOException, InterruptedException, EvalException {
    Files.write(root.resolve("foo.txt").getPath(), new byte[0]);
    Files.write(root.resolve("bar.txt").getPath(), new byte[0]);
    Files.write(root.resolve("bar.jpg").getPath(), new byte[0]);
    AbsPath buildFile = root.resolve("BUCK");
    Files.write(
        buildFile.getPath(), ImmutableList.of("txts = glob(['*.txt'], exclude=['bar.txt'])"));
    assertThat(
        assertEvaluate(buildFile).getGlobals().get("txts"),
        equalTo(StarlarkList.immutableCopyOf(ImmutableList.of("foo.txt"))));
  }

  @Test
  public void testMatchingDirectoryIsReturnedWhenDirsAreNotExcluded() throws Exception {
    Files.createDirectories(root.resolve("some_dir").getPath());
    AbsPath buildFile = root.resolve("BUCK");
    Files.write(
        buildFile.getPath(),
        ImmutableList.of("txts = glob(['some_dir'], exclude_directories=False)"));
    assertThat(
        assertEvaluate(buildFile).getGlobals().get("txts"),
        equalTo(StarlarkList.immutableCopyOf(ImmutableList.of("some_dir"))));
  }

  @Test
  public void testMatchingDirectoryIsNotReturnedWhenDirsAreExcluded() throws Exception {
    Files.createDirectories(root.resolve("some_dir").getPath());
    AbsPath buildFile = root.resolve("BUCK");
    Files.write(
        buildFile.getPath(),
        ImmutableList.of("txts = glob(['some_dir'], exclude_directories=True)"));
    assertThat(
        assertEvaluate(buildFile).getGlobals().get("txts"),
        equalTo(StarlarkList.immutableCopyOf(ImmutableList.of())));
  }

  @Test
  public void testMatchingDirectoryIsNotReturnedWhenDirExclusionIsNotSpecified() throws Exception {
    Files.createDirectories(root.resolve("some_dir").getPath());
    AbsPath buildFile = root.resolve("BUCK");
    Files.write(buildFile.getPath(), ImmutableList.of("txts = glob(['some_dir'])"));
    assertThat(
        assertEvaluate(buildFile).getGlobals().get("txts"),
        equalTo(StarlarkList.immutableCopyOf(ImmutableList.of())));
  }

  @Test
  public void emptyIncludeListIsOk() throws Exception {
    AbsPath buildFile = root.resolve("BUCK");
    Files.write(buildFile.getPath(), ImmutableList.of("txts = glob([])"));
    assertThat(
        assertEvaluate(buildFile).getGlobals().get("txts"),
        equalTo(StarlarkList.immutableCopyOf(ImmutableList.of())));
  }

  private Module assertEvaluate(AbsPath buildFile)
      throws IOException, InterruptedException, EvalException {
    try (Mutability mutability = Mutability.create("BUCK")) {
      return assertEvaluate(buildFile, mutability);
    }
  }

  private Module assertEvaluate(AbsPath buildFile, Mutability mutability)
      throws IOException, InterruptedException, EvalException {
    byte[] buildFileContent = Files.readAllBytes(buildFile.getPath());
    StarlarkFile buildFileAst =
        StarlarkFile.parse(
            ParserInput.create(
                new String(buildFileContent, StandardCharsets.UTF_8), buildFile.toString()));

    ImmutableMap.Builder<String, Object> vars = ImmutableMap.builder();
    vars.putAll(Starlark.UNIVERSE);
    // only "glob" function is neede from the module
    Starlark.addMethods(vars, SkylarkBuildModule.BUILD_MODULE);

    Module module = Module.withPredeclared(BuckStarlark.BUCK_STARLARK_SEMANTICS, vars.build());

    StarlarkThread env = new StarlarkThread(mutability, BuckStarlark.BUCK_STARLARK_SEMANTICS);

    new ParseContext(
            PackageContext.of(
                NativeGlobber.create(root),
                ImmutableMap.of(),
                PackageIdentifier.create(RepositoryName.DEFAULT, PathFragment.create("pkg")),
                ForwardRelativePath.of("pkg"),
                eventHandler,
                ImmutableMap.of()))
        .setup(env);

    Resolver.resolveFile(buildFileAst, module);
    if (!buildFileAst.errors().isEmpty()) {
      for (SyntaxError error : buildFileAst.errors()) {
        eventHandler.handle(Event.error(error.location(), error.message()));
      }
      fail();
    }

    EvalUtils.exec(buildFileAst, module, env);

    return module;
  }
}
