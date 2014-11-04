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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import javax.annotation.Nullable;

public class ParcelableClass {

  private final String packageName;

  private final Iterable<String> imports;

  private final String className;

  private final String creatorClassName;

  /**
   * Visibility to use for fields that do not specify their own visibility level.
   * <p>
   * Must be one of:
   * <ul>
   *   <li>{@code ""} for default visibility
   *   <li>{@code "public"} for public visibility
   *   <li>{@code "private"} for private visibility
   *   <li>{@code "protected"} for protected visibility
   * </ul>
   */
  private final String defaultFieldVisibility;

  private final Iterable<ParcelableField> fields;

  @Nullable private final String superClassName;

  @Nullable private final String rawSuperParams;

  public ParcelableClass(String packageName,
      Iterable<String> imports,
      String className,
      String creatorClassName,
      String defaultFieldVisibility,
      Iterable<ParcelableField> fields,
      @Nullable String superClassName,
      @Nullable String rawSuperParams) {
    Preconditions.checkArgument(!Iterables.isEmpty(fields),
        "There must be fields, or else there is nothing to parcel.");
    boolean hasJsonAnnotations = false;
    for (ParcelableField field : fields) {
      if (field.getJsonProperty() != null) {
        hasJsonAnnotations = true;
        break;
      }
    }

    this.packageName = packageName;
    ImmutableSortedSet.Builder<String> importsBuilder = ImmutableSortedSet.<String>naturalOrder()
        .addAll(imports)
        .add("import android.os.Parcel;")
        .add("import android.os.Parcelable;")
        // Lists is used to facilitate creating an array to pass to Parcel.readTypedList().
        .add("import com.google.common.collect.Lists;");
    if (hasJsonAnnotations) {
      importsBuilder.add("import com.fasterxml.jackson.annotation.JsonProperty;");
    }
    this.imports = importsBuilder.build();
    this.className = className;
    this.creatorClassName = creatorClassName;
    this.defaultFieldVisibility = defaultFieldVisibility;

    this.fields = fields;

    this.superClassName = superClassName;
    this.rawSuperParams = rawSuperParams;
  }

  public String getPackageName() {
    return packageName;
  }

  public Iterable<String> getImports() {
    return imports;
  }

  public String getClassName() {
    return className;
  }

  public String getCreatorClassName() {
    return creatorClassName;
  }

  public String getDefaultFieldVisibility() {
    return defaultFieldVisibility;
  }

  public Iterable<ParcelableField> getFields() {
    return fields;
  }

  public boolean hasSuperClass() {
    return superClassName != null;
  }

  @Nullable public String getSuperClassName() {
    return superClassName;
  }

  @Nullable public String getRawSuperParams() {
    return rawSuperParams;
  }

}
