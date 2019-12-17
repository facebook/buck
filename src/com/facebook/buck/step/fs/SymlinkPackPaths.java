/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.step.fs;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.Objects;

/**
 * Class which composes multiple {@link SymlinkPaths} as a single {@link SymlinkPaths} (analogous to
 * {@link com.facebook.buck.core.rules.impl.SymlinkPack}).
 */
public class SymlinkPackPaths implements SymlinkPaths {

  private final ImmutableList<SymlinkPaths> links;

  public SymlinkPackPaths(ImmutableList<SymlinkPaths> links) {
    this.links = links;
  }

  public static SymlinkPaths of(SymlinkPaths... links) {
    return new SymlinkPackPaths(ImmutableList.copyOf(links));
  }

  @Override
  public void forEachSymlink(SymlinkConsumer consumer) throws IOException {
    for (SymlinkPaths l : links) {
      l.forEachSymlink(consumer);
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    SymlinkPackPaths that = (SymlinkPackPaths) o;
    return links.equals(that.links);
  }

  @Override
  public int hashCode() {
    return Objects.hash(links);
  }
}
