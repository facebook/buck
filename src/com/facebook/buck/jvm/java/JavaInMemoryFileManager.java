/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import com.facebook.buck.zip.CustomZipOutputStream;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipEntry;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;

/**
 * A {@link StandardJavaFileManager} that creates and writes the content of files directly into a
 * Jar output stream instead of writing the files to disk.
 */
public class JavaInMemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager>
    implements StandardJavaFileManager {

  private CustomZipOutputStream jarOutputStream;
  private StandardJavaFileManager delegate;
  private Semaphore jarFileSemaphore = new Semaphore(1);
  private Set<String> existingDirectoryNames;

  public JavaInMemoryFileManager(
      StandardJavaFileManager standardManager,
      CustomZipOutputStream jarOutputStream) {
    super(standardManager);
    this.delegate = standardManager;
    this.jarOutputStream = jarOutputStream;
    this.existingDirectoryNames = new HashSet<>();
  }

  /**
   * Creates a ZipEntry for placing in the jar output stream. Sets the modification time to 0 for
   * a deterministic jar.
   * @param name the name of the entry
   * @return the zip entry for the file specified
   */
  public static ZipEntry createEntry(String name) {
    ZipEntry entry = new ZipEntry(name);
    entry.setTime(0);
    return entry;
  }

  @Override
  public JavaFileObject getJavaFileForOutput(
      Location location,
      String className,
      JavaFileObject.Kind kind,
      FileObject sibling) throws IOException {
    // Create the directories that are part of the class name path.
    for (int i = 0; i < className.length(); ++i) {
      if (className.charAt(i) == '.') {
        String directoryName = className.substring(0, i + 1);
        if (existingDirectoryNames.contains(directoryName)) {
          continue;
        }
        createDirectory(directoryName);
        existingDirectoryNames.add(directoryName);
      }
    }
    return createJavaMemoryFileObject(className, kind);
  }

  @Override
  public boolean isSameFile(FileObject a, FileObject b) {
    boolean aInMemoryInstance = a instanceof JavaInMemoryFileObject;
    boolean bInMemoryInstance = b instanceof JavaInMemoryFileObject;
    if (aInMemoryInstance || bInMemoryInstance) {
      return aInMemoryInstance && bInMemoryInstance && a.getName().equals(b.getName());
    }
    return super.isSameFile(a, b);
  }

  @Override
  public Iterable<? extends JavaFileObject> getJavaFileObjectsFromFiles(
      Iterable<? extends File> files) {
    return delegate.getJavaFileObjectsFromFiles(files);
  }

  @Override
  public Iterable<? extends JavaFileObject> getJavaFileObjects(File... files) {
    return delegate.getJavaFileObjects(files);
  }

  @Override
  public Iterable<? extends JavaFileObject> getJavaFileObjectsFromStrings(Iterable<String> names) {
    return delegate.getJavaFileObjectsFromStrings(names);
  }

  @Override
  public Iterable<? extends JavaFileObject> getJavaFileObjects(String... names) {
    return delegate.getJavaFileObjects(names);
  }

  @Override
  public void setLocation(
      Location location,
      Iterable<? extends File> path) throws IOException {
    delegate.setLocation(location, path);
  }

  @Override
  public Iterable<? extends File> getLocation(Location location) {
    return delegate.getLocation(location);
  }

  private void createDirectory(String name) throws IOException {
    JavaFileObject fileObject = createJavaMemoryFileObject(name, JavaFileObject.Kind.OTHER);
    jarFileSemaphore.acquireUninterruptibly();
    jarOutputStream.putNextEntry(createEntry(fileObject.getName()));
    jarOutputStream.closeEntry();
    jarFileSemaphore.release();
  }

  private JavaFileObject createJavaMemoryFileObject(String className, JavaFileObject.Kind kind) {
    return new JavaInMemoryFileObject(className, kind, jarOutputStream, jarFileSemaphore);
  }
}
