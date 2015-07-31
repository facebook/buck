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

package com.facebook.buck.util;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import javax.annotation.Nullable;

public class LineFetcher implements AutoCloseable {

  static final int BUFFER_LENGTH = 8192;
  private char[] readBuffer = new char[BUFFER_LENGTH];
  private char[] dosLineEndingCheckBuffer = new char[1];

  private final PushbackReader inputReader;

  LineFetcher(Reader baseReader) {
    inputReader = new PushbackReader(baseReader, BUFFER_LENGTH + dosLineEndingCheckBuffer.length);
  }

  @Nullable
  String readLine() throws IOException {
    int read = inputReader.read(readBuffer);
    if (read == -1) {
      return null;
    }

    for (int lineEnd = 0; lineEnd < read; lineEnd++) {
      if (isLineEnd(readBuffer[lineEnd])) {
        pushUnreadBack(lineEnd, read);
        return new String(readBuffer, 0, lineEnd);
      }
    }

    String thisSection = new String(readBuffer, 0, read);
    String nextSection = readLine();

    return nextSection == null ? thisSection : thisSection + nextSection;
  }

  private boolean isLineEnd(char c) {
    return c == '\r' || c == '\n';
  }

  private void pushUnreadBack(int lineEnd, int read)
    throws IOException {
    int startOfPushback = lineEnd + 1;

    if (readBuffer[lineEnd] == '\r') {
      if (lineEnd == read - 1) {
        handlePossibleWindowsLineEndingWithUnreadNewline();
      } else if (readBuffer[startOfPushback] == '\n') {
        startOfPushback++;
      }
    }

    inputReader.unread(readBuffer, startOfPushback, read - startOfPushback);
  }

  private void handlePossibleWindowsLineEndingWithUnreadNewline()
      throws IOException {
    inputReader.read(dosLineEndingCheckBuffer, 0, 1);
    if (dosLineEndingCheckBuffer[0] == '\n') {
      return;
    }

    inputReader.unread(dosLineEndingCheckBuffer);
  }

  @Override
  public void close() throws Exception {
    if (inputReader != null) {
      inputReader.close();
    }
  }
}
