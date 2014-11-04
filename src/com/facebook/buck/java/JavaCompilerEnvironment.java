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

package com.facebook.buck.java;

import com.google.common.base.Optional;

import java.nio.file.Path;

public class JavaCompilerEnvironment {

  // Default combined source and target level.
  public static final String TARGETED_JAVA_VERSION = "7";
  public static final JavaCompilerEnvironment DEFAULT =
      new JavaCompilerEnvironment(
          Optional.<Path> absent(),
          Optional.<JavacVersion> absent(),
          TARGETED_JAVA_VERSION,
          TARGETED_JAVA_VERSION);

  private final Optional<Path> javacPath;
  private final Optional<JavacVersion> javacVersion;
  private final String sourceLevel;
  private final String targetLevel;

  public JavaCompilerEnvironment(
      Optional<Path> javacPath,
      Optional<JavacVersion> javacVersion,
      String sourceLevel,
      String targetLevel) {
    this.javacPath = javacPath;
    this.javacVersion = javacVersion;
    this.sourceLevel = sourceLevel;
    this.targetLevel = targetLevel;
  }

  public Optional<Path> getJavacPath() {
    return this.javacPath;
  }

  public Optional<JavacVersion> getJavacVersion() {
    return this.javacVersion;
  }

  public String getSourceLevel() {
    return sourceLevel;
  }

  public String getTargetLevel() {
    return targetLevel;
  }
}
