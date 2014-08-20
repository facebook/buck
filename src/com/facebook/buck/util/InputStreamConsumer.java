/*
 * Copyright 2012-present Facebook, Inc.
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

import com.google.common.base.Preconditions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;

public final class InputStreamConsumer implements Runnable {

  private static final String LINE_SEPARATOR = System.getProperty("line.separator");

  private final BufferedReader inputReader;
  private final PrintStream printStream;
  private final Ansi ansi;
  private final boolean flagOutputWrittenToStream;
  private boolean hasWrittenOutputToPrintStream = false;

  /**
   * @param shouldFlagOutputWrittenToStream If {@code true}, any output in this stream will be
   *     flagged in the console using ANSI escape codes, if appropriate.
   */
  public InputStreamConsumer(InputStream inputStream,
      PrintStream printStream,
      Ansi ansi,
      boolean shouldFlagOutputWrittenToStream) {
    this(new InputStreamReader(inputStream), printStream, ansi, shouldFlagOutputWrittenToStream);
  }

  public InputStreamConsumer(Reader reader,
      PrintStream printStream,
      Ansi ansi,
      boolean flagOutputWrittenToStream) {
    this.inputReader = new BufferedReader(reader);
    this.printStream = Preconditions.checkNotNull(printStream);
    this.ansi = Preconditions.checkNotNull(ansi);
    this.flagOutputWrittenToStream = flagOutputWrittenToStream;
  }

  @Override
  public void run() {
    String line;
    try {
      while ((line = inputReader.readLine()) != null) {
        if (!hasWrittenOutputToPrintStream && flagOutputWrittenToStream) {
          printStream.print(ansi.getHighlightedWarningSequence());
          hasWrittenOutputToPrintStream = true;
        }

        // We pass `line + LINE_SEPARATOR` to print() rather than invoke println() because
        // we want the line and the separator to be guaranteed to be printed together.
        // println() is implemented by calling print() then newLine(). Because those calls could be
        // interleaved when stdout and stderr are being consumed simultaneously (and I have seen
        // this happen), then you could end up with confusing output when stdout and stderr are
        // connected to the same terminal (which is often the case).
        printStream.print(line + LINE_SEPARATOR);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      if (hasWrittenOutputToPrintStream) {
        printStream.print(ansi.getHighlightedResetSequence());
      }
    }
  }
}
