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

package com.facebook.buck.core.model;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import org.immutables.value.Value;

/**
 * A {@link com.facebook.buck.core.model.Flavor} passing information between targets, which is
 * irrelevant to the end user. Historically, all flavors were converted to internal, some need to be
 * moved to UserFlavor.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractInternalFlavor implements Flavor {

  @Override
  @Value.Parameter
  public abstract String getName();

  @Override
  @Value.Check
  public void check() {
    Flavor.super.check();
  }

  @Override
  public String toString() {
    return getName();
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof Flavor)) {
      return false;
    }

    return this.getName().equals(((Flavor) obj).getName());
  }

  @Override
  public int hashCode() {
    return this.getName().hashCode();
  }
}
