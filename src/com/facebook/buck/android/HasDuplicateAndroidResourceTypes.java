/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import com.facebook.buck.core.sourcepath.SourcePath;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.immutables.value.Value;

public interface HasDuplicateAndroidResourceTypes {
  // Do not inspect this, getAllowedDuplicateResourcesTypes, or getBannedDuplicateResourceTypes
  // directly, use getEffectiveBannedDuplicateResourceTypes.
  // Ideally these should be private, but Arg-population doesn't allow that.
  //
  // If set to ALLOW_BY_DEFAULT, bannedDuplicateResourceTypes is used and setting
  // allowedDuplicateResourceTypes is an error.
  //
  // If set to BAN_BY_DEFAULT, allowedDuplicateResourceTypes is used and setting
  // bannedDuplicateResourceTypes is an error.
  // This only exists to enable migration from allowing by default to banning by default.
  @Value.Default
  default DuplicateResourceBehaviour getDuplicateResourceBehavior() {
    return DuplicateResourceBehaviour.ALLOW_BY_DEFAULT;
  }

  Set<RType> getAllowedDuplicateResourceTypes();

  Set<RType> getBannedDuplicateResourceTypes();

  Optional<SourcePath> getDuplicateResourceWhitelist();

  @Value.Derived
  default EnumSet<RType> getEffectiveBannedDuplicateResourceTypes() {
    if (getDuplicateResourceBehavior() == DuplicateResourceBehaviour.ALLOW_BY_DEFAULT) {
      if (!getAllowedDuplicateResourceTypes().isEmpty()) {
        throw new IllegalArgumentException(
            "Cannot set allowed_duplicate_resource_types if "
                + "duplicate_resource_behaviour is allow_by_default");
      }
      if (!getBannedDuplicateResourceTypes().isEmpty()) {
        return EnumSet.copyOf(getBannedDuplicateResourceTypes());
      } else {
        return EnumSet.noneOf(RType.class);
      }
    } else if (getDuplicateResourceBehavior() == DuplicateResourceBehaviour.BAN_BY_DEFAULT) {
      if (!getBannedDuplicateResourceTypes().isEmpty()) {
        throw new IllegalArgumentException(
            "Cannot set banned_duplicate_resource_types if "
                + "duplicate_resource_behaviour is ban_by_default");
      }
      if (!getAllowedDuplicateResourceTypes().isEmpty()) {
        return EnumSet.complementOf(EnumSet.copyOf(getAllowedDuplicateResourceTypes()));
      } else {
        return EnumSet.allOf(RType.class);
      }
    } else {
      throw new IllegalArgumentException(
          "Unrecognized duplicate_resource_behavior: " + getDuplicateResourceBehavior());
    }
  }

  enum DuplicateResourceBehaviour {
    ALLOW_BY_DEFAULT,
    BAN_BY_DEFAULT
  }
}
