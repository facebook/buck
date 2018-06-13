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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.rules.coercer.AbstractSourceList.Type;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.versions.FixedTargetNodeTranslator;
import com.facebook.buck.versions.TargetNodeTranslator;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Arrays;
import java.util.Collections;
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

  @Test
  public void testConcatOfEmptyListCreatesEmptyList() {
    assertTrue(SourceList.concat(Collections.emptyList()).isEmpty());
  }

  @Test
  public void testConcatOfDifferentTypesThrowsExceptionForUnnamed() {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    SourceList unnamedList =
        SourceList.ofUnnamedSources(ImmutableSortedSet.of(DefaultBuildTargetSourcePath.of(target)));
    SourceList namedList =
        SourceList.ofNamedSources(
            ImmutableSortedMap.of("name", DefaultBuildTargetSourcePath.of(target)));

    try {
      SourceList.concat(Arrays.asList(unnamedList, namedList));
    } catch (IllegalStateException e) {
      assertEquals("Expected unnamed source list, got: NAMED", e.getMessage());
    }
  }

  @Test
  public void testConcatOfDifferentTypesThrowsExceptionForNamed() {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    SourceList unnamedList =
        SourceList.ofUnnamedSources(ImmutableSortedSet.of(DefaultBuildTargetSourcePath.of(target)));
    SourceList namedList =
        SourceList.ofNamedSources(
            ImmutableSortedMap.of("name", DefaultBuildTargetSourcePath.of(target)));

    try {
      SourceList.concat(Arrays.asList(namedList, unnamedList));
    } catch (IllegalStateException e) {
      assertEquals("Expected named source list, got: UNNAMED", e.getMessage());
    }
  }

  @Test
  public void testConcatOfSameTypesReturnsCompleteListForUnnamed() {
    BuildTarget target1 = BuildTargetFactory.newInstance("//:rule1");
    BuildTarget target2 = BuildTargetFactory.newInstance("//:rule2");
    SourcePath sourcePath1 = DefaultBuildTargetSourcePath.of(target1);
    SourcePath sourcePath2 = DefaultBuildTargetSourcePath.of(target2);
    SourceList unnamedList1 = SourceList.ofUnnamedSources(ImmutableSortedSet.of(sourcePath1));
    SourceList unnamedList2 = SourceList.ofUnnamedSources(ImmutableSortedSet.of(sourcePath2));

    SourceList result = SourceList.concat(Arrays.asList(unnamedList1, unnamedList2));

    assertEquals(Type.UNNAMED, result.getType());
    assertEquals(2, result.getUnnamedSources().get().size());
    assertEquals(sourcePath1, result.getUnnamedSources().get().first());
    assertEquals(sourcePath2, result.getUnnamedSources().get().last());
  }

  @Test
  public void testConcatOfSameTypesReturnsCompleteListForNamed() {
    BuildTarget target1 = BuildTargetFactory.newInstance("//:rule1");
    BuildTarget target2 = BuildTargetFactory.newInstance("//:rule2");
    SourcePath sourcePath1 = DefaultBuildTargetSourcePath.of(target1);
    SourcePath sourcePath2 = DefaultBuildTargetSourcePath.of(target2);

    SourceList namedList1 = SourceList.ofNamedSources(ImmutableSortedMap.of("name1", sourcePath1));
    SourceList namedList2 = SourceList.ofNamedSources(ImmutableSortedMap.of("name2", sourcePath2));

    SourceList result = SourceList.concat(Arrays.asList(namedList1, namedList2));

    assertEquals(Type.NAMED, result.getType());
    assertEquals(2, result.getNamedSources().get().size());
    assertEquals(sourcePath1, result.getNamedSources().get().get("name1"));
    assertEquals(sourcePath2, result.getNamedSources().get().get("name2"));
  }
}
