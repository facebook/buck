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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.Test;

public class DiagnosticCleanerTest {

  @Test
  public void testCleanerRemovesDuplicateDiagnostics() {

    assertEquals(
        Arrays.asList(
            newDiagnostic(Diagnostic.Kind.ERROR, "proc.messager", 10, 10, 20, 1, 1),
            newDiagnostic(Diagnostic.Kind.ERROR, "proc.messager", 0, 0, 10, 2, 1),
            newDiagnostic(Diagnostic.Kind.ERROR, "proc.messager", 0, 0, 10, 1, 1)),
        DiagnosticCleaner.clean(
            Arrays.asList(
                newDiagnostic(Diagnostic.Kind.ERROR, "proc.messager", 10, 10, 20, 1, 1),
                newDiagnostic(Diagnostic.Kind.ERROR, "proc.messager", 0, 0, 10, 2, 1),
                newDiagnostic(Diagnostic.Kind.ERROR, "proc.messager", 0, 0, 10, 1, 1),
                newDiagnostic(Diagnostic.Kind.ERROR, "proc.messager", 0, 0, 10, 2, 1),
                newDiagnostic(Diagnostic.Kind.ERROR, "proc.messager", 0, 0, 10, 1, 1),
                newDiagnostic(Diagnostic.Kind.ERROR, "proc.messager", 10, 10, 20, 1, 1))));
  }

  @Test
  public void testCleanerPutsBlockingDiagnosticsFirst() {
    assertEquals(
        Arrays.asList(
            newDiagnostic(Diagnostic.Kind.ERROR, "proc.messager", 10, 10, 20, 1, 1),
            newDiagnostic(Diagnostic.Kind.ERROR, "foo.bar", 0, 0, 10, 2, 1),
            newDiagnostic(Diagnostic.Kind.ERROR, "compiler.err.doesnt.exist", 0, 0, 10, 1, 1)),
        DiagnosticCleaner.clean(
            Arrays.asList(
                newDiagnostic(Diagnostic.Kind.ERROR, "compiler.err.doesnt.exist", 0, 0, 10, 1, 1),
                newDiagnostic(Diagnostic.Kind.ERROR, "proc.messager", 10, 10, 20, 1, 1),
                newDiagnostic(Diagnostic.Kind.ERROR, "foo.bar", 0, 0, 10, 2, 1))));
  }

  @Test
  public void testCleanerUsesStableSort() {
    assertEquals(
        Arrays.asList(
            newDiagnostic(Diagnostic.Kind.ERROR, "proc.messager", 10, 10, 20, 1, 1),
            newDiagnostic(Diagnostic.Kind.ERROR, "compiler.err.doesnt.exist", 0, 0, 10, 2, 1),
            newDiagnostic(Diagnostic.Kind.ERROR, "compiler.err.doesnt.exist", 0, 0, 10, 1, 1)),
        DiagnosticCleaner.clean(
            Arrays.asList(
                newDiagnostic(Diagnostic.Kind.ERROR, "compiler.err.doesnt.exist", 0, 0, 10, 2, 1),
                newDiagnostic(Diagnostic.Kind.ERROR, "proc.messager", 10, 10, 20, 1, 1),
                newDiagnostic(
                    Diagnostic.Kind.ERROR, "compiler.err.doesnt.exist", 0, 0, 10, 1, 1))));
  }

  private static FakeDiagnostic newDiagnostic(
      Diagnostic.Kind kind,
      String code,
      long pos,
      long startPos,
      long endPos,
      long line,
      long col) {
    return new FakeDiagnostic(kind, code, pos, startPos, endPos, line, col);
  }

  private static class FakeDiagnostic implements Diagnostic<JavaFileObject> {

    private final Kind kind;
    private final String code;
    private final long pos;
    private final long startPos;
    private final long endPos;
    private final long line;
    private final long col;

    private FakeDiagnostic(
        Kind kind, String code, long pos, long startPos, long endPos, long line, long col) {
      this.kind = kind;
      this.code = code;
      this.pos = pos;
      this.startPos = startPos;
      this.endPos = endPos;
      this.line = line;
      this.col = col;
    }

    @Override
    public Kind getKind() {
      return kind;
    }

    @Override
    public JavaFileObject getSource() {
      return null;
    }

    @Override
    public long getPosition() {
      return pos;
    }

    @Override
    public long getStartPosition() {
      return startPos;
    }

    @Override
    public long getEndPosition() {
      return endPos;
    }

    @Override
    public long getLineNumber() {
      return line;
    }

    @Override
    public long getColumnNumber() {
      return col;
    }

    @Override
    public String getCode() {
      return code;
    }

    @Override
    public String getMessage(Locale locale) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      FakeDiagnostic that = (FakeDiagnostic) o;

      if (pos != that.pos) {
        return false;
      }
      if (startPos != that.startPos) {
        return false;
      }
      if (endPos != that.endPos) {
        return false;
      }
      if (line != that.line) {
        return false;
      }
      if (col != that.col) {
        return false;
      }
      if (kind != that.kind) {
        return false;
      }
      return code.equals(that.code);
    }

    @Override
    public int hashCode() {
      int result = kind.hashCode();
      result = 31 * result + code.hashCode();
      result = 31 * result + (int) (pos ^ (pos >>> 32));
      result = 31 * result + (int) (startPos ^ (startPos >>> 32));
      result = 31 * result + (int) (endPos ^ (endPos >>> 32));
      result = 31 * result + (int) (line ^ (line >>> 32));
      result = 31 * result + (int) (col ^ (col >>> 32));
      return result;
    }

    @Override
    public String toString() {
      return "FakeDiagnostic{"
          + "kind="
          + kind
          + ", code='"
          + code
          + '\''
          + ", pos="
          + pos
          + ", startPos="
          + startPos
          + ", endPos="
          + endPos
          + ", line="
          + line
          + ", col="
          + col
          + '}';
    }
  }
}
