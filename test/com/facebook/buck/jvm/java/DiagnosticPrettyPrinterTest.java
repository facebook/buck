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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.util.string.MoreStrings;
import java.nio.file.Paths;
import java.util.Locale;
import javax.annotation.Nullable;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import org.junit.Test;

public class DiagnosticPrettyPrinterTest {

  @Test
  public void ifThereAreNoLineNumbersOnlyTheFormattedMessageIsReturned() throws Exception {
    String message = "Something has gone wrong.";

    String formatted =
        DiagnosticPrettyPrinter.format(
            createDiagnostic(message, "Example.java", "package foo", -1, -1));

    // Paths should be absolute.
    assertEquals(
        Paths.get("Example.java").toUri().getPath()
            + ":-1: error: Something has gone wrong."
            + System.lineSeparator(),
        formatted);
  }

  @Test
  public void ifThereAreNoLineNumbersAllLinesOfTheFormattedMessageAreReturned() throws Exception {
    String summary = "Something has gone wrong";
    String remainder = "Very, very wrong";

    String formatted =
        DiagnosticPrettyPrinter.format(
            createDiagnostic(
                summary + System.lineSeparator() + remainder,
                "Example.java",
                "package foo",
                -1,
                -1));

    assertTrue(formatted, formatted.contains(summary));
    assertTrue(formatted, formatted.contains(remainder));
  }

  @Test
  public void ifThereAreLineNumbersErrorContextIsDisplayed() throws Exception {
    String code = MoreStrings.linesToText("some line of", "code with an", "error");
    //                           123
    String formatted =
        DiagnosticPrettyPrinter.format(createDiagnostic("EOL", "Example.java", code, 2, 3));

    assertTrue(formatted, formatted.contains(MoreStrings.linesToText("code with an", "  ^")));
  }

  @Test
  public void errorContextIsDisplayedAfterTheSummaryButBeforeTheRemainderOfTheMessage()
      throws Exception {
    String code = MoreStrings.linesToText("some line of", "code with an", "error");
    //                           123
    String formatted =
        DiagnosticPrettyPrinter.format(
            createDiagnostic(
                MoreStrings.linesToText("Oh noes!", "All your build", "Are Belong to Fail"),
                "Example.java",
                code,
                2,
                3));

    // The path is actually prefixed with the cwd. This is close enough to the full report to do.
    assertTrue(
        formatted,
        formatted.contains(
            MoreStrings.linesToText(
                "Example.java:2: error: Oh noes!",
                "code with an",
                "  ^",
                "All your build",
                "Are Belong to Fail")));
  }

  /**
   * Create a {@link Diagnostic} for use in tests.
   *
   * @param message The compilation error message.
   * @param row The row within the source, 1-indexed because the compiler does that.
   * @param column The column within {@code row}, also 1-indexed.
   */
  private Diagnostic<? extends JavaFileObject> createDiagnostic(
      String message, String pathToSource, String sourceContents, long row, long column)
      throws Exception {
    JavaFileObject fileObject = new StringJavaFileObject(pathToSource, sourceContents);

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

    protected StringJavaFileObject(String pathToSource, String content) {
      super(Paths.get(pathToSource).toUri(), Kind.SOURCE);
      this.content = content;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return content;
    }
  }
}
