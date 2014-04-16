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

import java.nio.file.Path;

/**
 * Represents a source that is required to build a rule (typically a file).
 */
public interface SourcePath extends Comparable<SourcePath>  {

  /**
   * Converts this SourcePath to the {@link Path} it represents. Note, this method should only be
   * called while a rule is executing.
   *
   * @return the Path which this instance represents.
   */
  public Path resolve();

  /**
   * @return a representation of path in a stable manner that does not involve calling {#resolve()}
   */
  public Object asReference();
}
