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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSetMultimap;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

/**
 * Tracks which classes are actually read by the compiler by providing a special {@link
 * JavaFileManager}.
 */
class ClassUsageTracker {
  private static final String FILE_SCHEME = "file";
  private static final String JAR_SCHEME = "jar";
  private static final String JIMFS_SCHEME = "jimfs"; // Used in tests

  // Examples: First anonymous class is Foo$1.class. First local class named Bar is Foo$1Bar.class.
  private static final Pattern LOCAL_OR_ANONYMOUS_CLASS = Pattern.compile("^.*\\$\\d.*.class$");

  private final ImmutableSetMultimap.Builder<Path, Path> resultBuilder =
      ImmutableSetMultimap.builder();

  @Nullable private ImmutableSetMultimap<Path, Path> result;

  /**
   * Returns a {@link JavaFileManager} that tracks which files are opened. Provide this to {@code
   * JavaCompiler.getTask} anytime file usage tracking is desired.
   */
  public StandardJavaFileManager wrapFileManager(StandardJavaFileManager inner) {
    return new UsageTrackingFileManager(inner);
  }

  /**
   * Returns a multimap from JAR path on disk to .class file paths within the jar for any classes
   * that were used.
   */
  public ImmutableSetMultimap<Path, Path> getClassUsageMap() {
    if (result == null) {
      result = resultBuilder.build();
    }

    return result;
  }

  private void addReadFile(FileObject fileObject) {
    Preconditions.checkState(result == null); // Can't add after having built

    if (!(fileObject instanceof JavaFileObject)) {
      return;
    }

    JavaFileObject javaFileObject = (JavaFileObject) fileObject;

    URI classFileJarUri = javaFileObject.toUri();
    if (!classFileJarUri.getScheme().equals(JAR_SCHEME)) {
      // Not in a jar; must not have been built with java_library
      return;
    }

    // The jar: scheme is somewhat underspecified. See the JarURLConnection docs
    // for the closest thing it has to documentation.
    String jarUriSchemeSpecificPart = classFileJarUri.getRawSchemeSpecificPart();
    final String[] split = jarUriSchemeSpecificPart.split("!/");
    Preconditions.checkState(split.length == 2);

    if (isLocalOrAnonymousClass(split[1])) {
      // The compiler reads local and anonymous classes because of the naive way in which it
      // completes the enclosing class, but changes to them can't affect compilation of dependent
      // classes so we don't need to consider them "used".
      return;
    }

    URI jarFileUri = URI.create(split[0]);
    Preconditions.checkState(
        jarFileUri.getScheme().equals(FILE_SCHEME)
            || jarFileUri.getScheme().equals(JIMFS_SCHEME)); // jimfs is used in tests
    Path jarFilePath = Paths.get(jarFileUri);

    // Using URI.create here for de-escaping
    Path classPath = Paths.get(URI.create(split[1]).toString());

    Preconditions.checkState(jarFilePath.isAbsolute());
    Preconditions.checkState(!classPath.isAbsolute());
    resultBuilder.put(jarFilePath, classPath);
  }

  private boolean isLocalOrAnonymousClass(String className) {
    return LOCAL_OR_ANONYMOUS_CLASS.matcher(className).matches();
  }

  private class UsageTrackingFileManager extends ForwardingStandardJavaFileManager {

    private final FileObjectTracker fileTracker = new FileObjectTracker();

    public UsageTrackingFileManager(StandardJavaFileManager fileManager) {
      super(fileManager);
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
      // javac does not play nice with wrapped file objects in this method; so we unwrap
      return super.inferBinaryName(location, unwrap(file));
    }

    @Override
    public boolean isSameFile(FileObject a, FileObject b) {
      // javac does not play nice with wrapped file objects in this method; so we unwrap
      return super.isSameFile(unwrap(a), unwrap(b));
    }

    private JavaFileObject unwrap(JavaFileObject file) {
      if (file instanceof TrackingJavaFileObject) {
        return ((TrackingJavaFileObject) file).getJavaFileObject();
      }
      return file;
    }

