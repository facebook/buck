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

package com.facebook.buck.model;

import com.google.common.base.Preconditions;

import org.immutables.value.Value;

import java.util.regex.Pattern;

@Value.Immutable
public abstract class Flavor implements Comparable<Flavor> {

  private static final Pattern INVALID_FLAVOR_CHARACTERS = Pattern.compile("[^-a-zA-Z0-9_\\.]");

  public static String replaceInvalidCharacters(String name) {
    return INVALID_FLAVOR_CHARACTERS.matcher(name).replaceAll("_");
  }

  @Value.Parameter
  public abstract String getName();

  @Value.Check
  protected void check() {
    Preconditions.checkArgument(
        !getName().isEmpty(),
        "Empty flavor name");
    Preconditions.checkArgument(
        !INVALID_FLAVOR_CHARACTERS.matcher(getName()).find(),
        "Invalid characters in flavor name: " + getName());
  }

  @Override
  public int compareTo(Flavor that) {
    if (this == that) {
      return 0;
    }

    return this.getName().compareTo(that.getName());
  }

  @Override
  public String toString() {
    return getName();
  }

}
