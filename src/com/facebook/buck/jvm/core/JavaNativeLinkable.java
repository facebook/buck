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

package com.facebook.buck.jvm.core;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.google.common.collect.ImmutableMap;

/**
 * Represents components which contribute native shared libraries to a Java-based package.
 */
public interface JavaNativeLinkable {

  /**
   * @return a map of shared library SONAME to shared library path for the given
   *     {@link CxxPlatform}.
   */
  ImmutableMap<String, SourcePath> getSharedLibraries(
      TargetGraph targetGraph,
      CxxPlatform cxxPlatform);

}
