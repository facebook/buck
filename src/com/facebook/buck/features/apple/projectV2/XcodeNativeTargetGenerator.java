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

package com.facebook.buck.features.apple.projectV2;

import com.facebook.buck.apple.AppleBinaryDescription;
import com.facebook.buck.apple.AppleBundleDescription;
import com.facebook.buck.apple.AppleBundleDescriptionArg;
import com.facebook.buck.apple.AppleBundleExtension;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.AppleNativeTargetDescriptionArg;
import com.facebook.buck.apple.AppleTestDescription;
import com.facebook.buck.apple.AppleTestDescriptionArg;
import com.facebook.buck.apple.xcode.xcodeproj.ProductType;
import com.facebook.buck.apple.xcode.xcodeproj.ProductTypes;
import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.features.halide.HalideLibraryDescription;
import com.google.common.annotations.VisibleForTesting;
import java.util.Optional;

/**
 * Generates all the target attributes {@link GeneratedTargetAttributes} to be written into a {@link
 * com.facebook.buck.apple.xcode.xcodeproj.PBXNativeTarget}.
 */
public class XcodeNativeTargetGenerator {

  final TargetNode<?> targetNode;
  final TargetGraph targetGraph;
  final BuildTarget buildTarget;

  /**
   * @param targetNode The target node for which to generate attributes for.
   * @param targetGraph The graph the target node belongs to.
   */
  public XcodeNativeTargetGenerator(TargetNode<?> targetNode, TargetGraph targetGraph) {
    this.targetNode = targetNode;
    this.targetGraph = targetGraph;
    this.buildTarget = targetNode.getBuildTarget();
  }

  /**
   * Generate target attributes based on the target node.
   *
   * @return Attributes parsed from the target node.
   */
  GeneratedTargetAttributes generate() {
    GeneratedTargetAttributes.Builder builder = GeneratedTargetAttributes.builder();

    builder.setProductType(getProductType(targetNode, targetGraph));

    return builder.build();
  }

  /**
   * Get the {@link ProductType} for a target node within a target graph.
   *
   * @param targetNode Target node to get the product type.
   * @param targetGraph Target graph the {@code targetNode}
   * @return A {@link ProductType} corresponding to the target if one exists.
   */
  @VisibleForTesting
  static Optional<ProductType> getProductType(TargetNode<?> targetNode, TargetGraph targetGraph) {
    BaseDescription<?> targetNodeDesc = targetNode.getDescription();

    if (targetNodeDesc instanceof AppleBinaryDescription) {
      // We treat apple binary targets as a TOOL
      // TODO(chatatap): Figure out why this is, and why bundles are extracted for their binaries
      return Optional.of(ProductTypes.TOOL);
    } else if (targetNodeDesc instanceof AppleBundleDescription) {
      TargetNode<AppleBundleDescriptionArg> bundleNode =
          TargetNodes.castArg(targetNode, AppleBundleDescriptionArg.class).get();
      Optional<String> xCodeProductType = bundleNode.getConstructorArg().getXcodeProductType();
      if (xCodeProductType.isPresent()) {
        // If there is an Xcode product type set, use that directly.
        return Optional.of(ProductType.of(xCodeProductType.get()));
      } else if (bundleNode.getConstructorArg().getExtension().isLeft()) {
        // apple_bundles (https://buck.build/rule/apple_bundle.html) must have a binary (which must
        // be an apple_binary or apple_library) attribute.
        TargetNode<AppleNativeTargetDescriptionArg> binaryNode =
            TargetNodes.castArg(
                    targetGraph.get(getBundleBinaryTarget(bundleNode)),
                    AppleNativeTargetDescriptionArg.class)
                .get();
        DescriptionWithTargetGraph<?> binaryNodeDesc =
            (DescriptionWithTargetGraph<?>) binaryNode.getDescription();
        AppleBundleExtension extension = bundleNode.getConstructorArg().getExtension().getLeft();
        if (binaryNodeDesc instanceof AppleLibraryDescription
            || binaryNodeDesc instanceof CxxLibraryDescription) {
          // If an apple_library attribute,
          if (binaryNode
              .getBuildTarget()
              .getFlavors()
              .contains(CxxDescriptionEnhancer.SHARED_FLAVOR)) {
            // That is build as a shared library, get the product type based on the file extension
            // of the output.
            return dylibProductTypeByBundleExtension(extension);
          } else if (extension == AppleBundleExtension.FRAMEWORK) {
            // otherwise treat as a static framework.
            return Optional.of(ProductTypes.STATIC_FRAMEWORK);
          }
        } else if (binaryNodeDesc instanceof AppleBinaryDescription) {
          // If a binary is set, the bundle extension should also be .app
          if (extension == AppleBundleExtension.APP) {
            return Optional.of(ProductTypes.APPLICATION);
          }
        }
      }
      // Fall through and treat as an apple bundle. The targets are most likely not configured
      // correctly.
      return Optional.of(ProductTypes.BUNDLE);
    } else if (targetNodeDesc instanceof AppleLibraryDescription
        || targetNodeDesc instanceof CxxLibraryDescription) {
      // Apple/Cxx libraries are treated as dynamic or static libraries based on the shared flavor.
      boolean isShared =
          targetNode.getBuildTarget().getFlavors().contains(CxxDescriptionEnhancer.SHARED_FLAVOR);
      return Optional.of(isShared ? ProductTypes.DYNAMIC_LIBRARY : ProductTypes.STATIC_LIBRARY);
    } else if (targetNodeDesc instanceof AppleTestDescription) {
      // Determine whether it is a UI or Unit test.
      TargetNode<AppleTestDescriptionArg> testNode =
          TargetNodes.castArg(targetNode, AppleTestDescriptionArg.class).get();
      boolean isUITest = testNode.getConstructorArg().getIsUiTest();
      return Optional.of(isUITest ? ProductTypes.UI_TEST : ProductTypes.UNIT_TEST);
    } else if (targetNodeDesc instanceof HalideLibraryDescription) {
      // Halide libraries are treated as static libraries.
      return Optional.of(ProductTypes.STATIC_LIBRARY);
    }
    return Optional.empty();
  }

  /** @return Product Type of a bundle containing a dylib. */
  static Optional<ProductType> dylibProductTypeByBundleExtension(AppleBundleExtension extension) {
    switch (extension) {
      case FRAMEWORK:
        return Optional.of(ProductTypes.FRAMEWORK);
      case APPEX:
        return Optional.of(ProductTypes.APP_EXTENSION);
      case BUNDLE:
        return Optional.of(ProductTypes.BUNDLE);
      case XCTEST:
        return Optional.of(ProductTypes.UNIT_TEST);
        // $CASES-OMITTED$
      default:
        return Optional.empty();
    }
  }

  /**
   * Get the binary build target defined on the apple_bundle.
   *
   * @param bundle The bundle target node to process.
   * @return The binary build target defined on the bundle.
   */
  static BuildTarget getBundleBinaryTarget(TargetNode<AppleBundleDescriptionArg> bundle) {
    return bundle
        .getConstructorArg()
        .getBinary()
        .orElseThrow(
            () ->
                new HumanReadableException(
                    "apple_bundle rules without binary attribute are not supported."));
  }
}
