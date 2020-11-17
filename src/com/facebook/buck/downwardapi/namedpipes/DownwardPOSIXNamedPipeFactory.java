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

package com.facebook.buck.downwardapi.namedpipes;

import com.facebook.buck.io.namedpipes.NamedPipeFactory;
import com.facebook.buck.io.namedpipes.NamedPipeReader;
import com.facebook.buck.io.namedpipes.NamedPipeWriter;
import com.facebook.buck.io.namedpipes.posix.POSIXNamedPipeFactory;
import java.io.IOException;
import java.nio.file.Path;

/** {@link POSIXNamedPipeFactory} specific to Downward API. */
public enum DownwardPOSIXNamedPipeFactory implements NamedPipeFactory {
  INSTANCE;

  private final POSIXNamedPipeFactory delegate = POSIXNamedPipeFactory.INSTANCE;

  @Override
  public NamedPipeWriter createAsWriter() throws IOException {
    return delegate.createAsWriter();
  }

  @Override
  public NamedPipeReader createAsReader() throws IOException {
    Path namedPathName = POSIXNamedPipeFactory.createNamedPipe();
    return new DownwardPOSIXServerNamedPipeReader(namedPathName);
  }

  @Override
  public NamedPipeWriter connectAsWriter(Path namedPipePath) throws IOException {
    return delegate.connectAsWriter(namedPipePath);
  }

  @Override
  public NamedPipeReader connectAsReader(Path namedPipePath) throws IOException {
    return delegate.connectAsReader(namedPipePath);
  }
}
