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

import com.fasterxml.jackson.annotation.JsonProperty;

import javax.annotation.Nullable;

public class SerializableDependentModule {
  @JsonProperty
  protected final String type;
  @JsonProperty
  @Nullable
  protected String name;
  @JsonProperty
  @Nullable
  protected String moduleName;
  @JsonProperty
  @Nullable
  protected Boolean forTests;
  /**
   * Set if {@link #type} is {@code jdk}.
   */
  @JsonProperty
  @Nullable
  protected String jdkName;
  /**
   * Set if {@link #type} is {@code jdk}.
   */
  @JsonProperty
  @Nullable
  protected String jdkType;
  @JsonProperty
  @Nullable
  String scope;

  public SerializableDependentModule(String type) {
    this.type = type;
  }
}
