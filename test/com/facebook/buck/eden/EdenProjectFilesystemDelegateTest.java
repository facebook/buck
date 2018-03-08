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

package com.facebook.buck.eden;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.filesystem.ProjectFilesystemDelegate;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemDelegate;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.eden.thrift.EdenError;
import com.facebook.thrift.TException;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.Test;

public class EdenProjectFilesystemDelegateTest {
  private static final Sha1HashCode DUMMY_SHA1 = Sha1HashCode.of(Strings.repeat("faceb00c", 5));

  /**
   * This is the location of the working directory for {@link Configuration#unix()}. Creating
   * symlinks via {@link Files#createSymbolicLink(Path, Path,
   * java.nio.file.attribute.FileAttribute[])} in the working directory of Jimfs does not touch the
   * actual filesystem.
   */
  private static final String JIMFS_WORKING_DIRECTORY = "/work";

  @Test
  public void computeSha1ForOrdinaryFileUnderMount() throws IOException, EdenError, TException {
    FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
    Path root = fs.getPath(JIMFS_WORKING_DIRECTORY);
    ProjectFilesystemDelegate delegate = new DefaultProjectFilesystemDelegate(root);

    EdenMount mount = createMock(EdenMount.class);
    Path path = fs.getPath("foo/bar");
    expect(mount.getBindMounts()).andReturn(ImmutableList.of());
    expect(mount.getPathRelativeToProjectRoot(root.resolve(path))).andReturn(Optional.of(path));
    expect(mount.getSha1(path)).andReturn(DUMMY_SHA1);
    replay(mount);

    EdenProjectFilesystemDelegate edenDelegate = new EdenProjectFilesystemDelegate(mount, delegate);
    assertEquals(DUMMY_SHA1, edenDelegate.computeSha1(path));

    verify(mount);
  }

  @Test
  public void computeSha1ForOrdinaryFileUnderMountButBehindBindMount() throws IOException {
    FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
    Path root = fs.getPath(JIMFS_WORKING_DIRECTORY);
    ProjectFilesystemDelegate delegate = new DefaultProjectFilesystemDelegate(root);

    EdenMount mount = createMock(EdenMount.class);
    Path path = fs.getPath("buck-out/gen/some-output");
    Files.createDirectories(path.getParent());
    Files.createFile(path);
    byte[] bytes = new byte[] {66, 85, 67, 75};
    Files.write(path, bytes);

    expect(mount.getBindMounts()).andReturn(ImmutableList.of(fs.getPath("buck-out")));
    expect(mount.getPathRelativeToProjectRoot(root.resolve(path))).andReturn(Optional.of(path));
    replay(mount);

    EdenProjectFilesystemDelegate edenDelegate = new EdenProjectFilesystemDelegate(mount, delegate);
    assertEquals(
        "EdenProjectFilesystemDelegate.computeSha1() should compute the SHA-1 directly via "
            + "DefaultProjectFilesystemDelegate because the path is behind a bind mount.",
        Sha1HashCode.fromHashCode(Hashing.sha1().hashBytes(bytes)),
        edenDelegate.computeSha1(path));

    verify(mount);
  }

  @Test
  public void computeSha1ForSymlinkUnderMountThatPointsToFileUnderMount()
      throws EdenError, TException, IOException {
    FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
    Path root = fs.getPath(JIMFS_WORKING_DIRECTORY);
    ProjectFilesystemDelegate delegate = new DefaultProjectFilesystemDelegate(root);

    // Create a symlink within the project root.
    Path link = fs.getPath("/work/link");
    Path target = fs.getPath("/work/target");
    Files.createFile(target);
    Files.createSymbolicLink(link, target);

    // Eden will throw when the SHA-1 for the link is requested, but return a SHA-1 when the target
    // is requested.
    EdenMount mount = createMock(EdenMount.class);
    expect(mount.getBindMounts()).andReturn(ImmutableList.of());
    expect(mount.getPathRelativeToProjectRoot(link)).andReturn(Optional.of(fs.getPath("link")));
    expect(mount.getPathRelativeToProjectRoot(target)).andReturn(Optional.of(fs.getPath("target")));
    expect(mount.getSha1(fs.getPath("link"))).andThrow(new EdenError());
    expect(mount.getSha1(fs.getPath("target"))).andReturn(DUMMY_SHA1);
    replay(mount);

    EdenProjectFilesystemDelegate edenDelegate = new EdenProjectFilesystemDelegate(mount, delegate);
    assertEquals(DUMMY_SHA1, edenDelegate.computeSha1(link));

    verify(mount);
  }

  @Test
  public void computeSha1ForSymlinkUnderMountThatPointsToFileOutsideMount()
      throws IOException, EdenError, TException {
    FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
    Path root = fs.getPath(JIMFS_WORKING_DIRECTORY);
    ProjectFilesystemDelegate delegate = new DefaultProjectFilesystemDelegate(root);

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
    expect(mount.getBindMounts()).andReturn(ImmutableList.of());
    expect(mount.getPathRelativeToProjectRoot(link)).andReturn(Optional.of(fs.getPath("link")));
    expect(mount.getPathRelativeToProjectRoot(target)).andReturn(Optional.empty());
    expect(mount.getSha1(fs.getPath("link"))).andThrow(new EdenError());
    replay(mount);

    EdenProjectFilesystemDelegate edenDelegate = new EdenProjectFilesystemDelegate(mount, delegate);
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
    ProjectFilesystemDelegate delegate = new DefaultProjectFilesystemDelegate(root);
    Path target = fs.getPath("/example");
    Files.createFile(target);
    byte[] bytes = new byte[] {66, 85, 67, 75};
    Files.write(target, bytes);

    EdenMount mount = createMock(EdenMount.class);
    expect(mount.getBindMounts()).andReturn(ImmutableList.of());
    expect(mount.getPathRelativeToProjectRoot(target)).andReturn(Optional.empty());
    replay(mount);

    EdenProjectFilesystemDelegate edenDelegate = new EdenProjectFilesystemDelegate(mount, delegate);
    assertEquals(
        "EdenProjectFilesystemDelegate.computeSha1() should return the SHA-1 of a file that is "
            + "outside of the EdenFS root.",
        Sha1HashCode.fromHashCode(Hashing.sha1().hashBytes(bytes)),
        edenDelegate.computeSha1(target));

    verify(mount);
  }
}
