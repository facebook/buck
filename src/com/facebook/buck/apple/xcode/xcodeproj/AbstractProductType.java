/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.apple.xcode.xcodeproj;

import com.facebook.buck.util.immutables.BuckStyleImmutable;

import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractProductType {
  public static final ProductType STATIC_LIBRARY = ProductType.of(
      "com.apple.product-type.library.static");
  public static final ProductType DYNAMIC_LIBRARY = ProductType.of(
      "com.apple.product-type.library.dynamic");
  public static final ProductType TOOL = ProductType.of(
      "com.apple.product-type.tool");
  public static final ProductType BUNDLE = ProductType.of(
      "com.apple.product-type.bundle");
  public static final ProductType FRAMEWORK = ProductType.of(
      "com.apple.product-type.framework");
  public static final ProductType STATIC_FRAMEWORK = ProductType.of(
      "com.apple.product-type.framework.static");
  public static final ProductType APPLICATION = ProductType.of(
      "com.apple.product-type.application");
  public static final ProductType WATCH_APPLICATION = ProductType.of(
      "com.apple.product-type.application.watchapp2");
  public static final ProductType UNIT_TEST = ProductType.of(
      "com.apple.product-type.bundle.unit-test");
  public static final ProductType APP_EXTENSION = ProductType.of(
      "com.apple.product-type.app-extension");

  @Value.Parameter
  public abstract String getIdentifier();

  @Override
  public String toString() {
    return getIdentifier();
  }
}
