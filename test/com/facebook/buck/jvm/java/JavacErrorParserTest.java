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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.util.string.MoreStrings;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class JavacErrorParserTest {

  ProjectFilesystem projectFilesystem;
  JavacErrorParser javacErrorParser;

  @Before
  public void setUp() throws InterruptedException {
    projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(Paths.get(".").toAbsolutePath());
    JavaPackageFinder javaPackageFinder =
        DefaultJavaPackageFinder.createDefaultJavaPackageFinder(ImmutableSet.of("/src/"));
    javacErrorParser = new JavacErrorParser(projectFilesystem, javaPackageFinder);
  }

  @Test
  public void shouldFindSymbolFromCannotFindSymbolError() {
    String error =
        MoreStrings.linesToText(
            "Foo.java:22: error: cannot find symbol",
            "import com.facebook.buck.util.BuckConstant;",
            "                             ^",
            "  symbol:   class BuckConstant",
            "  location: package com.facebook.buck.util",
            "");

    assertEquals(
        "JavacErrorParser didn't find the right symbol.",
        Optional.of("com.facebook.buck.util.BuckConstant"),
        javacErrorParser.getMissingSymbolFromCompilerError(error));
  }

  @Test
  public void shouldFindSymbolFromCannotFindSymbolInCurrentPackageError() {
    String error =
        projectFilesystem.getRootPath().toAbsolutePath().normalize()
            + MoreStrings.linesToText(
                "/src/com/facebook/buck/jvm/java/DefaultJavaLibrary.java:277: error: cannot find symbol",
                "      final JavacStep javacStep;",
                "            ^",
                "  symbol:   class JavacStep",
                "  location: class com.facebook.buck.jvm.java.DefaultJavaLibrary");

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
        projectFilesystem.getRootPath().toAbsolutePath().normalize()
            + MoreStrings.linesToText(
                "/src/com/facebook/Foo.java:60: error: package BarBaz does not exist",
                "      BarBaz.doStuff(),",
                "           ^",
                "");

    assertEquals(
        "JavacErrorParser didn't find the right symbol.",
        Optional.of("com.facebook.BarBaz"),
        javacErrorParser.getMissingSymbolFromCompilerError(error));
  }

  @Test
  public void shouldFindSymbolFromImportPackageDoesNotExistError() {
    String error =
        MoreStrings.linesToText(
            "Foo.java:24: error: package com.facebook.buck.step does not exist",
            "import com.facebook.buck.step.ExecutionContext;",
            "                             ^,",
            "");

    assertEquals(
        "JavacErrorParser didn't find the right symbol.",
        Optional.of("com.facebook.buck.step.ExecutionContext"),
        javacErrorParser.getMissingSymbolFromCompilerError(error));
  }

  @Test
  public void shouldFindSymbolFromStaticImportPackageDoesNotExistError() {
    String error =
        MoreStrings.linesToText(
            "Foo.java:19: error: package com.facebook.buck.rules.BuildableProperties does not exist",
            "import static com.facebook.buck.rules.BuildableProperties.Kind.ANDROID;",
            "                                                              ^",
            "");

    assertEquals(
        "JavacErrorParser didn't find the right symbol.",
        Optional.of("com.facebook.buck.rules.BuildableProperties.Kind"),
        javacErrorParser.getMissingSymbolFromCompilerError(error));
  }

  @Test
  public void shouldFindSymbolFromCannotAccessError() {
    String error =
        MoreStrings.linesToText(
            "Foo.java:46: error: cannot access com.facebook.buck.rules.Description",
            "public class SomeDescription implements Description<SomeDescription.Arg>,",
            "       ^",
            "  class file for com.facebook.buck.rules.Description not found",
            "");

    assertEquals(
        "JavacErrorParser didn't find the right symbol.",
        Optional.of("com.facebook.buck.rules.Description"),
        javacErrorParser.getMissingSymbolFromCompilerError(error));
  }
}
