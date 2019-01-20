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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.jvm.java.toolchain.JavaToolchain;
import com.google.common.collect.ImmutableList;

public class JavaCompilationConstants {

  public static final JavaBuckConfig DEFAULT_JAVA_CONFIG =
      JavaBuckConfig.of(FakeBuckConfig.builder().build());

  public static final ImmutableList<String> DEFAULT_JAVA_COMMAND_PREFIX = ImmutableList.of("java");

  public static final JavaOptions DEFAULT_JAVA_OPTIONS = JavaBuckConfig.DEFAULT_JAVA_OPTIONS;

  public static final JavacOptions DEFAULT_JAVA8_JAVAC_OPTIONS =
      JavacOptions.builderForUseInJavaBuckConfig().setSourceLevel("8").setTargetLevel("8").build();

  public static final JavacOptions DEFAULT_JAVAC_OPTIONS =
      JavacOptions.builderForUseInJavaBuckConfig().setSourceLevel("7").setTargetLevel("7").build();

  public static final JavacOptions ANDROID_JAVAC_OPTIONS =
      JavacOptions.builderForUseInJavaBuckConfig().setSourceLevel("7").setTargetLevel("6").build();

  public static final Javac DEFAULT_JAVAC = new JdkProvidedInMemoryJavac();
  public static final JavaToolchain DEFAULT_JAVA_TOOLCHAIN =
      JavaToolchain.builder()
          .setJavacProvider(DEFAULT_JAVA_CONFIG.getJavacSpec().getJavacProvider())
          .build();

  private JavaCompilationConstants() {
    // Thou shalt not instantiate utility classes.
  }
}
