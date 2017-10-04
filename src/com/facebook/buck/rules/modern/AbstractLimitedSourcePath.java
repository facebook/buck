/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.modern;

import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import org.immutables.value.Value;

/**
 * This can be passed around as a SourcePath, but it can only be resolved by InputPathResolver's
 * LimitedSourcePathResolver. This is just meant to be used for interacting with old-style Steps
 * that take in SourcePath and resolvers.
 */
@BuckStyleTuple
@Value.Immutable(prehash = true)
abstract class AbstractLimitedSourcePath implements SourcePath {

  public abstract SourcePath getLimitedSourcePath();

  @Override
  public int compareTo(SourcePath other) {
    int result = compareClasses(other);
    if (result == 0) {
      result =
          getLimitedSourcePath()
              .compareTo(((AbstractLimitedSourcePath) other).getLimitedSourcePath());
    }
    return result;
  }
}
