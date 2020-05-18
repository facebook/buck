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

import com.facebook.buck.core.starlark.compatible.BuckStarlark;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.filesystem.skylark.SkylarkFilesystem;
import com.facebook.buck.skylark.parser.context.ReadConfigContext;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.PrintingEventHandler;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.EvalUtils;
import com.google.devtools.build.lib.syntax.Module;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.ParserInput;
import com.google.devtools.build.lib.syntax.Starlark;
import com.google.devtools.build.lib.syntax.StarlarkFile;
import com.google.devtools.build.lib.syntax.StarlarkThread;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.util.EnumSet;
import org.junit.Before;
import org.junit.Test;

public class ReadConfigTest {

  private Path root;
  private PrintingEventHandler eventHandler;
  private ImmutableMap<String, ImmutableMap<String, String>> rawConfig;

  @Before
  public void setUp() {
    ProjectFilesystem projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    SkylarkFilesystem fileSystem = SkylarkFilesystem.using(projectFilesystem);
    root = fileSystem.getPath(projectFilesystem.getRootPath().toString());
    eventHandler = new PrintingEventHandler(EnumSet.allOf(EventKind.class));
    rawConfig = ImmutableMap.of();
  }

  @Test
  public void defaultValueIsReturned() throws Exception {
    assertThat(
        evaluate("value = read_config('foo', 'bar', 'baz')").getGlobals().get("value"),
        equalTo("baz"));
  }

  @Test
  public void noneIsReturnedWhenFieldIsNotPresent() throws Exception {
    assertThat(
        evaluate("value = read_config('foo', 'bar')").getGlobals().get("value"),
        equalTo(Starlark.NONE));
  }

  @Test
  public void configValueIsReturnedIfExists() throws Exception {
    rawConfig = ImmutableMap.of("foo", ImmutableMap.of("bar", "value"));
    assertThat(
        evaluate("value = read_config('foo', 'bar')").getGlobals().get("value"), equalTo("value"));
  }

  private StarlarkThread evaluate(String expression) throws IOException, InterruptedException {
    Path buildFile = root.getChild("BUCK");
    FileSystemUtils.writeContentAsLatin1(buildFile, expression);
    return evaluate(buildFile);
  }

  private StarlarkThread evaluate(Path buildFile) throws IOException, InterruptedException {
    try (Mutability mutability = Mutability.create("BUCK")) {
      return evaluate(buildFile, mutability);
    }
  }

  private StarlarkThread evaluate(Path buildFile, Mutability mutability)
      throws IOException, InterruptedException {
    byte[] buildFileContent =
        FileSystemUtils.readWithKnownFileSize(buildFile, buildFile.getFileSize());
    StarlarkFile buildFileAst =
        StarlarkFile.parse(ParserInput.create(buildFileContent, buildFile.asFragment()));

    ImmutableMap.Builder<String, Object> module = ImmutableMap.builder();
    module.putAll(Starlark.UNIVERSE);
    Starlark.addMethods(module, SkylarkBuildModule.BUILD_MODULE);

    StarlarkThread env =
        StarlarkThread.builder(mutability)
            .setGlobals(Module.createForBuiltins(module.build()))
            .setSemantics(BuckStarlark.BUCK_STARLARK_SEMANTICS)
            .build();
    ReadConfigContext readConfigContext = new ReadConfigContext(rawConfig);
    readConfigContext.setup(env);

    try {
      EvalUtils.exec(buildFileAst, env);
    } catch (EvalException e) {
      throw new RuntimeException(e);
    }
    return env;
  }
}
