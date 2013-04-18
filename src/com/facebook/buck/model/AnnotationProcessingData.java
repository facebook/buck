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
package com.facebook.buck.model;

import com.google.common.collect.ImmutableSet;

import javax.annotation.Nullable;

public interface AnnotationProcessingData {
  public static AnnotationProcessingData EMPTY = new AnnotationProcessingData() {
    @Override
    public boolean isEmpty() {
      return true;
    }

    @Override
    public ImmutableSet<String> getSearchPathElements() {
      return ImmutableSet.of();
    }

    @Override
    public ImmutableSet<String> getNames() {
      return ImmutableSet.of();
    }

    @Override
    public ImmutableSet<String> getParameters() {
      return ImmutableSet.of();
    }

    @Override
    @Nullable
    public String getGeneratedSourceFolderName() {
      return null;
    }
  };

  /**
   * True iff this has no data and will return empty sets and null Strings.
   */
  public boolean isEmpty();

  /**
   * Path to search for annotation processors.
   */
  public ImmutableSet<String> getSearchPathElements();

  /**
   * The set of fully-qualified names of annotation processor classes.
   */
  public ImmutableSet<String> getNames();

  /**
   * The set of parameters to pass to annotation processing (via javac -A).
   */
  public ImmutableSet<String> getParameters();

  /**
   * The name of the root folder where annotation processing source should be generated.
   */
  @Nullable
  public String getGeneratedSourceFolderName();
}
