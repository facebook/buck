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

package com.facebook.buck.json;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSortedSet;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.file.Path;
import java.util.Optional;

@VisibleForTesting
class JsonConcatenator {

  private final ProjectFilesystem filesystem;
  private ImmutableSortedSet<Path> inputs;

  private BufferedWriter destinationBufferedWriter;
  private boolean stillEmpty;

  @VisibleForTesting static final String JSON_ENCODING = "UTF-8";

  public JsonConcatenator(
      ImmutableSortedSet<Path> inputs, Path destination, ProjectFilesystem filesystem)
      throws IOException {
    this.inputs = inputs;
    this.stillEmpty = true;
    try {
      this.destinationBufferedWriter =
          new BufferedWriter(
              new OutputStreamWriter(filesystem.newFileOutputStream(destination), JSON_ENCODING));
    } catch (IOException e) {
      closeAll();
      throw e;
    }
    this.filesystem = filesystem;
  }

  public void closeAll() throws IOException {
    if (destinationBufferedWriter != null) {
      destinationBufferedWriter.close();
    }
  }

  public void concatenate() throws IOException {
    try {
      initializeArray();
      for (Path input : inputs) {
        appendArray(loadJsonArrayFromFile(input));
      }
    } finally {
      finalizeArray();
    }
  }

  @VisibleForTesting
  void initializeArray() throws IOException {
    destinationBufferedWriter.write("[");
  }

  @VisibleForTesting
  void appendArray(String array) throws IOException {
    if (isArrayEmpty(array)) {
      return;
    }
    if (!stillEmpty) {
      destinationBufferedWriter.write(",");
    }
    destinationBufferedWriter.write(stripArrayTokens(array));
    stillEmpty = false;
  }

  @VisibleForTesting
  void finalizeArray() throws IOException {
    try {
      destinationBufferedWriter.write("]");
    } finally {
      closeAll();
    }
  }

  private String loadJsonArrayFromFile(Path input) throws IOException {
    Optional<String> result = filesystem.readFileIfItExists(input);
    if (result.isPresent()) {
      return result.get();
    } else {
      throw new IOException("Error loading " + input.toString());
    }
  }

  @VisibleForTesting
  boolean isArrayEmpty(String array) {
    return array.matches("(\\s)*\\[(\\s)*\\](\\s)*");
  }

  @VisibleForTesting
  String stripArrayTokens(String array) {
    return array.replaceAll("^(\\s)*\\[", "").replaceAll("\\](\\s)*$", "");
  }
}
