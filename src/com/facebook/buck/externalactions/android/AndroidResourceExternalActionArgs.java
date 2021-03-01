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
import javax.annotation.Nullable;

/** Args for {@link AndroidResourceExternalAction}. */
@BuckStyleValue
@JsonDeserialize(as = ImmutableAndroidResourceExternalActionArgs.class)
public abstract class AndroidResourceExternalActionArgs implements JsonArgs {

  @JsonProperty("resPath")
  @Nullable
  abstract String getResPath();

  @JsonProperty("pathToTextSymbolsDir")
  abstract String getPathToTextSymbolsDir();

  @JsonProperty("pathToTextSymbolsFile")
  abstract String getPathToTextSymbolsFile();

  @JsonProperty("pathToRDotJavaPackageFile")
  abstract String getPathToRDotJavaPackageFile();

  @JsonProperty("manifestFilePath")
  @Nullable
  abstract String getPathToManifestFile();

  @JsonProperty("pathsToSymbolsOfDeps")
  abstract Collection<String> getPathsToSymbolsOfDeps();

  @JsonProperty("isVerifyingXmlAttrsEnabled")
  abstract boolean isVerifyingXmlAttrsEnabled();

  @JsonProperty("rDotJavaPackageArgument")
  @Nullable
  abstract String getRDotJavaPackageArgument();

  /** Returns an instance of {@link AndroidResourceExternalActionArgs}. */
  public static AndroidResourceExternalActionArgs of(
      String resPath,
      String pathToTextSymbolsDir,
      String pathToTextSymbolsFile,
      String pathToRDotJavaPackageFile,
      String manifestFilePath,
      Collection<String> pathsToSymbolsOfDeps,
      boolean isVerifyingXmlAttrsEnabled,
      String rDotJavaPackageArgument) {
    return ImmutableAndroidResourceExternalActionArgs.ofImpl(
        resPath,
        pathToTextSymbolsDir,
        pathToTextSymbolsFile,
        pathToRDotJavaPackageFile,
        manifestFilePath,
        pathsToSymbolsOfDeps,
        isVerifyingXmlAttrsEnabled,
        rDotJavaPackageArgument);
  }
}
