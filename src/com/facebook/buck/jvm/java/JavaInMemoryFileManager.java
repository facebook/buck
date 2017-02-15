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

import com.facebook.buck.log.Logger;
import com.facebook.buck.util.PatternsMatcher;
import com.facebook.buck.zip.CustomZipEntry;
import com.facebook.buck.zip.CustomZipOutputStream;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
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
  private static final Logger LOG = Logger.get(JavaInMemoryFileManager.class);

  private CustomZipOutputStream jarOutputStream;
  private StandardJavaFileManager delegate;
  private Semaphore jarFileSemaphore = new Semaphore(1);
  private Set<String> directoryPaths;
  private Set<String> fileForOutputPaths;
  private PatternsMatcher classesToRemoveFromJar;

  public JavaInMemoryFileManager(
      StandardJavaFileManager standardManager,
      CustomZipOutputStream jarOutputStream,
      ImmutableSet<Pattern> classesToRemoveFromJar) {
    super(standardManager);
    this.delegate = standardManager;
    this.jarOutputStream = jarOutputStream;
    this.directoryPaths = new HashSet<>();
    this.fileForOutputPaths = new HashSet<>();
    this.classesToRemoveFromJar = new PatternsMatcher(classesToRemoveFromJar);
  }

  /**
   * Creates a ZipEntry for placing in the jar output stream. Sets the modification time to 0 for
   * a deterministic jar.
   * @param name the name of the entry
   * @return the zip entry for the file specified
   */
  public static ZipEntry createEntry(String name) {
    CustomZipEntry entry = new CustomZipEntry(name);
    // We want deterministic JARs, so avoid mtimes.
    entry.setFakeTime();
    return entry;
  }

  private static String getPath(String className) {
    return className.replace('.', '/');
  }

  private static String getPath(String className, JavaFileObject.Kind kind) {
    return className.replace('.', '/') + kind.extension;
  }

  private static String getPath(String packageName, String relativeName) {
    return !packageName.isEmpty() ?
        packageName.replace('.', '/') + '/' + relativeName :
        relativeName;
  }

  @Override
  public JavaFileObject getJavaFileForOutput(
      Location location,
      String className,
      JavaFileObject.Kind kind,
      FileObject sibling) throws IOException {

    // Use the normal FileObject that writes to the disk for source files.
    if (kind.equals(JavaFileObject.Kind.SOURCE)) {
      return delegate.getJavaFileForOutput(location, className, kind, sibling);
    }

    String path = getPath(className, kind);
    // If the class is to be removed from the Jar create a NoOp FileObject.
    if (classesToRemoveFromJar.hasPatterns() &&
        classesToRemoveFromJar.matches(className.toString())) {
      LOG.info(
          "%s was excluded from the Jar because it matched a remove_classes pattern.",
          className.toString());
      return createJavaNoOpFileObject(path, kind);
    }

    ensureDirectories(path);

    return createJavaMemoryFileObject(path, kind);
  }

  @Override
  public FileObject getFileForOutput(
      Location location,
      String packageName,
      String relativeName,
      FileObject sibling) throws IOException {
    if (super.getFileForInput(location, packageName, relativeName) != null) {
      return super.getFileForOutput(location, packageName, relativeName, sibling);
    }
    String path = getPath(packageName, relativeName);
    ensureDirectories(path);

    return createMemoryFileObject(path);
  }

  @Override
  public boolean isSameFile(FileObject a, FileObject b) {
    boolean aInMemoryJavaFileInstance = a instanceof JavaInMemoryFileObject;
    boolean bInMemoryJavaFileInstance = b instanceof JavaInMemoryFileObject;
    if (aInMemoryJavaFileInstance || bInMemoryJavaFileInstance) {
      return aInMemoryJavaFileInstance &&
          bInMemoryJavaFileInstance &&
          a.getName().equals(b.getName());
    }
    boolean aInMemoryFileInstance = a instanceof InMemoryFileObject;
    boolean bInMemoryFileInstance = b instanceof InMemoryFileObject;
    if (aInMemoryFileInstance || bInMemoryFileInstance) {
      return aInMemoryFileInstance && bInMemoryFileInstance && a.getName().equals(b.getName());
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

  public ImmutableSet<String> getEntries() {
    return ImmutableSet.copyOf(Sets.union(directoryPaths, fileForOutputPaths));
  }

  /*
   * Creates the directories within the jar file to represent java packages
   */
  private void ensureDirectories(String filePath) throws IOException {
    for (int i = 0; i < filePath.length(); ++i) {
      if (filePath.charAt(i) == '/') {
        String directoryPath = getPath(filePath.substring(0, i + 1));
        if (directoryPaths.contains(directoryPath)) {
          continue;
        }
        createDirectory(directoryPath);
        directoryPaths.add(directoryPath);
      }
    }
  }

  private void createDirectory(String name) throws IOException {
    JavaFileObject fileObject = createJavaMemoryFileObject(name, JavaFileObject.Kind.OTHER);
    jarFileSemaphore.acquireUninterruptibly();
    try {
      jarOutputStream.putNextEntry(createEntry(fileObject.getName()));
      jarOutputStream.closeEntry();
    } finally {
      jarFileSemaphore.release();
    }
  }

  private JavaFileObject createJavaMemoryFileObject(String path, JavaFileObject.Kind kind) {
    JavaFileObject obj = new JavaInMemoryFileObject(path, kind, jarOutputStream, jarFileSemaphore);
    fileForOutputPaths.add(obj.getName());
    return obj;
  }

  private JavaFileObject createJavaNoOpFileObject(String path, JavaFileObject.Kind kind) {
    JavaFileObject obj = new JavaNoOpFileObject(path, kind);
    fileForOutputPaths.add(obj.getName());
    return obj;
  }

  private FileObject createMemoryFileObject(String path) {
    JavaFileObject obj = new InMemoryFileObject(path, jarOutputStream, jarFileSemaphore);
    fileForOutputPaths.add(obj.getName());
    return obj;
  }
}
