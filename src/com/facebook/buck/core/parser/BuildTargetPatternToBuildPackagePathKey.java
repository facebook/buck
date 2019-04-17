/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.core.parser;

import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetPatternData;
import org.immutables.value.Value;

/** Transformation key containing build target pattern for which to discover package paths */
@Value.Immutable(builder = false, copy = false, prehash = true)
public abstract class BuildTargetPatternToBuildPackagePathKey
    implements ComputeKey<BuildPackagePaths> {

  @Value.Parameter
  /**
   * Pattern for which to discover paths to appropriate packages. Pattern can specify single target
   * like //package:target or multiple targets like //package: or //package/...
   */
  public abstract BuildTargetPatternData getPattern();

  @Override
  public Class<? extends ComputeKey<?>> getKeyClass() {
    return BuildTargetPatternToBuildPackagePathKey.class;
  }
}
