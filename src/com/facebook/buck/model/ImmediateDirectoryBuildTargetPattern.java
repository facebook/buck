/*
 * Copyright 2012-present Facebook, Inc.
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
package com.facebook.buck.model;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

import javax.annotation.Nullable;

/**
 * A pattern matches build targets that are all in the same directory.
 */
public class ImmediateDirectoryBuildTargetPattern implements BuildTargetPattern {

  private final String basePathWithSlash;

  /**
   * @param basePathWithSlash The base path of all valid build targets. It is expected to
   *     match the value returned from a {@link BuildTarget#getBasePathWithSlash()} call.
   */
  public ImmediateDirectoryBuildTargetPattern(String basePathWithSlash) {
    Preconditions.checkNotNull(basePathWithSlash);
    Preconditions.checkArgument(basePathWithSlash.isEmpty() || basePathWithSlash.endsWith("/"),
        "basePathWithSlash must either be the empty string or end with a slash");
    this.basePathWithSlash = basePathWithSlash;
  }

  /**
   * @return true if the given target not null and has the same basePathWithSlash,
   *         otherwise return false.
   */
  @Override
  public boolean apply(@Nullable BuildTarget target) {
    if (target == null) {
      return false;
    } else {
      return Objects.equal(this.basePathWithSlash, target.getBasePathWithSlash());
    }
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof ImmediateDirectoryBuildTargetPattern)) {
      return false;
    }
    ImmediateDirectoryBuildTargetPattern that = (ImmediateDirectoryBuildTargetPattern) o;
    return Objects.equal(this.basePathWithSlash, that.basePathWithSlash);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(basePathWithSlash);
  }

}
