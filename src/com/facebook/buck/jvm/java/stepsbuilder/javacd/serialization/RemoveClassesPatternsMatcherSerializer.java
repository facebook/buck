/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization;

import com.facebook.buck.javacd.model.JarParameters;
import com.facebook.buck.jvm.java.RemoveClassesPatternsMatcher;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.regex.Pattern;

/** {@link RemoveClassesPatternsMatcher} to protobuf serializer */
class RemoveClassesPatternsMatcherSerializer {

  private RemoveClassesPatternsMatcherSerializer() {};

  /**
   * Serializes {@link RemoveClassesPatternsMatcher} into javacd model's {@link
   * JarParameters.RemoveClassesPatternsMatcher}.
   */
  public static JarParameters.RemoveClassesPatternsMatcher serialize(
      RemoveClassesPatternsMatcher removeClassesPatternsMatcher) {
    ImmutableList<Pattern> patterns = removeClassesPatternsMatcher.getPatterns();
    com.facebook.buck.javacd.model.JarParameters.RemoveClassesPatternsMatcher.Builder builder =
        com.facebook.buck.javacd.model.JarParameters.RemoveClassesPatternsMatcher.newBuilder();
    for (Pattern pattern : patterns) {
      builder.addPatterns(pattern.pattern());
    }
    return builder.build();
  }

  /**
   * Deserializes javacd model's {@link JarParameters.RemoveClassesPatternsMatcher} into {@link
   * RemoveClassesPatternsMatcher}.
   */
  public static RemoveClassesPatternsMatcher deserialize(
      JarParameters.RemoveClassesPatternsMatcher removeClassesPatternsMatcher) {
    ImmutableSet<Pattern> patterns =
        removeClassesPatternsMatcher.getPatternsList().stream()
            .map(Pattern::compile)
            .collect(ImmutableSet.toImmutableSet());
    return new RemoveClassesPatternsMatcher(patterns);
  }
}
