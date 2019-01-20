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

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystemDelegate;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.eden.thrift.EdenError;
import com.facebook.thrift.TException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.Optional;

public final class EdenProjectFilesystemDelegate implements ProjectFilesystemDelegate {

  private static final Logger LOG = Logger.get(EdenProjectFilesystemDelegate.class);

  /**
   * Config option in the {@code [eden]} section of {@code .buckconfig} to disable going through
   * Eden's Thrift API to get the SHA-1 of a file. This defaults to {@code false}. This should be
   * tweaked during profiling to confirm that going through Eden's Thrift API is more efficient.
   */
  private static final String BUCKCONFIG_DISABLE_SHA1_FAST_PATH = "disable_sha1_fast_path";

  private static final String BUCKCONFIG_USE_XATTR_FOR_SHA1 = "use_xattr";

  private static final int SHA1_HEX_LENGTH = 40;

  private final EdenMount mount;

  /** Delegate to forward requests to for files that are outside of the {@link #mount}. */
  private final ProjectFilesystemDelegate delegate;

  private final ImmutableList<Path> bindMounts;

  private final boolean disableSha1FastPath;

  private final boolean useXattr;

  public EdenProjectFilesystemDelegate(
      EdenMount mount, ProjectFilesystemDelegate delegate, Config config) {
    this(
        mount,
        delegate,
        config.getBooleanValue("eden", BUCKCONFIG_DISABLE_SHA1_FAST_PATH, false),
        config.getBooleanValue("eden", BUCKCONFIG_USE_XATTR_FOR_SHA1, false));
  }

  @VisibleForTesting
  EdenProjectFilesystemDelegate(EdenMount mount, ProjectFilesystemDelegate delegate) {
    this(mount, delegate, /* disableSha1FastPath */ false, /* useXattr */ false);
  }

  private EdenProjectFilesystemDelegate(
      EdenMount mount,
      ProjectFilesystemDelegate delegate,
      boolean disableSha1FastPath,
      boolean useXattr) {
    this.mount = mount;
    this.delegate = delegate;
    this.bindMounts = mount.getBindMounts();
    this.disableSha1FastPath = disableSha1FastPath;
    this.useXattr = useXattr;
  }

  @Override
  public ImmutableMap<String, ? extends Object> getDetailsForLogging() {
    return ImmutableMap.<String, Object>builder()
        .put("filesystem", "eden")
        .put("eden.filesystem", true)
        .put("eden.mountPoint", mount.getProjectRoot().toString())
        .put("eden.disableSha1FastPath", disableSha1FastPath)
        .build();
  }

  @Override
  public Sha1HashCode computeSha1(Path pathRelativeToProjectRootOrJustAbsolute) throws IOException {
    Path fileToHash = getPathForRelativePath(pathRelativeToProjectRootOrJustAbsolute);
    return computeSha1(fileToHash, /* retryWithRealPathIfEdenError */ true);
  }

  private Sha1HashCode computeSha1(Path path, boolean retryWithRealPathIfEdenError)
      throws IOException {
    Preconditions.checkArgument(path.isAbsolute());
    if (disableSha1FastPath) {
      return delegate.computeSha1(path);
    }

    Optional<Sha1HashCode> ret =
        useXattr
            ? computeSha1ViaXAttr(path)
            : computeSha1ViaThrift(path, retryWithRealPathIfEdenError);

    return ret.isPresent() ? ret.get() : delegate.computeSha1(path);
  }

  private Optional<Sha1HashCode> computeSha1ViaXAttr(Path path) throws IOException {
    try {
      if (path.getFileSystem().supportedFileAttributeViews().contains("user")) {
        UserDefinedFileAttributeView view =
            Files.getFileAttributeView(path, UserDefinedFileAttributeView.class);
        if (view != null) {
          // Eden returns the SHA1 as a UTF-8 encoded hexadecimal string
          ByteBuffer buf = ByteBuffer.allocate(SHA1_HEX_LENGTH);
          view.read("sha1", buf);
          buf.position(0);
          CharsetDecoder decoder = Charset.forName("UTF-8").newDecoder();
          decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
          try {
            return Optional.of(Sha1HashCode.of(decoder.decode(buf).toString()));
          } catch (CharacterCodingException e) {
            LOG.warn(e, "Got invalid UTF-8 from getxattr for %s", path);
          }
        }
      }
    } catch (FileSystemException | IllegalArgumentException e) {
      LOG.debug(e, "Failed when fetching SHA-1 for %s", path);
    }
    return Optional.empty();
  }

  private Optional<Sha1HashCode> computeSha1ViaThrift(
      Path path, boolean retryWithRealPathIfEdenError) throws IOException {
    Optional<Path> entry = mount.getPathRelativeToProjectRoot(path);
    if (entry.isPresent() && !isUnderBindMount(entry.get())) {
      try {
        return Optional.of(mount.getSha1(entry.get()));
      } catch (TException | IOException e) {
        LOG.info(e, "Failed when fetching SHA-1 for %s", path);
      } catch (EdenError e) {
        if (retryWithRealPathIfEdenError) {
          // It's possible that an EdenError was thrown because entry.get() was a path to a
          // symlink, which is not supported by Eden's getSha1() API. Try again if the real path
          // is different from the original path.
          Path realPath = path.toRealPath();
          if (!realPath.equals(path)) {
            return Optional.of(computeSha1(realPath, /* retryWithRealPathIfEdenError */ false));
          }
        }
      }
    }
    return Optional.empty();
  }

  private boolean isUnderBindMount(Path pathRelativeToProjectRoot) {
    for (Path bindMount : bindMounts) {
      if (pathRelativeToProjectRoot.startsWith(bindMount)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public Path getPathForRelativePath(Path pathRelativeToProjectRootOrJustAbsolute) {
    return delegate.getPathForRelativePath(pathRelativeToProjectRootOrJustAbsolute);
  }

  @Override
  public boolean isExecutable(Path child) {
    return delegate.isExecutable(child);
  }

  @Override
  public boolean isSymlink(Path path) {
    return delegate.isSymlink(path);
  }

  @Override
  public boolean exists(Path pathRelativeToProjectRoot, LinkOption... options) {
    return delegate.exists(pathRelativeToProjectRoot, options);
  }
}
