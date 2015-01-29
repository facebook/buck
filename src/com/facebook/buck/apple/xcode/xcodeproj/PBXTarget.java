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

import com.facebook.buck.apple.xcode.XcodeprojSerializer;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.Lists;

import java.util.List;

import javax.annotation.Nullable;

import org.immutables.value.Value;

/**
 * Information for building a specific artifact (a library, binary, or test).
 */
public abstract class PBXTarget extends PBXProjectItem {
  @Value.Immutable
  @BuckStyleImmutable
  public abstract static class ProductType {
    public static final ProductType STATIC_LIBRARY = ImmutableProductType.of(
        "com.apple.product-type.library.static");
    public static final ProductType DYNAMIC_LIBRARY = ImmutableProductType.of(
        "com.apple.product-type.library.dynamic");
    public static final ProductType TOOL = ImmutableProductType.of(
        "com.apple.product-type.tool");
    public static final ProductType BUNDLE = ImmutableProductType.of(
        "com.apple.product-type.bundle");
    public static final ProductType FRAMEWORK = ImmutableProductType.of(
        "com.apple.product-type.framework");
    public static final ProductType STATIC_FRAMEWORK = ImmutableProductType.of(
        "com.apple.product-type.framework.static");
    public static final ProductType APPLICATION = ImmutableProductType.of(
        "com.apple.product-type.application");
    public static final ProductType UNIT_TEST = ImmutableProductType.of(
        "com.apple.product-type.bundle.unit-test");
    public static final ProductType APP_EXTENSION = ImmutableProductType.of(
        "com.apple.product-type.app-extension");

    @Value.Parameter
    public abstract String getIdentifier();

    @Override
    public String toString() {
      return getIdentifier();
    }
  }

  private final String name;
  private final ProductType productType;
  private final List<PBXTargetDependency> dependencies;
  private final List<PBXBuildPhase> buildPhases;
  private final XCConfigurationList buildConfigurationList;
  @Nullable private String productName;
  @Nullable private PBXFileReference productReference;

  public PBXTarget(String name, ProductType productType) {
    this.name = name;
    this.productType = productType;
    this.dependencies = Lists.newArrayList();
    this.buildPhases = Lists.newArrayList();
    this.buildConfigurationList = new XCConfigurationList();
  }

  public String getName() {
    return name;
  }

  public ProductType getProductType() {
    return productType;
  }

  public List<PBXTargetDependency> getDependencies() {
    return dependencies;
  }

  public List<PBXBuildPhase> getBuildPhases() {
    return buildPhases;
  }

  public XCConfigurationList getBuildConfigurationList() {
    return buildConfigurationList;
  }

  @Nullable
  public String getProductName() {
    return productName;
  }

  public void setProductName(String productName) {
    this.productName = productName;
  }

  @Nullable
  public PBXFileReference getProductReference() {
    return productReference;
  }

  public void setProductReference(PBXFileReference v) {
    productReference = v;
  }

  @Override
  public String isa() {
    return "PBXTarget";
  }

  @Override
  public int stableHash() {
    return name.hashCode();
  }

  @Override
  public void serializeInto(XcodeprojSerializer s) {
    super.serializeInto(s);

    s.addField("name", name);
    s.addField("productType", productType.toString());
    if (productName != null) {
      s.addField("productName", productName);
    }
    if (productReference != null) {
      s.addField("productReference", productReference);
    }
    s.addField("dependencies", dependencies);
    s.addField("buildPhases", buildPhases);
    s.addField("buildConfigurationList", buildConfigurationList);
  }
}
