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

package com.facebook.buck.swift;

import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.Files;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Creates a OutputFileMap.json file that describes the output of the Swift compiler when it's run
 * in incremental mode.
 */
public class OutputFileMap {
  private SourcePathResolverAdapter resolver;
  private ImmutableSortedSet<SourcePath> sourceFiles;
  private Path outputPath;

  OutputFileMap(
      SourcePathResolverAdapter resolver,
      ImmutableSortedSet<SourcePath> sourceFiles,
      Path outputPath) {
    this.resolver = resolver;
    this.sourceFiles = sourceFiles;
    this.outputPath = outputPath;
  }

  public void render(JsonGenerator json) throws IOException {
    json.writeStartObject();
    for (SourcePath fileSourcePath : sourceFiles) {
      writeSourceFileSection(json, resolver.getIdeallyRelativePath(fileSourcePath), outputPath);
    }
    writeModuleSection(json, outputPath);
    json.writeEndObject();
  }

  private static void writeSourceFileSection(
      JsonGenerator json, Path sourceFilePath, Path outputPath) throws IOException {
    json.writeFieldName(sourceFilePath.toString());

    json.writeStartObject();

    String fileName = Files.getNameWithoutExtension(sourceFilePath.getFileName().toString());
    json.writeStringField("object", outputPath.resolve(fileName + ".o").toString());
    // Bitcode has ".o" extension as well as object file.
    // It is safe to do so, because if "-emit-bc" is passed for WATCHOS and APPLETVOS platforms,
    // it will override "-emit-object", and will emit bitcode instead of binary symbols.
    // When CxxLink job is invoked it expects bitcode file to have ".o" extension.
    json.writeStringField("llvm-bc", outputPath.resolve(fileName + ".o").toString());
    json.writeStringField(
        "swift-dependencies", outputPath.resolve(fileName + ".swiftdeps").toString());
    json.writeStringField(
        "swiftmodule", outputPath.resolve(fileName + "~partial.swiftmodule").toString());
    json.writeStringField(
        "swiftdoc", outputPath.resolve(fileName + "~partial.swiftdoc").toString());

    json.writeEndObject();
  }

  private static void writeModuleSection(JsonGenerator json, Path outputPath) throws IOException {
    json.writeFieldName("");
    json.writeStartObject();
    json.writeStringField(
        "swift-dependencies", outputPath.resolve("module-build-record.swiftdeps").toString());
    json.writeEndObject();
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof OutputFileMap)) {
      return false;
    }

    OutputFileMap other = (OutputFileMap) obj;
    return Objects.equal(this.outputPath, other.outputPath)
        && Objects.equal(this.sourceFiles, other.sourceFiles);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(outputPath, sourceFiles);
  }
}
