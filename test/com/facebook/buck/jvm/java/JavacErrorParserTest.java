/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;

public class JavacErrorParserTest {

  ProjectFilesystem projectFilesystem;
  JavacErrorParser javacErrorParser;

  @Before
  public void setUp() {
    projectFilesystem = new ProjectFilesystem(Paths.get(".").toAbsolutePath());
    JavaPackageFinder javaPackageFinder = DefaultJavaPackageFinder.createDefaultJavaPackageFinder(
        ImmutableSet.of("/src/"));
    javacErrorParser = new JavacErrorParser(projectFilesystem, javaPackageFinder);
  }

  @Test
  public void shouldFindSymbolFromCannotFindSymbolError() {
    String error =
        "Foo.java:22: error: cannot find symbol\n" +
        "import com.facebook.buck.util.BuckConstant;\n" +
        "                             ^\n" +
        "  symbol:   class BuckConstant\n" +
        "  location: package com.facebook.buck.util\n";

    assertEquals(
        "JavacErrorParser didn't find the right symbol.",
        Optional.of("com.facebook.buck.util.BuckConstant"),
        javacErrorParser.getMissingSymbolFromCompilerError(error));
  }

  @Test
  public void shouldFindSymbolFromCannotFindSymbolInCurrentPackageError() {
    String error =
        projectFilesystem.getRootPath().toAbsolutePath().normalize() +
        "/src/com/facebook/buck/jvm/java/DefaultJavaLibrary.java:277: error: cannot find symbol\n" +
        "      final JavacStep javacStep;\n" +
        "            ^\n" +
        "  symbol:   class JavacStep\n" +
        "  location: class com.facebook.buck.jvm.java.DefaultJavaLibrary";

    assertEquals(
        "JavacErrorParser didn't find the right symbol.",
        Optional.of("com.facebook.buck.jvm.java.JavacStep"),
        javacErrorParser.getMissingSymbolFromCompilerError(error));

    String errorWithVariable = error.replace("class JavacStep", "variable JavacStep");
    assertEquals(
        "JavacErrorParser didn't find the right symbol for a 'variable' error.",
        Optional.of("com.facebook.buck.jvm.java.JavacStep"),
        javacErrorParser.getMissingSymbolFromCompilerError(errorWithVariable));
  }

  @Test
  public void shouldFindSymbolFromPackageDoesNotExistInCurrentPackageError() {
    String error =
        projectFilesystem.getRootPath().toAbsolutePath().normalize() +
        "/src/com/facebook/Foo.java:60: error: package BarBaz does not exist\n" +
        "      BarBaz.doStuff(),\n" +
        "           ^\n";


    assertEquals(
        "JavacErrorParser didn't find the right symbol.",
        Optional.of("com.facebook.BarBaz"),
        javacErrorParser.getMissingSymbolFromCompilerError(error));
  }

  @Test
  public void shouldFindSymbolFromImportPackageDoesNotExistError() {
    String error =
        "Foo.java:24: error: package com.facebook.buck.step does not exist\n" +
        "import com.facebook.buck.step.ExecutionContext;\n" +
        "                             ^\n";

      assertEquals(
          "JavacErrorParser didn't find the right symbol.",
          Optional.of("com.facebook.buck.step.ExecutionContext"),
          javacErrorParser.getMissingSymbolFromCompilerError(error));

  }

  @Test
  public void shouldFindSymbolFromStaticImportPackageDoesNotExistError() {
    String error =
        "Foo.java:19: error: package com.facebook.buck.rules.BuildableProperties does not exist\n" +
        "import static com.facebook.buck.rules.BuildableProperties.Kind.ANDROID;\n" +
        "                                                              ^\n";

    assertEquals(
        "JavacErrorParser didn't find the right symbol.",
        Optional.of("com.facebook.buck.rules.BuildableProperties.Kind"),
        javacErrorParser.getMissingSymbolFromCompilerError(error));
  }

  @Test
  public void shouldFindSymbolFromCannotAccessError() {
    String error =
        "Foo.java:46: error: cannot access com.facebook.buck.rules.Description\n" +
        "public class SomeDescription implements Description<SomeDescription.Arg>,\n" +
        "       ^\n" +
        "  class file for com.facebook.buck.rules.Description not found\n";

    assertEquals(
        "JavacErrorParser didn't find the right symbol.",
        Optional.of("com.facebook.buck.rules.Description"),
        javacErrorParser.getMissingSymbolFromCompilerError(error));
  }
}
