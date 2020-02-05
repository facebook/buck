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
import com.facebook.buck.core.description.arg.DataTransferObject;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import java.util.Map;

/** A coercer for Immutables using the same flow as Description's args */
public class ImmutableTypeCoercer<T extends DataTransferObject> implements TypeCoercer<T> {

  private final DataTransferObjectDescriptor<T> constructorArgDescriptor;
  private final ImmutableMap<String, ParamInfo> paramInfos;

  ImmutableTypeCoercer(DataTransferObjectDescriptor<T> constructorArgDescriptor) {
    this.constructorArgDescriptor = constructorArgDescriptor;
    // Translate keys from lowerCamel to lower_hyphen
    this.paramInfos =
        constructorArgDescriptor.getParamInfos().values().stream()
            .collect(
                ImmutableMap.toImmutableMap(
                    paramInfo -> paramInfo.getPythonName(), paramInfo -> paramInfo));
  }

  @Override
  public Class<T> getOutputClass() {
    return constructorArgDescriptor.objectClass();
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return paramInfos.values().stream().anyMatch(paramInfo -> paramInfo.hasElementTypes(types));
  }

  @Override
  public void traverse(CellNameResolver cellRoots, T object, Traversal traversal) {
    traversal.traverse(object);
    for (ParamInfo paramInfo : paramInfos.values()) {
      @SuppressWarnings("unchecked")
      TypeCoercer<Object> paramTypeCoercer = (TypeCoercer<Object>) paramInfo.getTypeCoercer();
      Object fieldValue = paramInfo.get(object);
      paramTypeCoercer.traverse(cellRoots, fieldValue, traversal);
    }
  }

  @Override
  public T coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration,
      Object object)
      throws CoerceFailedException {

    Object builder = constructorArgDescriptor.getBuilderFactory().get();
    if (!(object instanceof Map)) {
      throw CoerceFailedException.simple(object, getOutputClass(), "expected a dict");
    }
    Map<?, ?> map = (Map<?, ?>) object;
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      Object key = entry.getKey();
      if (!(key instanceof String)) {
        throw CoerceFailedException.simple(object, getOutputClass(), "keys should be strings");
      }
      ParamInfo paramInfo = paramInfos.get(key);
      if (paramInfo == null) {
        throw CoerceFailedException.simple(
            object,
            getOutputClass(),
            "parameter '" + key + "' not found on " + paramInfos.keySet());
      }
      try {
        paramInfo.set(
            cellRoots,
            filesystem,
            pathRelativeToProjectRoot,
            targetConfiguration,
            hostConfiguration,
            builder,
            entry.getValue());
      } catch (ParamInfoException e) {
        throw new CoerceFailedException(e.getMessage(), e.getCause());
      }
    }
    return constructorArgDescriptor.build(builder, builder.getClass().getSimpleName());
  }
}
