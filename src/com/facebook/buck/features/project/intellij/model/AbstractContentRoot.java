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

package com.facebook.buck.features.project.intellij.model;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.features.project.intellij.model.folders.IjSourceFolder;
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractContentRoot implements Comparable<ContentRoot> {
  public abstract String getUrl();

  public abstract ImmutableList<IjSourceFolder> getFolders();

  @Override
  public int compareTo(ContentRoot o) {
    if (this == o) {
      return 0;
    }

    return getUrl().compareTo(o.getUrl());
  }
}
