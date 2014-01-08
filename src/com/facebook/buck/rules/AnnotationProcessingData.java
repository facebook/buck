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
package com.facebook.buck.rules;

import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.Path;

import javax.annotation.Nullable;

public interface AnnotationProcessingData {
  public static AnnotationProcessingData EMPTY = new AnnotationProcessingData() {
    @Override
    public boolean isEmpty() {
      return true;
    }

    @Override
    public ImmutableSortedSet<Path> getSearchPathElements() {
      return ImmutableSortedSet.of();
    }

    @Override
    public ImmutableSortedSet<String> getNames() {
      return ImmutableSortedSet.of();
    }

    @Override
    public ImmutableSortedSet<String> getParameters() {
      return ImmutableSortedSet.of();
    }

    @Override
    public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) throws IOException {
      return builder;
    }

    @Override
    @Nullable
    public Path getGeneratedSourceFolderName() {
      return null;
    }

    @Override
    public boolean getProcessOnly() {
      return false;
    }
  };

  /**
   * True iff this has no data and will return empty sets and null Strings.
   */
  public boolean isEmpty();

  /**
   * Path to search for annotation processors.
   * Each element is a path relative to the project root.
   */
  public ImmutableSortedSet<Path> getSearchPathElements();

  /**
   * The set of fully-qualified names of annotation processor classes.
   */
  public ImmutableSortedSet<String> getNames();

  /**
   * The set of parameters to pass to annotation processing (via javac -A).
   */
  public ImmutableSortedSet<String> getParameters();

  /**
   * Contributes state to builder.
   */
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) throws IOException;

  /**
   * Controls whether compilation happens along with annotation processing:
   * false => process annotations (if any) and compile.  This is the default.
   * true => process annotations (if any) only and do not compile.
   */
  public boolean getProcessOnly();

  /**
   * The name of the root folder where annotation processing source should be generated.
   */
  @Nullable
  public Path getGeneratedSourceFolderName();
}
