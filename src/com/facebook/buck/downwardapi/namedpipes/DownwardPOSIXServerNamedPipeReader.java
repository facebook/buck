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

import com.facebook.buck.downward.model.EndEvent;
import com.facebook.buck.downward.model.EventTypeMessage;
import com.facebook.buck.downwardapi.protocol.DownwardProtocol;
import com.facebook.buck.downwardapi.protocol.DownwardProtocolType;
import com.facebook.buck.io.namedpipes.NamedPipeFactory;
import com.facebook.buck.io.namedpipes.NamedPipeWriter;
import com.facebook.buck.io.namedpipes.posix.POSIXServerNamedPipeReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;

/** {@link POSIXServerNamedPipeReader} specific to Downward API. */
public class DownwardPOSIXServerNamedPipeReader extends POSIXServerNamedPipeReader
    implements DownwardNamedPipeServer {

  private static final long SHUTDOWN_TIMEOUT = 2;
  private static final TimeUnit SHUTDOWN_TIMEOUT_UNIT = TimeUnit.SECONDS;

  @Nullable private DownwardProtocol protocol;

  protected DownwardPOSIXServerNamedPipeReader(Path path) throws IOException {
    super(path);
  }

  @Override
  public void setProtocol(DownwardProtocol protocol) {
    if (this.protocol != null) {
      throw new IllegalStateException(
          "Cannot set downward protocol again once it has been established!");
    }
    this.protocol = protocol;
  }

  @Nullable
  @Override
  public DownwardProtocol getProtocol() {
    return protocol;
  }

  @Override
  public void prepareToClose(Future<Void> readyToClose)
      throws IOException, ExecutionException, TimeoutException, InterruptedException {
    try (NamedPipeWriter writer =
            NamedPipeFactory.getFactory().connectAsWriter(Paths.get(getName()));
        OutputStream outputStream = writer.getOutputStream()) {
      // This null check is not perfectly synchronized with the handler, but in practice by the
      // time the subprocess has finished, the handler should have read the protocol from the
      // subprocess already, if any, so this is okay.
      DownwardProtocol protocol = getProtocol();
      if (protocol == null) {
        // Client has not written anything into named pipe. Arbitrarily pick binary protocol to
        // communicate with handler
        DownwardProtocolType protocolType = DownwardProtocolType.BINARY;
        protocolType.writeDelimitedTo(outputStream);
        protocol = protocolType.getDownwardProtocol();
      }
      protocol.write(
          EventTypeMessage.newBuilder().setEventType(EventTypeMessage.EventType.END_EVENT).build(),
          EndEvent.getDefaultInstance(),
          outputStream);
      readyToClose.get(SHUTDOWN_TIMEOUT, SHUTDOWN_TIMEOUT_UNIT);
    }
  }
}
