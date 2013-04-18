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

import com.facebook.buck.model.AnnotationProcessingData;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

public interface JavaLibraryRule extends BuildRule {

  /**
   * @return The set of entries to pass to {@code javac}'s {@code -classpath} flag in order to
   *     compile the {@code srcs} associated with this rule
   */
  public ImmutableSet<String> getClasspathEntries();

  public ImmutableSortedSet<String> getJavaSrcs();

  public AnnotationProcessingData getAnnotationProcessingData();
}
