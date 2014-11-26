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

package com.facebook.buck.java;

import static com.facebook.buck.java.JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS;
import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.io.IOException;

public class JavaFileParserTest {

  static String javaCodeWithManyClasses =
      "package com.example;\n" +
      "\n" +
      "public class Example {\n" +
      "  public static int variablesNotCaptured, maybeLater;\n" +
      "\n" +
      "  private Example() {}\n" +
      "\n" +
      "  public static void functionsNotCapturedEither() {\n" +
      "  }\n" +
      "\n" +
      "  public enum InnerEnum {\n" +
      "    foo;\n" +
      "\n" +
      "    public class InnerClass {\n" +
      "    }\n" +
      "  }\n" +
      "\n" +
      "  interface InnerInterface {\n" +
      "  }" +
      "}\n" +
      "\n" +
      "class AnotherOuterClass {\n" +
      "}\n";

  @Test
  public void testJavaFileParsing() throws IOException {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);

    ImmutableSortedSet<String> symbols = parser.getExportedSymbolsFromString(
        javaCodeWithManyClasses);

    assertEquals(
        "JavaFileParser didn't find the symbols we expected.",
        ImmutableSortedSet.of(
            "com.example.AnotherOuterClass",
            "com.example.Example",
            "com.example.Example.InnerEnum",
            "com.example.Example.InnerEnum.InnerClass",
            "com.example.Example.InnerInterface"),
        symbols);
  }

  static String javaCodeWithLocalClass =
      "package com.example;\n" +
      "public class NonlocalClass {\n" +
      "  public static void exampleMethod() {\n" +
      "    class LocalClass {\n" +
      "    }\n" +
      "  }\n" +
      "}\n";

  @Test
  public void testJavaFileParsingWithLocalClass() throws IOException {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);

    ImmutableSortedSet<String> symbols = parser.getExportedSymbolsFromString(
        javaCodeWithLocalClass);

    assertEquals(
        "JavaFileParser didn't find the symbols we expected.",
        ImmutableSortedSet.of("com.example.NonlocalClass"),
        symbols);
  }

  static String javaCodeWithNoPackage = "public class NoPackageExample { }";

  @Test
  public void testJavaFileParsingWithNoPackage() throws IOException {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);

    ImmutableSortedSet<String> symbols = parser.getExportedSymbolsFromString(
        javaCodeWithNoPackage);

    assertEquals(
        "JavaFileParser didn't find the symbols we expected.",
        ImmutableSortedSet.of("NoPackageExample"),
        symbols);
  }

  static String javaCodeWithAnnotationType = "public @interface ExampleAnnotationType { }";

  @Test
  public void testJavaFileParsingWithAnnotationType() throws IOException {
    JavaFileParser parser = JavaFileParser.createJavaFileParser(DEFAULT_JAVAC_OPTIONS);

    ImmutableSortedSet<String> symbols = parser.getExportedSymbolsFromString(
        javaCodeWithAnnotationType);

    assertEquals(
        "JavaFileParser didn't find the symbols we expected.",
        ImmutableSortedSet.of("ExampleAnnotationType"),
        symbols);
  }


}
