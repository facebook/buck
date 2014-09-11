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

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import java.util.Objects;

public class AppleBundleDestination {
  public enum SubfolderSpec {
    ABSOLUTE,
    WRAPPER,
    EXECUTABLES,
    RESOURCES,
    FRAMEWORKS,
    SHARED_FRAMEWORKS,
    SHARED_SUPPORT,
    PLUGINS,
    JAVA_RESOURCES,
    PRODUCTS,
  }

  private final SubfolderSpec subfolderSpec;
  private final Optional<String> subpath;

  public AppleBundleDestination(SubfolderSpec subfolderSpec, Optional<String> subpath) {
    this.subfolderSpec = Preconditions.checkNotNull(subfolderSpec);
    this.subpath = Preconditions.checkNotNull(subpath);
  }

  public SubfolderSpec getSubfolderSpec() {
    return subfolderSpec;
  }

  public Optional<String> getSubpath() {
    return subpath;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof AppleBundleDestination)) {
      return false;
    }

    AppleBundleDestination that = (AppleBundleDestination) other;

    return Objects.equals(this.subfolderSpec, that.subfolderSpec) &&
        Objects.equals(this.subpath, that.subpath);
  }

  @Override
  public int hashCode() {
    return Objects.hash(subfolderSpec, subpath);
  }
}
