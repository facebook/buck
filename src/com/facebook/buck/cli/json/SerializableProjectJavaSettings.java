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

package com.facebook.buck.cli.json;

import com.facebook.buck.cli.JavaProjectBuckConfig;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Objects;

import javax.annotation.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SerializableProjectJavaSettings {
  @JsonProperty @Nullable private final String languageLevel;
  @JsonProperty @Nullable private final String jdkName;
  @JsonProperty @Nullable private final String jdkType;

  private SerializableProjectJavaSettings(String languageLevel, String jdkName, String jdkType) {
    this.languageLevel = languageLevel;
    this.jdkName = jdkName;
    this.jdkType = jdkType;
  }

  public static SerializableProjectJavaSettings newSerializableJavaProjectSettings(
      JavaProjectBuckConfig intellijConfig) {
    return new SerializableProjectJavaSettings(
        intellijConfig.resolveLanguageLevel(),
        intellijConfig.resolveJDKName(),
        intellijConfig.resolveJDKType());
  }

  @Override
  public String toString() {
    return Objects.toStringHelper(SerializableProjectJavaSettings.class)
        .add("languageLevel", languageLevel)
        .add("jdkName", jdkName)
        .add("jdkType", jdkType)
        .toString();
  }
}