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
import java.util.Optional;

/** Args for {@link AndroidBuildConfigExternalAction}. */
@BuckStyleValue
@JsonDeserialize(as = ImmutableAndroidBuildConfigExternalActionArgs.class)
public abstract class AndroidBuildConfigExternalActionArgs implements JsonArgs {

  @JsonProperty("source")
  abstract String getSource();

  @JsonProperty("javaPackage")
  abstract String getJavaPackage();

  @JsonProperty("useConstantExpressions")
  abstract boolean useConstantExpressions();

  @JsonProperty("valuesFile")
  abstract Optional<String> getValuesFile();

  @JsonProperty("defaultValues")
  abstract Collection<String> getDefaultValues();

  @JsonProperty("outBuildConfigPath")
  abstract String getOutBuildConfigPath();

  /** Returns an instance of {@link AndroidBuildConfigExternalActionArgs}. */
  public static AndroidBuildConfigExternalActionArgs of(
      String source,
      String javaPackage,
      boolean useConstantExpressions,
      Optional<String> valuesFile,
      Collection<String> defaultValues,
      String outBuildConfigPath) {
    return ImmutableAndroidBuildConfigExternalActionArgs.ofImpl(
        source, javaPackage, useConstantExpressions, valuesFile, defaultValues, outBuildConfigPath);
  }
}
