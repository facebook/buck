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

package com.facebook.buck.js;

import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

public class JsFlavors {
  public static final ImmutableFlavor ANDROID = ImmutableFlavor.of("android");
  public static final ImmutableFlavor IOS = ImmutableFlavor.of("ios");
  public static final ImmutableFlavor PROD = ImmutableFlavor.of("prod");

  private static final ImmutableSet<Flavor> platforms = ImmutableSet.of(ANDROID, IOS);
  private static final ImmutableSet<Flavor> other = ImmutableSet.of(PROD);

  public static boolean validateFlavors(ImmutableSet<Flavor> flavors) {
    return Sets.intersection(flavors, platforms).size() < 2 &&
           other.containsAll(Sets.difference(flavors, platforms));
  }

  public static String getPlatform(ImmutableSet<Flavor> flavors) {
    return flavors.contains(IOS) ? "ios" : "android";
  }

  private JsFlavors() {}
}
