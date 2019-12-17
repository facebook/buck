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

import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link SymlinkPaths} implemented via a static {@link ImmutableMap} of destination links to
 * {@link Path}s.
 */
public class SymlinkMapsPaths implements SymlinkPaths {

  private final ImmutableMap<Path, Path> links;

  public SymlinkMapsPaths(ImmutableMap<Path, Path> links) {
    this.links = links;
  }

  @Override
  public void forEachSymlink(SymlinkConsumer consumer) throws IOException {
    for (Map.Entry<Path, Path> link : links.entrySet()) {
      consumer.accept(link.getKey(), link.getValue());
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
    SymlinkMapsPaths that = (SymlinkMapsPaths) o;
    return links.equals(that.links);
  }

  @Override
  public int hashCode() {
    return Objects.hash(links);
  }
}
