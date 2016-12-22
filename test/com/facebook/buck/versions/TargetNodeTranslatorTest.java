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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cxx.CxxLibraryBuilder;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.TargetNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.util.Optional;

public class TargetNodeTranslatorTest {

  @Test
  public void translate() {
    BuildTarget a = BuildTargetFactory.newInstance("//:a");
    BuildTarget b = BuildTargetFactory.newInstance("//:b");
    BuildTarget c = BuildTargetFactory.newInstance("//:c");
    final BuildTarget d = BuildTargetFactory.newInstance("//:d");
    TargetNode<CxxLibraryDescription.Arg, ?> node =
        new CxxLibraryBuilder(a)
            .setDeps(ImmutableSortedSet.of(b))
            .setExportedDeps(ImmutableSortedSet.of(c))
            .build();
    TargetNodeTranslator translator =
        new TargetNodeTranslator() {
          @Override
          public Optional<BuildTarget> translateBuildTarget(BuildTarget target) {
            return Optional.of(d);
          }
          @Override
          public Optional<ImmutableMap<BuildTarget, Version>> getSelectedVersions(
              BuildTarget target) {
            return Optional.empty();
          }
        };
    Optional<TargetNode<CxxLibraryDescription.Arg, ?>> translated = translator.translateNode(node);
    assertThat(
        translated.get().getBuildTarget(),
        Matchers.equalTo(d));
    assertThat(
        translated.get().getDeclaredDeps(),
        Matchers.equalTo(ImmutableSet.of(d)));
    assertThat(
        translated.get().getExtraDeps(),
        Matchers.equalTo(ImmutableSet.of(d)));
    assertThat(
        translated.get().getConstructorArg().deps,
        Matchers.equalTo(ImmutableSortedSet.of(d)));
    assertThat(
        translated.get().getConstructorArg().exportedDeps,
        Matchers.equalTo(ImmutableSortedSet.of(d)));
  }

  @Test
  public void noTranslate() {
    BuildTarget a = BuildTargetFactory.newInstance("//:a");
    BuildTarget b = BuildTargetFactory.newInstance("//:b");
    BuildTarget c = BuildTargetFactory.newInstance("//:c");
    TargetNode<CxxLibraryDescription.Arg, ?> node =
        new CxxLibraryBuilder(a)
            .setDeps(ImmutableSortedSet.of(b))
            .setExportedDeps(ImmutableSortedSet.of(c))
            .build();
    TargetNodeTranslator translator =
        new TargetNodeTranslator() {
          @Override
          public Optional<BuildTarget> translateBuildTarget(BuildTarget target) {
            return Optional.empty();
          }
          @Override
          public Optional<ImmutableMap<BuildTarget, Version>> getSelectedVersions(
              BuildTarget target) {
            return Optional.empty();
          }
        };
    Optional<TargetNode<CxxLibraryDescription.Arg, ?>> translated = translator.translateNode(node);
    assertFalse(translated.isPresent());
  }

  @Test
  public void selectedVersions() {
    TargetNode<VersionPropagatorBuilder.Arg, ?> node =
        new VersionPropagatorBuilder("//:a")
            .build();
    final ImmutableMap<BuildTarget, Version> selectedVersions =
        ImmutableMap.of(
            BuildTargetFactory.newInstance("//:b"),
            Version.of("1.0"));
    TargetNodeTranslator translator =
        new TargetNodeTranslator() {
          @Override
          public Optional<BuildTarget> translateBuildTarget(BuildTarget target) {
            return Optional.empty();
          }
          @Override
          public Optional<ImmutableMap<BuildTarget, Version>> getSelectedVersions(
              BuildTarget target) {
            return Optional.of(selectedVersions);
          }
        };
    Optional<TargetNode<VersionPropagatorBuilder.Arg, ?>> translated =
        translator.translateNode(node);
    assertTrue(translated.isPresent());
    assertThat(
        translated.get().getSelectedVersions(),
        Matchers.equalTo(Optional.of(selectedVersions)));
  }

  @Test
  public void translatePair() {
    BuildTarget a = BuildTargetFactory.newInstance("//:a");
    BuildTarget b = BuildTargetFactory.newInstance("//:b");
    TargetNodeTranslator translator =
        new TargetNodeTranslator() {
          @Override
          public Optional<BuildTarget> translateBuildTarget(BuildTarget target) {
            return Optional.of(b);
          }
          @Override
          public Optional<ImmutableMap<BuildTarget, Version>> getSelectedVersions(
              BuildTarget target) {
            return Optional.empty();
          }
        };
    assertThat(
        translator.translatePair(new Pair<>("hello", a)),
        Matchers.equalTo(Optional.of(new Pair<>("hello", b))));
  }

  @Test
  public void translateBuildTargetSourcePath() {
    BuildTarget a = BuildTargetFactory.newInstance("//:a");
    BuildTarget b = BuildTargetFactory.newInstance("//:b");
    TargetNodeTranslator translator =
        new TargetNodeTranslator() {
          @Override
          public Optional<BuildTarget> translateBuildTarget(BuildTarget target) {
            return Optional.of(b);
          }
          @Override
          public Optional<ImmutableMap<BuildTarget, Version>> getSelectedVersions(
              BuildTarget target) {
            return Optional.empty();
          }
        };
    assertThat(
        translator.translateBuildTargetSourcePath(new BuildTargetSourcePath(a)),
        Matchers.equalTo(Optional.of(new BuildTargetSourcePath(b))));
  }

  @Test
  public void translateSourceWithFlags() {
    BuildTarget a = BuildTargetFactory.newInstance("//:a");
    BuildTarget b = BuildTargetFactory.newInstance("//:b");
    TargetNodeTranslator translator =
        new TargetNodeTranslator() {
          @Override
          public Optional<BuildTarget> translateBuildTarget(BuildTarget target) {
            return Optional.of(b);
          }
          @Override
          public Optional<ImmutableMap<BuildTarget, Version>> getSelectedVersions(
              BuildTarget target) {
            return Optional.empty();
          }
        };
    assertThat(
        translator.translateSourceWithFlags(
            SourceWithFlags.of(new BuildTargetSourcePath(a), ImmutableList.of("-flag"))),
        Matchers.equalTo(
            Optional.of(
                SourceWithFlags.of(new BuildTargetSourcePath(b), ImmutableList.of("-flag")))));
  }

}
