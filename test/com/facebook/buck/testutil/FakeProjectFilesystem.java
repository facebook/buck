/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.testutil;

import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.FileVisitor;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

// TODO(user): Implement methods that throw UnsupportedOperationException.
public class FakeProjectFilesystem extends ProjectFilesystem {

  private final Map<Path, byte[]> fileContents;

  public FakeProjectFilesystem() {
    super(Paths.get("."));
    fileContents = Maps.newHashMap();
  }

  private byte[] getFileBytes(Path path) {
    return fileContents.get(path.normalize());
  }

  private void rmFile(Path path) {
    fileContents.remove(path.normalize());
  }

  @Override
  public boolean exists(Path path) {
    return fileContents.containsKey(path.normalize());
  }

  @Override
  public long getFileSize(Path path) throws IOException {
    if (!exists(path)) {
      throw new FileNotFoundException(path.toString());
    }
    return getFileBytes(path).length;
  }

  @Override
  public boolean deleteFileAtPath(Path path) {
    if (exists(path)) {
      rmFile(path);
    }
    return true;
  }

  @Override
  public Properties readPropertiesFile(Path pathToPropertiesFile) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isFile(Path path) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isDirectory(Path path, LinkOption... linkOptions) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void walkFileTree(Path root, FileVisitor<Path> fileVisitor) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void rmdir(Path path) {
    Path normalizedPath = path.normalize();
    for (Iterator<Path> iterator = fileContents.keySet().iterator(); iterator.hasNext();) {
      if (iterator.next().startsWith(normalizedPath)) {
        iterator.remove();
      }
    }
  }

  @Override
  public void mkdirs(Path path) {
  }

  @Override
  public void createParentDirs(Path path) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void writeLinesToPath(Iterable<String> lines, Path path) {
    StringBuilder builder = new StringBuilder();
    if (!Iterables.isEmpty(lines)) {
      Joiner.on('\n').appendTo(builder, lines);
      builder.append('\n');
    }
    writeContentsToPath(builder.toString(), path);
  }

  @Override
  public void writeContentsToPath(String contents, Path path) {
    writeBytesToPath(contents.getBytes(Charsets.UTF_8), path);
  }

  @Override
  public void writeBytesToPath(byte[] bytes, Path path) {
    fileContents.put(path.normalize(), Preconditions.checkNotNull(bytes));
  }

  @Override
  public void copyToPath(final InputStream inputStream, Path path) throws IOException {
    writeBytesToPath(ByteStreams.toByteArray(inputStream), path);
  }

  @Override
  public Optional<String> readFileIfItExists(Path path) {
    if (!exists(path)) {
      return Optional.absent();
    }
    return Optional.of(new String(getFileBytes(path), Charsets.UTF_8));
  }

  @Override
  public Optional<Reader> getReaderIfFileExists(Path path) {
    Optional<String> content = readFileIfItExists(path);
    if (!content.isPresent()) {
      return Optional.absent();
    }
    return Optional.of((Reader) new StringReader(content.get()));
  }

  @Override
  public Optional<String> readFirstLine(Path path) {
    List<String> lines;
    try {
      lines = readLines(path);
    } catch (IOException e) {
      return Optional.absent();
    }

    return Optional.fromNullable(Iterables.get(lines, 0, null));
  }

  @Override
  public List<String> readLines(Path path) throws IOException {
    Optional<String> contents = readFileIfItExists(path);
    if (!contents.isPresent() || contents.get().isEmpty()) {
      return ImmutableList.of();
    }
    String content = contents.get();
    content = content.endsWith("\n") ? content.substring(0, content.length() - 1) : content;
    return Splitter.on('\n').splitToList(content);
  }

  @Override
  public String computeSha1(Path path) throws IOException {
    if (!exists(path)) {
      throw new FileNotFoundException(path.toString());
    }
    return Hashing.sha1().hashBytes(getFileBytes(path)).toString();
  }

  @Override
  public boolean isPathChangeEvent(WatchEvent<?> event) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void copyFolder(Path source, Path target) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void copyFile(Path source, Path target) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void createSymLink(Path source, Path target, boolean force) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void createZip(Iterable<Path> pathsToIncludeInZip, File out) {
    throw new UnsupportedOperationException();
  }
}
