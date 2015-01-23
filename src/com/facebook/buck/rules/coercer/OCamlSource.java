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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Function;

import org.immutables.value.Value;

/**
 * Describes a OCaml source and the various paths it uses from input to output.
 */
@Value.Immutable
@BuckStyleImmutable
public abstract class OCamlSource {
  public static final Function<OCamlSource, SourcePath> TO_SOURCE_PATH = new
      Function<OCamlSource, SourcePath>() {
    @Override
    public SourcePath apply(OCamlSource input) {
      return input.getSource();
    }
  };

  @Value.Parameter
  public abstract String getName();

  @Value.Parameter
  public abstract SourcePath getSource();

  public static OCamlSource ofNameAndSourcePath(String name, SourcePath sourcePath) {
    return ImmutableOCamlSource.of(name, sourcePath);
  }
}
