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
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;

/**
 * Tracks which classes are actually read by the compiler by providing a special {@link
 * JavaFileManager}.
 */
class ClassUsageTracker implements FileManagerListener {
  private static final String FILE_SCHEME = "file";
  private static final String JAR_SCHEME = "jar";
  private static final String JIMFS_SCHEME = "jimfs"; // Used in tests

  // Examples: First anonymous class is Foo$1.class. First local class named Bar is Foo$1Bar.class.
  private static final Pattern LOCAL_OR_ANONYMOUS_CLASS = Pattern.compile("^.*\\$\\d.*.class$");

  private final ImmutableSetMultimap.Builder<Path, Path> resultBuilder =
      ImmutableSetMultimap.builder();

  /**
   * Returns a multimap from JAR path on disk to .class file paths within the jar for any classes
   * that were used.
   */
  public ImmutableSetMultimap<Path, Path> getClassUsageMap() {
    return resultBuilder.build();
  }

  @Override
  public void onFileRead(FileObject fileObject) {
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
    String[] split = jarUriSchemeSpecificPart.split("!/");
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

  @Override
  public void onFileWritten(FileObject file) {}

  private boolean isLocalOrAnonymousClass(String className) {
    return LOCAL_OR_ANONYMOUS_CLASS.matcher(className).matches();
  }
}
