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

/** Args for {@link AndroidManifestExternalAction}. */
@BuckStyleValue
@JsonDeserialize(as = ImmutableAndroidManifestExternalActionArgs.class)
public abstract class AndroidManifestExternalActionArgs implements JsonArgs {

  @JsonProperty("skeletonManifestPath")
  abstract String getSkeletonManifestPath();

  @JsonProperty("libraryManifestPaths")
  abstract Collection<String> getLibraryManifestPaths();

  @JsonProperty("getOutputManifestPath")
  abstract String getOutputManifestPath();

  @JsonProperty("mergeReportPath")
  abstract String getMergeReportPath();

  @JsonProperty("moduleName")
  abstract String getModuleName();

  /** Returns an instance of {@link AndroidManifestExternalActionArgs}. */
  public static AndroidManifestExternalActionArgs of(
      String skeletonManifestPath,
      Collection<String> libraryManifestPaths,
      String outputManifestPath,
      String mergeReportPath,
      String moduleName) {
    return ImmutableAndroidManifestExternalActionArgs.ofImpl(
        skeletonManifestPath,
        libraryManifestPaths,
        outputManifestPath,
        mergeReportPath,
        moduleName);
  }
}
