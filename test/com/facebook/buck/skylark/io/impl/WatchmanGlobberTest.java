/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.skylark.io.impl;

import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cli.TestWithBuckd;
import com.facebook.buck.io.StubWatchmanClient;
import com.facebook.buck.io.Watchman;
import com.facebook.buck.io.WatchmanClient;
import com.facebook.buck.io.WatchmanFactory;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.skylark.SkylarkFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class WatchmanGlobberTest {
  private Path root;
  private WatchmanGlobber globber;

  @Rule public ExpectedException thrown = ExpectedException.none();
  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public TestWithBuckd testWithBuckd = new TestWithBuckd(tmp); // set up Watchman

  @Before
  public void setUp() throws Exception {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem(tmp.getRoot());
    SkylarkFilesystem fileSystem = SkylarkFilesystem.using(projectFilesystem);
    root = fileSystem.getPath(tmp.getRoot().toString());
    WatchmanFactory watchmanFactory = new WatchmanFactory();
    Watchman watchman =
        watchmanFactory.build(
            ImmutableSet.of(),
            ImmutableMap.of(),
            new TestConsole(),
            FakeClock.doNotCare(),
            Optional.empty());
    assumeTrue(watchman.getTransportPath().isPresent());
    globber =
        WatchmanGlobber.create(watchman.createClient(), new SyncCookieState(), "", root.toString());
  }

  @Test
  public void testGlobFindsIncludes() throws IOException, InterruptedException {
    tmp.newFile("foo.txt");
    tmp.newFile("bar.txt");
    tmp.newFile("bar.jpg");

    assertThat(
        globber.run(Collections.singleton("*.txt"), Collections.emptySet(), false),
        equalTo(Optional.of(ImmutableSet.of("bar.txt", "foo.txt"))));
  }

  @Test
  public void testGlobExcludedElementsAreNotReturned() throws IOException, InterruptedException {
    tmp.newFile("foo.txt");
    tmp.newFile("bar.txt");
    tmp.newFile("bar.jpg");
    assertThat(
        globber.run(Collections.singleton("*.txt"), Collections.singleton("bar.txt"), false),
        equalTo(Optional.of(ImmutableSet.of("foo.txt"))));
  }

  @Test
  public void testMatchingDirectoryIsReturnedWhenDirsAreNotExcluded() throws Exception {
    tmp.newFolder("some_dir");
    assertThat(
        globber.run(Collections.singleton("some_dir"), Collections.emptySet(), false),
        equalTo(Optional.of(ImmutableSet.of("some_dir"))));
  }

  @Test
  public void testMatchingDirectoryIsNotReturnedWhenDirsAreExcluded() throws Exception {
    tmp.newFolder("some_dir");
    Path buildFile = root.getChild("BUCK");
    FileSystemUtils.writeContentAsLatin1(
        buildFile, "txts = glob(['some_dir'], exclude_directories=True)");
    assertThat(
        globber.run(Collections.singleton("some_dir"), Collections.emptySet(), true),
        equalTo(Optional.of(ImmutableSet.of())));
  }

  @Test
  public void noResultsAreReturnedIfWatchmanDoesNotProduceAnything() throws Exception {
    globber =
        WatchmanGlobber.create(
            new StubWatchmanClient(Optional.empty()), new SyncCookieState(), "", root.toString());
    assertFalse(globber.run(ImmutableList.of("*.txt"), ImmutableList.of(), false).isPresent());
  }

  @Test
  public void watchmanSyncIsIssuedForTheFirstInvocation() throws Exception {
    CapturingWatchmanClient watchmanClient = new CapturingWatchmanClient();
    globber = WatchmanGlobber.create(watchmanClient, new SyncCookieState(), "", root.toString());
    globber.run(ImmutableList.of("*.txt"), ImmutableList.of(), false);

    assertTrue(watchmanClient.syncRequested());
  }

  @Test
  public void watchmanSyncIsNotIssuedForTheSecondInvocation() throws Exception {
    CapturingWatchmanClient watchmanClient = new CapturingWatchmanClient();
    globber = WatchmanGlobber.create(watchmanClient, new SyncCookieState(), "", root.toString());
    globber.run(ImmutableList.of("*.txt"), ImmutableList.of(), false);
    globber.run(ImmutableList.of("*.txt"), ImmutableList.of(), false);

    assertTrue(watchmanClient.syncDisabled());
  }

  private static class CapturingWatchmanClient implements WatchmanClient {

    private ImmutableList<Object> query;

    @Override
    public Optional<? extends Map<String, ? extends Object>> queryWithTimeout(
        long timeoutNanos, Object... query) {
      this.query = ImmutableList.copyOf(query);
      return Optional.empty();
    }

    @Override
    public void close() {}

    public boolean syncRequested() {
      return !getQueryExpression().containsKey("sync_timeout");
    }

    public boolean syncDisabled() {
      return ((int) getQueryExpression().get("sync_timeout")) == 0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getQueryExpression() {
      return (Map<String, Object>) query.get(2);
    }
  }
}
