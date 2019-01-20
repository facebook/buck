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
package com.facebook.buck.versions;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.VersionMatchedCollection;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class VersionMatchedCollectionTest {

  private static final BuildTarget A = BuildTargetFactory.newInstance("//:a");
  private static final BuildTarget B = BuildTargetFactory.newInstance("//:b");
  private static final Version V1 = Version.of("1.0");
  private static final Version V2 = Version.of("2.0");
  private static final VersionMatchedCollection<String> COLLECTION =
      VersionMatchedCollection.<String>builder()
          .add(ImmutableMap.of(A, V1, B, V1), "a-1.0,b-1.0")
          .add(ImmutableMap.of(A, V1, B, V2), "a-1.0,b-2.0")
          .add(ImmutableMap.of(A, V2, B, V1), "a-2.0,b-1.0")
          .add(ImmutableMap.of(A, V2, B, V2), "a-2.0,b-2.0")
          .build();

  private static final CellPathResolver CELL_PATH_RESOLVER =
      TestCellPathResolver.get(new FakeProjectFilesystem());

  @Test
  public void testGetMatchingValues() {
    assertThat(
        COLLECTION.getMatchingValues(ImmutableMap.of(A, V1)),
        Matchers.equalTo(ImmutableList.of("a-1.0,b-1.0", "a-1.0,b-2.0")));
    assertThat(
        COLLECTION.getMatchingValues(ImmutableMap.of(A, V2)),
        Matchers.equalTo(ImmutableList.of("a-2.0,b-1.0", "a-2.0,b-2.0")));
    assertThat(
        COLLECTION.getMatchingValues(ImmutableMap.of(B, V1)),
        Matchers.equalTo(ImmutableList.of("a-1.0,b-1.0", "a-2.0,b-1.0")));
    assertThat(
        COLLECTION.getMatchingValues(ImmutableMap.of(B, V2)),
        Matchers.equalTo(ImmutableList.of("a-1.0,b-2.0", "a-2.0,b-2.0")));
    assertThat(
        COLLECTION.getMatchingValues(ImmutableMap.of(A, V1, B, V1)),
        Matchers.equalTo(ImmutableList.of("a-1.0,b-1.0")));
    assertThat(
        COLLECTION.getMatchingValues(ImmutableMap.of(A, V1, B, V2)),
        Matchers.equalTo(ImmutableList.of("a-1.0,b-2.0")));
    assertThat(
        COLLECTION.getMatchingValues(ImmutableMap.of(A, V2, B, V1)),
        Matchers.equalTo(ImmutableList.of("a-2.0,b-1.0")));
    assertThat(
        COLLECTION.getMatchingValues(ImmutableMap.of(A, V2, B, V2)),
        Matchers.equalTo(ImmutableList.of("a-2.0,b-2.0")));
  }

  @Test
  public void testGetOnlyMatchingValue() {
    assertThat(
        COLLECTION.getOnlyMatchingValue("test", ImmutableMap.of(A, V1, B, V1)),
        Matchers.equalTo("a-1.0,b-1.0"));
  }

  @Test(expected = HumanReadableException.class)
  public void testGetOnlyMatchingValueThrowsOnTooManyValues() {
    System.out.println(COLLECTION.getOnlyMatchingValue("test", ImmutableMap.of(A, V1)));
  }

  @Test(expected = HumanReadableException.class)
  public void testGetOnlyMatchingValueThrowsOnNoMatches() {
    System.out.println(
        COLLECTION.getOnlyMatchingValue("test", ImmutableMap.of(A, Version.of("3.0"))));
  }

  @Test
  public void translatedTargets() {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildTarget newTarget = BuildTargetFactory.newInstance("//something:else");
    TargetNodeTranslator translator =
        new FixedTargetNodeTranslator(
            new DefaultTypeCoercerFactory(), ImmutableMap.of(target, newTarget));
    VersionMatchedCollection<BuildTarget> collection =
        VersionMatchedCollection.<BuildTarget>builder().add(ImmutableMap.of(), target).build();
    assertThat(
        translator
            .translate(CELL_PATH_RESOLVER, "", collection)
            .map(VersionMatchedCollection::getValues),
        Matchers.equalTo(Optional.of(ImmutableList.of(newTarget))));
  }

  @Test
  public void translatedTargetsInVersionMapAreNotTranslated() {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildTarget newTarget = BuildTargetFactory.newInstance("//something:else");
    TargetNodeTranslator translator =
        new FixedTargetNodeTranslator(
            new DefaultTypeCoercerFactory(), ImmutableMap.of(target, newTarget));
    VersionMatchedCollection<BuildTarget> collection =
        VersionMatchedCollection.<BuildTarget>builder()
            .add(ImmutableMap.of(target, V1), target)
            .build();
    assertThat(
        translator
            .translate(CELL_PATH_RESOLVER, "", collection)
            .map(VersionMatchedCollection::getValuePairs),
        Matchers.equalTo(
            Optional.of(ImmutableList.of(new Pair<>(ImmutableMap.of(target, V1), newTarget)))));
  }

  @Test
  public void untranslatedTargets() {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    TargetNodeTranslator translator =
        new FixedTargetNodeTranslator(new DefaultTypeCoercerFactory(), ImmutableMap.of());
    VersionMatchedCollection<BuildTarget> collection =
        VersionMatchedCollection.<BuildTarget>builder().add(ImmutableMap.of(), target).build();
    assertThat(
        translator.translate(CELL_PATH_RESOLVER, "", collection),
        Matchers.equalTo(Optional.empty()));
  }
}
