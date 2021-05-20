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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.HostTargetConfigurationResolver;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.UnconfiguredSourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.types.Unit;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.TypeToken;
import java.util.List;

/** Coerce to {@link com.facebook.buck.rules.coercer.SourceSet}. */
public class SourceSetTypeCoercer extends SourceSetConcatable
    implements TypeCoercer<UnconfiguredSourceSet, SourceSet> {
  private final TypeCoercer<ImmutableSet<UnconfiguredSourcePath>, ImmutableSet<SourcePath>>
      unnamedHeadersTypeCoercer;
  private final TypeCoercer<
          ImmutableMap<String, UnconfiguredSourcePath>, ImmutableMap<String, SourcePath>>
      namedHeadersTypeCoercer;

  SourceSetTypeCoercer(
      TypeCoercer<String, String> stringTypeCoercer,
      TypeCoercer<UnconfiguredSourcePath, SourcePath> sourcePathTypeCoercer) {
    this.unnamedHeadersTypeCoercer = new SetTypeCoercer<>(sourcePathTypeCoercer);
    this.namedHeadersTypeCoercer = new MapTypeCoercer<>(stringTypeCoercer, sourcePathTypeCoercer);
  }

  @Override
  public TypeCoercer.SkylarkSpec getSkylarkSpec() {
    return new SkylarkSpec() {
      @Override
      public String spec() {
        return "attr.named_set(attr.source(), sorted=False)";
      }

      @Override
      public String topLevelSpec() {
        return "attr.named_set(attr.source(), sorted=False, default=[])";
      }
    };
  }

  @Override
  public TypeToken<SourceSet> getOutputType() {
    return TypeToken.of(SourceSet.class);
  }

  @Override
  public TypeToken<UnconfiguredSourceSet> getUnconfiguredType() {
    return TypeToken.of(UnconfiguredSourceSet.class);
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return unnamedHeadersTypeCoercer.hasElementClass(types)
        || namedHeadersTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverseUnconfigured(
      CellNameResolver cellRoots, UnconfiguredSourceSet object, Traversal traversal) {
    object.match(
        new UnconfiguredSourceSet.Matcher<Unit>() {
          @Override
          public Unit named(ImmutableMap<String, UnconfiguredSourcePath> named) {
            namedHeadersTypeCoercer.traverseUnconfigured(cellRoots, named, traversal);
            return Unit.UNIT;
          }

          @Override
          public Unit unnamed(ImmutableSet<UnconfiguredSourcePath> unnamed) {
            unnamedHeadersTypeCoercer.traverseUnconfigured(cellRoots, unnamed, traversal);
            return Unit.UNIT;
          }
        });
  }

  @Override
  public void traverse(CellNameResolver cellRoots, SourceSet object, Traversal traversal) {
    object.match(
        new SourceSet.Matcher<Unit>() {
          @Override
          public Unit named(ImmutableMap<String, SourcePath> named) {
            namedHeadersTypeCoercer.traverse(cellRoots, named, traversal);
            return Unit.UNIT;
          }

          @Override
          public Unit unnamed(ImmutableSet<SourcePath> unnamed) {
            unnamedHeadersTypeCoercer.traverse(cellRoots, unnamed, traversal);
            return Unit.UNIT;
          }
        });
  }

  @Override
  public UnconfiguredSourceSet coerceToUnconfigured(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelPath pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (object instanceof List) {
      return UnconfiguredSourceSet.ofUnnamedSources(
          unnamedHeadersTypeCoercer.coerceToUnconfigured(
              cellRoots, filesystem, pathRelativeToProjectRoot, object));
    } else {
      return UnconfiguredSourceSet.ofNamedSources(
          namedHeadersTypeCoercer.coerceToUnconfigured(
              cellRoots, filesystem, pathRelativeToProjectRoot, object));
    }
  }

  @Override
  public SourceSet coerce(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelPath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      HostTargetConfigurationResolver hostConfigurationResolver,
      UnconfiguredSourceSet object)
      throws CoerceFailedException {
    return object.match(
        new UnconfiguredSourceSet.MatcherWithException<SourceSet, CoerceFailedException>() {
          @Override
          public SourceSet named(ImmutableMap<String, UnconfiguredSourcePath> named)
              throws CoerceFailedException {
            return SourceSet.ofNamedSources(
                namedHeadersTypeCoercer.coerce(
                    cellRoots,
                    filesystem,
                    pathRelativeToProjectRoot,
                    targetConfiguration,
                    hostConfigurationResolver,
                    named));
          }

          @Override
          public SourceSet unnamed(ImmutableSet<UnconfiguredSourcePath> unnamed)
              throws CoerceFailedException {
            return SourceSet.ofUnnamedSources(
                unnamedHeadersTypeCoercer.coerce(
                    cellRoots,
                    filesystem,
                    pathRelativeToProjectRoot,
                    targetConfiguration,
                    hostConfigurationResolver,
                    unnamed));
          }
        });
  }
}
