/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.ide.intellij.model;

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import java.io.File;
import java.nio.file.Path;
import javax.annotation.Nullable;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractModuleIndexEntry implements Comparable<ModuleIndexEntry> {
  public abstract String getFileUrl();

  public abstract Path getFilePath();

  @Nullable
  public abstract String getGroup();

  @Override
  public int compareTo(ModuleIndexEntry o) {
    if (this == o) {
      return 0;
    }

    return getFilePath()
        .toString()
        .replace(File.separatorChar, ' ')
        .compareTo(o.getFilePath().toString().replace(File.separatorChar, ' '));
  }
}
