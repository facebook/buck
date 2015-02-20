/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.io.ProjectFilesystem;

import java.util.Objects;

/**
 * Abstract base class for implementations of {@link SourcePath}.
 */
abstract class AbstractSourcePath implements SourcePath {

  private final ProjectFilesystem projectFilesystem;

  protected AbstractSourcePath(ProjectFilesystem projectFilesystem) {
    this.projectFilesystem = projectFilesystem;
  }

  protected final ProjectFilesystem getProjectFilesystem() {
    return projectFilesystem;
  }

  /**
   * @return An opaque representation of the source path in a stable manner.
   */
  protected abstract Object asReference();

  @Override
  public int compareTo(SourcePath o) {
    if (o == this) {
      return 0;
    }

    return toString().compareTo(o.toString());
  }

  @Override
  public int hashCode() {
    return asReference().hashCode();
  }

  @Override
  public boolean equals(Object other) {
    if (other == null || !(other instanceof AbstractSourcePath)) {
      return false;
    }

    AbstractSourcePath that = (AbstractSourcePath) other;

    return Objects.equals(this.projectFilesystem, that.projectFilesystem) &&
        Objects.equals(this.asReference(), that.asReference());
  }

  @Override
  public String toString() {
    return String.valueOf(asReference());
  }
}
