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

import com.facebook.buck.zip.JarBuilder;
import java.net.URI;
import javax.annotation.Nullable;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;

/**
 * A {@link JavaFileObject} implementation that allows using jar URIs unlike {@link
 * SimpleJavaFileObject} that assumes the uri used is a file system uri. This implementation can be
 * used when the CLASS_OUTPUT is represented by a straight to jar write. The only content that it
 * stores is the full uri, the relative path within the jar and the kind of the {@link FileObject}.
 */
public abstract class JarFileObject implements JavaFileObject {

  protected final URI uri;
  protected final String pathInJar;
  protected final Kind kind;

  public JarFileObject(URI uri, String pathInJar, Kind kind) {
    this.uri = uri;
    this.pathInJar = pathInJar;
    this.kind = kind;
  }

  @Override
  public URI toUri() {
    return uri;
  }

  @Override
  public String getName() {
    return pathInJar;
  }

  @Override
  public long getLastModified() {
    return 0L;
  }

  @Override
  public boolean delete() {
    return false;
  }

  @Override
  public Kind getKind() {
    return this.kind;
  }

  @Override
  public boolean isNameCompatible(String simpleName, Kind kind) {
    String baseName = simpleName + kind.extension;
    return kind.equals(getKind())
        && (baseName.equals(pathInJar) || pathInJar.endsWith("/" + baseName));
  }

  @Override
  @Nullable
  public NestingKind getNestingKind() {
    return null;
  }

  @Override
  @Nullable
  public Modifier getAccessLevel() {
    return null;
  }

  @Override
  public String toString() {
    return this.getClass().getName() + "[" + toUri() + "]";
  }

  public abstract void writeToJar(JarBuilder jarBuilder, String owner);
}
