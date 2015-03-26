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

import com.facebook.buck.android.AndroidPrebuiltAar;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.file.Path;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SerializableAndroidAar {
  private final String name;

  @Nullable
  private final Path res;

  @Nullable
  private final Path assets;
  private final Path jar;

  private SerializableAndroidAar(String name, @Nullable Path res, @Nullable Path assets, Path jar) {
    this.name = name;
    this.res = res;
    this.assets = assets;
    this.jar = jar;
  }

  public static SerializableAndroidAar createSerializableAndroidAar(
      String aarName,
      AndroidPrebuiltAar preBuiltAar) {
    Path res = preBuiltAar.getRes();
    Path assets = preBuiltAar.getAssets();
    Path jar = preBuiltAar.getBinaryJar();
    return new SerializableAndroidAar(aarName, res, assets, jar);
  }

  @JsonProperty
  @Nonnull
  public String getName() {
    return name;
  }

  @JsonProperty
  @Nullable
  public Path getRes() {
    return res;
  }

  @JsonProperty
  @Nullable
  public Path getAssets() {
    return assets;
  }

  @JsonProperty
  @Nonnull
  public Path getJar() {
    return jar;
  }
}
