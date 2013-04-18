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

public final class InputStreamConsumer implements Runnable {

  private final InputStream inputStream;
  private final PrintStream printStream;
  private final boolean shouldRedirectInputStreamToPrintStream;
  private final Ansi ansi;
  private boolean hasWrittenOutputToPrintStream = false;

  public InputStreamConsumer(InputStream inputStream,
      PrintStream printStream,
      boolean shouldRedirectInputStreamToPrintStream,
      Ansi ansi) {
    this.inputStream = Preconditions.checkNotNull(inputStream);
    this.printStream = Preconditions.checkNotNull(printStream);
    this.shouldRedirectInputStreamToPrintStream = shouldRedirectInputStreamToPrintStream;
    this.ansi = Preconditions.checkNotNull(ansi);
  }

  @Override
  public void run() {
    BufferedReader streamReader = new BufferedReader(new InputStreamReader(inputStream));
    String line = null;
    try {
      while ((line = streamReader.readLine()) != null) {
        if (shouldRedirectInputStreamToPrintStream) {
          if (!hasWrittenOutputToPrintStream) {
            hasWrittenOutputToPrintStream = true;
            printStream.print(ansi.getHighlightedWarningSequence());
          }
          printStream.println(line);
        }
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
