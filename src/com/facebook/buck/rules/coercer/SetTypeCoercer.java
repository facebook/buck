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
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.TypeParameter;
import com.google.common.reflect.TypeToken;
import java.util.List;

/** Coerce to {@link com.google.common.collect.ImmutableSet}. */
public class SetTypeCoercer<U, T>
    extends CollectionTypeCoercer<ImmutableSet<U>, ImmutableSet<T>, U, T> {
  private final TypeToken<ImmutableSet<T>> typeToken;
  private final TypeToken<ImmutableSet<U>> typeTokenUnconfigured;

  SetTypeCoercer(TypeCoercer<U, T> elementTypeCoercer) {
    super(elementTypeCoercer);
    this.typeToken =
        new TypeToken<ImmutableSet<T>>() {}.where(
            new TypeParameter<T>() {}, elementTypeCoercer.getOutputType());
    this.typeTokenUnconfigured =
        new TypeToken<ImmutableSet<U>>() {}.where(
            new TypeParameter<U>() {}, elementTypeCoercer.getUnconfiguredType());
  }

  @Override
  public SkylarkSpec getSkylarkSpec() {
    return new SkylarkSpec() {
      @Override
      public String spec() {
        return String.format(
            "attr.set(%s, sorted=False)", elementTypeCoercer.getSkylarkSpec().spec());
      }

      @Override
      public String topLevelSpec() {
        return String.format(
            "attr.set(%s, sorted=False, default=[])", elementTypeCoercer.getSkylarkSpec().spec());
      }

      @Override
      public List<Class<? extends Enum<?>>> enums() {
        return elementTypeCoercer.getSkylarkSpec().enums();
      }
    };
  }

  @Override
  public TypeToken<ImmutableSet<T>> getOutputType() {
    return typeToken;
  }

  @Override
  public TypeToken<ImmutableSet<U>> getUnconfiguredType() {
    return typeTokenUnconfigured;
  }

  @Override
  public boolean unconfiguredToConfiguredCoercionIsIdentity() {
    return elementTypeCoercer.unconfiguredToConfiguredCoercionIsIdentity();
  }

  @SuppressWarnings("unchecked")
  @Override
  public ImmutableSet<T> coerce(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelPath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      HostTargetConfigurationResolver hostConfigurationResolver,
      ImmutableSet<U> object)
      throws CoerceFailedException {
    if (unconfiguredToConfiguredCoercionIsIdentity()) {
      return (ImmutableSet<T>) object;
    }

    ImmutableSet.Builder<T> builder = ImmutableSet.builder();
    fillConfigured(
        cellRoots,
        filesystem,
        pathRelativeToProjectRoot,
        targetConfiguration,
        hostConfigurationResolver,
        builder,
        object);
    return builder.build();
  }

  @Override
  public ImmutableSet<U> coerceToUnconfigured(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelPath pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    ImmutableSet.Builder<U> builder = ImmutableSet.builder();
    fillUnconfigured(cellRoots, filesystem, pathRelativeToProjectRoot, builder, object);
    return builder.build();
  }
}
