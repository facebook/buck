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

package com.facebook.buck.downwardapi;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/** Downward API Protocol type. */
public enum DownwardProtocolType {
  BINARY("b"),
  JSON("j");

  private final String protocolId;

  DownwardProtocolType(String protocolId) {
    this.protocolId = protocolId;
  }

  public void writeDelimitedTo(OutputStream outputStream) throws IOException {
    outputStream.write(protocolId.getBytes(UTF_8));
    DownwardProtocolUtils.writeDelimiter(outputStream);
  }

  /** Reads {@code DownwardProtocol} from {@code inputStream}. */
  public static DownwardProtocol readProtocol(InputStream inputStream) throws IOException {
    return DownwardProtocolUtils.readFromStream(
            inputStream,
            protocolId ->
                Arrays.stream(values())
                    .filter(p -> p.protocolId.equals(protocolId))
                    .findFirst()
                    .orElseThrow(IllegalStateException::new))
        .getDownwardProtocol();
  }

  /** Returns {@code DownwardProtocol}. */
  public DownwardProtocol getDownwardProtocol() {
    switch (this) {
      case JSON:
        return JsonDownwardProtocol.INSTANCE;
      case BINARY:
        return BinaryDownwardProtocol.INSTANCE;
    }
    throw new IllegalStateException(this + " is not supported!");
  }
}
