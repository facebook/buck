/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.abi;

import java.io.IOException;
import java.io.Writer;

class JarManifestWriter {

  private static final String NEW_LINE = "\r\n";
  private static final String NEW_LINE_AND_SPACE = "\r\n ";
  private static final int MAX_LINE_LENGTH = 72;
  private static final int NEW_LINE_LENGTH = NEW_LINE.length();

  private final Writer writer;

  public JarManifestWriter(Writer writer) {
    this.writer = writer;
  }

  public void writeEntry(String key, String value) throws IOException {
    StringBuilder builder = new StringBuilder();

    builder.append(key);
    builder.append(": ");
    builder.append(value);
    builder.append(NEW_LINE);

    // Manifest files expect each line to be no longer than 72 characters (including line
    // endings), and continuation lines start with a single space.
    int start = 0;
    int lineLength = builder.length();
    while (lineLength > MAX_LINE_LENGTH) {
      builder.insert(start + MAX_LINE_LENGTH - NEW_LINE_LENGTH, NEW_LINE_AND_SPACE);
      start += MAX_LINE_LENGTH;
      lineLength -= MAX_LINE_LENGTH;
    }

    writer.write(builder.toString());
  }

  public void writeLine() throws IOException {
    writer.write("\r\n");
  }

  public void flush() throws IOException {
    writer.flush();
  }
}
