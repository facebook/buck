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

package com.facebook.buck.util.autosparse;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class AbstractAutosparseFactoryTest {
  @Test
  public void sameSparseStateForSameHgRoot() throws InterruptedException {
    Path projectPathBar = Paths.get("/project/path/foo/bar");
    Path projectPathBaz = Paths.get("/project/path/foo/baz");
    Path hgRoot = Paths.get("/project/path/foo");
    AutoSparseConfig config = AutoSparseConfig.of(true, ImmutableList.<Path>of());

    FakeHgCommandlineInterface hgFake = new FakeHgCommandlineInterface(hgRoot, "hamspam");

    AutoSparseState state1 =
        AbstractAutoSparseFactory.getAutoSparseState(
            projectPathBar, projectPathBar.resolve("buck-out"), hgFake, config);
    AutoSparseState state2 =
        AbstractAutoSparseFactory.getAutoSparseState(
            projectPathBaz, projectPathBaz.resolve("buck-out"), hgFake, config);
    assertSame(state1, state2);
  }

  @Test
  public void newStateForDifferentRevisionId() throws InterruptedException {
    Path projectPathBar = Paths.get("/project/path/foo/bar");
    Path hgRoot = Paths.get("/project/path/foo");
    AutoSparseConfig config = AutoSparseConfig.of(true, ImmutableList.<Path>of());

    FakeHgCommandlineInterface hgFake1 = new FakeHgCommandlineInterface(hgRoot, "hamspam");
    AutoSparseState state1 =
        AbstractAutoSparseFactory.getAutoSparseState(
            projectPathBar, projectPathBar.resolve("buck-out"), hgFake1, config);

    FakeHgCommandlineInterface hgFake2 =
        new FakeHgCommandlineInterface(hgRoot, "differentrevisionid");
    AutoSparseState state2 =
        AbstractAutoSparseFactory.getAutoSparseState(
            projectPathBar, projectPathBar.resolve("buck-out"), hgFake2, config);

    assertNotSame(state1, state2);
  }

  @Test
  public void noHgRootNoState() throws InterruptedException {
    Path projectPathBar = Paths.get("/project/path/foo/bar");
    Path hgRoot = null;
    AutoSparseConfig config = AutoSparseConfig.of(true, ImmutableList.<Path>of());

    FakeHgCommandlineInterface hgFake = new FakeHgCommandlineInterface(hgRoot, "hamspam");
    AutoSparseState state =
        AbstractAutoSparseFactory.getAutoSparseState(
            projectPathBar, projectPathBar.resolve("buck-out"), hgFake, config);
    assertNull(state);
  }
}
