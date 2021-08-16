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
import com.facebook.buck.core.test.rule.TestRunnerSpec;
import com.facebook.buck.core.test.rule.UnconfiguredTestRunnerSpec;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.UnconfiguredStringWithMacros;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import java.util.Map;

/**
 * Coerces a freeform JSON as a {@link TestRunnerSpec}, which is basically a JSON containing {@link
 * StringWithMacros}
 */
public class TestRunnerSpecCoercer
    implements TypeCoercer<UnconfiguredTestRunnerSpec, TestRunnerSpec> {

  private final TypeCoercer<UnconfiguredStringWithMacros, StringWithMacros> macrosTypeCoercer;
  private final TypeCoercer<
          ImmutableMap<UnconfiguredStringWithMacros, UnconfiguredTestRunnerSpec>,
          ImmutableMap<StringWithMacros, TestRunnerSpec>>
      mapTypeCoercer;
  private final ListTypeCoercer<UnconfiguredTestRunnerSpec, TestRunnerSpec> listTypeCoercer;

  public TestRunnerSpecCoercer(
      TypeCoercer<UnconfiguredStringWithMacros, StringWithMacros> macrosTypeCoercer) {
    this.macrosTypeCoercer = macrosTypeCoercer;
    this.mapTypeCoercer = new MapTypeCoercer<>(macrosTypeCoercer, this);
    this.listTypeCoercer = new ListTypeCoercer<>(this);
  }

  @Override
  public SkylarkSpec getSkylarkSpec() {
    return () -> "attr.arg(json=True)";
  }

  @Override
  public TypeToken<TestRunnerSpec> getOutputType() {
    return TypeToken.of(TestRunnerSpec.class);
  }

  @Override
  public TypeToken<UnconfiguredTestRunnerSpec> getUnconfiguredType() {
    return TypeToken.of(UnconfiguredTestRunnerSpec.class);
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return false;
  }

  @Override
  public void traverseUnconfigured(
      CellNameResolver cellRoots, UnconfiguredTestRunnerSpec object, Traversal traversal) {}

  @Override
  public void traverse(CellNameResolver cellRoots, TestRunnerSpec object, Traversal traversal) {}

  @Override
  public UnconfiguredTestRunnerSpec coerceToUnconfigured(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelPath pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (object instanceof Map) {
      return UnconfiguredTestRunnerSpec.ofMap(
          mapTypeCoercer.coerceToUnconfigured(
              cellRoots, filesystem, pathRelativeToProjectRoot, object));
    }
    if (object instanceof Iterable) {
      return UnconfiguredTestRunnerSpec.ofList(
          listTypeCoercer.coerceToUnconfigured(
              cellRoots, filesystem, pathRelativeToProjectRoot, object));
    }
    if (object instanceof Number) {
      return UnconfiguredTestRunnerSpec.ofNumber((Number) object);
    } else if (object instanceof Boolean) {
      return UnconfiguredTestRunnerSpec.ofBoolean((Boolean) object);
    } else {
      return UnconfiguredTestRunnerSpec.ofStringWithMacros(
          macrosTypeCoercer.coerceToUnconfigured(
              cellRoots, filesystem, pathRelativeToProjectRoot, object));
    }
  }

  @Override
  public TestRunnerSpec coerce(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelPath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfigurationResolver hostConfigurationResolver,
      UnconfiguredTestRunnerSpec object)
      throws CoerceFailedException {
    return object.match(
        new UnconfiguredTestRunnerSpec.Matcher<TestRunnerSpec, CoerceFailedException>() {
          @Override
          public TestRunnerSpec map(
              ImmutableMap<UnconfiguredStringWithMacros, UnconfiguredTestRunnerSpec> map)
              throws CoerceFailedException {
            return TestRunnerSpec.ofMap(
                mapTypeCoercer.coerce(
                    cellRoots,
                    filesystem,
                    pathRelativeToProjectRoot,
                    targetConfiguration,
                    hostConfigurationResolver,
                    map));
          }

          @Override
          public TestRunnerSpec list(ImmutableList<UnconfiguredTestRunnerSpec> list)
              throws CoerceFailedException {
            return TestRunnerSpec.ofList(
                listTypeCoercer.coerce(
                    cellRoots,
                    filesystem,
                    pathRelativeToProjectRoot,
                    targetConfiguration,
                    hostConfigurationResolver,
                    list));
          }

          @Override
          public TestRunnerSpec stringWithMacros(UnconfiguredStringWithMacros stringWithMacros)
              throws CoerceFailedException {
            return TestRunnerSpec.ofStringWithMacros(
                macrosTypeCoercer.coerce(
                    cellRoots,
                    filesystem,
                    pathRelativeToProjectRoot,
                    targetConfiguration,
                    hostConfigurationResolver,
                    stringWithMacros));
          }

          @Override
          public TestRunnerSpec number(Number number) {
            return TestRunnerSpec.ofNumber(number);
          }

          @Override
          public TestRunnerSpec bool(boolean b) {
            return TestRunnerSpec.ofBoolean(b);
          }
        });
  }
}
