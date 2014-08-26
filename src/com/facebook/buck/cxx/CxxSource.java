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
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

/**
 * Describes a C/C++ source and the various paths it uses from input to output.
 */
public class CxxSource {

  // The logical, BUCK-file-relative name for the source as listed in the "srcs" parameter.
  private final String name;

  // The path to the source file.
  private final SourcePath source;

  public CxxSource(String name, SourcePath source) {
    this.name = Preconditions.checkNotNull(name);
    this.source = Preconditions.checkNotNull(source);
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

    if (!(o instanceof CxxSource)) {
      return false;
    }

    CxxSource cxxSource = (CxxSource) o;

    if (!name.equals(cxxSource.name)) {
      return false;
    }

    if (!source.equals(cxxSource.source)) {
      return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(name, source);
  }

}
