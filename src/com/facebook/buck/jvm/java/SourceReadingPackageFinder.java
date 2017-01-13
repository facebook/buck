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

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;

/**
 * Designed to parse just enough valid java to figure out the package name from a java source file.
 */
class SourceReadingPackageFinder implements JavaPackageFinder {

  private final ProjectFilesystem filesystem;

  public SourceReadingPackageFinder(ProjectFilesystem filesystem) {
    this.filesystem = filesystem;
  }

  @Override
  public Path findJavaPackageFolder(Path pathRelativeToProjectRoot) {
    if ("java".equals(MorePaths.getFileExtension(pathRelativeToProjectRoot))) {
      String packageName = findJavaPackage(pathRelativeToProjectRoot);

      Iterator<String> segments = Splitter.on('.').split(packageName).iterator();
      if (!segments.hasNext()) {
        throw new HumanReadableException(
            "Unable to create path from %s in %s",
            packageName,
            pathRelativeToProjectRoot);
      }
      Path path = pathRelativeToProjectRoot.getFileSystem().getPath(segments.next());
      while (segments.hasNext()) {
        path = path.resolve(segments.next());
      }
      return path;
    }
    throw new IllegalArgumentException("Only java source files are supported");
  }

  @Override
  public String findJavaPackage(Path pathRelativeToProjectRoot) {
    Path path = filesystem.resolve(pathRelativeToProjectRoot);

    try (BufferedReader reader = Files.newBufferedReader(path)) {
      return findJavaPackage(reader);
    } catch (IOException e) {
      throw new HumanReadableException(
          "Unable to read package for file: %s",
          pathRelativeToProjectRoot);
    }
  }

  @Override
  public String findJavaPackage(BuildTarget buildTarget) {
    throw new UnsupportedOperationException("Unable to just guess a package: " + buildTarget);
  }

  @VisibleForTesting
  String findJavaPackage(Reader reader) throws IOException {
    Lexer lexer = new Lexer(reader);

    for (;;) {
      Token token = lexer.getNextToken();

      switch (token.getKind()) {
        case PACKAGE:
          return token.getValue();

        case EOF:
        case UNKNOWN:
          // We have no idea what to do. Assume the default package
          return "";

        case COMMENT:
        case WHITE_SPACE:
          // Do nothing, allow the reader to continue;
      }
    }
  }

  private static class Lexer {
    private final Reader reader;

    public Lexer(Reader reader) {
      Preconditions.checkArgument(reader.markSupported(), "Reader does not support mark()");

      this.reader = reader;
    }

    public Token getNextToken() throws IOException {
      int ch = reader.read();

      switch (ch) {
        case 'p':
          return readPackage(reader);

        case '/':
          return readComment(reader);

        default:
          if (Character.isWhitespace(ch)) {
            return readWhiteSpace(reader);
          }
          return new Token(Token.Kind.UNKNOWN, "");
      }
    }

    private Token readWhiteSpace(Reader reader) throws IOException {
      // Just keep going until we see something that's not whitespace.
      for (;;) {
        reader.mark(1);
        int ch = reader.read();

        if (ch == -1) {
          return new Token(Token.Kind.EOF, "");
        }

        if (!Character.isWhitespace(ch)) {
          reader.reset();
          return new Token(Token.Kind.WHITE_SPACE, " ");
        }
      }
    }

    private Token readComment(Reader reader) throws IOException {
      // We've already consumed the first '/'. Find out if we've got garbage, single or multi-line
      int ch = reader.read();
      switch (ch) {
        case '/':
          return readSingleLineComment(reader);

        case '*':
          return readMultiLineComment(reader);
      }
      return new Token(Token.Kind.UNKNOWN, "");
    }

    private Token readSingleLineComment(Reader reader) throws IOException {
      // Read up until we see a line ending. Assume Mac, UNIX or DOS line endings.
      StringBuilder value = new StringBuilder("//");

      for (;;) {
        int ch = reader.read();

        switch (ch) {
          case -1:
            return new Token(Token.Kind.EOF, "");

          // New-line always means the end of the line
          case '\n':
            return new Token(Token.Kind.COMMENT, value.toString());

          // Carriage return might mean either a Mac EOL or a DOS one. Peek ahead
          case '\r':
            reader.mark(1);
            ch = reader.read();
            if (ch == -1) {
              return new Token(Token.Kind.UNKNOWN, "");
            }
            if (ch == '\n') { // DOS newline
              return new Token(Token.Kind.COMMENT, value.toString());
            } else { // Mac newline
              reader.reset();
              return new Token(Token.Kind.COMMENT, value.toString());
            }

          default:
            value.append((char) ch);
            break;
        }
      }
    }

    private Token readMultiLineComment(Reader reader) throws IOException {
      int last = reader.read();
      if (last == -1) {
        return new Token(Token.Kind.UNKNOWN, "");
      }

      StringBuilder value = new StringBuilder("/*");
      value.append((char) last);
      for (;;) {
        int ch = reader.read();

        if (ch == -1) {
          return new Token(Token.Kind.UNKNOWN, "");
        }

        if (last == '*' && ch == '/') {
          // Strip the trailing * from the comment
          return new Token(Token.Kind.COMMENT, value.substring(0, value.length() - 1));
        }

        last = ch;
        value.append((char) ch);
      }
    }

    private Token readPackage(Reader reader) throws IOException {
      // We've already consumed the first "p".
      int expectedLength = "ackage".length();
      char[] keyword = new char[expectedLength];
      int length = reader.read(keyword);
      if (length != expectedLength || !Arrays.equals("ackage".toCharArray(), keyword)) {
        return new Token(Token.Kind.UNKNOWN, "");
      }

      // Consume any whitespace
      int ch = reader.read();
      while (ch != -1 && Character.isWhitespace(ch)) {
        ch = reader.read();
      }
      if (ch == -1) {
        return new Token(Token.Kind.UNKNOWN, "");
      }

      // Read up until the end of a java identifier
      StringBuilder value = new StringBuilder();
      while (ch != -1 && (Character.isJavaIdentifierPart(ch) || '.' == ch)) {
        value.append((char) ch);
        ch = reader.read();
      }
      if (ch == -1) {
        return new Token(Token.Kind.UNKNOWN, "");
      }

      return new Token(Token.Kind.PACKAGE, value.toString());
    }
  }

  private static class Token {
    private final Kind kind;
    private final String value;

    public enum Kind {
      COMMENT,
      EOF,
      PACKAGE,
      UNKNOWN,
      WHITE_SPACE
    }

    public Token(Token.Kind kind, String value) {
      this.kind = kind;
      this.value = value;
    }

    public Kind getKind() {
      return kind;
    }

    public String getValue() {
      return value;
    }
  }
}
