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

package com.facebook.buck.downwardapi.testutil;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.facebook.buck.io.namedpipes.windows.handle.DefaultWindowsHandleFactory;
import com.facebook.buck.io.namedpipes.windows.handle.WindowsHandle;
import com.google.common.base.Preconditions;
import com.sun.jna.platform.win32.WinNT;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class TestWindowsHandleFactory extends DefaultWindowsHandleFactory {

  private final List<WindowsHandle> createdWindowsHandles = new ArrayList<>();

  @Override
  public WindowsHandle create(WinNT.HANDLE handle, String description) {
    WindowsHandle windowsHandle = super.create(handle, description);
    createdWindowsHandles.add(windowsHandle);
    return windowsHandle;
  }

  /** Verifies that all created windows handles are closed. */
  public void verifyAllCreatedHandlesClosed() {
    assertThat(createdWindowsHandles.isEmpty(), equalTo(false));
    Predicate<WindowsHandle> isClosed = WindowsHandle::isClosed;
    boolean allClosed = createdWindowsHandles.stream().allMatch(isClosed);
    Preconditions.checkState(
        allClosed,
        "Some of the created WindowsHandle has not been closed. Not closed handles:"
            + createdWindowsHandles.stream()
                .filter(isClosed.negate())
                .map(Objects::toString)
                .collect(Collectors.joining(System.lineSeparator())));
  }
}
