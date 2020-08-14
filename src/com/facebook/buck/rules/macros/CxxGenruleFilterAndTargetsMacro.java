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
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.versions.TargetNodeTranslator;
import com.google.common.collect.Comparators;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableList;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Base class for <code>cxx_genrule</code> flags-based macros. */
public abstract class CxxGenruleFilterAndTargetsMacro implements Macro {

  public abstract Optional<Pattern> getFilter();

  public abstract ImmutableList<BuildTargetWithOutputs> getTargetsWithOutputs();

  @Override
  public int compareTo(Macro o) {
    int result = Macro.super.compareTo(o);
    if (result != 0) {
      return result;
    }
    CxxGenruleFilterAndTargetsMacro other = (CxxGenruleFilterAndTargetsMacro) o;
    return ComparisonChain.start()
        .compare(
            getFilter(),
            other.getFilter(),
            Comparators.emptiesFirst(Comparator.comparing(Pattern::pattern)))
        .compare(
            getTargetsWithOutputs(),
            other.getTargetsWithOutputs(),
            Comparators.lexicographical(Comparator.<BuildTargetWithOutputs>naturalOrder()))
        .result();
  }

  /**
   * @return a copy of this {@link CxxGenruleFilterAndTargetsMacro} with the given {@link
   *     BuildTargetWithOutputs}.
   */
  abstract CxxGenruleFilterAndTargetsMacro withTargetsWithOutputs(
      ImmutableList<BuildTargetWithOutputs> targetsWithOutputs);

  @Override
  public Optional<Macro> translateTargets(
      CellNameResolver cellPathResolver, BaseName targetBaseName, TargetNodeTranslator translator) {
    ImmutableList<BuildTargetWithOutputs> targetsWithOutputs =
        getTargetsWithOutputs().stream()
            .map(
                targetWithOutputs ->
                    new Pair<>(
                        translator.translate(
                            cellPathResolver, targetBaseName, targetWithOutputs.getBuildTarget()),
                        targetWithOutputs.getOutputLabel()))
            .flatMap(
                pair ->
                    pair.getFirst().isPresent()
                        ? Stream.of(
                            BuildTargetWithOutputs.of(pair.getFirst().get(), pair.getSecond()))
                        : Stream.empty())
            .collect(ImmutableList.toImmutableList());
    if (targetsWithOutputs.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(withTargetsWithOutputs(targetsWithOutputs));
  }
}
