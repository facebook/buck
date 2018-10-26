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

package com.facebook.buck.versions;

import com.facebook.buck.core.model.targetgraph.TargetGraph;
import java.util.concurrent.TimeoutException;

/**
 * Takes a regular {@link TargetGraph}, resolves any versioned nodes, and returns a new graph with
 * the versioned nodes removed.
 */
public interface VersionedTargetGraphBuilder {

  /** @return the built {@link TargetGraph} */
  TargetGraph build() throws VersionException, TimeoutException, InterruptedException;
}
