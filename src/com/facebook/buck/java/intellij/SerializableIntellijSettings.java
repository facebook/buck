/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.java.intellij;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import javax.annotation.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SerializableIntellijSettings {
  @JsonProperty @Nullable private final String languageLevel;
  @JsonProperty @Nullable private final String jdkName;
  @JsonProperty @Nullable private final String jdkType;
  @JsonProperty @Nullable private final String outputUrl;

  private SerializableIntellijSettings(
      @Nullable String languageLevel,
      @Nullable String jdkName,
      @Nullable String jdkType,
      @Nullable String outputUrl) {
    this.languageLevel = languageLevel;
    this.jdkName = jdkName;
    this.jdkType = jdkType;
    this.outputUrl = outputUrl;
  }

  public static SerializableIntellijSettings createSerializableIntellijSettings(
      IntellijConfig intellijConfig) {
    return new SerializableIntellijSettings(
        intellijConfig.getLanguageLevel().orNull(),
        intellijConfig.getJdkName().orNull(),
        intellijConfig.getJdkType().orNull(),
        intellijConfig.getOutputUrl().orNull());
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(SerializableIntellijSettings.class)
        .add("languageLevel", languageLevel)
        .add("jdkName", jdkName)
        .add("jdkType", jdkType)
        .add("outputUrl", outputUrl)
        .toString();
  }
}
