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

package com.facebook.buck.log;

import java.io.IOException;
import java.io.OutputStreamWriter;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ReferenceCountedWriterTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  private OutputStreamWriter mockWriter;

  @Before
  public void setUp() {
    mockWriter = EasyMock.createMock(OutputStreamWriter.class);
  }

  @Test
  public void testWithSingleReference() throws IOException {
    mockWriter.close();
    EasyMock.expectLastCall().once();
    EasyMock.replay(mockWriter);

    new ReferenceCountedWriter(mockWriter).close();
    EasyMock.verify(mockWriter);
  }

  @Test
  public void testWithDoubleReference() throws IOException {
    mockWriter.flush(); // Mock will throw if anything else is called.
    EasyMock.replay(mockWriter);

    ReferenceCountedWriter ref1 = new ReferenceCountedWriter(mockWriter);
    ReferenceCountedWriter ref2 = ref1.newReference();

    ref1.close();
    EasyMock.verify(mockWriter);

    EasyMock.reset(mockWriter);
    mockWriter.close();
    EasyMock.expectLastCall().once();
    EasyMock.replay(mockWriter);
    ref2.close();
    EasyMock.verify(mockWriter);
  }

  @Test
  public void testClosingAfterRefCountIsBelowZero() throws IOException {
    mockWriter.close();
    EasyMock.expectLastCall().once();
    EasyMock.replay(mockWriter);

    ReferenceCountedWriter ref = new ReferenceCountedWriter(mockWriter);
    ref.close();
    ref.close();
    EasyMock.verify(mockWriter);
  }

  @Test
  public void testNewReferenceAfterBeingClosed() throws Exception {
    ReferenceCountedWriter ref = new ReferenceCountedWriter(mockWriter);
    ref.close();
    expectedException.expect(RuntimeException.class);
    expectedException.expectMessage("ReferenceCountedWriter is closed!");
    ref.newReference();
  }
}
