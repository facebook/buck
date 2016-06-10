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

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;

import javax.tools.SimpleJavaFileObject;

/**
 * A {@link SimpleJavaFileObject} implementation that represents a {@link javax.tools.FileObject}
 * that has no operations and does not write the contents to any form of output. The only content
 * that stores is the path and kind of the {@link javax.tools.FileObject}.
 */
public class JavaNoOpFileObject extends SimpleJavaFileObject {

  public JavaNoOpFileObject(String path, Kind kind) {
    super(URI.create(path), kind);
  }

  @Override
  public OutputStream openOutputStream() throws IOException {
    return new OutputStream() {
      @Override
      public void write(int b) throws IOException {}

      @Override
      public void close() throws IOException {}
    };
  }

  @Override
  public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
    throw new UnsupportedOperationException();
  }
}
