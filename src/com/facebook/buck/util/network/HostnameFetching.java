/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.util.network;

import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.ptr.IntByReference;

import java.io.IOException;

public class HostnameFetching {
  private static final int HOSTNAME_MAX_LEN = 512;

  // Utility class; do not instantiate.
  private HostnameFetching() { }

  public static String getHostname() throws IOException {
    if (Platform.isWindows()) {
      return getHostnameWin32();
    } else {
      return getHostnamePosix();
    }
  }

  private static String getHostnamePosix() throws IOException {
    byte[] hostnameBuf = new byte[HOSTNAME_MAX_LEN];
    try {
      // Subtract 1 to ensure NUL termination.
      HostnameFetchingPosixLibrary.gethostname(hostnameBuf, hostnameBuf.length - 1);
      return Native.toString(hostnameBuf);
    } catch (LastErrorException e) {
      throw new IOException(e);
    }
  }

  private static String getHostnameWin32() throws IOException {
    char[] hostnameBuf = new char[HOSTNAME_MAX_LEN];
    IntByReference hostnameBufLen = new IntByReference(hostnameBuf.length);
    boolean result = HostnameFetchingWin32Library.INSTANCE.GetComputerNameEx(
        HostnameFetchingWin32Library.NAME_TYPE_DNS_HOSTNAME,
        hostnameBuf,
        hostnameBufLen);
    if (!result) {
      throw new IOException(String.format(
          "Call to GetComputerNameEx failed with code %d",
          Native.getLastError()));
    }
    return new String(hostnameBuf, 0, hostnameBufLen.getValue());
  }
}
