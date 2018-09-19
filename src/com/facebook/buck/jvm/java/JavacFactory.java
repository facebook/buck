/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.jvm.java.toolchain.JavaToolchain;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.collect.ImmutableCollection;
import java.util.function.Supplier;
import javax.annotation.Nullable;

public final class JavacFactory {
  private final Supplier<JavacProvider> javacProvider;

  public JavacFactory(Supplier<JavacProvider> javacProvider) {
    this.javacProvider = javacProvider;
  }

  /** Returns either the defautl javac or one created from the provided args. */
  public Javac create(SourcePathRuleFinder ruleFinder, @Nullable JvmLibraryArg args) {
    if (args != null) {
      JavacSpec spec = args.getJavacSpec(ruleFinder);
      if (spec != null) {
        return spec.getJavacProvider().resolve(ruleFinder);
      }
    }
    return javacProvider.get().resolve(ruleFinder);
  }

  /** Creates a JavacFactory for the default Java toolchain. */
  public static JavacFactory getDefault(ToolchainProvider toolchainProvider) {
    return new JavacFactory(
        MoreSuppliers.memoize(
            () ->
                toolchainProvider
                    .getByName(JavaToolchain.DEFAULT_NAME, JavaToolchain.class)
                    .getJavacProvider()));
  }

  /**
   * Adds the parse time deps required for javac based on the args. If the args has a spec for
   * javac, we assume that the parse time deps will be derived elsewhere.
   */
  public void addParseTimeDeps(
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder,
      @Nullable JvmLibraryArg args) {
    if (args == null || !args.hasJavacSpec()) {
      javacProvider.get().addParseTimeDeps(targetGraphOnlyDepsBuilder);
    }
  }
}
