/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java;

import java.io.File;
import java.io.IOException;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;

public class ForwardingStandardJavaFileManager
    extends ForwardingJavaFileManager<StandardJavaFileManager> implements StandardJavaFileManager {

  public ForwardingStandardJavaFileManager(StandardJavaFileManager fileManager) {
    super(fileManager);
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
  public Iterable<? extends JavaFileObject> getJavaFileObjectsFromStrings(Iterable<String> names) {
    return fileManager.getJavaFileObjectsFromStrings(names);
  }

  @Override
  public Iterable<? extends JavaFileObject> getJavaFileObjects(String... names) {
    return fileManager.getJavaFileObjects(names);
  }

  @Override
  public void setLocation(Location location, Iterable<? extends File> path) throws IOException {
    fileManager.setLocation(location, path);
  }

  @Override
  public Iterable<? extends File> getLocation(Location location) {
    return fileManager.getLocation(location);
  }
}
