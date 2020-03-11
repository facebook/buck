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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.common.BuildRules;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.jvm.java.JarBuildStepsFactory.JavaDependencyInfo;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import java.util.Objects;
import java.util.Optional;
import org.immutables.builder.Builder;
import org.immutables.value.Value;

@BuckStyleValueWithBuilder
abstract class DefaultJavaLibraryClasspaths {

  @Builder.Parameter
  abstract ActionGraphBuilder getActionGraphBuilder();

  abstract BuildRuleParams getBuildRuleParams();

  abstract JavaLibraryDeps getDeps();

  @Value.Default
  public CompileAgainstLibraryType getCompileAgainstLibraryType() {
    return CompileAgainstLibraryType.FULL;
  }

  @Value.Lazy
  protected ImmutableSortedSet<BuildRule> getCompileTimeClasspathFullDeps() {
    return getCompileTimeClasspathUnfilteredFullDeps().stream()
        .filter(dep -> dep instanceof HasJavaAbi)
        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
  }

  @Value.Lazy
  protected Iterable<BuildRule> getCompileTimeFirstOrderDeps() {
    return Iterables.concat(
        getDeps().getDeps(),
        Objects.requireNonNull(getDeps()).getProvidedDeps(),
        Objects.requireNonNull(getDeps()).getExportedProvidedDeps());
  }

  @Value.Lazy
  protected ImmutableSet<BuildRule> getCompileTimeClasspathUnfilteredFullDeps() {
    Iterable<BuildRule> firstOrderDeps = getCompileTimeFirstOrderDeps();

    ImmutableSet<BuildRule> rulesExportedByDependencies =
        BuildRules.getUnsortedExportedRules(firstOrderDeps);

    return RichStream.from(Iterables.concat(firstOrderDeps, rulesExportedByDependencies))
        .collect(ImmutableSet.toImmutableSet());
  }

  @Value.Lazy
  public ImmutableList<JavaDependencyInfo> getDependencyInfos() {
    ImmutableList.Builder<JavaDependencyInfo> builder = ImmutableList.builder();

    ImmutableSortedSet<BuildRule> sourceOnlyAbiDeps = getDeps().getSourceOnlyAbiDeps();
    CompileAgainstLibraryType compileAgainstLibraryType = getCompileAgainstLibraryType();

    for (BuildRule compileTimeFullDep : getCompileTimeClasspathFullDeps()) {
      Preconditions.checkState(compileTimeFullDep instanceof HasJavaAbi);

      BuildRule abiDep = getAbiDep(compileTimeFullDep);
      BuildRule compileTimeDep =
          compileAgainstLibraryType == CompileAgainstLibraryType.FULL ? compileTimeFullDep : abiDep;

      SourcePath compileTimeSourcePath = compileTimeDep.getSourcePathToOutput();
      // Some deps might not actually contain any source files. In that case, they have no output.
      // Just skip them.
      if (compileTimeSourcePath == null) {
        continue;
      }

      boolean requiredForSourceOnlyAbi =
          (compileTimeFullDep instanceof MaybeRequiredForSourceOnlyAbi
                  && ((MaybeRequiredForSourceOnlyAbi) compileTimeFullDep)
                      .getRequiredForSourceOnlyAbi())
              || sourceOnlyAbiDeps.contains(compileTimeFullDep);

      builder.add(
          new JavaDependencyInfo(
              compileTimeSourcePath, abiDep.getSourcePathToOutput(), requiredForSourceOnlyAbi));
    }

    return builder.build();
  }

  @Value.Lazy
  public ImmutableList<JavaDependencyInfo> getDependencyInfosForSourceOnlyAbi() {
    return getDependencyInfos().stream()
        .filter(javaDependencyInfo -> javaDependencyInfo.isRequiredForSourceOnlyAbi)
        .collect(ImmutableList.toImmutableList());
  }

  private BuildRule getAbiDep(BuildRule compileTimeFullDep) {
    Preconditions.checkState(compileTimeFullDep instanceof HasJavaAbi);
    HasJavaAbi hasJavaAbi = (HasJavaAbi) compileTimeFullDep;
    CompileAgainstLibraryType compileAgainstLibraryType = getCompileAgainstLibraryType();
    Optional<BuildTarget> abiJarTarget = Optional.empty();

    if (compileAgainstLibraryType.equals(CompileAgainstLibraryType.SOURCE_ONLY_ABI)) {
      abiJarTarget = hasJavaAbi.getSourceOnlyAbiJar();
    }

    if (!abiJarTarget.isPresent()) {
      abiJarTarget = hasJavaAbi.getAbiJar();
    }

    if (abiJarTarget.isPresent()) {
      return getActionGraphBuilder().requireRule(abiJarTarget.get());
    } else {
      return compileTimeFullDep;
    }
  }
}
