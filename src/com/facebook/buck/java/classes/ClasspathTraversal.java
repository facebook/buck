/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.java.classes;

import com.facebook.buck.io.DirectoryTraversal;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.util.ZipFileTraversal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.Nullable;

/**
 * Traversal strategy for traversing a set of paths that themselves are traversed.  The provided
 * paths can point to zip/jar files, directories of resource/class files, or individual files
 * themselves.
 * <p>
 * For example, given the input paths of { foo.zip, foo/, and foo.txt }, traverse would first
 * expand foo.zip and traverse its contents, then list the files recursively in foo/, and finally
 * visit the single file foo.txt.
 */
public abstract class ClasspathTraversal {
  private final Iterable<Path> paths;
  private final ProjectFilesystem filesystem;

  public ClasspathTraversal(Collection<Path> paths, ProjectFilesystem filesystem) {
    this.paths = paths;
    this.filesystem = filesystem;
  }

  public abstract void visit(FileLike fileLike) throws IOException;

  /**
   * Subclasses can override this method to return a value of any type. This often represents some
   * sort of cumulative value that is computed as a result of the traversal.
   */
  // TODO(mbolin): Change this from Object to a generic <T>.
  @Nullable
  public Object getResult() {
    return null;
  }

  public final void traverse() throws IOException {
    for (Path path : paths) {
      ClasspathTraverser adapter = createTraversalAdapter(filesystem.getPathForRelativePath(path));
      adapter.traverse(this);
    }
  }

  private ClasspathTraverser createTraversalAdapter(Path path) {
    String extension = MorePaths.getFileExtension(path);
    if (extension.equalsIgnoreCase("jar") || extension.equalsIgnoreCase("zip")) {
      return new ZipFileTraversalAdapter(path);
    } else if (Files.isDirectory(path)) {
      return new DirectoryTraversalAdapter(path);
    } else if (Files.isRegularFile(path)) {
      return new FileTraversalAdapter(path);
    } else {
      throw new IllegalArgumentException("Unsupported classpath traversal input: " + path);
    }
  }

  private static class ZipFileTraversalAdapter implements ClasspathTraverser {
    private final Path file;

    public ZipFileTraversalAdapter(Path file) {
      this.file = file;
    }

    @Override
    public void traverse(final ClasspathTraversal traversal) throws IOException {
      ZipFileTraversal impl = new ZipFileTraversal(file) {
        @Override
        public void visit(ZipFile zipFile, ZipEntry zipEntry) throws IOException {
          traversal.visit(new FileLikeInZip(file, zipFile, zipEntry));
        }
      };
      impl.traverse();
    }

    private static class FileLikeInZip extends AbstractFileLike {
      private final Path container;
      private final ZipFile zipFile;
      private final ZipEntry entry;

      public FileLikeInZip(Path container, ZipFile zipFile, ZipEntry entry) {
        this.container = container;
        this.zipFile = zipFile;
        this.entry = entry;
      }

      @Override
      public Path getContainer() {
        return container;
      }

      @Override
      public String getRelativePath() {
        return entry.getName();
      }

      @Override
      public long getSize() {
        return entry.getSize();
      }

      @Override
      public InputStream getInput() throws IOException {
        return zipFile.getInputStream(entry);
      }
    }
  }

  private static class DirectoryTraversalAdapter implements ClasspathTraverser {
    private final Path file;

    public DirectoryTraversalAdapter(Path file) {
      this.file = file;
    }

    @Override
    public void traverse(final ClasspathTraversal traversal) throws IOException {
      DirectoryTraversal impl = new DirectoryTraversal(file) {
        @Override
        public void visit(Path file, String relativePath) throws IOException {
          traversal.visit(new FileLikeInDirectory(file, relativePath));
        }
      };
      impl.traverse();
    }
  }

  private static class FileTraversalAdapter implements ClasspathTraverser {
    private final Path file;

    public FileTraversalAdapter(Path file) {
      this.file = file;
    }

    @Override
    public void traverse(ClasspathTraversal traversal) throws IOException {
      traversal.visit(new FileLikeInDirectory(file, file.getFileName().toString()));
    }
  }

  private static class FileLikeInDirectory extends AbstractFileLike {
    private final Path file;
    private final String relativePath;

    public FileLikeInDirectory(Path file, String relativePath) {
      // Currently, the only instances of FileLikeInDirectory appear to be the .class files
      // generated from an R.java in Android. The only exception is in unit tests.
      this.file = file;
      this.relativePath = relativePath;
    }

    @Override
    public Path getContainer() {
      return file;
    }

    @Override
    public String getRelativePath() {
      return relativePath;
    }

    @Override
    public long getSize() throws IOException {
      return Files.size(file);
    }

    @Override
    public InputStream getInput() throws IOException {
      return Files.newInputStream(file);
    }
  }
}

