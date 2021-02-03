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

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinBase;

/**
 * Wrapper class over {@link WinBase.OVERLAPPED} that also holds a reference to {@link
 * WindowsHandle} and close it in the {@link #close()} called
 */
class WindowsOverlapped {

  private final WinBase.OVERLAPPED overlapped;
  private final WindowsHandle windowsHandle;

  WindowsOverlapped(WindowsHandle windowsHandle) {
    this.windowsHandle = windowsHandle;
    this.overlapped = new WinBase.OVERLAPPED();
    overlapped.hEvent = windowsHandle.getHandle();
    overlapped.write();
  }

  public Pointer getPointer() {
    return overlapped.getPointer();
  }

  public void close() {
    windowsHandle.close();
  }
}
