/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;

import java.io.IOException;
import java.nio.file.Path;

public class RecordFileSha1Step implements Step {

  private final ProjectFilesystem filesystem;
  private final Path inputFile;
  private final String metadataKey;
  private final BuildableContext buildableContext;

  public RecordFileSha1Step(
      ProjectFilesystem filesystem,
      Path inputFile,
      String metadataKey,
      BuildableContext buildableContext) {
    this.filesystem = filesystem;
    this.inputFile = inputFile;
    this.metadataKey = metadataKey;
    this.buildableContext = buildableContext;
  }

  @Override
  public int execute(ExecutionContext context) {
    try {
      buildableContext.addMetadata(metadataKey, filesystem.computeSha1(inputFile));
    } catch (IOException e) {
      context.logError(e, "Error hashing %s", inputFile.toString());
      return 1;
    }
    return 0;
  }

  @Override
  public String getShortName() {
    return "record_file_sha1";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("%s --input %s --metadata-key %s",
        getShortName(),
        inputFile.toString(),
        metadataKey);
  }
}
