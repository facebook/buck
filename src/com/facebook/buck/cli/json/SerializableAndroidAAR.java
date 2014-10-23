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

import com.facebook.buck.android.AndroidPrebuiltAar;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SerializableAndroidAAR {
  @JsonProperty @Nonnull private final String name;
  @JsonProperty @Nullable private final String res;
  @JsonProperty @Nullable private final String assets;
  @JsonProperty @Nonnull private final String jar;

  private SerializableAndroidAAR(String name, String res, String assets, String jar) {
    this.name = name;
    this.res = res;
    this.assets = assets;
    this.jar = jar;
  }

  public static SerializableAndroidAAR newSerializableAndroidAAR(String aarName, AndroidPrebuiltAar preBuiltAAR) {
    String name = aarName;
    String res =  preBuiltAAR.getResPath();
    String assets = preBuiltAAR.getAssetsPath();
    String jar = preBuiltAAR.getBinaryJarPath();
    return new SerializableAndroidAAR(name, res, assets, jar);
  }
}