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

package com.facebook.buck.rules.macros;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetPattern;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.versions.TargetNodeTranslator;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;

/** Base class for macros wrapping a single {@link BuildTarget}. */
public abstract class BuildTargetMacro implements Macro {

  public abstract BuildTarget getTarget();

  /** @return a copy of this {@link BuildTargetMacro} with the given {@link BuildTarget}. */
  abstract BuildTargetMacro withTarget(BuildTarget target);

  @Override
  public final Optional<Macro> translateTargets(
      CellPathResolver cellPathResolver,
      BuildTargetPatternParser<BuildTargetPattern> pattern,
      TargetNodeTranslator translator) {
    return translator.translate(cellPathResolver, pattern, getTarget()).map(this::withTarget);
  }

  // TODO: remove this logic when cell path is removed from BuildTarget
  @Override
  public int hashCode() {
    return Objects.hash(getTarget().getFullyQualifiedName());
  }

  // TODO: remove this logic when cell path is removed from BuildTarget
  @Override
  public boolean equals(@Nullable Object another) {
    if (this == another) return true;
    return another instanceof BuildTargetMacro
        && getTarget()
            .getFullyQualifiedName()
            .equals(((BuildTargetMacro) another).getTarget().getFullyQualifiedName());
  }
}
