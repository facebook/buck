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

package com.facebook.buck.java;

import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

/**
 * A build rule should implement this interface if it has a 'srcs' attribute that lists Java source
 * files.
 */
public interface HasJavaSrcs {

  /**
   * @return the set of paths (which may be relative paths) to the expanded version of the 'srcs'
   *     attribute for a build rule
   */
  public ImmutableSortedSet<Path> getJavaSrcs();
}
