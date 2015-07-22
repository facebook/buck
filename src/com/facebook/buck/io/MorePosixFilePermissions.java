/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.io;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

public class MorePosixFilePermissions {

  private MorePosixFilePermissions() {}

  private static final ImmutableList<PosixFilePermission> ORDERED_PERMISSIONS =
      ImmutableList.of(
          PosixFilePermission.OTHERS_EXECUTE,
          PosixFilePermission.OTHERS_WRITE,
          PosixFilePermission.OTHERS_READ,
          PosixFilePermission.GROUP_EXECUTE,
          PosixFilePermission.GROUP_WRITE,
          PosixFilePermission.GROUP_READ,
          PosixFilePermission.OWNER_EXECUTE,
          PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.OWNER_READ);

  private static final ImmutableMap<PosixFilePermission, PosixFilePermission> READ_TO_EXECUTE_MAP =
      ImmutableMap.of(
          PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE,
          PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
          PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE);

  /**
   * Convert a set of posix file permissions the unix bit representation (e.g. 0644).
   */
  public static long toMode(Set<PosixFilePermission> permissions) {
    long mode = 0;

    for (int index = 0; index < ORDERED_PERMISSIONS.size(); index++) {
      PosixFilePermission permission = ORDERED_PERMISSIONS.get(index);
      if (permissions.contains(permission)) {
        mode |= (1 << index);
      }
    }

    return mode;
  }

  /**
   * Convert a unix bit representation (e.g. 0644) into a set of posix file permissions.
   */
  public static ImmutableSet<PosixFilePermission> fromMode(long mode) {
    ImmutableSet.Builder<PosixFilePermission> permissions = ImmutableSet.builder();

    for (int index = 0; index < ORDERED_PERMISSIONS.size(); index++) {
      if ((mode & (1 << index)) != 0) {
        permissions.add(ORDERED_PERMISSIONS.get(index));
      }
    }

    return permissions.build();
  }

  /**
   * Return a new set of permissions which include execute permission for each of the
   * roles that already have read permissions (e.g. 0606 =&gt; 0707).
   */
  public static ImmutableSet<PosixFilePermission> addExecutePermissionsIfReadable(
      Set<PosixFilePermission> permissions) {

    ImmutableSet.Builder<PosixFilePermission> newPermissions = ImmutableSet.builder();

    // The new permissions are a superset of the current ones.
    newPermissions.addAll(permissions);

    // If we see a read permission for the given role, add in the corresponding
    // execute permission.
    for (ImmutableMap.Entry<PosixFilePermission, PosixFilePermission> ent :
        READ_TO_EXECUTE_MAP.entrySet()) {
      if (permissions.contains(ent.getKey())) {
        newPermissions.add(ent.getValue());
      }
    }

    return newPermissions.build();
  }

}
