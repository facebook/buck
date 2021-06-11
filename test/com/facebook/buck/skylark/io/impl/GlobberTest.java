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
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanQueryFailedException;
import com.facebook.buck.io.watchman.WatchmanTestUtils;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.skylark.io.Globber;
import com.facebook.buck.skylark.io.GlobberFactory;
import com.facebook.buck.testutil.AssumePath;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import javax.annotation.Nullable;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class GlobberTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> getParsers() {
    return ImmutableList.of(
        new Object[] {ParserConfig.SkylarkGlobHandler.JAVA},
        new Object[] {ParserConfig.SkylarkGlobHandler.WATCHMAN});
  }

  @Parameterized.Parameter(value = 0)
  public ParserConfig.SkylarkGlobHandler skylarkGlobHandler;

  @Nullable private Watchman watchman;
  private GlobberFactory globberFactory;
  private AbsPath root;
  private Globber globber;

  @Before
  public void before() throws Exception {
    switch (skylarkGlobHandler) {
      case JAVA:
        globberFactory = NativeGlobber.Factory.INSTANCE;
        break;
      case WATCHMAN:
        WatchmanTestUtils.setupWatchman(tmp.getRoot());
        watchman = WatchmanTestUtils.buildWatchmanAssumeNotNull(tmp.getRoot());
        globberFactory = HybridGlobberFactory.using(watchman, tmp.getRoot().getPath());
        break;
      default:
        throw new AssertionError("unreachable");
    }

    root = tmp.getRoot();
    globber = globberFactory.create(tmp.getRoot());
  }

  @After
  public void after() {
    if (globberFactory != null) {
      try {
        globberFactory.close();
      } catch (Throwable ignore) {
        // ignore
      }
    }
    if (watchman != null) {
      try {
        watchman.close();
      } catch (Throwable ignore) {
        // ignore
      }
    }
  }

  private void sync() throws IOException, InterruptedException, WatchmanQueryFailedException {
    if (watchman != null) {
      WatchmanTestUtils.sync(watchman);
    }
  }

  @Test
  public void testGlobFindsRecursiveIncludes() throws Exception {
    AbsPath child = root.resolve("child");
    Files.createDirectory(child.getPath());
    Files.write(child.resolve("foo.txt").getPath(), new byte[0]);
    Files.write(child.resolve("bar.txt").getPath(), new byte[0]);
    Files.write(child.resolve("bar.jpg").getPath(), new byte[0]);
    sync();
    assertThat(
        globber.run(Collections.singleton("**/*.txt"), Collections.emptySet(), false),
        equalTo(ImmutableSet.of("child/bar.txt", "child/foo.txt")));
  }

  @Test
  public void testGlobFindsShallowRecursiveIncludes() throws Exception {
    AbsPath child = root.resolve("child");
    Files.createDirectory(child.getPath());
    Files.write(child.resolve("foo.txt").getPath(), new byte[0]);
    Files.write(child.resolve("bar.txt").getPath(), new byte[0]);
    Files.write(child.resolve("bar.jpg").getPath(), new byte[0]);
    sync();
    assertThat(
        globber.run(Collections.singleton("child/**/*.txt"), Collections.emptySet(), false),
        equalTo(ImmutableSet.of("child/bar.txt", "child/foo.txt")));
  }

  @Test
  public void testGlobFindsDeepRecursiveIncludes() throws Exception {
    AbsPath child = root.resolve("dir").resolve("child");
    Files.createDirectories(child.getPath());
    Files.write(child.resolve("foo.txt").getPath(), new byte[0]);
    Files.write(child.resolve("bar.txt").getPath(), new byte[0]);
    Files.write(child.resolve("bar.jpg").getPath(), new byte[0]);
    sync();
    assertThat(
        globber.run(Collections.singleton("dir/**/child/*.txt"), Collections.emptySet(), false),
        equalTo(ImmutableSet.of("dir/child/bar.txt", "dir/child/foo.txt")));
  }

  @Test
  public void starPatternDoesNotIncludeDot() throws Exception {
    Files.write(root.resolve("a.txt").getPath(), new byte[0]);
    Files.write(root.resolve(".a.txt").getPath(), new byte[0]);
    sync();
    assertThat(
        globber.run(Collections.singleton("*.txt"), Collections.emptySet(), false),
        equalTo(ImmutableSet.of("a.txt")));
    assertThat(
        globber.run(Collections.singleton("*"), Collections.emptySet(), false),
        equalTo(ImmutableSet.of("a.txt")));
  }

  @Test
  public void starStarDoesNotIncludeDotDirectories() throws Exception {
    Files.createDirectory(root.resolve("dir").getPath());
    Files.write(root.resolve("dir").resolve("foo.txt").getPath(), new byte[0]);
    Files.write(root.resolve("dir").resolve("bar.job").getPath(), new byte[0]);
    Files.createDirectory(root.resolve(".xx").getPath());
    Files.write(root.resolve(".xx").resolve("baz.txt").getPath(), new byte[0]);
    Files.write(root.resolve(".xx").resolve("qux.job").getPath(), new byte[0]);
    sync();
    assertThat(
        globber.run(Collections.singleton("**/*.txt"), Collections.emptySet(), false),
        equalTo(ImmutableSet.of("dir/foo.txt")));
  }

  @Test
  public void starDoesNotCrashOnNonMachingBrokenDotLinks() throws Exception {
    Files.createDirectory(root.resolve("dir").getPath());
    Files.write(root.resolve("dir").resolve("foo.txt").getPath(), new byte[0]);
    Files.write(root.resolve("dir").resolve("bar.job").getPath(), new byte[0]);
    Files.createSymbolicLink(
        root.resolve(".xx").getPath(), root.resolve("non-existent-target").getPath());
    sync();
    assertThat(
        globber.run(Collections.singleton("**/*.txt"), Collections.emptySet(), false),
        // .xx is broken, but it ** does not match it.
        equalTo(ImmutableSet.of("dir/foo.txt")));
  }

  @Test
  public void testGlobFindsIncludes() throws Exception {
    Files.write(root.resolve("foo.txt").getPath(), new byte[0]);
    Files.write(root.resolve("bar.txt").getPath(), new byte[0]);
    Files.write(root.resolve("bar.jpg").getPath(), new byte[0]);
    sync();
    assertThat(
        globber.run(Collections.singleton("*.txt"), Collections.emptySet(), false),
        equalTo(ImmutableSet.of("bar.txt", "foo.txt")));
  }

  @Test
  public void testGlobExcludedElementsAreNotReturned() throws Exception {
    Files.write(root.resolve("foo.txt").getPath(), new byte[0]);
    Files.write(root.resolve("bar.txt").getPath(), new byte[0]);
    Files.write(root.resolve("bar.jpg").getPath(), new byte[0]);
    sync();
    assertThat(
        globber.run(Collections.singleton("*.txt"), Collections.singleton("bar.txt"), false),
        equalTo(ImmutableSet.of("foo.txt")));
  }

  @Test
  public void doesNotReturnNonexistentDirectory() throws Exception {
    assertThat(
        globber.run(Collections.singleton("does/not/exist.txt"), Collections.emptySet(), false),
        Matchers.empty());
  }

  @Test
  public void doesNotReturnNonexistentFile() throws Exception {
    assertThat(
        globber.run(Collections.singleton("does_not_exist.txt"), Collections.emptySet(), false),
        Matchers.empty());
  }

  @Test
  public void doesNotCrashIfEncountersNonMatchingBrokenSymlink() throws Exception {
    Files.createDirectories(root.resolve("foo").getPath());
    Files.write(root.resolve("foo/bar.h").getPath(), new byte[0]);
    Files.createSymbolicLink(
        root.resolve("foo/non-existent.cpp").getPath(),
        root.resolve("foo/non-existent-target.cpp").getPath());
    sync();
    assertThat(
        globber.run(ImmutableList.of("foo/*.h"), ImmutableList.of(), false),
        equalTo(ImmutableSet.of("foo/bar.h")));
  }

  @Test
  public void testMatchingDirectoryIsReturnedWhenDirsAreNotExcluded() throws Exception {
    Files.createDirectories(root.resolve("some_dir").getPath());
    sync();
    assertThat(
        globber.run(Collections.singleton("some_dir"), Collections.emptySet(), false),
        equalTo(ImmutableSet.of("some_dir")));
  }

  @Test
  public void testMatchingDirectoryIsNotReturnedWhenDirsAreExcluded() throws Exception {
    tmp.newFolder("some_dir");
    AbsPath buildFile = root.resolve("BUCK");
    MostFiles.write(buildFile, "txts = glob(['some_dir'], exclude_directories=True)");

    sync();

    assertThat(
        globber.run(Collections.singleton("some_dir"), Collections.emptySet(), true),
        equalTo(ImmutableSet.of()));
  }

  @Test
  public void testMatchingIsCaseInsensitiveByDefault() throws Exception {
    // TODO: java behavior is different
    assumeTrue(skylarkGlobHandler == ParserConfig.SkylarkGlobHandler.WATCHMAN);

    AssumePath.assumeNamesAreCaseInsensitive(tmp.getRoot());

    tmp.newFolder("directory");
    tmp.newFile("directory/file");

    sync();

    // HACK: Watchman's case sensitivity rules are strange without **/.
    // TODO(T46207782): Remove '**/'.
    assertThat(
        globber.run(Collections.singleton("**/DIRECTORY"), Collections.emptySet(), false),
        equalTo(ImmutableSet.of("directory")));
    assertThat(
        globber.run(Collections.singleton("**/DIRECTORY/FILE"), Collections.emptySet(), false),
        equalTo(ImmutableSet.of("directory/file")));
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

    sync();

    assertThat(
        globber.run(
            Collections.singleton("symlink-to-regular-file"), Collections.emptySet(), false),
        equalTo(ImmutableSet.of("symlink-to-regular-file")));
    assertThat(
        globber.run(Collections.singleton("symlink-to-directory"), Collections.emptySet(), false),
        equalTo(ImmutableSet.of("symlink-to-directory")));
    assertThat(
        globber.run(Collections.singleton("broken-symlink"), Collections.emptySet(), false),
        equalTo(ImmutableSet.of("broken-symlink")));
    assertThat(
        globber.run(Collections.singleton("*"), Collections.emptySet(), false),
        equalTo(
            ImmutableSet.of(
                "broken-symlink",
                "directory",
                "regular-file",
                "symlink-to-directory",
                "symlink-to-regular-file")));
  }

  @Test
  public void testMatchingSymbolicLinkToDirectoryIsReturnedWhenDirectoriesAreExcluded()
      throws Exception {
    tmp.newFolder("directory");
    Files.createSymbolicLink(
        root.resolve("symlink-to-directory").getPath(), Paths.get("directory"));

    sync();

    assertThat(
        globber.run(Collections.singleton("symlink-to-directory"), Collections.emptySet(), true),
        equalTo(ImmutableSet.of("symlink-to-directory")));
    assertThat(
        globber.run(Collections.singleton("*"), Collections.emptySet(), true),
        equalTo(ImmutableSet.of("symlink-to-directory")));
  }

  @Test
  public void whenDirectoryIsSymlink() throws Exception {
    tmp.newFolder("foo");
    tmp.newFile("foo/a.txt");
    Files.createSymbolicLink(tmp.getRoot().resolve("bar").getPath(), Paths.get("foo"));
    assertThat(
        globber.run(ImmutableList.of("foo/*.txt"), ImmutableList.of(), false),
        equalTo(ImmutableSet.of("foo/a.txt")));
    assumeTrue(Files.exists(tmp.getRoot().resolve("bar/a.txt").getPath()));
    assertThat(
        globber.run(ImmutableList.of("bar/*.txt"), ImmutableList.of(), false),
        equalTo(ImmutableSet.of()));
  }
}
