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

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.HasSourcePath;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/** Generate linker command line for Rust library when used as a dependency. */
@BuckStyleValue
public abstract class RustLibraryArg implements Arg, HasSourcePath {
  @AddToRuleKey
  public abstract String getCrate();

  @AddToRuleKey
  public abstract SourcePath getRlib();

  @AddToRuleKey
  public abstract boolean getDirect();

  @AddToRuleKey
  public abstract Optional<String> getAlias();

  public static RustLibraryArg of(
      String crate, SourcePath rlib, boolean direct, Optional<String> alias) {
    return ImmutableRustLibraryArg.ofImpl(crate, rlib, direct, alias);
  }

  @Override
  public SourcePath getPath() {
    return getRlib();
  }

  @Override
  public void appendToCommandLine(
      Consumer<String> consumer, SourcePathResolverAdapter pathResolver) {
    // Use absolute path to make sure cross-cell references work.
    Path path = pathResolver.getAbsolutePath(getRlib());
    // NOTE: each of these logical args must be put on the command line as a single parameter
    // (otherwise dedup might just remove one piece of it)
    if (getDirect()) {
      consumer.accept(String.format("--extern=%s=%s", getAlias().orElse(getCrate()), path));
    } else {
      consumer.accept(String.format("-Ldependency=%s", path.getParent()));
    }
  }

  @Override
  public String toString() {
    return getCrate();
  }
}
