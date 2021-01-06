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

package com.facebook.buck.util.network.hostname;

import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

public interface HostnameFetchingWin32Library extends StdCallLibrary {
  HostnameFetchingWin32Library INSTANCE =
      Native.loadLibrary(
          "kernel32", HostnameFetchingWin32Library.class, W32APIOptions.UNICODE_OPTIONS);

  // See sysinfoapi.h
  int COMPUTER_NAME_FORMAT__ComputerNameNetBIOS = 0;
  int COMPUTER_NAME_FORMAT__ComputerNameDnsHostname = 1;
  int COMPUTER_NAME_FORMAT__ComputerNameDnsDomain = 2;
  int COMPUTER_NAME_FORMAT__ComputerNameDnsFullyQualified = 3;
  int COMPUTER_NAME_FORMAT__ComputerNamePhysicalNetBIOS = 4;
  int COMPUTER_NAME_FORMAT__ComputerNamePhysicalDnsHostname = 5;
  int COMPUTER_NAME_FORMAT__ComputerNamePhysicalDnsDomain = 6;
  int COMPUTER_NAME_FORMAT__ComputerNamePhysicalDnsFullyQualified = 7;

  boolean GetComputerNameEx(int nameType, char[] buffer, IntByReference bufferSize);
}
