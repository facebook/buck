/*
 * Copyright 2016-present Facebook, Inc.
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

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.versions.FixedTargetNodeTranslator;
import com.facebook.buck.versions.TargetNodeTranslator;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class SourceListTest {

  private static final CellPathResolver CELL_PATH_RESOLVER =
      TestCellPathResolver.get(new FakeProjectFilesystem());
  private static final BuildTargetPatternParser<BuildTargetPattern> PATTERN =
      BuildTargetPatternParser.fullyQualified();

  @Test
  public void translatedNamedSourcesTargets() {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildTarget newTarget = BuildTargetFactory.newInstance("//something:else");
    TargetNodeTranslator translator =
        new FixedTargetNodeTranslator(
            new DefaultTypeCoercerFactory(), ImmutableMap.of(target, newTarget));
    assertThat(
        translator.translate(
            CELL_PATH_RESOLVER,
            PATTERN,
            SourceList.ofNamedSources(
                ImmutableSortedMap.of("name", DefaultBuildTargetSourcePath.of(target)))),
        Matchers.equalTo(
            Optional.of(
                SourceList.ofNamedSources(
                    ImmutableSortedMap.of("name", DefaultBuildTargetSourcePath.of(newTarget))))));
  }

  @Test
  public void untranslatedNamedSourcesTargets() {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    TargetNodeTranslator translator =
        new FixedTargetNodeTranslator(new DefaultTypeCoercerFactory(), ImmutableMap.of());
    SourceList list =
        SourceList.ofNamedSources(
            ImmutableSortedMap.of("name", DefaultBuildTargetSourcePath.of(target)));
    assertThat(
        translator.translate(CELL_PATH_RESOLVER, PATTERN, list),
        Matchers.equalTo(Optional.empty()));
  }

  @Test
  public void translatedUnnamedSourcesTargets() {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildTarget newTarget = BuildTargetFactory.newInstance("//something:else");
    TargetNodeTranslator translator =
        new FixedTargetNodeTranslator(
            new DefaultTypeCoercerFactory(), ImmutableMap.of(target, newTarget));
    assertThat(
        translator.translate(
            CELL_PATH_RESOLVER,
            PATTERN,
            SourceList.ofUnnamedSources(
                ImmutableSortedSet.of(DefaultBuildTargetSourcePath.of(target)))),
        Matchers.equalTo(
            Optional.of(
                SourceList.ofUnnamedSources(
                    ImmutableSortedSet.of(DefaultBuildTargetSourcePath.of(newTarget))))));
  }

  @Test
  public void untranslatedUnnamedSourcesTargets() {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    TargetNodeTranslator translator =
        new FixedTargetNodeTranslator(new DefaultTypeCoercerFactory(), ImmutableMap.of());
    SourceList list =
        SourceList.ofUnnamedSources(ImmutableSortedSet.of(DefaultBuildTargetSourcePath.of(target)));
    assertThat(
        translator.translate(CELL_PATH_RESOLVER, PATTERN, list),
        Matchers.equalTo(Optional.empty()));
  }
}
