/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

public interface OutOfProcessJavacConnectionInterface {
  /**
   * This is interface that will be used to perform out of process compilation.
   *
   * @param compilerClassNameForJarBackedJavacMode String value of compilerClassName for Jar-backed
   *     mode, or null
   * @param serializedJavacExecutionContext JavacExecutionContext converted to String
   * @param invokingRuleBuildTargetAsString BuildTarget converted to String
   * @param options Immutable list of string options
   * @param sortedSetOfJavaSourceFilePathsAsStringsAsList ImmutableSortedSet<Path> represented as
   *     List of String objects.
   * @param pathToSrcsListAsString Path represented as String.
   * @param workingDirectory Path represented as String, or null.
   * @return Resulting code, 0 if build finished without issues, non-zero otherwise.
   */
  int buildWithClasspath(
      @Nullable String compilerClassNameForJarBackedJavacMode,
      Map<String, Object> serializedJavacExecutionContext,
      String invokingRuleBuildTargetAsString,
      List<String> options,
      List<String> sortedSetOfJavaSourceFilePathsAsStringsAsList,
      String pathToSrcsListAsString,
      @Nullable String workingDirectory,
      List<JavacPluginJsr199Fields> pluginFields,
      String javaCompilationModeAsString);
}
