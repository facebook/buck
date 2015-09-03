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

import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

public interface HostnameFetchingWin32Library extends StdCallLibrary {
  HostnameFetchingWin32Library INSTANCE = (HostnameFetchingWin32Library) Native.loadLibrary(
      "kernel32", HostnameFetchingWin32Library.class, W32APIOptions.UNICODE_OPTIONS);

  int NAME_TYPE_DNS_HOSTNAME = 1;

  @SuppressWarnings("checkstyle:methodname")
  boolean GetComputerNameEx(int nameType, char[] buffer, IntByReference bufferSize);
}
