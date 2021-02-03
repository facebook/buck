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

import static com.facebook.buck.io.namedpipes.windows.WindowsNamedPipeLibrary.INSTANCE;

import com.facebook.buck.core.util.log.Logger;
import com.google.common.base.Preconditions;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import java.io.Closeable;
import java.util.Optional;
import java.util.StringJoiner;

/** Wrapper around {@link WinNT.HANDLE} that is used to track if close() has been called. */
class WindowsHandle implements Closeable {

  private static final Logger LOG = Logger.get(WindowsHandle.class);

  private Optional<WinNT.HANDLE> handle;
  private final String description;

  private WindowsHandle(Optional<WinNT.HANDLE> handle, String description) {
    this.handle = handle;
    this.description = description;
  }

  /** Creates {@link WindowsHandle} */
  public static WindowsHandle of(WinNT.HANDLE handle, String description) {
    Preconditions.checkArgument(
        handle != null, "Handle has to be non null. Description: " + description);
    return new WindowsHandle(Optional.of(handle), description);
  }

  public boolean isInvalidHandle() {
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

    if (!INSTANCE.CloseHandle(getHandle())) {
      int error = INSTANCE.GetLastError();
      LOG.error("CloseHandle() failed. Handle: %s, error code: %s", toString(), error);
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
