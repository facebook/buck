/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.select;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetFactory;
import com.facebook.buck.core.select.impl.SelectorFactory;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.facebook.buck.rules.coercer.UnconfiguredBuildTargetTypeCoercer;
import com.google.common.collect.ImmutableList;
import java.util.Map;

public class TestSelectorListFactory {

  public static <T> SelectorList<T> createSelectorListForCoercer(
      TypeCoercer<T> elementTypeCoercer, Map<String, ?>... selectors) throws CoerceFailedException {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    SelectorFactory selectorFactory =
        new SelectorFactory(
            new UnconfiguredBuildTargetTypeCoercer(new ParsingUnconfiguredBuildTargetFactory()));
    ImmutableList.Builder<Selector<T>> selectorBuilder = ImmutableList.builder();
    for (Map<String, ?> selectorAttributes : selectors) {
      Selector<T> selector =
          selectorFactory.createSelector(
              TestCellPathResolver.get(projectFilesystem),
              projectFilesystem,
              projectFilesystem.getRootPath(),
              EmptyTargetConfiguration.INSTANCE,
              selectorAttributes,
              elementTypeCoercer);
      selectorBuilder.add(selector);
    }
    return new SelectorList<>(elementTypeCoercer, selectorBuilder.build());
  }
}
