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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThrows;

import com.facebook.buck.downwardapi.protocol.DownwardProtocolType;
import com.facebook.buck.testutil.TemporaryPaths;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class DownwardPOSIXServerNamedPipeReaderTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private DownwardPOSIXServerNamedPipeReader reader;

  @Before
  public void setUp() throws Exception {
    reader = new DownwardPOSIXServerNamedPipeReader(tmp.newFile().getPath());
  }

  @Test
  public void setProtocol() {
    DownwardProtocolType downwardProtocolType = DownwardProtocolType.JSON;
    reader.setProtocol(downwardProtocolType.getDownwardProtocol());
    assertThat(reader.getProtocol(), is(notNullValue()));
    assertThat(reader.getProtocol(), is(equalTo(downwardProtocolType.getDownwardProtocol())));
  }

  @Test
  public void setProtocolTwice() {
    DownwardProtocolType downwardProtocolType = DownwardProtocolType.BINARY;

    reader.setProtocol(downwardProtocolType.getDownwardProtocol());
    assertThat(reader.getProtocol(), is(notNullValue()));
    assertThat(reader.getProtocol(), is(downwardProtocolType.getDownwardProtocol()));
    reader.setProtocol(downwardProtocolType.getDownwardProtocol());
    assertThat(reader.getProtocol(), is(notNullValue()));
    assertThat(reader.getProtocol(), is(downwardProtocolType.getDownwardProtocol()));
  }

  @Test
  public void setDifferentProtocolOnceItHasBeenEstablished() {
    DownwardProtocolType existingProtocol = DownwardProtocolType.BINARY;
    DownwardProtocolType newProtocol = DownwardProtocolType.JSON;

    reader.setProtocol(existingProtocol.getDownwardProtocol());
    assertThat(reader.getProtocol(), is(notNullValue()));
    assertThat(reader.getProtocol(), is(existingProtocol.getDownwardProtocol()));

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () -> reader.setProtocol(newProtocol.getDownwardProtocol()));
    assertThat(
        exception.getMessage(),
        is("Cannot set a downward protocol to `json` once it has been established to `binary`"));
  }
}
