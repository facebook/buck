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

package com.facebook.buck.file;

import com.facebook.buck.event.BuckEventBus;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

public interface Downloader {

  /**
   * Download the given URL and, upon a successful download, place it in {@code output}. Note that
   * if a {@code Downloader} can't handle the {@link URI#scheme} then {@code false} will be
   * returned.
   *
   * @return Whether or not the download succeeded.
   * @throws IOException Should an exception be thrown when downloading.
   */
  boolean fetch(BuckEventBus eventBus, URI uri, Path output) throws IOException;
}
