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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

/** A coercer for Immutables using the same flow as Description's args */
public class ImmutableTypeCoercer<T> implements TypeCoercer<T> {

  private final Class<T> rawClass;
  private final Method builderMethod;
  private final Class<?> builderClass;
  private final Method buildMethod;
  private final ImmutableMap<String, ParamInfo> paramInfos;

  ImmutableTypeCoercer(Class<T> rawClass, Collection<ParamInfo> paramInfos) {
    this.rawClass = rawClass;
    try {
      this.builderMethod = rawClass.getMethod("builder");
    } catch (NoSuchMethodException | SecurityException e) {
      throw new AssertionError(rawClass + " builder should be accessible", e);
    }
    this.builderClass = builderMethod.getReturnType();
    try {
      this.buildMethod = builderClass.getMethod("build");
    } catch (NoSuchMethodException | SecurityException e) {
      throw new AssertionError(builderClass + " build should be accessible", e);
    }
    // Translate keys from lowerCamel to lower_hyphen
    this.paramInfos =
        paramInfos.stream()
            .collect(
                ImmutableMap.toImmutableMap(
                    paramInfo -> paramInfo.getPythonName(), paramInfo -> paramInfo));
  }

  @Override
  public Class<T> getOutputClass() {
    return rawClass;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return paramInfos.values().stream().anyMatch(paramInfo -> paramInfo.hasElementTypes(types));
  }

  @Override
  public void traverse(CellPathResolver cellRoots, T object, Traversal traversal) {
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
      Path pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      Object object)
      throws CoerceFailedException {
    Object builder;
    try {
      builder = builderMethod.invoke(null);
    } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
      throw new IllegalStateException(rawClass + " builder invocation failed", e);
    }
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
            builder,
            entry.getValue());
      } catch (ParamInfoException e) {
        throw new CoerceFailedException(e.getMessage(), e.getCause());
      }
    }
    try {
      @SuppressWarnings("unchecked")
      T result = (T) buildMethod.invoke(builder);
      return result;
    } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
      throw new IllegalStateException(builderClass + " build invocation failed", e);
    }
  }
}
