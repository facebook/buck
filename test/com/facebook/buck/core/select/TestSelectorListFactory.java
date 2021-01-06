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

package com.facebook.buck.core.select;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.ConfigurationBuildTargetFactoryForTests;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Map;

public class TestSelectorListFactory {

  public static <T> SelectorList<T> createSelectorListForCoercer(
      TypeCoercer<?, T> elementTypeCoercer, Map<String, ?>... selectors)
      throws CoerceFailedException {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    ImmutableList.Builder<Selector<T>> selectorBuilder = ImmutableList.builder();
    for (Map<String, ?> selectorAttributes : selectors) {
      ImmutableMap.Builder<SelectorKey, T> conditions = ImmutableMap.builder();

      for (Map.Entry<String, ?> condition : selectorAttributes.entrySet()) {
        SelectorKey key =
            condition.getKey().equals(SelectorKey.DEFAULT_KEYWORD)
                ? SelectorKey.DEFAULT
                : new SelectorKey(
                    ConfigurationBuildTargetFactoryForTests.newInstance(condition.getKey()));
        conditions.put(
            key,
            elementTypeCoercer.coerceBoth(
                TestCellPathResolver.get(projectFilesystem).getCellNameResolver(),
                projectFilesystem,
                ForwardRelativePath.of(""),
                UnconfiguredTargetConfiguration.INSTANCE,
                UnconfiguredTargetConfiguration.INSTANCE,
                condition.getValue()));
      }

      selectorBuilder.add(new Selector<>(conditions.build(), ImmutableSet.of(), ""));
    }
    return new SelectorList<>(selectorBuilder.build());
  }
}
