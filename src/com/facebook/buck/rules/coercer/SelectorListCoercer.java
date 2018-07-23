/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.select.Selector;
import com.facebook.buck.core.select.SelectorKey;
import com.facebook.buck.core.select.SelectorList;
import com.facebook.buck.core.select.impl.SelectorListFactory;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.nio.file.Path;
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
  private final SelectorListFactory selectorListFactory;

  public SelectorListCoercer(
      BuildTargetTypeCoercer buildTargetTypeCoercer,
      TypeCoercer<T> elementTypeCoercer,
      SelectorListFactory selectorListFactory) {
    this.buildTargetTypeCoercer = buildTargetTypeCoercer;
    this.elementTypeCoercer = elementTypeCoercer;
    this.selectorListFactory = selectorListFactory;
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
  public void traverse(CellPathResolver cellRoots, SelectorList<T> object, Traversal traversal) {
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
      Path pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    com.google.devtools.build.lib.syntax.SelectorList list =
        (com.google.devtools.build.lib.syntax.SelectorList) object;
    return selectorListFactory.create(
        cellRoots, filesystem, pathRelativeToProjectRoot, list.getElements(), elementTypeCoercer);
  }
}
