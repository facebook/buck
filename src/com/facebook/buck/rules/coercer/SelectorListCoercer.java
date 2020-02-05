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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.select.Selector;
import com.facebook.buck.core.select.SelectorKey;
import com.facebook.buck.core.select.SelectorList;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Map;

/**
 * {@link TypeCoercer} for {@link SelectorList}.
 *
 * <p>This {@link TypeCoercer} is used to convert the result of a <code>select</code> call to a
 * {@link SelectorList}.
 */
public class SelectorListCoercer<T> implements TypeCoercer<SelectorList<T>> {

  private final BuildTargetTypeCoercer buildTargetTypeCoercer;
  private final TypeCoercer<T> elementTypeCoercer;

  public SelectorListCoercer(
      BuildTargetTypeCoercer buildTargetTypeCoercer, TypeCoercer<T> elementTypeCoercer) {
    this.buildTargetTypeCoercer = buildTargetTypeCoercer;
    this.elementTypeCoercer = elementTypeCoercer;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Class<SelectorList<T>> getOutputClass() {
    return (Class<SelectorList<T>>) (Class<?>) SelectorList.class;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return elementTypeCoercer.hasElementClass(types) || hasBuildTargetType(types);
  }

  private static boolean hasBuildTargetType(Class<?>... types) {
    for (Class<?> type : types) {
      if (type.isAssignableFrom(BuildTarget.class)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void traverse(CellNameResolver cellRoots, SelectorList<T> object, Traversal traversal) {
    traversal.traverse(object);
    for (Selector<T> element : object.getSelectors()) {
      for (Map.Entry<SelectorKey, T> entry : element.getConditions().entrySet()) {
        if (!entry.getKey().isReserved()) {
          buildTargetTypeCoercer.traverse(cellRoots, entry.getKey().getBuildTarget(), traversal);
        }
        elementTypeCoercer.traverse(cellRoots, entry.getValue(), traversal);
      }
      for (SelectorKey selectorKey : element.getNullConditions()) {
        buildTargetTypeCoercer.traverse(cellRoots, selectorKey.getBuildTarget(), traversal);
      }
    }
  }

  @Override
  public SelectorList<T> coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration,
      Object object)
      throws CoerceFailedException {
    SelectorList<?> list = (SelectorList<?>) object;

    ImmutableList.Builder<Selector<T>> selectors = ImmutableList.builder();
    for (Selector<?> selector : list.getSelectors()) {
      selectors.add(
          coerceSelector(
              selector,
              cellRoots,
              filesystem,
              pathRelativeToProjectRoot,
              targetConfiguration,
              hostConfiguration));
    }
    return new SelectorList<>(elementTypeCoercer, selectors.build());
  }

  private Selector<T> coerceSelector(
      Selector<?> input,
      CellPathResolver cellPathResolver,
      ProjectFilesystem projectFilesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration)
      throws CoerceFailedException {
    ImmutableMap.Builder<SelectorKey, T> conditions = ImmutableMap.builder();
    for (Map.Entry<SelectorKey, ?> entry : input.getConditions().entrySet()) {
      conditions.put(
          entry.getKey(),
          elementTypeCoercer.coerce(
              cellPathResolver,
              projectFilesystem,
              pathRelativeToProjectRoot,
              targetConfiguration,
              hostConfiguration,
              entry.getValue()));
    }
    return new Selector<>(conditions.build(), input.getNullConditions(), input.getNoMatchMessage());
  }
}
