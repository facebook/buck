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

package com.facebook.buck.util.cache;

import com.facebook.buck.core.io.ArchiveMemberPath;
import com.facebook.buck.event.AbstractBuckEvent;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;

public class DelegatingFileHashCacheEngine implements FileHashCacheEngine {
  private final FileHashCacheEngine delegate;

  public DelegatingFileHashCacheEngine(FileHashCacheEngine delegate) {
    this.delegate = delegate;
  }

  @Override
  public void put(Path path, HashCodeAndFileType value) {
    delegate.put(path, value);
  }

  @Override
  public void putSize(Path path, long value) {
    delegate.putSize(path, value);
  }

  @Override
  public void invalidate(Path path) {
    delegate.invalidate(path);
  }

  @Override
  public void invalidateWithParents(Path path) {
    delegate.invalidateWithParents(path);
  }

  @Override
  public HashCode get(Path path) throws IOException {
    return delegate.get(path);
  }

  @Override
  public HashCode get(ArchiveMemberPath archiveMemberPath) throws IOException {
    return delegate.get(archiveMemberPath);
  }

  @Nullable
  @Override
  public HashCodeAndFileType getIfPresent(Path path) {
    return delegate.getIfPresent(path);
  }

  @Nullable
  @Override
  public Long getSizeIfPresent(Path path) {
    return delegate.getSizeIfPresent(path);
  }

  @Override
  public long getSize(Path relativePath) throws IOException {
    return delegate.getSize(relativePath);
  }

  @Override
  public void invalidateAll() {
    delegate.invalidateAll();
  }

  @Override
  public ConcurrentMap<Path, HashCodeAndFileType> asMap() {
    return delegate.asMap();
  }

  @Override
  public List<AbstractBuckEvent> getStatsEvents() {
    return delegate.getStatsEvents();
  }
}
