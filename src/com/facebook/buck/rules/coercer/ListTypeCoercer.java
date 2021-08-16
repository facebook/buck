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
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.TargetConfigurationResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.concat.Concatable;
import com.facebook.buck.rules.coercer.concat.ImmutableListConcatable;
import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeParameter;
import com.google.common.reflect.TypeToken;
import java.util.Collection;
import java.util.List;

/** Coere to {@link com.google.common.collect.ImmutableList}. */
public class ListTypeCoercer<U, T>
    extends CollectionTypeCoercer<ImmutableList<U>, ImmutableList<T>, U, T>
    implements Concatable<ImmutableList<T>> {

  private final ImmutableListConcatable<T> concatable = new ImmutableListConcatable<>();
  private final TypeToken<ImmutableList<T>> typeToken;
  private final TypeToken<ImmutableList<U>> typeTokenUnconfigured;

  public ListTypeCoercer(TypeCoercer<U, T> elementTypeCoercer) {
    super(elementTypeCoercer);
    this.typeToken =
        new TypeToken<ImmutableList<T>>() {}.where(
            new TypeParameter<T>() {}, elementTypeCoercer.getOutputType());
    this.typeTokenUnconfigured =
        new TypeToken<ImmutableList<U>>() {}.where(
            new TypeParameter<U>() {}, elementTypeCoercer.getUnconfiguredType());
  }

  @Override
  public SkylarkSpec getSkylarkSpec() {
    return new SkylarkSpec() {
      @Override
      public String spec() {
        return String.format("attr.list(%s)", elementTypeCoercer.getSkylarkSpec().spec());
      }

      @Override
      public String topLevelSpec() {
        return String.format(
            "attr.list(%s, default=[])", elementTypeCoercer.getSkylarkSpec().spec());
      }

      @Override
      public List<Class<? extends Enum<?>>> enums() {
        return elementTypeCoercer.getSkylarkSpec().enums();
      }
    };
  }

  @Override
  public TypeToken<ImmutableList<T>> getOutputType() {
    return typeToken;
  }

  @Override
  public TypeToken<ImmutableList<U>> getUnconfiguredType() {
    return typeTokenUnconfigured;
  }

  @Override
  public boolean unconfiguredToConfiguredCoercionIsIdentity() {
    return elementTypeCoercer.unconfiguredToConfiguredCoercionIsIdentity();
  }

  @SuppressWarnings("unchecked")
  @Override
  public ImmutableList<T> coerce(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelPath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfigurationResolver hostConfigurationResolver,
      ImmutableList<U> object)
      throws CoerceFailedException {

    if (unconfiguredToConfiguredCoercionIsIdentity()) {
      return (ImmutableList<T>) object;
    }

    ImmutableList.Builder<T> builder = ImmutableList.builder();
    boolean identity =
        fillConfigured(
            cellRoots,
            filesystem,
            pathRelativeToProjectRoot,
            targetConfiguration,
            hostConfigurationResolver,
            builder,
            object);

    if (identity) {
      // save allocation
      return (ImmutableList<T>) object;
    }

    return builder.build();
  }

  @SuppressWarnings("unchecked")
  @Override
  public ImmutableList<U> coerceToUnconfigured(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelPath pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (object instanceof Collection<?>) {
      boolean identity = true;

      ImmutableList.Builder<U> builder =
          ImmutableList.builderWithExpectedSize(((Collection<?>) object).size());
      for (Object element : (Collection<?>) object) {
        // if any element failed, the entire collection fails
        U coercedElement =
            elementTypeCoercer.coerceToUnconfigured(
                cellRoots, filesystem, pathRelativeToProjectRoot, element);
        builder.add(coercedElement);
        identity &= coercedElement == element;
      }
      if (identity && object instanceof ImmutableList<?>) {
        // reuse allocation
        return (ImmutableList<U>) object;
      }
      return builder.build();
    } else {
      throw CoerceFailedException.simple(object, getOutputType().getType());
    }
  }

  @Override
  public ImmutableList<T> concat(Iterable<ImmutableList<T>> elements) {
    return concatable.concat(elements);
  }
}
