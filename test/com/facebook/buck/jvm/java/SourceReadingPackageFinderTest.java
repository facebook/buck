/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Joiner;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;

public class SourceReadingPackageFinderTest {

  private SourceReadingPackageFinder finder;

  @Before
  public void setUpPackageFinder() {
    finder = new SourceReadingPackageFinder(FakeProjectFilesystem.createJavaOnlyFilesystem());
  }

  @Test
  public void shouldReadThePackage() throws IOException {
    String contents = "package com.example;";

    String name = finder.findJavaPackage(new StringReader(contents));

    assertEquals("com.example", name);
  }

  @Test
  public void shouldIgnoreASingleLineComment() throws IOException {
    String contents =
        "// Copyright Advanced Cheese Industries, 2016\n" +
        "package com.example;";

    String name = finder.findJavaPackage(new StringReader(contents));

    assertEquals("com.example", name);
  }

  @Test
  public void shouldIgnoreAMultiLineComment() throws IOException {
    String contents =
        "/*****\n" +
        "* Copyright Advanced Cheese Industries, 2016\n" +
        "*/package com.example;";

    String name = finder.findJavaPackage(new StringReader(contents));

    assertEquals("com.example", name);
  }

  @Test
  public void anImportStatmentMeansTheDefaultPackageIsBeingUsed() throws IOException {
    String contents = "import com.example.Cheese;";

    String name = finder.findJavaPackage(new StringReader(contents));

    assertEquals("", name);
  }

  @Test
  public void theBeginningOfAClassDefinitionMeansThatTheDefaultPackageIsUsed() throws IOException {
    String contents = "public class A {}";

    String name = finder.findJavaPackage(new StringReader(contents));

    assertEquals("", name);
  }

  @Test
  public void leadingWhitespaceIsTotesLegit() throws IOException {
    String contents = "  \t\t\r\n   package com.example;";

    String name = finder.findJavaPackage(new StringReader(contents));

    assertEquals("com.example", name);
  }

  @Test
  public void realisticLookingClassesGetParsedProperly() throws IOException {
    String contents = Joiner.on('\n').join(
        "// This is a comment",
        "/* Copyright belongs to someone, right",
        " * @author foo@example.com",
        " */",
        "",
        "package com.example;");

    String name = finder.findJavaPackage(new StringReader(contents));

    assertEquals("com.example", name);
  }

}
