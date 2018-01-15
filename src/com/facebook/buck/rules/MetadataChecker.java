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

package com.facebook.buck.rules;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.Threads;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

public class MetadataChecker {
  private static final Logger LOG = Logger.get(MetadataChecker.class);

  // Utility class. Do not instantiate.
  private MetadataChecker() {}

  private static void writeMetadataStorage(
      ProjectFilesystem filesystem,
      Path metadataTypePath,
      CachingBuildEngine.MetadataStorage metadataStorage)
      throws IOException {
    filesystem.createParentDirs(metadataTypePath);

    // If metadata.type becomes corrupt (e.g., is created but not written), then buck-out is in an
    // unrecoverable state, since we don't know how the existing metadata is stored.  Prevent this
    // from happening by writing to a temp file and then moving it into place.
    Path tempMetadataTypePath = filesystem.createTempFile("metadata", ".type");
    filesystem.getPathForRelativePath(tempMetadataTypePath).toFile().setReadable(true, false);
    filesystem.writeContentsToPath(metadataStorage.toString(), tempMetadataTypePath);

    // Using {@link StandardCopyOption#ATOMIC_MOVE} would be ideal, but it's implementation-defined
    // whether overwrites can be done atomically.  Since overwriting is what we need, let's hope
    // this is "atomic enough".
    filesystem.move(tempMetadataTypePath, metadataTypePath, StandardCopyOption.REPLACE_EXISTING);
  }

  // Special case deletion for scratch dir since build.log is open, which is a problem for Windows
  private static void deleteScratchDir(ProjectFilesystem filesystem) throws IOException {
    Path scratchDir = filesystem.getPathForRelativePath(filesystem.getBuckPaths().getScratchDir());
    Files.walkFileTree(
        scratchDir,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (!file.getFileName().toString().equals("build.log")) {
              Files.delete(file);
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            if (exc != null) {
              throw exc;
            }
            if (!dir.equals(scratchDir)) {
              Files.delete(dir);
            }
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static void checkAndCleanIfNeededInner(Cell rootCell) throws IOException {
    for (Path cellPath : rootCell.getKnownRoots()) {
      Cell cell = rootCell.getCell(cellPath);
      LOG.debug("Checking metadata_storage for %s", cell.getFilesystem().getRootPath());

      CachingBuildEngineBuckConfig config =
          cell.getBuckConfig().getView(CachingBuildEngineBuckConfig.class);
      CachingBuildEngine.MetadataStorage fromConfig = config.getBuildMetadataStorage();

      ProjectFilesystem filesystem = cell.getFilesystem();
      Path metadataPath = BuildInfoStore.getMetadataTypePath(filesystem);
      Optional<String> metadataString = filesystem.readFileIfItExists(metadataPath);
      if (metadataString.isPresent()) {
        // Only need consistency check if type file exists, otherwise it's a clean build.
        Optional<CachingBuildEngine.MetadataStorage> fromFile;
        try {
          fromFile = Optional.of(CachingBuildEngine.MetadataStorage.valueOf(metadataString.get()));
        } catch (IllegalArgumentException e) {
          fromFile = Optional.empty();
        }

        if (fromFile.isPresent() && fromConfig == fromFile.get()) {
          // Our configured metadata storage backend is consistent with the last-used one.
          continue;
        }

        // An inconsistent metadata backend has been requested; we need to clean the repo.
        LOG.debug(
            "Changing metadata_storage from %s to %s, cleaning: %s",
            metadataString.get(), fromConfig, cell.getFilesystem().getRootPath());
        deleteScratchDir(filesystem);
        filesystem.deleteRecursivelyIfExists(filesystem.getBuckPaths().getGenDir());
        filesystem.deleteRecursivelyIfExists(filesystem.getBuckPaths().getTrashDir());
      }
      writeMetadataStorage(filesystem, metadataPath, fromConfig);
    }
  }

  public static void checkAndCleanIfNeeded(Cell rootCell) throws IOException {
    try {
      checkAndCleanIfNeededInner(rootCell);
    } catch (ClosedByInterruptException e) {
      Threads.interruptCurrentThread();
    }
  }
}
