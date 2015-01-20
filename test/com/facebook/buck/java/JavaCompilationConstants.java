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
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;

public class JavaCompilationConstants {

  public static final JavaCompilerEnvironment DEFAULT_JAVAC_ENV = new JavaCompilerEnvironment(
      Optional.<Path>absent(),
      Optional.<JavacVersion>absent());

  public static final Javac DEFAULT_JAVAC = new Jsr199Javac();

  public static final JavacOptions DEFAULT_JAVAC_OPTIONS =
      JavacOptions.builderForUseInJavaBuckConfig()
          .setJavaCompilerEnvironment(DEFAULT_JAVAC_ENV)
          .setSourceLevel("7")
          .setTargetLevel("7")
          .setBootclasspathMap(ImmutableMap.<String, String>of())
          .build();

  public static final JavacOptions ANDROID_JAVAC_OPTIONS =
      JavacOptions.builderForUseInJavaBuckConfig()
          .setJavaCompilerEnvironment(DEFAULT_JAVAC_ENV)
          .setSourceLevel("7")
          .setTargetLevel("6")
          .setBootclasspathMap(ImmutableMap.<String, String>of())
          .build();

  private JavaCompilationConstants() {
    // Thou shalt not instantiate utility classes.
  }
}
