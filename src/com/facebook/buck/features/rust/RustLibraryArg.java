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

package com.facebook.buck.features.rust;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.HasSourcePath;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Generate linker command line for Rust library when used as a dependency. */
public class RustLibraryArg implements Arg, HasSourcePath {
  @AddToRuleKey private final String crate;
  @AddToRuleKey private final SourcePath rlib;
  @AddToRuleKey private final boolean direct;

  RustLibraryArg(String crate, SourcePath rlib, boolean direct) {
    this.crate = crate;
    this.rlib = rlib;
    this.direct = direct;
  }

  @Override
  public void appendToCommandLine(Consumer<String> consumer, SourcePathResolver pathResolver) {
    Path path = pathResolver.getRelativePath(rlib);
    // NOTE: each of these logical args must be put on the command line as a single parameter
    // (otherwise dedup might just remove one piece of it)
    if (direct) {
      consumer.accept(String.format("--extern=%s=%s", crate, path));
    } else {
      consumer.accept(String.format("-Ldependency=%s", path.getParent()));
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
    if (!crate.equals(that.crate)) {
      return false;
    }
    return rlib.equals(that.rlib);
  }

  @Override
  public int hashCode() {
    int result = crate.hashCode();
    result = 31 * result + crate.hashCode();
    result = 31 * result + rlib.hashCode();
    result = 31 * result + (direct ? 1 : 0);
    return result;
  }

  @Override
  public SourcePath getPath() {
    return rlib;
  }
}
