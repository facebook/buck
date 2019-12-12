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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.starlark.compatible.BuckStarlark;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.filesystem.skylark.SkylarkFilesystem;
import com.facebook.buck.skylark.io.impl.NativeGlobber;
import com.facebook.buck.skylark.packages.PackageContext;
import com.facebook.buck.skylark.parser.context.ParseContext;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.EventCollector;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.packages.BazelLibrary;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.ParserInputSource;
import com.google.devtools.build.lib.syntax.SkylarkUtils;
import com.google.devtools.build.lib.syntax.SkylarkUtils.Phase;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.EnumSet;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class SkylarkBuildModuleTest {

  private Path root;
  private EventCollector eventHandler;
  private ImmutableMap<String, ImmutableMap<String, String>> rawConfig;

  @Before
  public void setUp() {
    ProjectFilesystem projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    SkylarkFilesystem fileSystem = SkylarkFilesystem.using(projectFilesystem);
    root = fileSystem.getPath(projectFilesystem.getRootPath().toString());
    eventHandler = new EventCollector(EnumSet.allOf(EventKind.class));
    rawConfig = ImmutableMap.of();
  }

  @Test
  public void defaultValueIsReturned() throws Exception {
    assertThat(evaluate("pkg = package_name()", true).moduleLookup("pkg"), equalTo("my/package"));
    assertEquals(eventHandler.count(), 0);
  }

  @Test
  public void packageIsNotAllowed() throws Exception {
    evaluate("package()", false);
    assertEquals(eventHandler.iterator().next().getMessage(), "name 'package' is not defined");
  }

  private Environment evaluate(String expression, boolean expectSuccess)
      throws IOException, InterruptedException {
    Path buildFile = root.getChild("BUCK");
    FileSystemUtils.writeContentAsLatin1(buildFile, expression);
    return evaluate(buildFile, expectSuccess);
  }

  private Environment evaluate(Path buildFile, boolean expectSuccess)
      throws IOException, InterruptedException {
    try (Mutability mutability = Mutability.create("BUCK")) {
      return evaluate(buildFile, mutability, expectSuccess);
    }
  }

  private Environment evaluate(Path buildFile, Mutability mutability, boolean expectSuccess)
      throws IOException, InterruptedException {
    byte[] buildFileContent =
        FileSystemUtils.readWithKnownFileSize(buildFile, buildFile.getFileSize());
    BuildFileAST buildFileAst =
        BuildFileAST.parseBuildFile(
            ParserInputSource.create(buildFileContent, buildFile.asFragment()), eventHandler);
    Environment env =
        Environment.builder(mutability)
            .setGlobals(BazelLibrary.GLOBALS)
            .setSemantics(BuckStarlark.BUCK_STARLARK_SEMANTICS)
            .build();
    SkylarkUtils.setPhase(env, Phase.LOADING);
    new ParseContext(
            PackageContext.of(
                NativeGlobber.create(root),
                rawConfig,
                PackageIdentifier.create(RepositoryName.DEFAULT, PathFragment.create("my/package")),
                eventHandler,
                ImmutableMap.of()))
        .setup(env);
    env.setup(
        "package_name",
        FuncallExpression.getBuiltinCallable(SkylarkBuildModule.BUILD_MODULE, "package_name"));
    boolean exec = buildFileAst.exec(env, eventHandler);
    if (!exec && expectSuccess) {
      Assert.fail("Build file evaluation must have succeeded");
    }
    return env;
  }
}
