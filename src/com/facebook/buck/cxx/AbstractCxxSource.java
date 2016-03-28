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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import org.immutables.value.Value;

import java.util.List;

/**
 * Describes a source file written in the C programming language or a
 * derivative (C++, Objective-C, Objective-C++, etc.) and the various
 * paths it uses from input to output.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractCxxSource {

  public enum Type {

    C("c", "cpp-output", Optional.of("c-header"), "c"),
    CXX(
        "c++",
        "c++-cpp-output",
        Optional.of("c++-header"),
        "cc", "cp", "cxx", "cpp", "CPP", "c++", "C"),
    OBJC("objective-c", "objective-c-cpp-output", Optional.of("objective-c-header"), "m"),
    OBJCXX("objective-c++", "objective-c++-cpp-output", Optional.of("objective-c++-header"), "mm"),
    CUDA("cuda", "cuda-cpp-output", "cu"),

    C_CPP_OUTPUT("cpp-output", "cpp-output", "i"),
    CXX_CPP_OUTPUT("c++-cpp-output", "c++-cpp-output", "ii"),
    OBJC_CPP_OUTPUT("objective-c-cpp-output", "objective-c-cpp-output", "mi"),
    OBJCXX_CPP_OUTPUT("objective-c++-cpp-output", "objective-c++-cpp-output", "mii"),
    CUDA_CPP_OUTPUT("cuda-cpp-output", "cuda-cpp-output", "cui"),

    // Note, this is declared before ASSEMBLER so .s files are recognized as needing preprocessor.
    ASSEMBLER_WITH_CPP("assembler-with-cpp", "assembler", "s", "S"),
    ASSEMBLER("assembler", "assembler", "s"),
    ;

    private final String language;
    private final String preprocessedLanguage;
    private final Optional<String> precompiledHeaderLanguage;
    private final ImmutableSet<String> extensions;

    Type(
        String language,
        String preprocessedLanguage,
        String... extensions) {
      this(language, preprocessedLanguage, Optional.<String>absent(), extensions);
    }

    Type(
        String language,
        String preprocessedLanguage,
        Optional<String> precompiledHeaderLanguage,
        String... extensions) {
      this.language = language;
      this.preprocessedLanguage = preprocessedLanguage;
      this.precompiledHeaderLanguage = precompiledHeaderLanguage;
      this.extensions = ImmutableSet.copyOf(extensions);
    }

    public static Optional<Type> fromExtension(String extension) {
      for (Type type : values()) {
        if (type.extensions.contains(extension)) {
          return Optional.of(type);
        }
      }
      return Optional.absent();
    }

    public String getLanguage() {
      return language;
    }

    public String getPreprocessedLanguage() {
      return preprocessedLanguage;
    }

    /**
     * "Language" type to pass to the compiler in order to generate a precompiled header.
     *
     * Will be {@code absent} for source types which do not support precompiled headers.
     */
    public Optional<String> getPrecompiledHeaderLanguage() {
      return precompiledHeaderLanguage;
    }

    public boolean isPreprocessable() {
      return !preprocessedLanguage.equals(language);
    }

    public ImmutableSet<String> getExtensions() {
      return extensions;
    }

    public boolean isAssembly() {
      return this == ASSEMBLER || this == ASSEMBLER_WITH_CPP;
    }

  }

  @Value.Parameter
  public abstract Type getType();

  @Value.Parameter
  public abstract SourcePath getPath();

  @Value.Parameter
  public abstract List<String> getFlags();

}
