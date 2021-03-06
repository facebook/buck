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

package com.facebook.buck.skylark.io.impl;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cli.TestWithBuckd;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.watchman.StubWatchmanClient;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanClient;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.io.watchman.WatchmanQuery;
import com.facebook.buck.io.watchman.WatchmanQueryFailedException;
import com.facebook.buck.testutil.AssumePath;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.timing.FakeClock;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class WatchmanGlobberTest {
  private static final Logger LOG = Logger.get(WatchmanGlobberTest.class);

  private AbsPath root;
  private WatchmanGlobber globber;

  @Rule public ExpectedException thrown = ExpectedException.none();
  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public TestWithBuckd testWithBuckd = new TestWithBuckd(tmp); // set up Watchman

  @Before
  public void setUp() throws Exception {
    root = tmp.getRoot();
    WatchmanFactory watchmanFactory = new WatchmanFactory();
    Watchman watchman =
        watchmanFactory.build(
            ImmutableSet.of(),
            ImmutableMap.of(),
            new TestConsole(),
            FakeClock.doNotCare(),
            Optional.empty(),
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
    AbsPath buildFile = root.resolve("BUCK");
    MostFiles.write(buildFile, "txts = glob(['some_dir'], exclude_directories=True)");
    assertThat(
        globber.run(Collections.singleton("some_dir"), Collections.emptySet(), true),
        equalTo(Optional.of(ImmutableSet.of())));
  }

  @Test
  public void testMatchingSymbolicLinkIsReturnedWhenSymlinksAreNotExcluded() throws Exception {
    Files.createSymbolicLink(root.resolve("broken-symlink").getPath(), Paths.get("does-not-exist"));
    tmp.newFolder("directory");
    Files.createSymbolicLink(
        root.resolve("symlink-to-directory").getPath(), Paths.get("directory"));
    tmp.newFile("regular-file");
    Files.createSymbolicLink(
        root.resolve("symlink-to-regular-file").getPath(), Paths.get("regular-file"));

    assertThat(
        globber.run(
            Collections.singleton("symlink-to-regular-file"), Collections.emptySet(), false),
        equalTo(Optional.of(ImmutableSet.of("symlink-to-regular-file"))));
    assertThat(
        globber.run(Collections.singleton("symlink-to-directory"), Collections.emptySet(), false),
        equalTo(Optional.of(ImmutableSet.of("symlink-to-directory"))));
    assertThat(
        globber.run(Collections.singleton("broken-symlink"), Collections.emptySet(), false),
        equalTo(Optional.of(ImmutableSet.of("broken-symlink"))));
    assertThat(
        globber.run(Collections.singleton("*"), Collections.emptySet(), false),
        equalTo(
            Optional.of(
                ImmutableSet.of(
                    "broken-symlink",
                    "directory",
                    "regular-file",
                    "symlink-to-directory",
                    "symlink-to-regular-file"))));
  }

  @Test
  public void testMatchingSymbolicLinkToDirectoryIsReturnedWhenDirectoriesAreExcluded()
      throws Exception {
    tmp.newFolder("directory");
    Files.createSymbolicLink(
        root.resolve("symlink-to-directory").getPath(), Paths.get("directory"));

    assertThat(
        globber.run(Collections.singleton("symlink-to-directory"), Collections.emptySet(), true),
        equalTo(Optional.of(ImmutableSet.of("symlink-to-directory"))));
    assertThat(
        globber.run(Collections.singleton("*"), Collections.emptySet(), true),
        equalTo(Optional.of(ImmutableSet.of("symlink-to-directory"))));
  }

  @Test
  public void testMatchingIsCaseInsensitiveByDefault() throws Exception {
    AssumePath.assumeNamesAreCaseInsensitive(tmp.getRoot());

    tmp.newFolder("directory");
    tmp.newFile("directory/file");

    // HACK: Watchman's case sensitivity rules are strange without **/.
    assertThat(
        globber.run(Collections.singleton("**/DIRECTORY"), Collections.emptySet(), false),
        equalTo(Optional.of(ImmutableSet.of("directory"))));
    assertThat(
        globber.run(Collections.singleton("**/DIRECTORY/FILE"), Collections.emptySet(), false),
        equalTo(Optional.of(ImmutableSet.of("directory/file"))));
  }

  @Test
  public void testMatchingIsCaseSensitiveIfForced() throws Exception {
    AssumePath.assumeNamesAreCaseInsensitive(tmp.getRoot());

    tmp.newFolder("directory");
    tmp.newFile("directory/file");

    // HACK: Watchman's case sensitivity rules are strange without **/.
    assertThat(
        globber.run(
            Collections.singleton("**/DIRECTORY"),
            Collections.emptySet(),
            WatchmanGlobber.Option.FORCE_CASE_SENSITIVE),
        equalTo(Optional.of(ImmutableSet.of())));
    assertThat(
        globber.run(
            Collections.singleton("**/DIRECTORY/FILE"),
            Collections.emptySet(),
            WatchmanGlobber.Option.FORCE_CASE_SENSITIVE),
        equalTo(Optional.of(ImmutableSet.of())));
    assertThat(
        globber.run(
            Collections.singleton("**/directory/FILE"),
            Collections.emptySet(),
            WatchmanGlobber.Option.FORCE_CASE_SENSITIVE),
        equalTo(Optional.of(ImmutableSet.of())));
    assertThat(
        globber.run(
            Collections.singleton("**/DIRECTORY/file"),
            Collections.emptySet(),
            WatchmanGlobber.Option.FORCE_CASE_SENSITIVE),
        equalTo(Optional.of(ImmutableSet.of())));
    assertThat(
        globber.run(
            Collections.singleton("**/directory/file"),
            Collections.emptySet(),
            WatchmanGlobber.Option.FORCE_CASE_SENSITIVE),
        equalTo(Optional.of(ImmutableSet.of("directory/file"))));
  }

  @Test
  public void testExcludingIsCaseInsensitiveByDefault() throws Exception {
    AssumePath.assumeNamesAreCaseInsensitive(tmp.getRoot());

    tmp.newFile("file");
    assertThat(
        globber.run(Collections.singleton("file"), Collections.singleton("FILE"), false),
        equalTo(Optional.of(ImmutableSet.of())));
  }

  @Test
  public void testExcludingIsCaseSensitiveIfForced() throws Exception {
    tmp.newFile("file");
    assertThat(
        globber.run(
            Collections.singleton("file"),
            Collections.singleton("FILE"),
            WatchmanGlobber.Option.FORCE_CASE_SENSITIVE),
        equalTo(Optional.of(ImmutableSet.of("file"))));
  }

  @Test
  public void testMatchingSymbolicLinkIsNotReturnedWhenSymlinksAreExcluded() throws Exception {
    Files.createSymbolicLink(root.resolve("broken-symlink").getPath(), Paths.get("does-not-exist"));
    tmp.newFolder("directory");
    Files.createSymbolicLink(
        root.resolve("symlink-to-directory").getPath(), Paths.get("directory"));
    tmp.newFile("regular-file");
    Files.createSymbolicLink(
        root.resolve("symlink-to-regular-file").getPath(), Paths.get("regular-file"));

    assertThat(
        globber.run(
            Collections.singleton("symlink-to-regular-file"),
            Collections.emptySet(),
            WatchmanGlobber.Option.EXCLUDE_SYMLINKS),
        equalTo(Optional.of(ImmutableSet.of())));
    assertThat(
        globber.run(
            Collections.singleton("symlink-to-directory"),
            Collections.emptySet(),
            WatchmanGlobber.Option.EXCLUDE_SYMLINKS),
        equalTo(Optional.of(ImmutableSet.of())));
    assertThat(
        globber.run(
            Collections.singleton("broken-symlink"),
            Collections.emptySet(),
            WatchmanGlobber.Option.EXCLUDE_SYMLINKS),
        equalTo(Optional.of(ImmutableSet.of())));
    assertThat(
        globber.run(
            Collections.singleton("*"),
            Collections.emptySet(),
            WatchmanGlobber.Option.EXCLUDE_SYMLINKS),
        equalTo(Optional.of(ImmutableSet.of("directory", "regular-file"))));
  }

  @Test
  public void noResultsAreReturnedIfWatchmanDoesNotProduceAnything() throws Exception {
    globber =
        WatchmanGlobber.create(
            new StubWatchmanClient(Either.ofRight(WatchmanClient.Timeout.INSTANCE)),
            new SyncCookieState(),
            "",
            root.toString());
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

  @Test
  public void throwsIfWatchmanReturnsError() throws IOException, InterruptedException {
    WatchmanClient client =
        new WatchmanClient() {
          @Override
          public Either<Map<String, Object>, Timeout> queryWithTimeout(
              long timeoutNanos, long warnTimeNanos, WatchmanQuery query) {
            LOG.info("Processing query: %s", query);
            if (query instanceof WatchmanQuery.Query) {
              return Either.ofLeft(
                  ImmutableMap.of(
                      "version",
                      "4.9.4",
                      "error",
                      String.format(
                          "RootResolveError: unable to resolve root %s: directory %s not watched",
                          ((WatchmanQuery.Query) query).getPath(),
                          ((WatchmanQuery.Query) query).getPath())));

            } else {
              throw new RuntimeException("Watchman query not implemented");
            }
          }

          @Override
          public void close() {}
        };

    String queryRoot = root.toString();
    globber = WatchmanGlobber.create(client, new SyncCookieState(), "", queryRoot);

    thrown.expect(WatchmanQueryFailedException.class);
    thrown.expect(
        Matchers.hasProperty(
            "watchmanErrorMessage",
            Matchers.equalTo(
                String.format(
                    "RootResolveError: unable to resolve root %s: directory %s not watched",
                    queryRoot, queryRoot))));
    globber.run(ImmutableList.of("*.txt"), ImmutableList.of(), false);
  }

  @Test
  public void testGlobberRunWithExtraFields() throws IOException, InterruptedException {
    tmp.newFile("foo.txt");

    Optional<ImmutableMap<String, WatchmanGlobber.WatchmanFileAttributes>> globberMap =
        globber.runWithExtraFields(
            Collections.singleton("foo.txt"),
            Collections.emptySet(),
            EnumSet.noneOf(WatchmanGlobber.Option.class),
            TimeUnit.SECONDS.toNanos(30),
            TimeUnit.SECONDS.toNanos(10),
            ImmutableList.of("name", "type"));

    ImmutableMap<String, WatchmanGlobber.WatchmanFileAttributes> expected =
        ImmutableMap.of(
            "foo.txt",
            new WatchmanGlobber.WatchmanFileAttributes(
                ImmutableMap.of("name", "foo.txt", "type", "f")));

    assertThat(globberMap, equalTo(Optional.of(expected)));
  }

  @Test
  public void testGlobberWildcardRunWithExtraFields() throws IOException, InterruptedException {
    tmp.newFile("foo.txt");
    tmp.newFile("bar.txt");

    Optional<ImmutableMap<String, WatchmanGlobber.WatchmanFileAttributes>> globberMap =
        globber.runWithExtraFields(
            Collections.singleton("*.txt"),
            Collections.emptySet(),
            EnumSet.noneOf(WatchmanGlobber.Option.class),
            TimeUnit.SECONDS.toNanos(30),
            TimeUnit.SECONDS.toNanos(10),
            ImmutableList.of("name", "type"));
    ImmutableMap<String, WatchmanGlobber.WatchmanFileAttributes> expected =
        ImmutableMap.of(
            "foo.txt",
            new WatchmanGlobber.WatchmanFileAttributes(
                ImmutableMap.of("name", "foo.txt", "type", "f")),
            "bar.txt",
            new WatchmanGlobber.WatchmanFileAttributes(
                ImmutableMap.of("name", "bar.txt", "type", "f")));

    assertThat(globberMap, equalTo(Optional.of(expected)));
  }

  @Test
  public void testGlobberRunWithExtraFieldsNoMatch() throws IOException, InterruptedException {
    tmp.newFile("foo.txt");

    Optional<ImmutableMap<String, WatchmanGlobber.WatchmanFileAttributes>> globberMap =
        globber.runWithExtraFields(
            Collections.singleton("bar.txt"),
            Collections.emptySet(),
            EnumSet.noneOf(WatchmanGlobber.Option.class),
            TimeUnit.SECONDS.toNanos(30),
            TimeUnit.SECONDS.toNanos(10),
            ImmutableList.of("name", "type"));

    assertThat(globberMap, equalTo(Optional.empty()));
  }

  @Test
  public void testGlobberRunWithExtraFieldsButStillNameOnly()
      throws IOException, InterruptedException {
    tmp.newFile("foo.txt");
    assertThat(
        globber.run(Collections.singleton("foo.txt"), Collections.emptySet(), false),
        equalTo(Optional.of(ImmutableSet.of("foo.txt"))));

    Optional<ImmutableMap<String, WatchmanGlobber.WatchmanFileAttributes>> globberMap =
        globber.runWithExtraFields(
            Collections.singleton("foo.txt"),
            Collections.emptySet(),
            EnumSet.noneOf(WatchmanGlobber.Option.class),
            TimeUnit.SECONDS.toNanos(30),
            TimeUnit.SECONDS.toNanos(10),
            ImmutableList.of("name"));

    ImmutableMap<String, WatchmanGlobber.WatchmanFileAttributes> expected =
        ImmutableMap.of(
            "foo.txt",
            new WatchmanGlobber.WatchmanFileAttributes(ImmutableMap.of("name", "foo.txt")));
    assertThat(globberMap, equalTo(Optional.of(expected)));
  }

  private static class CapturingWatchmanClient implements WatchmanClient {

    private WatchmanQuery query;

    @Override
    public Either<Map<String, Object>, Timeout> queryWithTimeout(
        long timeoutNanos, long warnTimeNanos, WatchmanQuery query) {
      this.query = query;
      return Either.ofLeft(ImmutableMap.of("files", ImmutableList.of()));
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
      return ((WatchmanQuery.Query) query).getArgs();
    }
  }
}
