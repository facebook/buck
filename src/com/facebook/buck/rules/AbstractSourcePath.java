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

/**
 * Abstract base class for implementations of {@link SourcePath}.
 */
abstract class AbstractSourcePath implements SourcePath {

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
  public boolean equals(Object that) {
    if (that == null || !(that instanceof SourcePath)) {
      return false;
    }

    return asReference().equals(((SourcePath) that).asReference());
  }

  @Override
  public String toString() {
    return String.valueOf(asReference());
  }
}
