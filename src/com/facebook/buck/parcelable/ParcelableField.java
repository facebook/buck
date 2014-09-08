/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.parcelable;

import javax.annotation.Nullable;

public class ParcelableField {

  private final String type;

  private final String name;

  private final boolean mutable;

  /**
   * If not {@code null}, then must be one of:
   * <ul>
   *   <li>{@code ""} for default visibility
   *   <li>{@code "public"} for public visibility
   *   <li>{@code "private"} for private visibility
   *   <li>{@code "protected"} for protected visibility
   * </ul>
   * If {@code null}, use {@link ParcelableClass#getDefaultFieldVisibility()} from the enclosing
   * class.
   */

  @Nullable private final String visibility;

  @Nullable private final String jsonProperty;

  @Nullable private final String defaultValue;

  public ParcelableField(
      String type,
      String name,
      boolean mutable,
      @Nullable String visibility,
      @Nullable String jsonProperty,
      @Nullable String defaultValue) {
    this.type = type;
    this.name = name;
    this.mutable = mutable;
    this.visibility = visibility;
    this.jsonProperty = jsonProperty;
    this.defaultValue = defaultValue;
  }

  public String getType() {
    return type;
  }

  public String getName() {
    return name;
  }

  public boolean isMutable() {
    return mutable;
  }

  @Nullable public String getVisibility() {
    return visibility;
  }

  @Nullable public String getJsonProperty() {
    return jsonProperty;
  }

  @Nullable public String getDefaultValue() {
    return defaultValue;
  }
}
