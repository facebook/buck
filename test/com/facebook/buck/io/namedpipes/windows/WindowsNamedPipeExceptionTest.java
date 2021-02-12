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

package com.facebook.buck.io.namedpipes.windows;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.facebook.buck.io.namedpipes.windows.handle.WindowsHandle;
import com.facebook.buck.io.namedpipes.windows.handle.WindowsHandleFactory;
import com.sun.jna.platform.win32.WinNT;
import org.junit.Test;

public class WindowsNamedPipeExceptionTest {

  @Test
  public void exceptionMessageFormatting() {
    int currentlyOpened = WindowsHandle.getNumberOfOpenedHandles();
    int createWinHandles = 5;
    String namedPipe = "/testNamedPipe/path";
    int connectError = 123;

    createWindowsHandles(createWinHandles);

    WindowsNamedPipeException windowsNamedPipeException =
        new WindowsNamedPipeException(
            "MyMagicOperation() failed. Named pipe: %s, error: %s",
            namedPipe, getErrorMessageByCode(connectError));

    assertThat(
        windowsNamedPipeException.getMessage(),
        equalTo(
            "MyMagicOperation() failed. "
                + "Named pipe: /testNamedPipe/path, "
                + "error: Pretty error message for: 123"
                + " Opened handles count: "
                + (currentlyOpened + createWinHandles)));
  }

  @Test
  public void exceptionMessageFormattingWithEmptyParams() {
    int currentlyOpened = WindowsHandle.getNumberOfOpenedHandles();
    int createWinHandles = 3;

    createWindowsHandles(createWinHandles);

    WindowsNamedPipeException windowsNamedPipeException =
        new WindowsNamedPipeException("MyMagicOperation() failed.");

    assertThat(
        windowsNamedPipeException.getMessage(),
        equalTo(
            "MyMagicOperation() failed."
                + " Opened handles count: "
                + (currentlyOpened + createWinHandles)));
  }

  private void createWindowsHandles(int howMany) {
    WindowsHandleFactory windowsHandleFactory = WindowsNamedPipeFactory.windowsHandleFactory;
    for (int i = 0; i < howMany; i++) {
      windowsHandleFactory.create(new WinNT.HANDLE(), "test");
    }
  }

  private String getErrorMessageByCode(int errorCode) {
    return "Pretty error message for: " + errorCode;
  }
}
