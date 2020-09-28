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

package com.facebook.buck.intellij.ideabuck.file;

import com.intellij.lang.Language;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Represents a in memory VirtualFile that is associated to a VirtualFile on disk. */
public class LightVirtualFileWithDiskFile extends LightVirtualFile {
  private final VirtualFile onDiskFile;

  @NotNull
  @Override
  public String getPath() {
    return onDiskFile.getPath();
  }

  @NotNull
  @Override
  public String getName() {
    return onDiskFile.getName();
  }

  @Nullable
  @Override
  public String getCanonicalPath() {
    return onDiskFile.getCanonicalPath();
  }

  @Override
  public VirtualFile getParent() {
    return onDiskFile.getParent();
  }

  public LightVirtualFileWithDiskFile(
      String name, Language language, String content, VirtualFile onDiskFile) {
    super(name, language, content);
    this.onDiskFile = onDiskFile;
  }
}
