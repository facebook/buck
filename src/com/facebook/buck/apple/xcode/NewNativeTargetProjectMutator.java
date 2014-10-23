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

package com.facebook.buck.apple.xcode;

import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXGroup;
import com.facebook.buck.apple.xcode.xcodeproj.PBXNativeTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.model.BuildTarget;
import com.google.common.base.Optional;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Configures a PBXProject by adding a PBXNativeTarget and its associated dependencies into a
 * PBXProject object graph.
 */
public class NewNativeTargetProjectMutator {
  public static class Result {
    public final PBXNativeTarget target;
    public final PBXGroup targetGroup;

    private Result(PBXNativeTarget target, PBXGroup targetGroup) {
      this.target = target;
      this.targetGroup = targetGroup;
    }
  }

  private PBXTarget.ProductType productType = PBXTarget.ProductType.BUNDLE;
  private Path productOutputPath = Paths.get("");
  private String productName = "";
  private String targetName;
  private Optional<String> gid = Optional.absent();

  public NewNativeTargetProjectMutator(BuildTarget buildTarget) {
    this.targetName = buildTarget.getFullyQualifiedName();
  }


  /**
   * Set product related configuration.
   *
   * @param productType       declared product type
   * @param productName       product display name
   * @param productOutputPath build output relative product path.
   */
  public NewNativeTargetProjectMutator setProduct(
      PBXNativeTarget.ProductType productType,
      String productName,
      Path productOutputPath) {
    this.productName = productName;
    this.productType = productType;
    this.productOutputPath = productOutputPath;
    return this;
  }

  public NewNativeTargetProjectMutator setGid(Optional<String> gid) {
    this.gid = gid;
    return this;
  }

  public NewNativeTargetProjectMutator setTargetName(String targetName) {
    this.targetName = targetName;
    return this;
  }

  public Result buildAndAddToProject(PBXProject project) {
    PBXNativeTarget target = new PBXNativeTarget(targetName, productType);
    PBXGroup targetGroup = project.getMainGroup().getOrCreateChildGroupByName(targetName);

    if (gid.isPresent()) {
      target.setGlobalID(gid.get());
    }

    // Product

    PBXGroup productsGroup = project.getMainGroup().getOrCreateChildGroupByName("Products");
    PBXFileReference productReference = productsGroup.getOrCreateFileReferenceBySourceTreePath(
        new SourceTreePath(PBXReference.SourceTree.BUILT_PRODUCTS_DIR, productOutputPath));
    target.setProductName(productName);
    target.setProductReference(productReference);

    return new Result(target, targetGroup);
  }

}
