/*
 * Copyright 2015-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.Locale;

import javax.annotation.Nullable;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;

public class DiagnosticPrettyPrinterTest {

  @Test
  public void ifThereAreNoLineNumbersOnlyTheFormattedMessageIsReturned() throws Exception {
    String message = "Something has gone wrong.";

    String formatted = DiagnosticPrettyPrinter.format(
        createDiagnostic(message, "Example.java", "package foo", -1, -1));

    // Paths should be absolute.
    assertEquals(
        Paths.get("Example.java").toAbsolutePath() + ":-1: error: Something has gone wrong.\n",
        formatted);
  }

  @Test
  public void ifThereAreNoLineNumbersAllLinesOfTheFormattedMessageAreReturned() throws Exception {
    String summary = "Something has gone wrong";
    String remainder = "Very, very wrong";

    String formatted = DiagnosticPrettyPrinter.format(
        createDiagnostic(summary + "\n" + remainder, "Example.java", "package foo", -1, -1));

    assertTrue(formatted, formatted.contains(summary));
    assertTrue(formatted, formatted.contains(remainder));
  }

  @Test
  public void ifThereAreLineNumbersErrorContextIsDisplayed() throws Exception {
    String code = "some line of\ncode with an\nerror";
    //                           123
    String formatted = DiagnosticPrettyPrinter.format(
        createDiagnostic("EOL", "Example.java", code, 2, 3));

    assertTrue(formatted, formatted.contains("code with an\n  ^"));
  }

  @Test
  public void errorContextIsDisplayedAfterTheSummaryButBeforeTheRemainderOfTheMessage()
      throws Exception {
    String code = "some line of\ncode with an\nerror";
    //                           123
    String formatted = DiagnosticPrettyPrinter.format(createDiagnostic(
            "Oh noes!\nAll your build\nAre Belong to Fail", "Example.java", code, 2, 3));

    // The path is actually prefixed with the cwd. This is close enough to the full report to do.
    assertTrue(
        formatted,
        formatted.contains(
            "Example.java:2: error: Oh noes!\n" +
            "code with an\n" +
            "  ^\n" +
            "All your build\n" +
            "Are Belong to Fail"));
  }

  /**
   * Create a {@link Diagnostic} for use in tests.
   *
   * @param message The compilation error message.
   * @param row The row within the source, 1-indexed because the compiler does that.
   * @param column The column within {@code row}, also 1-indexed.
   */
  private Diagnostic<? extends JavaFileObject> createDiagnostic(
      final String message,
      String pathToSource,
      String sourceContents,
      final long row,
      final long column) throws Exception {
    final JavaFileObject fileObject = new StringJavaFileObject(pathToSource, sourceContents);

    // Calculate the position, because we're all bad at counting things
    int pos = -1;
    if (row != -1) {
      pos = -1;
      int rowCount = 1;
      while (rowCount <= row) {
        pos++;
        if (sourceContents.charAt(pos) == '\n') {
          rowCount++;
        }
      }

      // And now just add the row, which is 1 indexed, so we then subtract 1.
      pos += row - 1;
    }
    final int position = pos;

    return new Diagnostic<JavaFileObject>() {
      @Override
      public Kind getKind() {
        return Kind.ERROR;
      }

      @Override
      public JavaFileObject getSource() {
        return fileObject;
      }

      @Override
      public long getPosition() {
        return position;
      }

      @Override
      public long getStartPosition() {
        return position;
      }

      @Override
      public long getEndPosition() {
        return position;
      }

      @Override
      public long getLineNumber() {
        return row;
      }

      @Override
      public long getColumnNumber() {
        return column;
      }

      @Override
      @Nullable
      public String getCode() {
        return null;
      }

      @Override
      public String getMessage(Locale locale) {
        return message;
      }
    };
  }

  private class StringJavaFileObject extends SimpleJavaFileObject {
    private final String content;

    protected StringJavaFileObject(String pathToSource, String content) throws URISyntaxException {
      super(Paths.get(pathToSource).toUri(), Kind.SOURCE);
      this.content = content;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
      return content;
    }
  }
}
