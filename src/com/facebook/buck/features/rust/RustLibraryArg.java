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

package com.facebook.buck.features.rust;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.HasSourcePath;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Generate linker command line for Rust library when used as a dependency.
 *
 * <p>Rust distinguished between direct and indirect (transitive) dependencies. Direct dependencies
 * have to be specified with the `--extern` option, which also provides the name that crate can be
 * referenced by. Indirect dependencies are not explicitly enumerated; instead the `-Ldependency`
 * option adds a search directory in which dependencies can be found (in practice with Buck builds,
 * there's one directory per dependency).
 *
 * <p>We also keep the target name we're adding the dependency for, and the target a dependent crate
 * comes from. This is so we can pass an `--extern-location` option to rustc which allows compiler
 * diagnostics for unused dependencies to directly reference the dependency which needs to be
 * removed.
 */
@BuckStyleValue
public abstract class RustLibraryArg implements Arg, HasSourcePath {
  /// Target the dependency is for
  @AddToRuleKey
  public abstract BuildTarget getTarget();

  /// Local crate name of the dependency
  @AddToRuleKey
  public abstract String getCrate();

  /// Path to a dependency's .rlib (or whatever) file
  @AddToRuleKey
  public abstract SourcePath getRlib();

  /// Present if this is a direct dependency, containing the
  /// target by which the dependency is specified.
  @AddToRuleKey
  public abstract Optional<BuildTarget> getDirectDependent();

  /// Relative path from project root to .rlib target
  @AddToRuleKey
  public abstract String getRlibRelativePath();

  /// True if the `extern_locations` option is set.
  @AddToRuleKey
  public abstract boolean getExternLoc();

  public static RustLibraryArg of(
      BuildTarget target,
      String crate,
      SourcePath rlib,
      Optional<BuildTarget> directDependent,
      String rlibRelativePath,
      boolean extern_loc) {
    return ImmutableRustLibraryArg.ofImpl(
        target, crate, rlib, directDependent, rlibRelativePath, extern_loc);
  }

  @Override
  public SourcePath getPath() {
    return getRlib();
  }

  @Override
  public void appendToCommandLine(
      Consumer<String> consumer, SourcePathResolverAdapter pathResolver) {
    // NOTE: each of these logical args must be put on the command line as a single parameter
    // (otherwise dedup might just remove one piece of it)
    Optional<BuildTarget> directDep = getDirectDependent();
    if (directDep.isPresent()) {
      String crate = getCrate();
      consumer.accept(String.format("--extern=%s=%s", crate, getRlibRelativePath()));
      if (getExternLoc()) {
        // assume targets never need json string quoting
        consumer.accept(
            String.format(
                "--extern-location=%s=json:{\"target\":\"%s\",\"dep\":\"%s\"}",
                crate,
                directDep.get().withoutFlavors().getFullyQualifiedName(),
                getTarget().getFullyQualifiedName()));
      }
    } else {
      consumer.accept(
          String.format("-Ldependency=%s", Paths.get(getRlibRelativePath()).getParent()));
    }
  }

  @Override
  public String toString() {
    return getCrate();
  }
}
