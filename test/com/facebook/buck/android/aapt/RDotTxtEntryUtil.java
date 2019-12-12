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

package com.facebook.buck.android.aapt;

import com.facebook.buck.android.aapt.RDotTxtEntry.CustomDrawableType;
import com.facebook.buck.android.aapt.RDotTxtEntry.IdType;
import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import com.google.common.base.Objects;
import javax.annotation.Nullable;

/**
 * Util class for testing {@link RDotTxtEntry} objects. Testing {@link RDotTxtEntry} objects is
 * somewhat error prone; equality and hashing on these objects is only checked against the type and
 * the name (though the comparator also considers the parent...).
 *
 * <p>Use the abstractions in this class to more easily test against the fields you care about
 * (though this is still somewhat error prone as you need to make sure you are comparing 2 {@link
 * FakeEntry} objects).
 */
public class RDotTxtEntryUtil {

  private static final String FAKE_ID = "0x00000000";

  public static RDotTxtEntry matchDefault(RDotTxtEntry entry) {
    return FakeEntry.create(entry.idType, entry.type, entry.name);
  }

  /** Return an entry that checks equality against the id + the default fields. */
  public static RDotTxtEntry matchCustomDrawables(RDotTxtEntry entry) {
    return FakeEntry.createWithCustomDrawable(
        entry.idType, entry.type, entry.name, entry.customType);
  }

  /** Return an entry that checks equality against the custom type + the default fields. */
  public static RDotTxtEntry matchId(RDotTxtEntry entry) {
    return FakeEntry.createWithId(entry.idType, entry.type, entry.name, entry.idValue);
  }

  /** Return an entry that checks equality against the parent + the default fields. */
  public static RDotTxtEntry matchParent(RDotTxtEntry entry) {
    return FakeEntry.createWithParent(entry.idType, entry.type, entry.name, entry.parent);
  }

  public static class FakeEntry extends RDotTxtEntry {

    private final boolean customTypeDefined;

    public static FakeEntry create(IdType idType, RType type, String name) {
      return new FakeEntry(idType, type, name, FAKE_ID, null, null);
    }

    public static FakeEntry createWithId(IdType idType, RType type, String name, String idValue) {
      return new FakeEntry(idType, type, name, idValue, null, null);
    }

    public static FakeEntry createWithCustomDrawable(
        IdType idType, RType type, String name, CustomDrawableType customType) {
      return new FakeEntry(idType, type, name, null, customType, null);
    }

    public static FakeEntry createWithParent(
        IdType idType, RType type, String name, String parent) {
      return new FakeEntry(idType, type, name, FAKE_ID, null, parent);
    }

    private FakeEntry(
        IdType idType,
        RType type,
        String name,
        @Nullable String idValue,
        @Nullable CustomDrawableType customType,
        @Nullable String parent) {
      super(
          idType,
          type,
          name,
          idValue == null ? FAKE_ID : idValue,
          customType == null ? CustomDrawableType.NONE : customType,
          parent);

      customTypeDefined = customType != null;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof FakeEntry)) {
        throw new IllegalStateException(
            String.format(
                "Testing objects should only be compared against other testing objects. Call the conversion methods in the testing class. (Class: %s)",
                obj.getClass()));
      }

      FakeEntry that = (FakeEntry) obj;
      return customTypeDefined == that.customTypeDefined
          && Objects.equal(type, that.type)
          && Objects.equal(name, that.name)
          && Objects.equal(idValue, that.idValue)
          && Objects.equal(customType, that.customType)
          && Objects.equal(parent, that.parent);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(customTypeDefined, type, name, idValue, customType, parent);
    }
  }
}
