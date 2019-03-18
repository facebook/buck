/*
 * Copyright 2018-present Facebook, Inc.
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
package com.facebook.buck.parser;

import com.facebook.buck.core.cell.CellProvider;
import com.facebook.buck.core.files.DirectoryListCache;
import com.facebook.buck.core.files.FileTreeCache;
import com.facebook.buck.event.BuckEventBus;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import java.nio.file.Path;

/** Creates instance of {@link TargetSpecResolver} that could be used in tests */
public class TestTargetSpecResolverFactory {

  /** Create new instance of {@link TargetSpecResolver} that could be used in tests */
  public static TargetSpecResolver create(CellProvider cellProvider, BuckEventBus eventBus) {
    return new TargetSpecResolver(
        eventBus,
        4,
        cellProvider,
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<Path, DirectoryListCache>() {
                  @Override
                  public DirectoryListCache load(Path path) {
                    return DirectoryListCache.of(path);
                  }
                }),
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<Path, FileTreeCache>() {
                  @Override
                  public FileTreeCache load(Path path) {
                    return FileTreeCache.of(path);
                  }
                }));
  }
}