    private FileObject unwrap(FileObject file) {
      if (file instanceof JavaFileObject) {
        return unwrap((JavaFileObject) file);
      }

      return file;
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromFiles(
        Iterable<? extends File> files) {
      return fileManager.getJavaFileObjectsFromFiles(files);
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjects(File... files) {
      return fileManager.getJavaFileObjects(files);
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromStrings(
        Iterable<String> names) {
      return fileManager.getJavaFileObjectsFromStrings(names);
    }

    @Override
    public Iterable<? extends JavaFileObject> getJavaFileObjects(String... names) {
      return fileManager.getJavaFileObjects(names);
    }

    @Override
    public Iterable<JavaFileObject> list(
        Location location, String packageName, Set<JavaFileObject.Kind> kinds, boolean recurse)
        throws IOException {
      Iterable<JavaFileObject> listIterator = super.list(location, packageName, kinds, recurse);
      if (location == StandardLocation.ANNOTATION_PROCESSOR_PATH) {
        return listIterator;
      } else {
        return new TrackingIterable(listIterator);
      }
    }

    @Override
    public JavaFileObject getJavaFileForInput(
        Location location, String className, JavaFileObject.Kind kind) throws IOException {
      JavaFileObject javaFileObject = super.getJavaFileForInput(location, className, kind);
      if (location == StandardLocation.ANNOTATION_PROCESSOR_PATH) {
        return javaFileObject;
      } else {
        return fileTracker.wrap(javaFileObject);
      }
    }

    @Override
    public JavaFileObject getJavaFileForOutput(
        Location location, String className, JavaFileObject.Kind kind, FileObject sibling)
        throws IOException {
      JavaFileObject javaFileObject =
          super.getJavaFileForOutput(location, className, kind, sibling);
      if (location == StandardLocation.ANNOTATION_PROCESSOR_PATH) {
        return javaFileObject;
      } else {
        return fileTracker.wrap(javaFileObject);
      }
    }

    @Override
    public FileObject getFileForInput(Location location, String packageName, String relativeName)
        throws IOException {
      FileObject fileObject = super.getFileForInput(location, packageName, relativeName);
      if (location == StandardLocation.ANNOTATION_PROCESSOR_PATH) {
        return fileObject;
      } else {
        return fileTracker.wrap(fileObject);
      }
    }

    @Override
    public FileObject getFileForOutput(
        Location location, String packageName, String relativeName, FileObject sibling)
        throws IOException {
      FileObject fileObject = super.getFileForOutput(location, packageName, relativeName, sibling);
      if (location == StandardLocation.ANNOTATION_PROCESSOR_PATH) {
        return fileObject;
      } else {
        return fileTracker.wrap(fileObject);
      }
    }

    private class TrackingIterable implements Iterable<JavaFileObject> {
      private final Iterable<? extends JavaFileObject> inner;

      public TrackingIterable(final Iterable<? extends JavaFileObject> inner) {
        this.inner = inner;
      }

      @Override
      public Iterator<JavaFileObject> iterator() {
        return new TrackingIterator(inner.iterator());
      }
    }

    private class TrackingIterator implements Iterator<JavaFileObject> {

      private final Iterator<? extends JavaFileObject> inner;

      public TrackingIterator(final Iterator<? extends JavaFileObject> inner) {
        this.inner = inner;
      }

      @Override
      public boolean hasNext() {
        return inner.hasNext();
      }

      @Override
      public JavaFileObject next() {
        return fileTracker.wrap(inner.next());
      }

      @Override
      public void remove() {
        inner.remove();
      }
    }
  }

  private class FileObjectTracker {
    private final Map<JavaFileObject, JavaFileObject> javaFileObjectCache = new IdentityHashMap<>();

    public FileObject wrap(FileObject inner) {
      if (inner instanceof JavaFileObject) {
        return wrap((JavaFileObject) inner);
      }

      return inner;
    }

    public JavaFileObject wrap(JavaFileObject inner) {
      if (!javaFileObjectCache.containsKey(inner)) {
        javaFileObjectCache.put(inner, new TrackingJavaFileObject(inner));
      }

      return Preconditions.checkNotNull(javaFileObjectCache.get(inner));
    }
  }

  private class TrackingJavaFileObject extends ForwardingJavaFileObject<JavaFileObject> {
    public TrackingJavaFileObject(JavaFileObject fileObject) {
      super(fileObject);
    }

    public JavaFileObject getJavaFileObject() {
      return fileObject;
    }

    @Override
    public InputStream openInputStream() throws IOException {
      addReadFile(fileObject);
      return super.openInputStream();
    }

    @Override
    public Reader openReader(boolean ignoreEncodingErrors) throws IOException {
      addReadFile(fileObject);
      return super.openReader(ignoreEncodingErrors);
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
      addReadFile(fileObject);
      return super.getCharContent(ignoreEncodingErrors);
    }
  }
}
