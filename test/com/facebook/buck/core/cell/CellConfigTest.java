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

package com.facebook.buck.core.cell;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.util.config.RawConfig;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class CellConfigTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void getOverridesByPath() throws Exception {
    String section = "section";
    CellConfig cellConfig =
        CellConfig.builder()
            .put(CellName.ROOT_CELL_NAME, section, "root_cell", "root")
            .put(CellName.ALL_CELLS_SPECIAL_NAME, section, "all_cells", "all")
            .put(CellName.of("cell"), section, "cell", "cell")
            .build();

    ImmutableMap<AbsPath, RawConfig> overridesByPath =
        cellConfig.getOverridesByPath(
            ImmutableMap.of(
                CellName.ROOT_CELL_NAME,
                AbsPath.of(Paths.get("root").toAbsolutePath()),
                CellName.of("cell"),
                AbsPath.of(Paths.get("cell").toAbsolutePath())));

    assertThat(
        overridesByPath,
        Matchers.equalTo(
            ImmutableMap.of(
                AbsPath.of(Paths.get("root").toAbsolutePath()),
                RawConfig.builder()
                    .put(section, "root_cell", "root")
                    .put(section, "all_cells", "all")
                    .build(),
                AbsPath.of(Paths.get("cell").toAbsolutePath()),
                RawConfig.builder()
                    .put(section, "cell", "cell")
                    .put(section, "all_cells", "all")
                    .build())));
  }

  @Test
  public void throwsOnAmbiguousOverrides() throws Exception {
    String section = "section";
    CellConfig cellConfig =
        CellConfig.builder().put(CellName.of("firstpath"), section, "cell", "cell").build();

    expectedException.expect(InvalidCellOverrideException.class);
    expectedException.expectMessage(
        Matchers.stringContainsInOrder("root", "firstpath", "secondpath"));
    cellConfig.getOverridesByPath(
        ImmutableMap.of(
            CellName.ROOT_CELL_NAME,
            AbsPath.of(Paths.get("root").toAbsolutePath()),
            CellName.of("firstpath"),
            AbsPath.of(Paths.get("cell").toAbsolutePath()),
            CellName.of("secondpath"),
            AbsPath.of(Paths.get("cell").toAbsolutePath())));
  }

  @Test
  public void doesNotThrowWhenAmbiguousCellNotOverridden() throws Exception {
    String section = "section";
    CellConfig cellConfig =
        CellConfig.builder().put(CellName.of("ok"), section, "cell", "cell").build();

    cellConfig.getOverridesByPath(
        ImmutableMap.of(
            CellName.ROOT_CELL_NAME,
            AbsPath.of(Paths.get("root").toAbsolutePath()),
            CellName.of("ok"),
            AbsPath.of(Paths.get("cell").toAbsolutePath()),
            CellName.of("firstpath"),
            AbsPath.of(Paths.get("bad").toAbsolutePath()),
            CellName.of("secondpath"),
            AbsPath.of(Paths.get("bad").toAbsolutePath())));
  }

  @Test
  public void testThrowsOnUnknownCell() throws Exception {
    String section = "section";
    CellConfig cellConfig =
        CellConfig.builder().put(CellName.of("unknown"), section, "cell", "cell").build();

    expectedException.expect(InvalidCellOverrideException.class);
    expectedException.expectMessage(Matchers.stringContainsInOrder("unknown"));
    cellConfig.getOverridesByPath(
        ImmutableMap.of(
            CellName.ROOT_CELL_NAME,
            AbsPath.of(Paths.get("root").toAbsolutePath()),
            CellName.of("somecell"),
            AbsPath.of(Paths.get("cell").toAbsolutePath())));
  }
}
