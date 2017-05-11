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

import com.google.common.base.Preconditions;

/** Abstract base class for implementations of {@link SourcePath}. */
abstract class AbstractSourcePath<T extends AbstractSourcePath<T>> implements SourcePath {
  protected abstract int compareReferences(T o);

  @SuppressWarnings("unchecked")
  @Override
  public int compareTo(SourcePath o) {
    if (o == this) {
      return 0;
    }

    if (this.getClass() != o.getClass()) {
      int result = this.getClass().getName().compareTo(o.getClass().getName());
      if (result != 0) {
        return result;
      }

      Preconditions.checkState(
          this.getClass().equals(o.getClass()), "Classes are different but have the same name.");
    }

    return this.compareReferences((T) o);
  }
}
