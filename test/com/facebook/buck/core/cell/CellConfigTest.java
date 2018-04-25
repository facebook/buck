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

package com.facebook.buck.core.cell;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.name.RelativeCellName;
import com.facebook.buck.util.config.RawConfig;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
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
            .put(RelativeCellName.ROOT_CELL_NAME, section, "root_cell", "root")
            .put(RelativeCellName.ALL_CELLS_SPECIAL_NAME, section, "all_cells", "all")
            .put(RelativeCellName.fromComponents("cell"), section, "cell", "cell")
            .build();

    ImmutableMap<Path, RawConfig> overridesByPath =
        cellConfig.getOverridesByPath(
            ImmutableMap.of(
                RelativeCellName.ROOT_CELL_NAME,
                Paths.get("root"),
                RelativeCellName.fromComponents("cell"),
                Paths.get("cell")));

    assertThat(
        overridesByPath,
        Matchers.equalTo(
            ImmutableMap.of(
                Paths.get("root"),
                RawConfig.builder()
                    .put(section, "root_cell", "root")
                    .put(section, "all_cells", "all")
                    .build(),
                Paths.get("cell"),
                RawConfig.builder()
                    .put(section, "cell", "cell")
                    .put(section, "all_cells", "all")
                    .build())));
  }

  @Test
  public void throwsOnAmbiguousOverrides() throws Exception {
    String section = "section";
    CellConfig cellConfig =
        CellConfig.builder()
            .put(RelativeCellName.fromComponents("firstpath"), section, "cell", "cell")
            .build();

    expectedException.expect(CellConfig.MalformedOverridesException.class);
    expectedException.expectMessage(
        Matchers.stringContainsInOrder("root", "firstpath", "secondpath"));
    cellConfig.getOverridesByPath(
        ImmutableMap.of(
            RelativeCellName.ROOT_CELL_NAME,
            Paths.get("root"),
            RelativeCellName.fromComponents("firstpath"),
            Paths.get("cell"),
            RelativeCellName.fromComponents("root", "secondpath"),
            Paths.get("cell")));
  }

  @Test
  public void doesNotThrowWhenAmbiguousCellNotOverridden() throws Exception {
    String section = "section";
    CellConfig cellConfig =
        CellConfig.builder()
            .put(RelativeCellName.fromComponents("ok"), section, "cell", "cell")
            .build();

    cellConfig.getOverridesByPath(
        ImmutableMap.of(
            RelativeCellName.ROOT_CELL_NAME,
            Paths.get("root"),
            RelativeCellName.fromComponents("ok"),
            Paths.get("cell"),
            RelativeCellName.fromComponents("root", "firstpath"),
            Paths.get("bad"),
            RelativeCellName.fromComponents("root", "secondpath"),
            Paths.get("bad")));
  }

  @Test
  public void testThrowsOnUnknownCell() throws Exception {
    String section = "section";
    CellConfig cellConfig =
        CellConfig.builder()
            .put(RelativeCellName.fromComponents("unknown"), section, "cell", "cell")
            .build();

    expectedException.expect(CellConfig.MalformedOverridesException.class);
    expectedException.expectMessage(Matchers.stringContainsInOrder("unknown"));
    cellConfig.getOverridesByPath(
        ImmutableMap.of(
            RelativeCellName.ROOT_CELL_NAME,
            Paths.get("root"),
            RelativeCellName.fromComponents("somecell"),
            Paths.get("cell")));
  }
}
