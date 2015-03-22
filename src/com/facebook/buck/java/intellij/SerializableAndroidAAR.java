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

package com.facebook.buck.java.intellij;

import com.facebook.buck.android.AndroidPrebuiltAar;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SerializableAndroidAAR {
  private final String name;
  private final String res;
  private final String assets;
  private final String jar;

  private SerializableAndroidAAR(String name, String res, String assets, String jar) {
    this.name = name;
    this.res = res;
    this.assets = assets;
    this.jar = jar;
  }

  public static SerializableAndroidAAR newSerializableAndroidAAR(
      String aarName,
      AndroidPrebuiltAar preBuiltAAR) {
    String res = preBuiltAAR.getResPath();
    String assets = preBuiltAAR.getAssetsPath();
    String jar = preBuiltAAR.getBinaryJarPath();
    return new SerializableAndroidAAR(aarName, res, assets, jar);
  }

  @JsonProperty
  @Nonnull
  public String getName() {
    return name;
  }

  @JsonProperty
  @Nullable
  public String getRes() {
    return res;
  }

  @JsonProperty
  @Nullable
  public String getAssets() {
    return assets;
  }

  @JsonProperty
  @Nonnull
  public String getJar() {
    return jar;
  }
}
