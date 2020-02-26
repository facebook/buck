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
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.test.rule.TestRunnerSpec;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import java.util.Map;

/**
 * Coerces a freeform JSON as a {@link TestRunnerSpec}, which is basically a JSON containing {@link
 * StringWithMacros}
 */
public class TestRunnerSpecCoercer implements TypeCoercer<Object, TestRunnerSpec> {

  private final TypeCoercer<Object, StringWithMacros> macrosTypeCoercer;
  private final TypeCoercer<
          ImmutableMap<Object, Object>, ImmutableMap<StringWithMacros, TestRunnerSpec>>
      mapTypeCoercer;
  private final ListTypeCoercer<Object, TestRunnerSpec> listTypeCoercer;

  public TestRunnerSpecCoercer(TypeCoercer<Object, StringWithMacros> macrosTypeCoercer) {
    this.macrosTypeCoercer = macrosTypeCoercer;
    this.mapTypeCoercer = new MapTypeCoercer<>(macrosTypeCoercer, this);
    this.listTypeCoercer = new ListTypeCoercer<>(this);
  }

  @Override
  public TypeToken<TestRunnerSpec> getOutputType() {
    return TypeToken.of(TestRunnerSpec.class);
  }

  @Override
  public TypeToken<Object> getUnconfiguredType() {
    return TypeToken.of(Object.class);
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return false;
  }

  @Override
  public void traverse(CellNameResolver cellRoots, TestRunnerSpec object, Traversal traversal) {}

  @Override
  public Object coerceToUnconfigured(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    return object;
  }

  @Override
  public TestRunnerSpec coerce(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration,
      Object object)
      throws CoerceFailedException {

    return TestRunnerSpec.of(
        coerceRecursively(
            cellRoots,
            filesystem,
            pathRelativeToProjectRoot,
            targetConfiguration,
            hostConfiguration,
            object));
  }

  private Object coerceRecursively(
      CellNameResolver cellNameResolver,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration,
      Object object)
      throws CoerceFailedException {
    if (object instanceof Map) {
      return mapTypeCoercer.coerceBoth(
          cellNameResolver,
          filesystem,
          pathRelativeToProjectRoot,
          targetConfiguration,
          hostConfiguration,
          object);
    }
    if (object instanceof Iterable) {
      return listTypeCoercer.coerceBoth(
          cellNameResolver,
          filesystem,
          pathRelativeToProjectRoot,
          targetConfiguration,
          hostConfiguration,
          object);
    }
    if (object instanceof Number || object instanceof Boolean) {
      return object;
    }
    return macrosTypeCoercer.coerce(
        cellNameResolver,
        filesystem,
        pathRelativeToProjectRoot,
        targetConfiguration,
        hostConfiguration,
        object);
  }
}
