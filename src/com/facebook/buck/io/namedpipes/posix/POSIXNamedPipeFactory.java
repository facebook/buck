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

package com.facebook.buck.io.namedpipes.posix;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.namedpipes.NamedPipe;
import com.facebook.buck.io.namedpipes.NamedPipeFactory;
import java.io.IOException;

/** POSIX named pipe factory. */
public class POSIXNamedPipeFactory implements NamedPipeFactory {

  @Override
  public NamedPipe create() throws IOException {
    return new POSIXNamedPipe.OwnedPOSIXNamedPipe(generateNamedPathName());
  }

  @Override
  public NamedPipe connect(AbsPath namedPipePath) throws IOException {
    return new POSIXNamedPipe(namedPipePath);
  }
}
