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

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystemDelegate;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.eden.thrift.EdenError;
import com.facebook.thrift.TException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
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
    this.disableSha1FastPath = disableSha1FastPath;
    this.useXattr = useXattr;
  }

  @Override
  public ImmutableMap<String, ? extends Object> getDetailsForLogging() {
    return ImmutableMap.<String, Object>builder()
        .put("filesystem", "eden")
        .put("eden_filesystem", true)
        .put("eden_mountpoint", mount.getProjectRoot().toString())
        .put("eden_disablesha1fastpath", disableSha1FastPath)
        .build();
  }

  @Override
  public Sha1HashCode computeSha1(Path pathRelativeToProjectRootOrJustAbsolute) throws IOException {
    AbsPath fileToHash = getPathForRelativePath(pathRelativeToProjectRootOrJustAbsolute);
    return computeSha1(fileToHash, /* retryWithRealPathIfEdenError */ true);
  }

  private Sha1HashCode computeSha1(AbsPath path, boolean retryWithRealPathIfEdenError)
      throws IOException {
    if (disableSha1FastPath) {
      return delegate.computeSha1(path.getPath());
    }

    Optional<Sha1HashCode> ret =
        useXattr
            ? computeSha1ViaXAttr(path)
            : computeSha1ViaThrift(path, retryWithRealPathIfEdenError);

    return ret.isPresent() ? ret.get() : delegate.computeSha1(path.getPath());
  }

  private Optional<Sha1HashCode> computeSha1ViaXAttr(AbsPath path) throws IOException {
    try {
      if (path.getFileSystem().supportedFileAttributeViews().contains("user")) {
        UserDefinedFileAttributeView view =
            Files.getFileAttributeView(path.getPath(), UserDefinedFileAttributeView.class);
        if (view != null) {
          // Eden returns the SHA1 as a UTF-8 encoded hexadecimal string
          ByteBuffer buf = ByteBuffer.allocate(SHA1_HEX_LENGTH);
          view.read("sha1", buf);
          buf.position(0);
          CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
          decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
          try {
            return Optional.of(Sha1HashCode.of(decoder.decode(buf).toString()));
          } catch (CharacterCodingException e) {
            LOG.warn(e, "Got invalid UTF-8 from getxattr for %s", path);
          }
        }
      }
    } catch (FileSystemException | IllegalArgumentException e) {
      LOG.debug("Failed when fetching SHA-1 for %s", path);
    }
    return Optional.empty();
  }

  private Optional<Sha1HashCode> computeSha1ViaThrift(
      AbsPath path, boolean retryWithRealPathIfEdenError) throws IOException {
    Optional<Path> entry = mount.getPathRelativeToProjectRoot(path.getPath());
    if (entry.isPresent()) {
      try {
        return Optional.of(mount.getSha1(entry.get()));
      } catch (TException | IOException e) {
        LOG.info(e, "Failed when fetching SHA-1 for %s", path);
      } catch (EdenError e) {
        if (retryWithRealPathIfEdenError) {
          // It's possible that an EdenError was thrown because entry.get() was a path to a
          // symlink, which is not supported by Eden's getSha1() API. Try again if the real path
          // is different from the original path.
          AbsPath realPath = path.toRealPath();
          if (!realPath.equals(path)) {
            return Optional.of(computeSha1(realPath, /* retryWithRealPathIfEdenError */ false));
          }
        }
      }
    }
    return Optional.empty();
  }

  @Override
  public AbsPath getPathForRelativePath(Path pathRelativeToProjectRootOrJustAbsolute) {
    return delegate.getPathForRelativePath(pathRelativeToProjectRootOrJustAbsolute);
  }
}
