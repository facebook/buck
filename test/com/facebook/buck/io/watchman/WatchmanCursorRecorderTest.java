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

package com.facebook.buck.io.watchman;

import static org.hamcrest.junit.MatcherAssert.assertThat;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class WatchmanCursorRecorderTest {

  private final ProjectFilesystem fileSystem = new FakeProjectFilesystem();

  @Test
  public void testWatchmanCursorReadWrite() {
    WatchmanCursorRecord cursorRecord = new WatchmanCursorRecord("c:123:123", "ababa");
    WatchmanCursorRecorder recorder =
        new WatchmanCursorRecorder(fileSystem.getBuckPaths().getBuckOut().getPath());
    // update the local watchman cursor
    recorder.recordWatchmanCursor(cursorRecord);
    // read the latest watchman cursor
    Optional<WatchmanCursorRecord> cursorOptional = recorder.readWatchmanCursorRecord();
    // assert the read value is the same as updated value
    assertThat(cursorOptional.isPresent(), Matchers.equalTo(true));
    assertThat(cursorOptional.get(), Matchers.equalTo(cursorRecord));
  }
}
