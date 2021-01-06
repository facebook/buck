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

package com.facebook.buck.jvm.java.abi.source;

import java.io.IOException;
import javax.annotation.processing.Filer;
import javax.lang.model.element.Element;
import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;

class TreeBackedFiler implements Filer {
  private final FrontendOnlyJavacTask task;
  private final Filer javacFiler;

  public TreeBackedFiler(FrontendOnlyJavacTask task, Filer javacFiler) {
    this.task = task;
    this.javacFiler = javacFiler;
  }

  @Override
  public JavaFileObject createSourceFile(CharSequence name, Element... originatingElements)
      throws IOException {
    return javacFiler.createSourceFile(
        name, task.getElements().getJavacElements(originatingElements));
  }

  @Override
  public JavaFileObject createClassFile(CharSequence name, Element... originatingElements)
      throws IOException {
    return javacFiler.createClassFile(
        name, task.getElements().getJavacElements(originatingElements));
  }

  @Override
  public FileObject createResource(
      JavaFileManager.Location location,
      CharSequence pkg,
      CharSequence relativeName,
      Element... originatingElements)
      throws IOException {
    return javacFiler.createResource(
        location, pkg, relativeName, task.getElements().getJavacElements(originatingElements));
  }

  @Override
  public FileObject getResource(
      JavaFileManager.Location location, CharSequence pkg, CharSequence relativeName)
      throws IOException {
    return javacFiler.getResource(location, pkg, relativeName);
  }
}
