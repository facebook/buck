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

package com.facebook.buck.features.project.intellij.lang.android;

import com.facebook.buck.features.project.intellij.model.folders.IJFolderFactory;
import com.facebook.buck.features.project.intellij.model.folders.SelfMergingOnlyFolder;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;

/** A path which contains a set of sources we wish to present to IntelliJ. */
public class AndroidResourceFolder extends SelfMergingOnlyFolder {

  public static final IJFolderFactory FACTORY =
      (path, wantsPrefix, inputs) -> new AndroidResourceFolder(path, inputs);

  AndroidResourceFolder(Path path, ImmutableSortedSet<Path> inputs) {
    super(path, true, inputs);
  }

  public AndroidResourceFolder(Path path) {
    super(path);
  }

  @Override
  public IJFolderFactory getFactory() {
    return FACTORY;
  }

  @Override
  public boolean isResourceFolder() {
    return true;
  }
}
