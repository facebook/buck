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
package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.watchman.Watchman;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.nio.file.Path;

/**
 * Allows multiple concurrently executing futures to share a constrained number of parsers.
 *
 * <p>Parser instances are lazily created up till a fixed maximum. If more than max parser are
 * requested the associated 'requests' are queued up. As soon as a parser is returned it will be
 * used to satisfy the first pending request, otherwise it is "parked".
 */
public interface FileParserPool<T> extends AutoCloseable {

  ListenableFuture<T> getManifest(
      BuckEventBus buckEventBus,
      Cell cell,
      Watchman watchman,
      Path parseFile,
      ListeningExecutorService executorService);
}
