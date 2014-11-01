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
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;

public class CxxHeaderSourceSpec {

  private final ImmutableMap<Path, SourcePath> cxxHeaders;
  private final ImmutableMap<String, CxxSource> cxxSources;

  public CxxHeaderSourceSpec(
      ImmutableMap<Path, SourcePath> cxxHeaders,
      ImmutableMap<String, CxxSource> cxxSources) {

    this.cxxHeaders = cxxHeaders;
    this.cxxSources = cxxSources;
  }

  public ImmutableMap<Path, SourcePath> getCxxHeaders() {
    return cxxHeaders;
  }

  public ImmutableMap<String, CxxSource> getCxxSources() {
    return cxxSources;
  }

  @Override
  public boolean equals(Object o) {

    if (this == o) {
      return true;
    }

    if (!(o instanceof CxxHeaderSourceSpec)) {
      return false;
    }

    CxxHeaderSourceSpec that = (CxxHeaderSourceSpec) o;

    if (!cxxHeaders.equals(that.cxxHeaders)) {
      return false;
    }

    if (!cxxSources.equals(that.cxxSources)) {
      return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(cxxHeaders, cxxSources);
  }

}
