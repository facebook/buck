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

package com.facebook.buck.rust;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.HasSourcePath;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;

/** Generate linker command line for Rust library when used as a dependency. */
public class RustLibraryArg implements Arg, HasSourcePath {
  private final SourcePathResolver resolver;
  private final String crate;
  private final ImmutableSortedSet<BuildRule> deps;
  private final SourcePath rlib;
  private final boolean direct;

  public RustLibraryArg(
      SourcePathResolver resolver,
      String crate,
      SourcePath rlib,
      boolean direct,
      ImmutableSortedSet<BuildRule> deps) {
    this.resolver = resolver;
    this.crate = crate;
    this.rlib = rlib;
    this.direct = direct;
    this.deps = deps;
  }

  @Override
  public ImmutableCollection<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    ImmutableSet.Builder<BuildRule> deps = ImmutableSet.builder();

    deps.addAll(ruleFinder.filterBuildRuleInputs(getInputs()));
    deps.addAll(this.deps);

    return deps.build();
  }

  @Override
  public ImmutableList<SourcePath> getInputs() {
    return ImmutableList.of(rlib);
  }

  @Override
  public void appendToCommandLine(
      ImmutableCollection.Builder<String> builder, SourcePathResolver pathResolver) {
    Path path = resolver.getRelativePath(rlib);

    // NOTE: each of these logical args must be put on the command line as a single parameter
    // (otherwise dedup might just remove one piece of it)
    if (direct) {
      builder.add(String.format("--extern=%s=%s", crate, path));
    } else {
      builder.add(String.format("-Ldependency=%s", path.getParent()));
    }
  }

  @Override
  public String toString() {
    return crate;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    RustLibraryArg that = (RustLibraryArg) o;

    if (direct != that.direct) {
      return false;
    }
    if (!resolver.equals(that.resolver)) {
      return false;
    }
    if (!crate.equals(that.crate)) {
      return false;
    }
    if (!deps.equals(that.deps)) {
      return false;
    }
    return rlib.equals(that.rlib);
  }

  @Override
  public int hashCode() {
    int result = resolver.hashCode();
    result = 31 * result + crate.hashCode();
    result = 31 * result + deps.hashCode();
    result = 31 * result + rlib.hashCode();
    result = 31 * result + (direct ? 1 : 0);
    return result;
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("crate", crate);
    sink.setReflectively("rlib", rlib);
  }

  @Override
  public SourcePath getPath() {
    return rlib;
  }
}
