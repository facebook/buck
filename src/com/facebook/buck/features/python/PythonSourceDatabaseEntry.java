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

package com.facebook.buck.features.python;

import static java.nio.charset.Charset.defaultCharset;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@BuckStyleValue
@JsonSerialize(as = ImmutablePythonSourceDatabaseEntry.class)
@JsonDeserialize(as = ImmutablePythonSourceDatabaseEntry.class)
public abstract class PythonSourceDatabaseEntry {

  public abstract ImmutableMap<String, String> getSources();

  public abstract ImmutableMap<String, String> getDependencies();

  private static Path maybeRelativize(Optional<Path> workingDir, Path path) {
    if (!workingDir.isPresent()) {
      return path;
    }
    Path relPath = workingDir.get().relativize(path);
    if (relPath.isAbsolute()) {
      throw new HumanReadableException("Cannot relativize %s against %s", path, workingDir.get());
    }
    return relPath;
  }

  public static void serialize(
      PythonComponents.Resolved sources,
      PythonResolvedComponentsGroup dependencies,
      Optional<Path> workingDir,
      OutputStream stream)
      throws IOException {

    JsonFactory factory = new JsonFactory();
    try (JsonGenerator generator = factory.createGenerator(stream, JsonEncoding.UTF8)) {
      generator.writeStartObject();

      // Serialize the sources.
      generator.writeFieldName("sources");
      generator.writeStartObject();
      sources.forEachPythonComponent(
          (dst, src) ->
              generator.writeStringField(
                  dst.toString(), maybeRelativize(workingDir, src).toString()));
      generator.writeEndObject();

      // Serialize all depencencies.
      generator.writeFieldName("dependencies");
      generator.writeStartObject();
      dependencies.forEachModule(
          Optional.empty(),
          (dst, src) ->
              generator.writeStringField(
                  dst.toString(), maybeRelativize(workingDir, src).toString()));
      generator.writeEndObject();

      generator.writeEndObject();
    }
  }

  public static PythonSourceDatabaseEntry parse(Path compilationDatabase) throws IOException {
    try (Reader fileReader = Files.newBufferedReader(compilationDatabase, defaultCharset())) {
      return ObjectMappers.READER
          .forType(new TypeReference<PythonSourceDatabaseEntry>() {})
          .readValue(fileReader);
    }
  }
}
