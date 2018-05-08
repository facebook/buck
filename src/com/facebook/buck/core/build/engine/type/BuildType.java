/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.build.engine.type;

/** The mode in which to build rules. */
public enum BuildType {

  // Perform a shallow build, only locally materializing the bare minimum needed to build the
  // top-level build targets.
  SHALLOW,

  // Perform a deep build, locally materializing all the transitive dependencies of the top-level
  // build targets.
  DEEP,

  // Perform local cache population by only loading all the transitive dependencies of
  // the top-level build targets from the remote cache, without building missing or changed
  // dependencies locally.
  POPULATE_FROM_REMOTE_CACHE,
}
