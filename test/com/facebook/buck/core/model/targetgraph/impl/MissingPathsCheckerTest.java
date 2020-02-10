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

package com.facebook.buck.core.model.targetgraph.impl;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
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
        ImmutableSet.of(ForwardRelativePath.of("b")));
  }

  @Test
  public void testCheckPathsPassesWithExistingPath() {
    MissingPathsChecker checker = new MissingPathsChecker();

    checker.checkPaths(
        new FakeProjectFilesystem(ImmutableSet.of(Paths.get("b"))),
        BuildTargetFactory.newInstance("//:a"),
        ImmutableSet.of(ForwardRelativePath.of("b")));
  }

  @Test
  public void testCheckPathsThrowsErrorForNonMissingFileErrors() {
    ProjectFilesystem filesystem =
        new FakeProjectFilesystem(ImmutableSet.of(Paths.get("b"))) {
          @Override
          public <A extends BasicFileAttributes> A readAttributes(
              Path pathRelativeToProjectRoot, Class<A> type, LinkOption... options)
              throws IOException {
            throw new AccessDeniedException("cannot access file");
          }
        };

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("//:a references inaccessible file or directory 'b'");

    MissingPathsChecker checker = new MissingPathsChecker();
    checker.checkPaths(
        filesystem,
        BuildTargetFactory.newInstance("//:a"),
        ImmutableSet.of(ForwardRelativePath.of("b")));
  }
}
