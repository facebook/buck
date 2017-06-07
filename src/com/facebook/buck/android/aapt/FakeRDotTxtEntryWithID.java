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

package com.facebook.buck.android.aapt;

import com.google.common.base.Objects;

/**
 * An {@link RDotTxtEntry} with given {@link #idValue}, useful for comparing two resource entries
 * for equality, since {@link RDotTxtEntry#compareTo(RDotTxtEntry)} and {@link
 * RDotTxtEntry#equals(Object)} ignores the id value.
 */
public class FakeRDotTxtEntryWithID extends RDotTxtEntry {

  public FakeRDotTxtEntryWithID(IdType idType, RType type, String name, String idValue) {
    super(idType, type, name, idValue);
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof RDotTxtEntry)) {
      return false;
    }

    RDotTxtEntry that = (RDotTxtEntry) obj;
    return Objects.equal(this.type, that.type)
        && Objects.equal(this.name, that.name)
        && Objects.equal(this.idValue, that.idValue);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(type, name, idValue);
  }
}
