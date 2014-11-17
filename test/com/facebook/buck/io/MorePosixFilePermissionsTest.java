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

import static org.junit.Assert.assertEquals;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;

import org.junit.Test;

import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.Set;

public class MorePosixFilePermissionsTest {

  static class Sample {

    public final Set<PosixFilePermission> permissions;
    public final long mode;

    public Sample(long mode, Set<PosixFilePermission> permissions) {
      this.permissions = Preconditions.checkNotNull(permissions);
      this.mode = mode;
    }

  }

  // Some examples of converting between unix modes and perm sets.
  @SuppressWarnings("PMD.AvoidUsingOctalValues")
  private static final ImmutableList<Sample> CONVERSION_SAMPLES = ImmutableList.of(
      new Sample(040, Sets.newHashSet(
          PosixFilePermission.GROUP_READ)),
      new Sample(0600, Sets.newHashSet(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE)),
      new Sample(0777, Sets.newHashSet(
          PosixFilePermission.values())),
      new Sample(01, Sets.newHashSet(
          PosixFilePermission.OTHERS_EXECUTE)),
      new Sample(0, new HashSet<PosixFilePermission>())
  );

  // Some examples that test various cases of adding in execute perms for readers.
  private static final ImmutableMap<String, String> ADD_EXECUTE_PERM_SAMPLES = ImmutableMap.of(
      "rw-r--r--", "rwxr-xr-x",
      "rw----r--", "rwx---r-x",
      "------r--", "------r-x",
      "---------", "---------",
      "r-x------", "r-x------");

  @Test
  public void testConversionToAndFromDoesNotChangePermissions() {
    for (Sample sample : CONVERSION_SAMPLES) {
      assertEquals(
          sample.permissions,
          MorePosixFilePermissions.fromMode(
              MorePosixFilePermissions.toMode(sample.permissions)));
    }
  }

  @Test
  public void testConversionToAndFromDoesNotChangeMode() {
    for (Sample sample : CONVERSION_SAMPLES) {
      assertEquals(
          sample.mode,
          MorePosixFilePermissions.toMode(
              MorePosixFilePermissions.fromMode(sample.mode)));
    }
  }

  @Test
  public void testModeAndPermissionsAreEqualAfterConversionEitherWay() {
    for (Sample sample : CONVERSION_SAMPLES) {
      assertEquals(
          MorePosixFilePermissions.fromMode(sample.mode),
          sample.permissions);
      assertEquals(
          sample.mode,
          MorePosixFilePermissions.toMode(sample.permissions));
    }
  }

  @Test
  public void testAddingExecutePermissionsIfReadable() {
    for (ImmutableMap.Entry<String, String> ent : ADD_EXECUTE_PERM_SAMPLES.entrySet()) {
      assertEquals(
          MorePosixFilePermissions.addExecutePermissionsIfReadable(
              PosixFilePermissions.fromString(ent.getKey())),
          PosixFilePermissions.fromString(ent.getValue()));
    }
  }

}
