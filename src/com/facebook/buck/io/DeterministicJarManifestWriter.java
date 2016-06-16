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
package com.facebook.buck.io;

import com.google.common.base.Preconditions;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A manifest implementation whose formatted output is deterministic given the same input (entries
 * and attributes are sorted). The JDK implementation in {@code java.util.Manifest} does not have
 * this property.
 */
public class DeterministicJarManifestWriter {
  private static final String NEW_LINE = "\r\n"; // For some reason JAR manifests use CRLF
  private static final String NEW_LINE_AND_SPACE = "\r\n ";
  private static final int MAX_LINE_LENGTH = 72;
  private static final int NEW_LINE_LENGTH = NEW_LINE.length();

  private final Map<String, Map<String, String>> entries = new TreeMap<>();
  private final OutputStream out;

  public DeterministicJarManifestWriter(OutputStream out) {
    this.out = out;
  }

  public DeterministicJarManifestWriter setEntryAttribute(
      String entryName,
      String key,
      String value) {
    Map<String, String> attributes = entries.get(entryName);
    if (attributes == null) {
      attributes = new TreeMap<>();
      entries.put(entryName, attributes);
    }

    attributes.put(key, value);
    return this;
  }

  public boolean hasEntries() {
    return !entries.isEmpty();
  }

  public void write() throws IOException {
    writeLine();  // Blank line separates global attributes from entries; we have no globals

    for (Map.Entry<String, Map<String, String>> entryNameAndAttributes : entries.entrySet()) {
      String entryName = entryNameAndAttributes.getKey();
      Set<Map.Entry<String, String>> attributes = entryNameAndAttributes.getValue().entrySet();
      Preconditions.checkState(!attributes.isEmpty());

      writeKeyValue("Name", entryName);
      for (Map.Entry<String, String> attribute : attributes) {
        writeKeyValue(attribute.getKey(), attribute.getValue());
      }
      writeLine();
    }
  }

  private void writeKeyValue(String key, String value) throws IOException {
    StringBuilder builder = new StringBuilder();

    builder.append(key);
    builder.append(": ");
    builder.append(value);
    builder.append(NEW_LINE);

    // Manifest files expect each line to be no longer than 72 characters (including line
    // endings), and continuation lines start with a single space.
    int start = 0;
    while (builder.length() - start > MAX_LINE_LENGTH) {
      builder.insert(start + MAX_LINE_LENGTH - NEW_LINE_LENGTH, NEW_LINE_AND_SPACE);
      start += MAX_LINE_LENGTH;
    }

    out.write(builder.toString().getBytes(StandardCharsets.UTF_8));
  }

  private void writeLine() throws IOException {
    out.write(NEW_LINE.getBytes(StandardCharsets.UTF_8));
  }
}
