/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.rules.args.Arg;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

class RelativeLinkArg implements Arg {
  @AddToRuleKey private final PathSourcePath library;
  private final ImmutableList<String> link;

  public RelativeLinkArg(PathSourcePath library) {
    this.library = library;
    Path fullPath = library.getFilesystem().resolve(library.getRelativePath());
    String name = MorePaths.stripPathPrefixAndExtension(fullPath.getFileName(), "lib");
    this.link = ImmutableList.of("-L" + fullPath.getParent(), "-l" + name);
  }

  @Override
  public void appendToCommandLine(Consumer<String> consumer, SourcePathResolver pathResolver) {
    link.forEach(consumer);
  }

  @Override
  public String toString() {
    return Joiner.on(' ').join(link);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof RelativeLinkArg)) {
      return false;
    }
    RelativeLinkArg relativeLinkArg = (RelativeLinkArg) o;
    return Objects.equals(library, relativeLinkArg.library);
  }

  @Override
  public int hashCode() {
    return Objects.hash(library);
  }
}
