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

import static org.junit.Assert.assertSame;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.cell.nameresolver.TestCellNameResolver;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.ConstantHostTargetConfigurationResolver;
import com.facebook.buck.core.model.HostTargetConfigurationResolver;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import org.junit.Test;

public class ListTypeCoercerTest {

  @Test
  public void unconfiguredOnly() throws Exception {
    ListTypeCoercer<String, String> coercer = new ListTypeCoercer<>(new StringTypeCoercer());

    ImmutableList<String> list = ImmutableList.of("a", "b");

    ImmutableList<String> result =
        coercer.coerce(
            TestCellNameResolver.forRoot(),
            new FakeProjectFilesystem(),
            ForwardRelPath.EMPTY,
            UnconfiguredTargetConfiguration.INSTANCE,
            new ConstantHostTargetConfigurationResolver(UnconfiguredTargetConfiguration.INSTANCE),
            list);

    assertSame(list, result);
  }

  @Test
  public void rawToUnconfiguredOptimized() throws Exception {
    ListTypeCoercer<String, String> coercer = new ListTypeCoercer<>(new StringTypeCoercer());

    ImmutableList<String> raw = ImmutableList.of("aa", "bb");
    ImmutableList<String> unconfigured =
        coercer.coerceToUnconfigured(
            TestCellNameResolver.forRoot(), new FakeProjectFilesystem(), ForwardRelPath.EMPTY, raw);
    assertSame(raw, unconfigured);
  }

  private static class AnotherStringCoercer implements TypeCoercer<String, String> {

    @Override
    public TypeToken<String> getOutputType() {
      return TypeToken.of(String.class);
    }

    @Override
    public TypeToken<String> getUnconfiguredType() {
      return TypeToken.of(String.class);
    }

    @Override
    public SkylarkSpec getSkylarkSpec() {
      return () -> {
        throw new RuntimeException();
      };
    }

    @Override
    public boolean hasElementClass(Class<?>... types) {
      throw new AssertionError();
    }

    @Override
    public void traverseUnconfigured(
        CellNameResolver cellRoots, String object, Traversal traversal) {
      throw new AssertionError();
    }

    @Override
    public void traverse(CellNameResolver cellRoots, String object, Traversal traversal) {
      throw new AssertionError();
    }

    @Override
    public String coerceToUnconfigured(
        CellNameResolver cellRoots,
        ProjectFilesystem filesystem,
        ForwardRelPath pathRelativeToProjectRoot,
        Object object) {
      throw new AssertionError();
    }

    @Override
    public String coerce(
        CellNameResolver cellRoots,
        ProjectFilesystem filesystem,
        ForwardRelPath pathRelativeToProjectRoot,
        TargetConfiguration targetConfiguration,
        HostTargetConfigurationResolver hostConfigurationResolver,
        String object) {
      return object;
    }

    @Override
    public boolean unconfiguredToConfiguredCoercionIsIdentity() {
      // This coercer is effectively identity, but does not declare it.
      return false;
    }
  }

  @Test
  public void listTypeCoercerRuntimeIdentity() throws Exception {
    ListTypeCoercer<String, String> coercer = new ListTypeCoercer<>(new AnotherStringCoercer());

    ImmutableList<String> list = ImmutableList.of("a", "b", "c");

    ImmutableList<String> coerced =
        coercer.coerce(
            TestCellNameResolver.forRoot(),
            new FakeProjectFilesystem(),
            ForwardRelPath.EMPTY,
            UnconfiguredTargetConfiguration.INSTANCE,
            new ConstantHostTargetConfigurationResolver(UnconfiguredTargetConfiguration.INSTANCE),
            list);

    assertSame(coerced, list);
  }
}
