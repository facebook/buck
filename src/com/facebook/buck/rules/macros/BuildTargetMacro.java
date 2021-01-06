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

package com.facebook.buck.rules.macros;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.versions.TargetNodeTranslator;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;

/** Base class for macros wrapping a single {@link BuildTarget}. */
public abstract class BuildTargetMacro implements Macro {

  public abstract BuildTargetWithOutputs getTargetWithOutputs();

  public BuildTarget getTarget() {
    return getTargetWithOutputs().getBuildTarget();
  }

  /**
   * @return a copy of this {@link BuildTargetMacro} with the given {@link BuildTargetWithOutputs}.
   */
  protected abstract BuildTargetMacro withTargetWithOutputs(BuildTargetWithOutputs target);

  @Override
  public final Optional<Macro> translateTargets(
      CellNameResolver cellPathResolver, BaseName targetBaseName, TargetNodeTranslator translator) {
    return translator
        .translate(cellPathResolver, targetBaseName, getTarget())
        .map(
            buildTarget ->
                withTargetWithOutputs(
                    BuildTargetWithOutputs.of(
                        buildTarget, getTargetWithOutputs().getOutputLabel())));
  }

  // TODO: remove this logic when cell path is removed from BuildTarget
  @Override
  public int hashCode() {
    return Objects.hash(getTargetWithOutputs());
  }

  // TODO: remove this logic when cell path is removed from BuildTarget
  @Override
  public boolean equals(@Nullable Object another) {
    if (this == another) return true;
    return another instanceof BuildTargetMacro
        && getTargetWithOutputs().equals(((BuildTargetMacro) another).getTargetWithOutputs());
  }
}
