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
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Computes the integral hash of the content of the given directory. Content of files which are
 * being pointed at by symbolic link contained by this directory is not hashed, instead, the
 * symbolic link resolved path is hashed.
 */
public class AppleComputeDirectoryContentHashStep extends AbstractExecutionStep {

  private final StringBuilder hashBuilder;
  private final AbsPath dirPath;
  private final ProjectFilesystem projectFilesystem;

  public AppleComputeDirectoryContentHashStep(
      StringBuilder hashBuilder, AbsPath dirPath, ProjectFilesystem projectFilesystem) {
    super("apple-compute-dir-content-hash");
    this.hashBuilder = hashBuilder;
    this.dirPath = dirPath;
    this.projectFilesystem = projectFilesystem;
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context)
      throws IOException, InterruptedException {

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
                if (projectFilesystem.isSymLink(file)) {
                  Path resolvedSymlink = projectFilesystem.readSymLink(file);
                  AppleComputeHashSupport.computeHash(resolvedSymlink.toString()).update(hasher);
                } else {
                  projectFilesystem.computeSha1(file).update(hasher);
                }
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

    hashBuilder.append(hasher.hash().toString());

    return StepExecutionResults.SUCCESS;
  }
}
