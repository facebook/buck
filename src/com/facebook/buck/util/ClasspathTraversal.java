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

package com.facebook.buck.util;

import com.google.common.base.Preconditions;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashCodes;
import com.google.common.io.Files;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
  private final Iterable<File> paths;

  public ClasspathTraversal(Iterable<File> paths) {
    this.paths = Preconditions.checkNotNull(paths);
  }

  public abstract void visit(FileLike fileLike) throws IOException;

  public final void traverse() throws IOException {
    for (File path : paths) {
      ClasspathTraverser adapter = createTraversalAdapter(path);
      adapter.traverse(this);
    }
  }

  private ClasspathTraverser createTraversalAdapter(File path) {
    String extension = Files.getFileExtension(path.getName());
    if (extension.equalsIgnoreCase("jar") || extension.equalsIgnoreCase("zip")) {
      return new ZipFileTraversalAdapter(path);
    } else if (path.isDirectory()) {
      return new DirectoryTraversalAdapter(path);
    } else if (path.isFile()) {
      return new FileTraversalAdapter(path);
    } else {
      throw new IllegalArgumentException("Unsupported classpath traversal input: " + path);
    }
  }

  private static class ZipFileTraversalAdapter implements ClasspathTraverser {
    private final File file;

    public ZipFileTraversalAdapter(File file) {
      this.file = Preconditions.checkNotNull(file);
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
      private final File container;
      private final ZipFile zipFile;
      private final ZipEntry entry;

      public FileLikeInZip(File container, ZipFile zipFile, ZipEntry entry) {
        this.container = container;
        this.zipFile = zipFile;
        this.entry = entry;
      }

      @Override
      public File getContainer() {
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

      @Override
      public HashCode fastHash() throws IOException {
        return HashCodes.fromLong(entry.getCrc());
      }
    }
  }

  private static class DirectoryTraversalAdapter implements ClasspathTraverser {
    private final File file;

    public DirectoryTraversalAdapter(File file) {
      this.file = Preconditions.checkNotNull(file);
    }

    @Override
    public void traverse(final ClasspathTraversal traversal) throws IOException {
      DirectoryTraversal impl = new DirectoryTraversal(file) {
        @Override
        public void visit(File file, String relativePath) throws IOException {
          traversal.visit(new FileLikeInDirectory(file, relativePath));
        }
      };
      impl.traverse();
    }
  }

  private static class FileTraversalAdapter implements ClasspathTraverser {
    private final File file;

    public FileTraversalAdapter(File file) {
      this.file = file;
    }

    @Override
    public void traverse(ClasspathTraversal traversal) throws IOException {
      traversal.visit(new FileLikeInDirectory(file, file.getName()));
    }
  }

  private static class FileLikeInDirectory extends AbstractFileLike {
    private final File file;
    private final String relativePath;

    public FileLikeInDirectory(File file, String relativePath) {
      this.file = Preconditions.checkNotNull(file);
      this.relativePath = Preconditions.checkNotNull(relativePath);
    }

    @Override
    public File getContainer() {
      return file;
    }

    @Override
    public String getRelativePath() {
      return relativePath;
    }

    @Override
    public long getSize() {
      return file.length();
    }

    @Override
    public InputStream getInput() throws IOException {
      return new FileInputStream(file);
    }
  }
}

