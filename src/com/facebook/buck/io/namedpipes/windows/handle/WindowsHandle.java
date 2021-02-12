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

package com.facebook.buck.io.namedpipes.windows.handle;

import com.facebook.buck.core.util.log.Logger;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import java.io.Closeable;
import java.util.Optional;
import java.util.StringJoiner;

/** Wrapper around {@link WinNT.HANDLE} that is used to track if close() has been called. */
public class WindowsHandle implements Closeable {

  private static final Logger LOG = Logger.get(WindowsHandle.class);

  private Optional<WinNT.HANDLE> handle;
  private final String description;

  WindowsHandle(Optional<WinNT.HANDLE> handle, String description) {
    this.handle = handle;
    this.description = description;
  }

  public boolean isInvalidHandle() {
    if (isClosed()) {
      return true;
    }
    return getHandle().equals(WinBase.INVALID_HANDLE_VALUE);
  }

  public WinNT.HANDLE getHandle() {
    return handle.orElseThrow(
        () -> new IllegalStateException("Handle is not available as it has been closed."));
  }

  public boolean isClosed() {
    return !handle.isPresent();
  }

  @Override
  public synchronized void close() {
    if (isClosed()) {
      return;
    }

    try {
      Kernel32Util.closeHandle(getHandle());
    } catch (Win32Exception e) {
      LOG.error(e, "Failed to close handle: %s", toString());
    }
    handle = Optional.empty();
  }

  @Override
  public String toString() {
    return new StringJoiner(", ", WindowsHandle.class.getSimpleName() + "[", "]")
        .add("description='" + description + "'")
        .add("isClosed=" + isClosed())
        .toString();
  }
}
