/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;

public class RepositoryTest {

  @Test
  public void shouldReturnItselfIfRequestedToGetARepoWithAnAbsentOptionalName()
      throws IOException, InterruptedException {
    Repository repo = new TestRepositoryBuilder().build();

    Repository target = repo.getRepository(Optional.<String>absent());

    assertSame(repo, target);
  }

  @Test(expected = HumanReadableException.class)
  public void shouldThrowAnExceptionIfTheNamedRepoIsNotPresent()
      throws IOException, InterruptedException {
    Repository repo = new TestRepositoryBuilder().build();

    repo.getRepository(Optional.of("not-there"));
  }

  @Test
  public void shouldResolveNamesOfReposAgainstThoseGivenInTheBuckConfig()
      throws IOException, InterruptedException {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());

    Path root = vfs.getPath("/opt/local/");
    Path repo1 = root.resolve("repo1");
    Files.createDirectories(repo1);
    Path repo2 = root.resolve("repo2");
    Files.createDirectories(repo2);

    ProjectFilesystem filesystem = new ProjectFilesystem(repo1.toAbsolutePath());
    FakeBuckConfig config = new FakeBuckConfig(
        filesystem,
        "[repositories]",
        "example = " + repo2.toAbsolutePath().toString());

    Repository repo = new TestRepositoryBuilder().setBuckConfig(config).setFilesystem(
        filesystem).build();
    Repository other = repo.getRepository(Optional.of("example"));

    assertEquals(repo2, other.getFilesystem().getRootPath());
  }
}
