/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.parser;

import com.facebook.buck.core.graph.transformation.model.ClassBasedComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetPattern;
import com.facebook.buck.core.util.immutables.BuckStylePrehashedValue;

/** Transformation key containing build target pattern for which to discover package paths */
@BuckStylePrehashedValue
public abstract class BuildTargetPatternToBuildPackagePathKey
    implements ComputeKey<BuildPackagePaths> {

  public static final ComputationIdentifier<BuildPackagePaths> IDENTIFIER =
      ClassBasedComputationIdentifier.of(
          BuildTargetPatternToBuildPackagePathKey.class, BuildPackagePaths.class);

  /**
   * Pattern for which to discover paths to appropriate packages. Pattern can specify single target
   * like //package:target or multiple targets like //package: or //package/...
   */
  public abstract BuildTargetPattern getPattern();

  @Override
  public ComputationIdentifier<BuildPackagePaths> getIdentifier() {
    return IDENTIFIER;
  }

  public static BuildTargetPatternToBuildPackagePathKey of(BuildTargetPattern pattern) {
    return ImmutableBuildTargetPatternToBuildPackagePathKey.of(pattern);
  }
}
