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

package com.facebook.buck.features.apple.projectV2;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListParser;
import com.facebook.buck.apple.AppleWrapperResourceArg;
import com.facebook.buck.apple.CoreDataModelDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.Optional;

/**
 * Represents a core data resource from disk.
 *
 * <p>A Core Data Resource can be a directory in the event that it contains version information. In
 * that case, the path will be the root name of the directory and it will contain a nonnull version
 * info object which contains all file paths for each versioned file as well as a .xccurrentversion
 * file which is an XML file pointing to the name of the current file.
 *
 * <p>In the event that it is not versioned, the versionInfo will be empty and the path itself will
 * simply be the path to the core data model object file.
 */
public class CoreDataResource {
  private final Path path;
  private final Optional<VersionInformation> versionInfo;

  /** Details the version information for a core data file. */
  public static class VersionInformation {
    private final Path currentVersionPath;
    private final ImmutableList<Path> allVersionPaths;

    public VersionInformation(Path currentVersion, ImmutableList<Path> allVersionPaths) {
      this.currentVersionPath = currentVersion;
      this.allVersionPaths = allVersionPaths;
    }

    public Path getCurrentVersionPath() {
      return currentVersionPath;
    }

    public ImmutableList<Path> getAllVersionPaths() {
      return allVersionPaths;
    }
  }

  public CoreDataResource(Path path, Optional<VersionInformation> versionInfo) {
    this.path = path;
    this.versionInfo = versionInfo;
  }

  public Path getPath() {
    return path;
  }

  public Optional<VersionInformation> versionInfo() {
    return versionInfo;
  }

  /**
   * Creates a CoreDataResource object from an AppleWrapperResourceArg
   *
   * @param dataModel The resource to convert.
   * @param projectFilesystem The file system to use for verifying version information.
   * @return A new CoreDataResource with its compiled verison information.
   * @throws IOException
   */
  public static CoreDataResource fromResourceArgs(
      AppleWrapperResourceArg dataModel, ProjectFilesystem projectFilesystem) throws IOException {
    if (CoreDataModelDescription.isVersionedDataModel(dataModel)) {
      // It's safe to do I/O here to figure out the current version because we're returning all
      // the versions and the file pointing to the current version from
      // getInputsToCompareToOutput(), so the rule will be correctly detected as stale if any of
      // them change.
      String currentVersionFileName = ".xccurrentversion";
      String currentVersionKey = "_XCCurrentVersionName";

      ImmutableList.Builder<Path> versionPathsBuilder = ImmutableList.builder();
      projectFilesystem
          .asView()
          .walkRelativeFileTree(
              dataModel.getPath(),
              EnumSet.of(FileVisitOption.FOLLOW_LINKS),
              new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                  if (dir.equals(dataModel.getPath())) {
                    return FileVisitResult.CONTINUE;
                  }
                  versionPathsBuilder.add(dir);
                  return FileVisitResult.SKIP_SUBTREE;
                }
              });

      Path currentVersionPath = dataModel.getPath().resolve(currentVersionFileName);
      try (InputStream in = projectFilesystem.newFileInputStream(currentVersionPath)) {
        NSObject rootObject;
        try {
          rootObject = PropertyListParser.parse(in);
        } catch (IOException e) {
          throw e;
        } catch (Exception e) {
          rootObject = null;
        }
        if (!(rootObject instanceof NSDictionary)) {
          throw new HumanReadableException("Malformed %s file.", currentVersionFileName);
        }
        NSDictionary rootDictionary = (NSDictionary) rootObject;
        NSObject currentVersionName = rootDictionary.objectForKey(currentVersionKey);
        if (!(currentVersionName instanceof NSString)) {
          throw new HumanReadableException("Malformed %s file.", currentVersionFileName);
        }

        VersionInformation versionInfo =
            new VersionInformation(
                dataModel.getPath().resolve(currentVersionName.toString()),
                versionPathsBuilder.build());
        return new CoreDataResource(dataModel.getPath(), Optional.of(versionInfo));
      } catch (NoSuchFileException e) {
        throw e;
      }
    } else {
      return new CoreDataResource(dataModel.getPath(), Optional.empty());
    }
  }
}
