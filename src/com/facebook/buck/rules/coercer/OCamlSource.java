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
import com.google.common.base.Function;
import com.google.common.base.Objects;

/**
 * Describes a OCaml source and the various paths it uses from input to output.
 */
public class OCamlSource {
  public static final Function<OCamlSource, SourcePath> TO_SOURCE_PATH = new
      Function<OCamlSource, SourcePath>() {
    @Override
    public SourcePath apply(OCamlSource input) {
      return input.getSource();
    }
  };

  private final String name;
  private final SourcePath source;

  private OCamlSource(String name, SourcePath source) {
    this.name = name;
    this.source = source;
  }

  public String getName() {
    return name;
  }

  public SourcePath getSource() {
    return source;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }

    if (!(o instanceof OCamlSource)) {
      return false;
    }

    OCamlSource ocamlSource = (OCamlSource) o;

    return name.equals(ocamlSource.name) && source.equals(ocamlSource.source);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(name, source);
  }

  public static OCamlSource ofNameAndSourcePath(String name, SourcePath sourcePath) {
    return new OCamlSource(name, sourcePath);
  }
}
