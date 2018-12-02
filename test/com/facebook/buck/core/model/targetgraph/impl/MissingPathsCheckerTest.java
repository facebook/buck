/*
 * Copyright 2018-present Facebook, Inc.
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
package com.facebook.buck.core.model.targetgraph.impl;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class MissingPathsCheckerTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testCheckPathsThrowsWithNonExistingPath() {
    MissingPathsChecker checker = new MissingPathsChecker();

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("//:a references non-existing file or directory 'b'");

    checker.checkPaths(
        new FakeProjectFilesystem(),
        BuildTargetFactory.newInstance("//:a"),
        ImmutableSet.of(Paths.get("b")));
  }

  @Test
  public void testCheckPathsPassesWithExistingPath() {
    MissingPathsChecker checker = new MissingPathsChecker();

    ImmutableSet<Path> paths = ImmutableSet.of(Paths.get("b"));

    checker.checkPaths(
        new FakeProjectFilesystem(paths), BuildTargetFactory.newInstance("//:a"), paths);
  }
}
