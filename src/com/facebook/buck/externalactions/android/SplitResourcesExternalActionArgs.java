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

package com.facebook.buck.externalactions.android;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.externalactions.model.JsonArgs;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.Collection;

/** Args for {@link SplitResourcesExternalAction}. */
@BuckStyleValue
@JsonDeserialize(as = ImmutableSplitResourcesExternalActionArgs.class)
public abstract class SplitResourcesExternalActionArgs implements JsonArgs {
  @JsonProperty("pathToAaptResources")
  abstract String getPathToAaptResources();

  @JsonProperty("pathToOriginalRDotTxt")
  abstract String getPathToOriginalRDotTxt();

  @JsonProperty("primaryResourceOutputPath")
  abstract String getPrimaryResourceOutputPath();

  @JsonProperty("unalignedExoPath")
  abstract String getUnalignedExoPath();

  @JsonProperty("pathTorDotTxtOutputPath")
  abstract String getPathToRDotTxtOutput();

  @JsonProperty("workingDirectory")
  abstract String getWorkingDirectory();

  @JsonProperty("cellRootPath")
  abstract String getCellRootPath();

  @JsonProperty("inputFile")
  abstract String getInputFile();

  @JsonProperty("outputFile")
  abstract String getOutputFile();

  @JsonProperty("withDownwardApi")
  abstract boolean withDownwardApi();

  @JsonProperty("zipAlignCommandPrefix")
  abstract Collection<String> getZipAlignCommandPrefix();

  /** Returns an instance of {@link SplitResourcesExternalActionArgs}. */
  public static SplitResourcesExternalActionArgs of(
      String pathToAaptResources,
      String pathToOriginalRDotTxt,
      String primaryResourceOutputPath,
      String unalignedExoPath,
      String pathToRDotTxtOutput,
      String workingDirectory,
      String cellRootPath,
      String inputFile,
      String outputFile,
      boolean withDownwardApi,
      Collection<String> zipAlignCommandPrefix) {
    return ImmutableSplitResourcesExternalActionArgs.ofImpl(
        pathToAaptResources,
        pathToOriginalRDotTxt,
        primaryResourceOutputPath,
        unalignedExoPath,
        pathToRDotTxtOutput,
        workingDirectory,
        cellRootPath,
        inputFile,
        outputFile,
        withDownwardApi,
        zipAlignCommandPrefix);
  }
}
