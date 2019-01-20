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

package com.facebook.buck.cxx;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.BuckStylePackageVisibleImmutable;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

/**
 * A structure to be passed through components of the C/C++/ObjC build (BuildRules, Steps, etc.) so
 * they may add data for diagnostics or logging.
 */
@Value.Immutable
@BuckStylePackageVisibleImmutable
abstract class AbstractCxxLogInfo {
  /** The (fully-flavored) target being built. */
  public abstract Optional<BuildTarget> getTarget();

  /** The source file: the {@code .c}, {@code .cpp}, {@code .m}, etc. */
  public abstract Optional<Path> getSourcePath();

  /**
   * The output file. The type of this file depends on how the rule is to be built, i.e. the
   * specifics of how the {@link Step} handles this build job.
   */
  public abstract Optional<Path> getOutputPath();
}
