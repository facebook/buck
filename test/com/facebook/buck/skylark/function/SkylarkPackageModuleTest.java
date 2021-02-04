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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.starlark.compatible.BuckStarlark;
import com.facebook.buck.core.starlark.eventhandler.Event;
import com.facebook.buck.core.starlark.eventhandler.EventCollector;
import com.facebook.buck.core.starlark.eventhandler.EventKind;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.api.PackageMetadata;
import com.facebook.buck.skylark.io.impl.NativeGlobber;
import com.facebook.buck.skylark.packages.PackageContext;
import com.facebook.buck.skylark.parser.context.ParseContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumSet;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.Program;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.SyntaxError;
import org.junit.Before;
import org.junit.Test;

public class SkylarkPackageModuleTest {
  private AbsPath root;
  private EventCollector eventHandler;

  @Before
  public void setUp() {
    ProjectFilesystem projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    root = projectFilesystem.getRootPath();
    eventHandler = new EventCollector(EnumSet.allOf(EventKind.class));
  }

  @Test
  public void packageIsParsed() throws Exception {
    assertEquals(
        evaluate("package(visibility=['PUBLIC'])", true).getPackage().getVisibility().get(0),
        "PUBLIC");
  }

  @Test
  public void defaultValueIsReturned() throws Exception {
    PackageMetadata packageMetadata = evaluate("", true).getPackage();
    assertTrue(packageMetadata.getVisibility().isEmpty());
    assertTrue(packageMetadata.getVisibility().isEmpty());
  }

  @Test
  public void nonPackageFunctionsAreNotAllowed() throws Exception {
    try {
      evaluate("pkg = package_name()", false);
      fail("unreachable");
    } catch (SyntaxError.Exception e) {
      assertEquals("name 'package_name' is not defined", e.getMessage());
    }
  }

  private ParseContext evaluate(String expression, boolean expectSuccess) throws Exception {
    AbsPath buildFile = root.resolve("PACKAGE");
    Files.write(buildFile.getPath(), ImmutableList.of(expression));
    return evaluate(buildFile, expectSuccess);
  }

  private ParseContext evaluate(AbsPath buildFile, boolean expectSuccess) throws Exception {
    try (Mutability mutability = Mutability.create("PACKAGE")) {
      return evaluate(buildFile, mutability, expectSuccess);
    }
  }

  private ParseContext evaluate(AbsPath buildFile, Mutability mutability, boolean expectSuccess)
      throws Exception {
    byte[] buildFileContent = Files.readAllBytes(buildFile.getPath());
    StarlarkFile buildFileAst =
        StarlarkFile.parse(
            ParserInput.fromString(
                new String(buildFileContent, StandardCharsets.UTF_8), buildFile.toString()));

    ImmutableMap.Builder<String, Object> vars = ImmutableMap.builder();
    vars.putAll(Starlark.UNIVERSE);
    Starlark.addMethods(vars, SkylarkPackageModule.PACKAGE_MODULE);

    Module module = Module.withPredeclared(BuckStarlark.BUCK_STARLARK_SEMANTICS, vars.build());

    StarlarkThread env = new StarlarkThread(mutability, BuckStarlark.BUCK_STARLARK_SEMANTICS);
    ParseContext parseContext =
        new ParseContext(
            PackageContext.of(
                NativeGlobber.create(root),
                ImmutableMap.of(),
                CanonicalCellName.rootCell(),
                ForwardRelativePath.of("my/package"),
                eventHandler,
                ImmutableMap.of()));
    parseContext.setup(env);

    if (!buildFileAst.errors().isEmpty()) {
      for (SyntaxError error : buildFileAst.errors()) {
        eventHandler.handle(Event.error(error.location(), error.message()));
      }
      assertFalse(expectSuccess);
      return parseContext;
    }

    try {
      Program program = Program.compileFile(buildFileAst, module);
      Starlark.execFileProgram(program, module, env);
      assertTrue(expectSuccess);
    } catch (EvalException e) {
      eventHandler.handle(Event.error(e.getDeprecatedLocation(), e.getMessage()));
      assertFalse(expectSuccess);
    }
    return parseContext;
  }
}
