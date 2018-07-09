/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.io.windowsfs;

import java.io.IOException;
import java.nio.file.Path;

/** Utility class for working with windows FS */
public class WindowsFS {

  /**
   * The Windows build we are running could be ones that contain the createSymlink with non
   * privileged flag support or not.
   *
   * <p>The reason for this enum is to keep state as of what Windows version we are using.
   *
   * <p>Win 10 Creator added a flag to allow creation of symlink when not elevated. There is no easy
   * way to distinguish between builds of Win 10. Windows versioning group suggests to code things
   * in a defensive way, so we can detect the version by a failure and falling to an older API.
   */
  enum WindowsPrivilegedApiUsage {
    Unknown,
    UseUnprivilegedApi,
    UsePrivilegedApi
  }

  private WindowsPrivilegedApiUsage windowsPrivilegedApiStatus = WindowsPrivilegedApiUsage.Unknown;

  /** A POD type that wraps the result of a call to the Java RT createSymbolicLink. */
  static class NativeCreateSymlinkResult {
    private final boolean result;
    private final int errorCode;

    public NativeCreateSymlinkResult(boolean result, int errorCode) {
      this.result = result;
      this.errorCode = errorCode;
    }

    public boolean getResult() {
      return result;
    }

    public int getErrorCode() {
      return errorCode;
    }
  }

  /**
   * Creates a symbolic link (using CreateSymbolicLink winapi call under the hood), passing the
   * SYMBOLIC_LINK_FLAG_ALLOW_UNPRIVILEGED_CREATE which was introduced in Win 10 Creator that allows
   * creation of symlinks not assuming elevated execution.
   *
   * @param symlink the path of the symbolic link to create
   * @param target the target of the symbolic link
   * @param dirLink whether the target is a directory
   * @return The result of CreateSymbolicLink with the error code for the operation.
   */
  private NativeCreateSymlinkResult unPrivilegedCreateSymbolicLink(
      String symlink, String target, boolean dirLink) {
    int flags =
        (dirLink ? WindowsFSLibrary.SYMBOLIC_LINK_FLAG_DIRECTORY : 0)
            | WindowsFSLibrary.SYMBOLIC_LINK_FLAG_ALLOW_UNPRIVILEGED_CREATE;
    boolean created = WindowsFSLibrary.INSTANCE.CreateSymbolicLinkW(symlink, target, flags) != 0;
    int lastError = WindowsFSLibrary.INSTANCE.GetLastError();
    return new NativeCreateSymlinkResult(created, lastError);
  }

  /**
   * Creates a symbolic link (using CreateSymbolicLink winapi call under the hood), not passing the
   * SYMBOLIC_LINK_FLAG_ALLOW_UNPRIVILEGED_CREATE, thus assuming this is an elevated execution.
   *
   * @param symlink the path of the symbolic link to create
   * @param target the target of the symbolic link
   * @param dirLink whether the target is a directory
   * @return The result of CreateSymbolicLink with the error code for the operation.
   */
  private NativeCreateSymlinkResult privilegedCreateSymbolicLink(
      String symlink, String target, boolean dirLink) {
    int flags = (dirLink ? WindowsFSLibrary.SYMBOLIC_LINK_FLAG_DIRECTORY : 0);
    boolean created = WindowsFSLibrary.INSTANCE.CreateSymbolicLinkW(symlink, target, flags) != 0;
    int lastError = WindowsFSLibrary.INSTANCE.GetLastError();
    return new NativeCreateSymlinkResult(created, lastError);
  }

  /**
   * Creates a symbolic link (using CreateSymbolicLink winapi call under the hood).
   *
   * @param symlink the path of the symbolic link to create
   * @param target the target of the symbolic link
   * @param dirLink whether the target is a directory
   * @throws IOException if an underlying system call fails
   */
  public void createSymbolicLink(Path symlink, Path target, boolean dirLink) throws IOException {
    String symlinkPathString = (symlink.isAbsolute() ? "\\\\?\\" : "") + symlink;
    String targetPathString = (target.isAbsolute() ? "\\\\?\\" : "") + target;

    NativeCreateSymlinkResult nativeResult;
    boolean failureDueToDevModeNotEnabled = false;

    switch (windowsPrivilegedApiStatus) {
      case Unknown:
        // First try creating the symlink using the NON_PRIVILEGED flag.
        nativeResult = unPrivilegedCreateSymbolicLink(symlinkPathString, targetPathString, dirLink);
        if (!nativeResult.getResult()
            && (nativeResult.getErrorCode() == WindowsFSLibrary.INVALID_PARAMETER_ERROR
                || nativeResult.getErrorCode() == WindowsFSLibrary.ERROR_PRIVILEGE_NOT_HELD)) {
          // Failed! Try without the NON_PRIVILEGED flag (this requires elevated run).
          NativeCreateSymlinkResult nativeResultPrivileged =
              privilegedCreateSymbolicLink(symlinkPathString, targetPathString, dirLink);

          // Set to use the PRIVILEGED call if that succeeds.
          if (nativeResultPrivileged.getResult()) {
            nativeResult = nativeResultPrivileged;
            windowsPrivilegedApiStatus = WindowsPrivilegedApiUsage.UsePrivilegedApi;
          } else if (nativeResult.getErrorCode() == WindowsFSLibrary.ERROR_PRIVILEGE_NOT_HELD) {
            // Failed to used non-privileged API! The Developer Mode not enabled.
            failureDueToDevModeNotEnabled = true;
          }
        } else {
          // Success for using the non privileged API - it is there and it is enabled.
          // From now on use the NON_PRIVILEGED flag only.
          windowsPrivilegedApiStatus = WindowsPrivilegedApiUsage.UseUnprivilegedApi;
        }
        break;
      case UseUnprivilegedApi:
        nativeResult = unPrivilegedCreateSymbolicLink(symlinkPathString, targetPathString, dirLink);
        break;
      case UsePrivilegedApi:
        nativeResult = privilegedCreateSymbolicLink(symlinkPathString, targetPathString, dirLink);
        break;
      default:
        nativeResult = new NativeCreateSymlinkResult(false, -1);
        assert false : "Invalid WindowsPrivilegedApiUsage value!";
    }

    if (!nativeResult.getResult()) {
      String message =
          "Tried to link "
              + symlinkPathString
              + " to "
              + targetPathString
              + " (winapi error: "
              + nativeResult.getErrorCode()
              + ")";

      if (failureDueToDevModeNotEnabled) {
        message +=
            ". You seem to be running Win 10 Creator or later OS with no Developer Mode enabled.\r\n"
                + " Please enable Developer Mode for non-privileged symlink creation to work.\r\n"
                + " Running Buck in elevated mode will also allow symlink creation.";
      } else if (windowsPrivilegedApiStatus == WindowsPrivilegedApiUsage.Unknown
          && nativeResult.getErrorCode() == WindowsFSLibrary.INVALID_PARAMETER_ERROR) {
        message +=
            ". You are running an OS earlier than Windows 10 Creator. Either upgrade to a\r\n"
                + "post-Creator version and enable Developer Mode, or run Buck in elevated mode\r\n"
                + "for symlink creation to work.\r\n"
                + "See https://buckbuild.com/setup/getting_started.html for more details.";
      }
      throw new IOException(message);
    }
  }
}
