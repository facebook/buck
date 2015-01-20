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

/**
 * Describes a source file written in the C programming language or a
 * derivative (C++, Objective-C, Objective-C++, etc.) and the various
 * paths it uses from input to output.
 */
@Value.Immutable
@BuckStyleImmutable
public abstract class CxxSource {

  public static enum Type {

    C("c", "c"),
    CXX("c++", "cc", "cp", "cxx", "cpp", "CPP", "c++", "C"),
    OBJC("objective-c", "m"),
    OBJCXX("objective-c++", "mm"),
    C_CPP_OUTPUT("c-cpp-output", "i"),
    CXX_CPP_OUTPUT("c++-cpp-output", "ii"),
    OBJC_CPP_OUTPUT("objective-c-cpp-output", "mi"),
    OBJCXX_CPP_OUTPUT("objective-c++-cpp-output", "mii"),
    ASSEMBLER("assembler", "s"),
    ASSEMBLER_WITH_CPP("assembler-with-cpp", "S"),
    ;

    private final String language;
    private final ImmutableSet<String> extensions;

    private Type(String language, String... extensions) {
      this.language = language;
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

    public ImmutableSet<String> getExtensions() {
      return extensions;
    }

  }

  @Value.Parameter
  public abstract Type getType();

  @Value.Parameter
  public abstract SourcePath getPath();

}
