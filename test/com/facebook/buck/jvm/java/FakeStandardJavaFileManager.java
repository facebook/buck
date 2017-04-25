/*
 * Copyright 2016-present Facebook, Inc.
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

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;

class FakeStandardJavaFileManager implements StandardJavaFileManager {

  private static final Function<File, JavaFileObject> fileToJavaFileObject =
      new Function<File, JavaFileObject>() {
        @Nullable
        @Override
        public JavaFileObject apply(@Nullable File input) {
          return new FakeJavaFileObject(null, input.getName(), JavaFileObject.Kind.CLASS);
        }
      };

  private static final Function<String, JavaFileObject> stringToJavaFileObject =
      new Function<String, JavaFileObject>() {
        @Nullable
        @Override
        public JavaFileObject apply(@Nullable String input) {
          return new FakeJavaFileObject(null, input, JavaFileObject.Kind.CLASS);
        }
      };

  private List<JavaFileObject> files = new ArrayList<>();

  public void addFile(Path jarPath, String fileName, JavaFileObject.Kind kind) {
    files.add(new FakeJavaFileObject(jarPath, fileName, kind));
  }

  private JavaFileObject getFile(String fileName) {
    for (JavaFileObject file : files) {
      if (file.getName().equals(fileName)) {
        return file;
      }
    }

    return null;
  }

  @Override
  public Iterable<JavaFileObject> list(
      Location location, String packageName, Set<JavaFileObject.Kind> kinds, boolean recurse)
      throws IOException {
    return files;
  }

  @Override
  public JavaFileObject getJavaFileForInput(
      Location location, String className, JavaFileObject.Kind kind) throws IOException {
    return getFile(className);
  }

  @Override
  public JavaFileObject getJavaFileForOutput(
      Location location, String className, JavaFileObject.Kind kind, FileObject sibling)
      throws IOException {
    return getFile(className);
  }

  @Override
  public FileObject getFileForInput(Location location, String packageName, String relativeName)
      throws IOException {
    return getFile(relativeName);
  }

  @Override
  public FileObject getFileForOutput(
      Location location, String packageName, String relativeName, FileObject sibling)
      throws IOException {
    return getFile(relativeName);
  }

  @Override
  public Iterable<? extends JavaFileObject> getJavaFileObjectsFromFiles(
      Iterable<? extends File> files) {
    return Iterables.transform(files, fileToJavaFileObject);
  }

  @Override
  public Iterable<? extends JavaFileObject> getJavaFileObjects(File... files) {
    return Iterables.transform(Lists.newArrayList(files), fileToJavaFileObject);
  }

  @Override
  public Iterable<? extends JavaFileObject> getJavaFileObjectsFromStrings(Iterable<String> names) {
    return Iterables.transform(names, stringToJavaFileObject);
  }

  @Override
  public Iterable<? extends JavaFileObject> getJavaFileObjects(String... names) {
    return Iterables.transform(Lists.newArrayList(names), stringToJavaFileObject);
  }

  @Override
  public ClassLoader getClassLoader(Location location) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String inferBinaryName(Location location, JavaFileObject file) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isSameFile(FileObject a, FileObject b) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean handleOption(String current, Iterator<String> remaining) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean hasLocation(Location location) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void flush() throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void close() throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setLocation(Location location, Iterable<? extends File> path) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Iterable<? extends File> getLocation(Location location) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int isSupportedOption(String option) {
    throw new UnsupportedOperationException();
  }

  public static class FakeJavaFileObject extends FakeFileObject implements JavaFileObject {

    private final Kind kind;

    private FakeJavaFileObject(@Nullable Path jarPath, String name, Kind kind) {
      super(jarPath, name);
      this.kind = kind;
    }

    @Override
    public Kind getKind() {
      return kind;
    }

    @Override
    public boolean isNameCompatible(String simpleName, Kind kind) {
      throw new UnsupportedOperationException();
    }

    @Override
    public NestingKind getNestingKind() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Modifier getAccessLevel() {
      throw new UnsupportedOperationException();
    }
  }

  private static class FakeFileObject implements FileObject {

    @Nullable protected final Path jarPath;
    protected final String name;

    private FakeFileObject(@Nullable Path jarPath, String name) {
      this.jarPath = jarPath;
      this.name = name;
    }

    @Override
    public String getName() {
      return name;
    }

    private static String getJarPath(Path jarPath, String filePath) {
      return String.format("jar:%s!/%s", jarPath.toUri().toString(), filePath);
    }

    @Override
    public URI toUri() {
      try {
        if (jarPath == null) {
          return Paths.get(name).toUri();
        } else {
          return new URI(getJarPath(jarPath, name));
        }
      } catch (URISyntaxException e) {
        throw new AssertionError(e);
      }
    }

    @Override
    public InputStream openInputStream() throws IOException {
      return null;
    }

    @Override
    public Reader openReader(boolean ignoreEncodingErrors) throws IOException {
      return null;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
      return null;
    }

    @Override
    public OutputStream openOutputStream() throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public Writer openWriter() throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public long getLastModified() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean delete() {
      throw new UnsupportedOperationException();
    }
  }
}
