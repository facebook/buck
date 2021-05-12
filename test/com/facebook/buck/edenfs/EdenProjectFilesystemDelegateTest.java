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

package com.facebook.buck.edenfs;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cli.TestWithBuckd;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.event.console.TestEventConsole;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemDelegate;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemDelegate;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.io.watchman.WatchmanTestUtils;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.ConfigBuilder;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.buck.util.timing.FakeClock;
import com.facebook.eden.thrift.EdenError;
import com.facebook.thrift.TException;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class EdenProjectFilesystemDelegateTest {
  private static final Sha1HashCode DUMMY_SHA1 = Sha1HashCode.of(Strings.repeat("faceb00c", 5));
  private ProjectFilesystem projectFilesystem;
  private Watchman watchman;

  /**
   * This is the location of the working directory for {@link Configuration#unix()}. Creating
   * symlinks via {@link Files#createSymbolicLink(Path, Path,
   * java.nio.file.attribute.FileAttribute[])} in the working directory of Jimfs does not touch the
   * actual filesystem.
   */
  private static final String JIMFS_WORKING_DIRECTORY = "/work";

  @Rule public ExpectedException thrown = ExpectedException.none();
  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public TestWithBuckd testWithBuckd = new TestWithBuckd(tmp); // set up Watchman

  @Before
  public void setUp() throws Exception {
    projectFilesystem = new FakeProjectFilesystem(tmp.getRoot());
    WatchmanFactory watchmanFactory = new WatchmanFactory();
    watchman =
        watchmanFactory.build(
            ImmutableSet.of(tmp.getRoot()),
            ImmutableMap.of(),
            new TestEventConsole(),
            FakeClock.doNotCare(),
            Optional.empty(),
            Optional.empty());
    assumeTrue(watchman.getTransportPath().isPresent());
  }

  @Test
  public void computeSha1ForOrdinaryFileUnderMount() throws IOException, EdenError, TException {
    FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
    Path root = fs.getPath(JIMFS_WORKING_DIRECTORY);
    ProjectFilesystemDelegate delegate =
        new DefaultProjectFilesystemDelegate(root, Optional.empty());

    EdenMount mount = createMock(EdenMount.class);
    Path path = fs.getPath("foo/bar");
    expect(mount.getPathRelativeToProjectRoot(root.resolve(path))).andReturn(Optional.of(path));
    expect(mount.getSha1(path)).andReturn(DUMMY_SHA1);
    replay(mount);

    EdenProjectFilesystemDelegate edenDelegate =
        new EdenProjectFilesystemDelegate(mount, delegate, AbsPath.of(root));
    assertEquals(DUMMY_SHA1, edenDelegate.computeSha1(path));

    verify(mount);
  }

  @Test
  public void computeSha1ForSymlinkUnderMountThatPointsToFileUnderMount()
      throws EdenError, TException, IOException {
    FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
    Path root = fs.getPath(JIMFS_WORKING_DIRECTORY);
    ProjectFilesystemDelegate delegate =
        new DefaultProjectFilesystemDelegate(root, Optional.empty());

    // Create a symlink within the project root.
    Path link = fs.getPath("/work/link");
    Path target = fs.getPath("/work/target");
    Files.createFile(target);
    Files.createSymbolicLink(link, target);

    // Eden will throw when the SHA-1 for the link is requested, but return a SHA-1 when the target
    // is requested.
    EdenMount mount = createMock(EdenMount.class);
    expect(mount.getPathRelativeToProjectRoot(link)).andReturn(Optional.of(fs.getPath("link")));
    expect(mount.getPathRelativeToProjectRoot(target)).andReturn(Optional.of(fs.getPath("target")));
    expect(mount.getSha1(fs.getPath("link"))).andThrow(new EdenError());
    expect(mount.getSha1(fs.getPath("target"))).andReturn(DUMMY_SHA1);
    replay(mount);

    EdenProjectFilesystemDelegate edenDelegate =
        new EdenProjectFilesystemDelegate(mount, delegate, AbsPath.of(root));
    assertEquals(DUMMY_SHA1, edenDelegate.computeSha1(link));

    verify(mount);
  }

  @Test
  public void computeSha1ForSymlinkUnderMountThatPointsToFileOutsideMount()
      throws IOException, EdenError, TException {
    FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
    Path root = fs.getPath(JIMFS_WORKING_DIRECTORY);
    ProjectFilesystemDelegate delegate =
        new DefaultProjectFilesystemDelegate(root, Optional.empty());

    // Create a symlink within the project root.
    Path link = fs.getPath("/work/link");
    Path target = fs.getPath("/example");
    Files.createFile(target);
    byte[] bytes = new byte[] {66, 85, 67, 75};
    Files.write(target, bytes);
    Files.createSymbolicLink(link, target);

    // Eden will throw when the SHA-1 for the link is requested, but return a SHA-1 when the target
    // is requested.
    EdenMount mount = createMock(EdenMount.class);
    expect(mount.getPathRelativeToProjectRoot(link)).andReturn(Optional.of(fs.getPath("link")));
    expect(mount.getPathRelativeToProjectRoot(target)).andReturn(Optional.empty());
    expect(mount.getSha1(fs.getPath("link"))).andThrow(new EdenError());
    replay(mount);

    EdenProjectFilesystemDelegate edenDelegate =
        new EdenProjectFilesystemDelegate(mount, delegate, AbsPath.of(root));
    assertEquals(
        "EdenProjectFilesystemDelegate.computeSha1() should return the SHA-1 of the target of "
            + "the symlink even though the target is outside of the EdenFS root.",
        Sha1HashCode.fromHashCode(Hashing.sha1().hashBytes(bytes)),
        edenDelegate.computeSha1(link));

    verify(mount);
  }

  @Test
  public void computeSha1ForOrdinaryFileOutsideMount() throws IOException {
    FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
    Path root = fs.getPath(JIMFS_WORKING_DIRECTORY);
    ProjectFilesystemDelegate delegate =
        new DefaultProjectFilesystemDelegate(root, Optional.empty());
    Path target = fs.getPath("/example");
    Files.createFile(target);
    byte[] bytes = new byte[] {66, 85, 67, 75};
    Files.write(target, bytes);

    EdenMount mount = createMock(EdenMount.class);
    expect(mount.getPathRelativeToProjectRoot(target)).andReturn(Optional.empty());
    replay(mount);

    EdenProjectFilesystemDelegate edenDelegate =
        new EdenProjectFilesystemDelegate(mount, delegate, AbsPath.of(root));
    assertEquals(
        "EdenProjectFilesystemDelegate.computeSha1() should return the SHA-1 of a file that is "
            + "outside of the EdenFS root.",
        Sha1HashCode.fromHashCode(Hashing.sha1().hashBytes(bytes)),
        edenDelegate.computeSha1(target));

    verify(mount);
  }

  @Test
  public void computeSha1ViaXattrForFileUnderMount() throws IOException {
    FileSystem fs =
        Jimfs.newFileSystem(Configuration.unix().toBuilder().setAttributeViews("user").build());
    Path root = fs.getPath(JIMFS_WORKING_DIRECTORY);
    ProjectFilesystemDelegate delegate =
        new DefaultProjectFilesystemDelegate(root, Optional.empty());

    Path path = fs.getPath("/foo");
    Files.createFile(path);
    UserDefinedFileAttributeView view =
        Files.getFileAttributeView(path, UserDefinedFileAttributeView.class);

    ByteBuffer buf = ByteBuffer.wrap(DUMMY_SHA1.toString().getBytes(StandardCharsets.UTF_8));
    view.write("sha1", buf);
    EdenMount mount = createMock(EdenMount.class);
    Config config = ConfigBuilder.createFromText("[eden]", "use_xattr = true");
    EdenProjectFilesystemDelegate edenDelegate =
        new EdenProjectFilesystemDelegate(
            mount,
            delegate,
            config,
            new WatchmanFactory.NullWatchman("EdenProjectFilesystemDelegateTest"),
            AbsPath.of(root));
    assertEquals(DUMMY_SHA1, edenDelegate.computeSha1(path));
  }

  @Test
  public void computeSha1ViaXattrForFileUnderMountInvalidUTF8() throws IOException {
    FileSystem fs =
        Jimfs.newFileSystem(Configuration.unix().toBuilder().setAttributeViews("user").build());
    Path root = fs.getPath(JIMFS_WORKING_DIRECTORY);
    ProjectFilesystemDelegate delegate =
        new DefaultProjectFilesystemDelegate(root, Optional.empty());

    Path path = fs.getPath("/foo");
    Files.createFile(path);
    byte[] bytes = new byte[] {66, 85, 67, 75};
    Files.write(path, bytes);
    UserDefinedFileAttributeView view =
        Files.getFileAttributeView(path, UserDefinedFileAttributeView.class);

    ByteBuffer buf = ByteBuffer.allocate(2);
    buf.putChar((char) 0xfffe);
    view.write("sha1", buf);
    EdenMount mount = createMock(EdenMount.class);
    Config config = ConfigBuilder.createFromText("[eden]", "use_xattr = true");
    EdenProjectFilesystemDelegate edenDelegate =
        new EdenProjectFilesystemDelegate(
            mount,
            delegate,
            config,
            new WatchmanFactory.NullWatchman("EdenProjectFilesystemDelegateTest"),
            AbsPath.of(root));
    assertEquals(
        "EdenProjectFilesystemDelegate.computeSha1() should return the SHA-1 of the contents",
        Sha1HashCode.fromHashCode(Hashing.sha1().hashBytes(bytes)),
        edenDelegate.computeSha1(path));
  }

  @Test
  public void computeSha1ViaXattrForFileOutsideMount() throws IOException {
    FileSystem fs =
        Jimfs.newFileSystem(Configuration.unix().toBuilder().setAttributeViews("user").build());
    Path root = fs.getPath(JIMFS_WORKING_DIRECTORY);
    ProjectFilesystemDelegate delegate =
        new DefaultProjectFilesystemDelegate(root, Optional.empty());

    Path path = fs.getPath("/foo");
    Files.createFile(path);
    byte[] bytes = new byte[] {66, 85, 67, 75};
    Files.write(path, bytes);

    EdenMount mount = createMock(EdenMount.class);
    Config config = ConfigBuilder.createFromText("[eden]", "use_xattr = true");
    EdenProjectFilesystemDelegate edenDelegate =
        new EdenProjectFilesystemDelegate(
            mount,
            delegate,
            config,
            new WatchmanFactory.NullWatchman("EdenProjectFilesystemDelegateTest"),
            AbsPath.of(root));
    assertEquals(
        "EdenProjectFilesystemDelegate.computeSha1() should return the SHA-1 of a file that is "
            + "outside of the EdenFS root.",
        Sha1HashCode.fromHashCode(Hashing.sha1().hashBytes(bytes)),
        edenDelegate.computeSha1(path));
  }

  @Test
  public void computeSha1ViaWatchman() throws Exception {
    ProjectFilesystemDelegate delegate =
        new DefaultProjectFilesystemDelegate(tmp.getRoot(), Optional.empty());
    Path path = tmp.newFile("foo").getPath();

    WatchmanTestUtils.sync(watchman);

    EdenMount mount = createMock(EdenMount.class);

    Config configWithFileSystem =
        ConfigBuilder.createFromText(
            "[eden]", "use_xattr = true", "[eden]", "use_watchman_content_sha1 = false");
    EdenProjectFilesystemDelegate edenDelegateWithFileSystem =
        new EdenProjectFilesystemDelegate(
            mount,
            delegate,
            configWithFileSystem,
            new WatchmanFactory.NullWatchman("EdenProjectFilesystemDelegateTest"),
            AbsPath.of(tmp.getRoot().getPath()));

    Config configWithWatchman =
        ConfigBuilder.createFromText(
            "[eden]", "use_watchman_content_sha1 = true", "[eden]", "use_xattr = false");
    EdenProjectFilesystemDelegate edenDelegateWithWatchman =
        new EdenProjectFilesystemDelegate(
            mount, delegate, configWithWatchman, watchman, projectFilesystem.getRootPath());

    assertEquals(
        edenDelegateWithFileSystem.computeSha1(path), edenDelegateWithWatchman.computeSha1(path));
  }

  @Test
  public void computeSha1ViaWatchmanFailedWatchmanNotSetup()
      throws IOException, TException, EdenError {
    FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
    Path root = fs.getPath(JIMFS_WORKING_DIRECTORY);
    ProjectFilesystemDelegate delegate =
        new DefaultProjectFilesystemDelegate(root, Optional.empty());

    Config configWithWatchman =
        ConfigBuilder.createFromText(
            "[eden]", "use_watchman_content_sha1 = true", "[eden]", "use_xattr = false");

    EdenMount mount = createMock(EdenMount.class);
    Path path = fs.getPath("foo/bar");
    expect(mount.getPathRelativeToProjectRoot(root.resolve(path))).andReturn(Optional.of(path));
    expect(mount.getSha1(path)).andReturn(DUMMY_SHA1);
    replay(mount);

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("Watchman is not set. Please turn off eden.use_watchman_content_sha1");

    EdenProjectFilesystemDelegate edenDelegate =
        new EdenProjectFilesystemDelegate(
            mount,
            delegate,
            configWithWatchman,
            new WatchmanFactory.NullWatchman("EdenProjectFilesystemDelegateTest"),
            AbsPath.of(root));
    edenDelegate.computeSha1(path);
  }

  @Test
  public void computeSha1ViaWatchmanForSymlink() throws Exception {
    ProjectFilesystemDelegate delegate =
        new DefaultProjectFilesystemDelegate(tmp.getRoot(), Optional.empty());
    EdenMount mount = createMock(EdenMount.class);

    // Create a symlink within the project root.
    Path link = tmp.getRoot().resolve("link").getPath();
    Path target = tmp.newFile("target").getPath();
    Files.createSymbolicLink(link, target);

    WatchmanTestUtils.sync(watchman);

    Config configWithFileSystem =
        ConfigBuilder.createFromText(
            "[eden]", "use_xattr = true", "[eden]", "use_watchman_content_sha1 = false");
    EdenProjectFilesystemDelegate edenDelegateWithFileSystem =
        new EdenProjectFilesystemDelegate(
            mount,
            delegate,
            configWithFileSystem,
            new WatchmanFactory.NullWatchman("EdenProjectFilesystemDelegateTest"),
            tmp.getRoot());

    Config configWithWatchman =
        ConfigBuilder.createFromText(
            "[eden]", "use_watchman_content_sha1 = true", "[eden]", "use_xattr = false");
    EdenProjectFilesystemDelegate edenDelegateWithWatchman =
        new EdenProjectFilesystemDelegate(
            mount, delegate, configWithWatchman, watchman, projectFilesystem.getRootPath());

    assertFalse(edenDelegateWithWatchman.globOnPath(link).isPresent());
    assertTrue(edenDelegateWithWatchman.globOnPath(target).isPresent());
    assertEquals(
        edenDelegateWithFileSystem.computeSha1(link), edenDelegateWithWatchman.computeSha1(target));

    assertEquals(
        edenDelegateWithFileSystem.computeSha1(link), edenDelegateWithWatchman.computeSha1(link));
  }

  @Test
  public void testGetSha1HahserFromConfig() {
    Config configWithUseWatchman =
        ConfigBuilder.createFromText(
            "[eden]", "use_watchman_content_sha1 = true", "[eden]", "use_xattr = false");
    assertEquals(
        EdenProjectFilesystemDelegate.Sha1Hasher.WATCHMAN,
        EdenProjectFilesystemDelegate.getSha1HasherFromConfig(configWithUseWatchman));

    Config configWithWatchmanSHA1 =
        ConfigBuilder.createFromText("[eden]", "sha1_hasher = watchman");
    assertEquals(
        EdenProjectFilesystemDelegate.Sha1Hasher.WATCHMAN,
        EdenProjectFilesystemDelegate.getSha1HasherFromConfig(configWithWatchmanSHA1));

    Config configWithUseXattr =
        ConfigBuilder.createFromText("[eden]", "[eden]", "use_xattr = true");
    assertEquals(
        EdenProjectFilesystemDelegate.Sha1Hasher.XATTR,
        EdenProjectFilesystemDelegate.getSha1HasherFromConfig(configWithUseXattr));

    Config configWithXattrSHA1 = ConfigBuilder.createFromText("[eden]", "sha1_hasher = xattr");
    assertEquals(
        EdenProjectFilesystemDelegate.Sha1Hasher.XATTR,
        EdenProjectFilesystemDelegate.getSha1HasherFromConfig(configWithXattrSHA1));

    Config configWithUseThrift =
        ConfigBuilder.createFromText(
            "[eden]", "use_watchman_content_sha1 = false", "[eden]", "use_xattr = false");
    assertEquals(
        EdenProjectFilesystemDelegate.Sha1Hasher.EDEN_THRIFT,
        EdenProjectFilesystemDelegate.getSha1HasherFromConfig(configWithUseThrift));

    Config configWithThriftSHA1 =
        ConfigBuilder.createFromText("[eden]", "sha1_hasher = eden_thrift");
    assertEquals(
        EdenProjectFilesystemDelegate.Sha1Hasher.EDEN_THRIFT,
        EdenProjectFilesystemDelegate.getSha1HasherFromConfig(configWithThriftSHA1));
  }
}
