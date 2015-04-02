/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

public class RepositoryFactoryTest {

  @ClassRule
  public static TemporaryFolder folder = new TemporaryFolder();

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Rule
  public DebuggableTemporaryFolder mainProjectFolder = new DebuggableTemporaryFolder();

  @Rule
  public DebuggableTemporaryFolder subProjectFolder = new DebuggableTemporaryFolder();

  @Test
  public void testRepositoryCanonicalNames() throws IOException, InterruptedException {
    String mainBuckConfigString =
        "[repositories]\n" +
        "sub = " + MorePaths.pathWithUnixSeparators(subProjectFolder.getRoot().toString()) + "\n";
    File mainBuckConfig = mainProjectFolder.newFile(".buckconfig");
    Files.write(mainBuckConfigString.getBytes(), mainBuckConfig);

    String subBuckConfigString =
        "[repositories]\n" +
        "main = " + MorePaths.pathWithUnixSeparators(mainProjectFolder.getRoot().toString()) + "\n";
    File subBuckConfig = subProjectFolder.newFile(".buckconfig");
    Files.write(subBuckConfigString.getBytes(), subBuckConfig);

    RepositoryFactory factory = new FakeRepositoryFactory(
        mainProjectFolder.getRoot().toPath().toRealPath());

    Repository mainProjectRepo = factory.getRepositoryByCanonicalName(Optional.<String>absent());
    ImmutableMap<Optional<String>, Optional<String>> mainLocalToCanonicalMap =
        mainProjectRepo.getLocalToCanonicalRepoNamesMap();
    assertEquals(Optional.absent(), mainLocalToCanonicalMap.get(Optional.absent()));
    assertEquals(Optional.of("sub"), mainLocalToCanonicalMap.get(Optional.of("sub")));

    Repository subProjectRepo = factory.getRepositoryByCanonicalName(Optional.of("sub"));
    ImmutableMap<Optional<String>, Optional<String>> subLocalToCanonicalMap =
        subProjectRepo.getLocalToCanonicalRepoNamesMap();
    assertEquals(Optional.absent(), subLocalToCanonicalMap.get(Optional.of("main")));
    assertEquals(Optional.of("sub"), subLocalToCanonicalMap.get(Optional.absent()));
  }
}
