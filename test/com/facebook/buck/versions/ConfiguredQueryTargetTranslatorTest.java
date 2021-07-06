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

package com.facebook.buck.versions;

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.CellPathResolverView;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.cell.nameresolver.TestCellNameResolver;
import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.query.Query;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ConfiguredQueryTargetTranslatorTest {

  private static final ProjectFilesystem FILESYSTEM = new FakeProjectFilesystem(Paths.get("/root"));
  private static final ProjectFilesystem SUBCELL_FILESYSTEM =
      new FakeProjectFilesystem(Paths.get("/subcell"));
  private static final CellPathResolver CELL_PATH_RESOLVER =
      TestCellPathResolver.create(
          FILESYSTEM.getRootPath(),
          ImmutableMap.of(
              "root", FILESYSTEM.getRootPath().getPath(),
              "subcell", SUBCELL_FILESYSTEM.getRootPath().getPath()));
  private static final CellPathResolver SUBCELL_CELL_PATH_RESOLVER =
      new CellPathResolverView(
          CELL_PATH_RESOLVER,
          TestCellNameResolver.forSecondary("subcell", Optional.of("root")),
          ImmutableSet.of("root", "subcell"),
          SUBCELL_FILESYSTEM.getRootPath());

  @Test
  public void translateTargets() {
    BuildTarget a = BuildTargetFactory.newInstance("//:a");
    BuildTarget b = BuildTargetFactory.newInstance("//:b");
    FixedTargetNodeTranslator translator =
        new FixedTargetNodeTranslator(
            new DefaultTypeCoercerFactory(), ImmutableMap.of(a, b), new TestCellBuilder().build());
    QueryTargetTranslator queryTranslator =
        new QueryTargetTranslator(new ParsingUnconfiguredBuildTargetViewFactory());
    assertThat(
        queryTranslator.translateTargets(
            CELL_PATH_RESOLVER.getCellNameResolver(),
            BaseName.ROOT,
            translator,
            Query.of("deps(//:a)", UnconfiguredTargetConfiguration.INSTANCE, BaseName.ROOT)),
        Matchers.equalTo(
            Optional.of(
                Query.of("deps(//:b)", UnconfiguredTargetConfiguration.INSTANCE, BaseName.ROOT))));
  }

  @Test
  public void translateRelativeTargets() {
    BuildTarget a = BuildTargetFactory.newInstance("//foo:a");
    BuildTarget b = BuildTargetFactory.newInstance("//bar:b");
    FixedTargetNodeTranslator translator =
        new FixedTargetNodeTranslator(
            new DefaultTypeCoercerFactory(), ImmutableMap.of(a, b), new TestCellBuilder().build());
    QueryTargetTranslator queryTranslator =
        new QueryTargetTranslator(new ParsingUnconfiguredBuildTargetViewFactory());
    assertThat(
        queryTranslator.translateTargets(
            CELL_PATH_RESOLVER.getCellNameResolver(),
            BaseName.of("//foo"),
            translator,
            Query.of(
                "deps(set(:a \":a\" ':a' :a_suffix ':a_suffix' \":a_suffix\" //blah:a))",
                UnconfiguredTargetConfiguration.INSTANCE,
                BaseName.ROOT)),
        Matchers.equalTo(
            Optional.of(
                Query.of(
                    "deps(set(//bar:b \"//bar:b\" '//bar:b' :a_suffix ':a_suffix' \":a_suffix\" //blah:a))",
                    UnconfiguredTargetConfiguration.INSTANCE,
                    BaseName.ROOT))));
  }

  @Test
  public void noTargets() {
    FixedTargetNodeTranslator translator =
        new FixedTargetNodeTranslator(
            new DefaultTypeCoercerFactory(), ImmutableMap.of(), new TestCellBuilder().build());
    QueryTargetTranslator queryTranslator =
        new QueryTargetTranslator(new ParsingUnconfiguredBuildTargetViewFactory());
    assertThat(
        queryTranslator.translateTargets(
            CELL_PATH_RESOLVER.getCellNameResolver(),
            BaseName.ROOT,
            translator,
            Query.of("$declared_deps", UnconfiguredTargetConfiguration.INSTANCE, BaseName.ROOT)),
        Matchers.equalTo(Optional.empty()));
  }

  @Test
  public void translateFullTargets() {
    BuildTarget a = BuildTargetFactory.newInstance("//foo:a");
    BuildTarget b = BuildTargetFactory.newInstance("//bar:b");
    FixedTargetNodeTranslator translator =
        new FixedTargetNodeTranslator(
            new DefaultTypeCoercerFactory(), ImmutableMap.of(a, b), new TestCellBuilder().build());
    QueryTargetTranslator queryTranslator =
        new QueryTargetTranslator(new ParsingUnconfiguredBuildTargetViewFactory());
    assertThat(
        queryTranslator.translateTargets(
            CELL_PATH_RESOLVER.getCellNameResolver(),
            BaseName.of("//foo"),
            translator,
            Query.of(
                "deps(set(//foo:a \"//foo:a\" '//foo:a' //foo:a_suffix '//foo:a_suffix' \"//foo:a_suffix\" //blah:a))",
                UnconfiguredTargetConfiguration.INSTANCE,
                BaseName.ROOT)),
        Matchers.equalTo(
            Optional.of(
                Query.of(
                    "deps(set(//bar:b \"//bar:b\" '//bar:b' //foo:a_suffix '//foo:a_suffix' \"//foo:a_suffix\" //blah:a))",
                    UnconfiguredTargetConfiguration.INSTANCE,
                    BaseName.ROOT))));
  }

  @Test
  public void translateOtherCellTargets() {
    BuildTarget a = BuildTargetFactory.newInstance("subcell//:a");
    BuildTarget b = BuildTargetFactory.newInstance("subcell//:b");
    FixedTargetNodeTranslator translator =
        new FixedTargetNodeTranslator(
            new DefaultTypeCoercerFactory(), ImmutableMap.of(a, b), new TestCellBuilder().build());
    QueryTargetTranslator queryTranslator =
        new QueryTargetTranslator(new ParsingUnconfiguredBuildTargetViewFactory());
    assertThat(
        queryTranslator.translateTargets(
            SUBCELL_CELL_PATH_RESOLVER.getCellNameResolver(),
            BaseName.ROOT,
            translator,
            Query.of("deps(//:a)", UnconfiguredTargetConfiguration.INSTANCE, BaseName.ROOT)),
        Matchers.equalTo(
            Optional.of(
                Query.of(
                    "deps(subcell//:b)",
                    UnconfiguredTargetConfiguration.INSTANCE,
                    BaseName.ROOT))));
  }
}
