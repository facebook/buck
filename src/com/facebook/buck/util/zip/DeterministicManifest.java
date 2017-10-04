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
package com.facebook.buck.util.zip;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

/**
 * A manifest implementation whose formatted output is deterministic given the same input (entries
 * and attributes are sorted). The JDK implementation in {@code java.util.Manifest} does not have
 * this property.
 */
public class DeterministicManifest extends Manifest {
  private static final String NEW_LINE = "\r\n"; // For some reason JAR manifests use CRLF
  private static final String NEW_LINE_AND_SPACE = "\r\n ";
  private static final int MAX_LINE_LENGTH = 72;
  private static final int NEW_LINE_LENGTH = NEW_LINE.length();

  public DeterministicManifest() {
    super();
  }

  public DeterministicManifest(InputStream is) throws IOException {
    super(is);
  }

  public DeterministicManifest(Manifest man) {
    super(man);
  }

  public void setEntryAttribute(String entryName, String key, String value) {
    Attributes attributes = getAttributes(entryName);
    if (attributes == null) {
      attributes = new Attributes();
      getEntries().put(entryName, attributes);
    }

    attributes.putValue(key, value);
  }

  public void setManifestAttribute(String key, String value) {
    getMainAttributes().putValue(key, value);
  }

  public boolean hasEntries() {
    return !getEntries().isEmpty();
  }

  @Override
  public void write(OutputStream out) throws IOException {
    writeAttributes(getMainAttributes(), out);

    List<String> sortedEntryNames =
        getEntries().keySet().stream().sorted().collect(Collectors.toList());

    for (String entryName : sortedEntryNames) {
      writeEntry(entryName, out);
    }
  }

  private void writeEntry(String entryName, OutputStream out) throws IOException {
    writeKeyValue("Name", entryName, out);

    writeAttributes(getAttributes(entryName), out);
  }

  private void writeAttributes(Attributes attributes, OutputStream out) throws IOException {
    List<Attributes.Name> sortedNames =
        attributes
            .keySet()
            .stream()
            .map(a -> (Attributes.Name) a)
            .sorted(Comparator.comparing(Attributes.Name::toString))
            .collect(Collectors.toList());

    for (Attributes.Name name : sortedNames) {
      writeKeyValue(name.toString(), attributes.getValue(name), out);
    }

    writeLine(out);
  }

  private void writeKeyValue(String key, String value, OutputStream out) throws IOException {
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

  private void writeLine(OutputStream out) throws IOException {
    out.write(NEW_LINE.getBytes(StandardCharsets.UTF_8));
  }
}
