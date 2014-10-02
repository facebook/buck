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

package com.facebook.buck.parser;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.TargetNode;
import com.google.common.collect.ImmutableSet;

/**
 * A specification used by the parser to match {@link TargetNode} objects.
 */
public interface TargetNodeSpec {

  /**
   * @return whether the given {@link TargetNode} should be traversed.
   */
  ImmutableSet<BuildTarget> filter(Iterable<TargetNode<?>> nodes);

  /**
   * @return a {@link BuildFileSpec} representing the build files to parse to search for
   *     explicit {@link TargetNode}.
   */
  BuildFileSpec getBuildFileSpec();

}
