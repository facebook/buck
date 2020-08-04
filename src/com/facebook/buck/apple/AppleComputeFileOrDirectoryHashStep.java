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

package com.facebook.buck.apple;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.cxx.toolchain.objectfile.Machos;
import com.facebook.buck.cxx.toolchain.objectfile.ObjectFileScrubbers;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.nio.ByteBufferUnmapper;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

/**
 * Computes the hash of the given file or an integral hash of the content of the given directory.
 * Includes configurable logic to extract UUIDs from Mach-O files, which can be used instead, as to
 * avoid re-hashing. Also includes configurable logic to not follow symlinks, but, instead, hash
 * their resolved paths.
 */
public class AppleComputeFileOrDirectoryHashStep extends AbstractExecutionStep {

  private final StringBuilder hashBuilder;
  private final AbsPath fileOrDirectoryPath;
  private final ProjectFilesystem projectFilesystem;
  private final boolean useMachoUuid;
  private final boolean symlinksHashResolvedPath;

  AppleComputeFileOrDirectoryHashStep(
      StringBuilder hashBuilder,
      AbsPath fileOrDirectoryPath,
      ProjectFilesystem projectFilesystem,
      boolean useMachoUuid,
      boolean symlinksHashResolvedPath) {
    super("apple-compute-file-or-directory-hash");
    this.hashBuilder = hashBuilder;
    this.fileOrDirectoryPath = fileOrDirectoryPath;
    this.projectFilesystem = projectFilesystem;
    this.useMachoUuid = useMachoUuid;
    this.symlinksHashResolvedPath = symlinksHashResolvedPath;
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context)
      throws IOException, InterruptedException {
    hashBuilder.append(computeFileOrDirectoryHashString(fileOrDirectoryPath));
    return StepExecutionResults.SUCCESS;
  }

  private String computeFileOrDirectoryHashString(AbsPath fileOrDirectoryPath) throws IOException {
    if (symlinksHashResolvedPath && projectFilesystem.isSymLink(fileOrDirectoryPath)) {
      Path resolvedSymlink = projectFilesystem.readSymLink(fileOrDirectoryPath.getPath());
      Sha1HashCode sha1 = AppleComputeHashSupport.computeHash(resolvedSymlink.toString());
      return sha1.toString();
    }
    AbsPath pathToHash = fileOrDirectoryPath.toRealPath();
    if (projectFilesystem.isDirectory(pathToHash)) {
      return computeDirectoryHashString(pathToHash);
    } else {
      return computeFileHashString(pathToHash);
    }
  }

  private String computeDirectoryHashString(AbsPath dirPath) throws IOException {
    Hasher hasher = Hashing.sha1().newHasher();

    projectFilesystem
        .asView()
        .walkFileTree(
            projectFilesystem.relativize(dirPath).getPath(),
            ImmutableSet.of(),
            new FileVisitor<Path>() {

              @Override
              public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                  throws IOException {
                if (!dir.equals(dirPath.getPath())) {
                  AppleComputeHashSupport.computeHash(pathRelativeToRootDirectory(dir).toString())
                      .update(hasher);
                }
                return FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                  throws IOException {
                if (!file.equals(dirPath.getPath())) {
                  AppleComputeHashSupport.computeHash(pathRelativeToRootDirectory(file).toString())
                      .update(hasher);
                }
                hasher.putUnencodedChars(computeFileOrDirectoryHashString(AbsPath.of(file)));
                return FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult visitFileFailed(Path file, IOException exc)
                  throws IOException {
                throw exc;
              }

              @Override
              public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                return FileVisitResult.CONTINUE;
              }

              private Path pathRelativeToRootDirectory(Path item) {
                return dirPath.relativize(AbsPath.of(item)).getPath();
              }
            });

    return hasher.hash().toString();
  }

  private String computeFileHashString(AbsPath filePath) throws IOException {
    if (useMachoUuid) {
      Optional<String> maybeUuid = getMachoUuid(filePath);
      if (maybeUuid.isPresent()) {
        return "uuid:" + maybeUuid.get();
      }
    }

    String sha1 = projectFilesystem.computeSha1(filePath.getPath()).getHash();
    // In order to guarantee no collisions between Mach-O and non-Mach-O files,
    // we namespace them with a suitable prefix.
    return (useMachoUuid ? "sha1:" : "") + sha1;
  }

  private static Optional<String> getMachoUuid(AbsPath path) throws IOException {
    try (FileChannel file = FileChannel.open(path.getPath(), StandardOpenOption.READ)) {
      if (!Machos.isMacho(file)) {
        return Optional.empty();
      }

      try (ByteBufferUnmapper unmapper =
          ByteBufferUnmapper.createUnsafe(
              file.map(FileChannel.MapMode.READ_ONLY, 0, file.size()))) {

        try {
          Optional<byte[]> maybeUuid = Machos.getUuidIfPresent(unmapper.getByteBuffer());
          if (maybeUuid.isPresent()) {
            String hexBytes = ObjectFileScrubbers.bytesToHex(maybeUuid.get(), true);
            return Optional.of(hexBytes);
          }
        } catch (Machos.MachoException e) {
          // Even though it's a Mach-O file, we failed to read it safely
          throw new RuntimeException("Internal Mach-O file parsing failure");
        }

        return Optional.empty();
      }
    }
  }
}
