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

package com.facebook.buck.rules.macros;

import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableList;

/** Unconfigured graph version of {@link StringWithMacros}. */
public interface UnconfiguredStringWithMacros {
  ImmutableList<Either<String, UnconfiguredMacroContainer>> getUnconfiguredParts();

  StringWithMacros configure(
      TargetConfiguration targetConfiguration, TargetConfiguration hostConfiguration);

  /** Default implementation */
  class WithMacros implements UnconfiguredStringWithMacros {
    private final ImmutableList<Either<String, UnconfiguredMacroContainer>> parts;

    public WithMacros(ImmutableList<Either<String, UnconfiguredMacroContainer>> parts) {
      this.parts = parts;
    }

    @Override
    public ImmutableList<Either<String, UnconfiguredMacroContainer>> getUnconfiguredParts() {
      return parts;
    }

    @Override
    public StringWithMacros configure(
        TargetConfiguration targetConfiguration, TargetConfiguration hostConfiguration) {
      return StringWithMacros.of(
          parts.stream()
              .map(e -> e.mapRight(m -> m.configure(targetConfiguration, hostConfiguration)))
              .collect(ImmutableList.toImmutableList()));
    }
  }

  /** String with macros is a sequence of strings and macros. Create it. */
  static UnconfiguredStringWithMacros ofUnconfigured(
      ImmutableList<Either<String, UnconfiguredMacroContainer>> parts) {
    return new UnconfiguredStringWithMacros.WithMacros(parts);
  }

  /** Create a string with macros with a single string without macros */
  static UnconfiguredStringWithMacros ofConstantStringUnconfigured(String singlePart) {
    if (singlePart.isEmpty()) {
      return StringWithMacros.Constant.EMPTY;
    } else {
      return new StringWithMacros.Constant(singlePart);
    }
  }
}
