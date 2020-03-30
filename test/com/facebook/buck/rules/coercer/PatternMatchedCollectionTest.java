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

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.versions.FixedTargetNodeTranslator;
import com.facebook.buck.versions.TargetNodeTranslator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Test;

public class PatternMatchedCollectionTest {

  private static final CellPathResolver CELL_PATH_RESOLVER =
      TestCellPathResolver.get(new FakeProjectFilesystem());

  @Test
  public void translatedTargets() {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildTarget newTarget = BuildTargetFactory.newInstance("//something:else");
    TargetNodeTranslator translator =
        new FixedTargetNodeTranslator(
            new DefaultTypeCoercerFactory(),
            ImmutableMap.of(target, newTarget),
            new TestCellBuilder().build());
    PatternMatchedCollection<BuildTarget> collection =
        PatternMatchedCollection.<BuildTarget>builder()
            .add(Pattern.compile("something"), target)
            .build();
    assertThat(
        translator
            .translate(CELL_PATH_RESOLVER.getCellNameResolver(), BaseName.ROOT, collection)
            .map(PatternMatchedCollection::getValues),
        Matchers.equalTo(Optional.of(ImmutableList.of(newTarget))));
  }

  @Test
  public void untranslatedTargets() {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    TargetNodeTranslator translator =
        new FixedTargetNodeTranslator(
            new DefaultTypeCoercerFactory(), ImmutableMap.of(), new TestCellBuilder().build());
    PatternMatchedCollection<BuildTarget> collection =
        PatternMatchedCollection.<BuildTarget>builder()
            .add(Pattern.compile("something"), target)
            .build();
    assertThat(
        translator.translate(CELL_PATH_RESOLVER.getCellNameResolver(), BaseName.ROOT, collection),
        Matchers.equalTo(Optional.empty()));
  }
}
