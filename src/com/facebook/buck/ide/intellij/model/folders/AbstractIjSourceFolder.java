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

package com.facebook.buck.ide.intellij.model.folders;

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import java.nio.file.Path;
import javax.annotation.Nullable;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractIjSourceFolder implements Comparable<IjSourceFolder> {
  public abstract String getType();

  public abstract String getUrl();

  public abstract Path getPath();

  public abstract boolean getIsTestSource();

  public abstract boolean getIsResourceFolder();

  public abstract IjResourceFolderType getIjResourceFolderType();

  @Nullable
  public abstract Path getRelativeOutputPath();

  @Nullable
  public abstract String getPackagePrefix();

  @Override
  public int compareTo(IjSourceFolder o) {
    if (this == o) {
      return 0;
    }

    return getUrl().compareTo(o.getUrl());
  }
}
