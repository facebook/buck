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
import com.facebook.buck.apple.AppleDescriptions;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.AppleNativeTargetDescriptionArg;
import com.facebook.buck.apple.AppleTestDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.swift.SwiftCommonArg;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Parser that derives Swift specific attributes from a Target and returns {@link SwiftAttributes}.
 */
public class SwiftAttributeParser {

  private final SwiftBuckConfig swiftBuckConfig;
  private final ProjectGenerationStateCache projectGenerationStateCache;
  private final ProjectFilesystem projectFilesystem;

  /**
   * Attribute parser for Swift specific attributes of a Target..
   *
   * @param swiftBuckConfig
   * @param projectGenerationStateCache
   * @param projectFilesystem
   */
  public SwiftAttributeParser(
      SwiftBuckConfig swiftBuckConfig,
      ProjectGenerationStateCache projectGenerationStateCache,
      ProjectFilesystem projectFilesystem) {
    this.swiftBuckConfig = swiftBuckConfig;
    this.projectGenerationStateCache = projectGenerationStateCache;
    this.projectFilesystem = projectFilesystem;
  }

  /** Parse Swift specific attributes for the {@code targetNode}. */
  public SwiftAttributes parseSwiftAttributes(
      TargetNode<? extends CxxLibraryDescription.CommonArg> targetNode) {
    ImmutableSwiftAttributes.Builder builder =
        ImmutableSwiftAttributes.builder().setTargetNode(targetNode);

    Optional<String> swiftVersion = getSwiftVersionForTargetNode(targetNode);
    builder.setSwiftVersion(swiftVersion);

    ImmutableMap<Path, Path> publicHeaderMapEntries =
        getPublicHeaderMapEntries(
            targetNode, swiftVersion.isPresent(), builder.build().objCGeneratedHeaderName());
    builder.setPublicHeaderMapEntries(publicHeaderMapEntries);

    return builder.build();
  }

  private Optional<String> getSwiftVersionForTargetNode(TargetNode<?> targetNode) {
    Optional<TargetNode<SwiftCommonArg>> targetNodeWithSwiftArgs =
        TargetNodes.castArg(targetNode, SwiftCommonArg.class);
    Optional<String> targetExplicitSwiftVersion =
        targetNodeWithSwiftArgs.flatMap(t -> t.getConstructorArg().getSwiftVersion());
    if (!targetExplicitSwiftVersion.isPresent()
        && (targetNode.getDescription() instanceof AppleLibraryDescription
            || targetNode.getDescription() instanceof AppleBinaryDescription
            || targetNode.getDescription() instanceof AppleTestDescription)) {
      return swiftBuckConfig.getVersion();
    }
    return targetExplicitSwiftVersion;
  }

  /**
   * Get the generated header name with the provided {@code optModuleName} or getting the module
   * name from the {@code node}.
   */
  public static String getSwiftObjCGeneratedHeaderName(
      TargetNode<?> node, Optional<String> optModuleName) {
    String moduleName = optModuleName.orElse(Utils.getModuleName(node));
    return moduleName + "-Swift.h";
  }

  private Path getSwiftObjCGeneratedHeaderPath(
      TargetNode<?> targetNode, String objCbjCGeneratedHeaderName) {
    Path derivedSourcesDir =
        Utils.getDerivedSourcesDirectoryForBuildTarget(
            targetNode.getBuildTarget(), projectFilesystem);
    return derivedSourcesDir.resolve(objCbjCGeneratedHeaderName);
  }

  private ImmutableMap<Path, Path> getPublicHeaderMapEntries(
      TargetNode<? extends CxxLibraryDescription.CommonArg> node,
      boolean hasSwiftVersionArg,
      String objCbjCGeneratedHeaderName) {
    if (!hasSwiftVersionArg) {
      return ImmutableMap.of();
    }

    Optional<TargetNode<AppleNativeTargetDescriptionArg>> maybeAppleNode =
        TargetNodes.castArg(node, AppleNativeTargetDescriptionArg.class);
    if (!maybeAppleNode.isPresent()) {
      return ImmutableMap.of();
    }

    TargetNode<? extends AppleNativeTargetDescriptionArg> appleNode = maybeAppleNode.get();
    if (!projectGenerationStateCache.targetContainsSwiftSourceCode(appleNode)) {
      return ImmutableMap.of();
    }

    BuildTarget buildTarget = appleNode.getBuildTarget();
    Path headerPrefix =
        AppleDescriptions.getHeaderPathPrefix(appleNode.getConstructorArg(), buildTarget);
    Path relativePath = headerPrefix.resolve(objCbjCGeneratedHeaderName);

    ImmutableSortedMap.Builder<Path, Path> builder = ImmutableSortedMap.naturalOrder();
    builder.put(
        relativePath,
        getSwiftObjCGeneratedHeaderPath(appleNode, objCbjCGeneratedHeaderName).toAbsolutePath());

    return builder.build();
  }
}
