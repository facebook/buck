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
import com.facebook.buck.core.rules.modern.annotations.CustomClassBehavior;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.modern.CustomClassSerialization;
import com.facebook.buck.rules.modern.ValueCreator;
import com.facebook.buck.rules.modern.ValueVisitor;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/** Relative link arg. */
@CustomClassBehavior(RelativeLinkArg.SerializationBehavior.class)
class RelativeLinkArg implements Arg {
  @AddToRuleKey private final PathSourcePath library;
  private final ImmutableList<String> link;

  public RelativeLinkArg(PathSourcePath library) {
    this.library = library;
    this.link = createLink(library);
  }

  private static ImmutableList<String> createLink(PathSourcePath library) {
    Path fullPath = library.getFilesystem().resolve(library.getRelativePath());
    String name = MorePaths.stripPathPrefixAndExtension(fullPath.getFileName(), "lib");
    return ImmutableList.of("-L" + fullPath.getParent(), "-l" + name);
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

  /** Custom serialization. */
  static class SerializationBehavior implements CustomClassSerialization<RelativeLinkArg> {
    @Override
    public <E extends Exception> void serialize(
        RelativeLinkArg instance, ValueVisitor<E> serializer) throws E {
      serializer.visitSourcePath(instance.library);
    }

    @Override
    public <E extends Exception> RelativeLinkArg deserialize(ValueCreator<E> deserializer)
        throws E {
      PathSourcePath library = (PathSourcePath) deserializer.createSourcePath();
      return new RelativeLinkArg(library);
    }
  }
}
