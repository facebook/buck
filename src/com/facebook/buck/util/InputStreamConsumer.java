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

  private final BufferedReader inputReader;
  private final PrintStream printStream;
  private final Ansi ansi;
  private boolean hasWrittenOutputToPrintStream = false;

  public InputStreamConsumer(InputStream inputStream,
      PrintStream printStream,
      Ansi ansi) {
    this(new InputStreamReader(inputStream),
        printStream,
        ansi);
  }

  public InputStreamConsumer(Reader reader,
      PrintStream printStream,
      Ansi ansi) {
    this.inputReader = new BufferedReader(reader);
    this.printStream = Preconditions.checkNotNull(printStream);
    this.ansi = Preconditions.checkNotNull(ansi);
  }

  @Override
  public void run() {
    String line;
    try {
      while ((line = inputReader.readLine()) != null) {
        if (!hasWrittenOutputToPrintStream) {
          printStream.print(ansi.getHighlightedWarningSequence());
          hasWrittenOutputToPrintStream = true;
        }
        printStream.println(line);
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
