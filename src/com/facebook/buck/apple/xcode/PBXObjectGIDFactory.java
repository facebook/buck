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

package com.facebook.buck.apple.xcode;

import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildFile;
import com.facebook.buck.apple.xcode.xcodeproj.PBXContainerItemProxy;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFrameworksBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXGroup;
import com.facebook.buck.apple.xcode.xcodeproj.PBXHeadersBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXNativeTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXObject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXShellScriptBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXSourcesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTargetDependency;
import com.facebook.buck.apple.xcode.xcodeproj.PBXVariantGroup;
import com.facebook.buck.apple.xcode.xcodeproj.XCBuildConfiguration;
import com.facebook.buck.apple.xcode.xcodeproj.XCConfigurationList;
import com.facebook.buck.apple.xcode.xcodeproj.XCVersionGroup;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/** A factory for creating PBXObjects that assigns a Global ID on initialization. */
public final class PBXObjectGIDFactory extends AbstractPBXObjectFactory {
  private final ConcurrentHashMap<String, Integer> classNameToCounterMap;

  public PBXObjectGIDFactory() {
    this.classNameToCounterMap = new ConcurrentHashMap<>();
  }

  @Override
  public PBXProject createProject(String name) {
    return objectWithGid(new PBXProject(name, this));
  }

  @Override
  public PBXBuildFile createBuildFile(PBXReference ref) {
    return objectWithGid(new PBXBuildFile(ref));
  }

  @Override
  public PBXContainerItemProxy createContainerItemProxy(
      PBXObject containerPortal,
      String remoteGlobalIDString,
      PBXContainerItemProxy.ProxyType proxyType) {
    return objectWithGid(
        new PBXContainerItemProxy(containerPortal, remoteGlobalIDString, proxyType));
  }

  @Override
  public PBXFileReference createFileReference(
      String name,
      @Nullable String path,
      PBXReference.SourceTree sourceTree,
      Optional<String> defaultType) {
    return objectWithGid(new PBXFileReference(name, path, sourceTree, defaultType));
  }

  @Override
  public PBXFrameworksBuildPhase createFrameworksBuildPhase() {
    return objectWithGid(new PBXFrameworksBuildPhase());
  }

  @Override
  public PBXGroup createPBXGroup(
      String name, @Nullable String path, PBXReference.SourceTree sourceTree) {
    return objectWithGid(new PBXGroup(name, path, sourceTree, this));
  }

  @Override
  public PBXHeadersBuildPhase createHeadersBuildPhase() {
    return objectWithGid(new PBXHeadersBuildPhase());
  }

  @Override
  public PBXNativeTarget createNativeTarget(String name) {
    return objectWithGid(new PBXNativeTarget(name, this));
  }

  @Override
  public PBXShellScriptBuildPhase createShellScriptBuildPhase() {
    return objectWithGid(new PBXShellScriptBuildPhase());
  }

  @Override
  public PBXSourcesBuildPhase createSourcesBuildPhase() {
    return objectWithGid(new PBXSourcesBuildPhase());
  }

  @Override
  public PBXTargetDependency createTargetDependency(PBXContainerItemProxy containerItemProxy) {
    return objectWithGid(new PBXTargetDependency(containerItemProxy));
  }

  @Override
  public PBXVariantGroup createVariantGroup(
      String name, @Nullable String path, PBXReference.SourceTree sourceTree) {
    return objectWithGid(new PBXVariantGroup(name, path, sourceTree, this));
  }

  @Override
  public XCConfigurationList createConfigurationList() {
    return objectWithGid(new XCConfigurationList(this));
  }

  @Override
  public XCBuildConfiguration createBuildConfiguration(String name) {
    return objectWithGid(new XCBuildConfiguration(name));
  }

  @Override
  public XCVersionGroup createVersionGroup(
      String name, @Nullable String path, PBXReference.SourceTree sourceTree) {
    return objectWithGid(new XCVersionGroup(name, path, sourceTree, this));
  }

  private <T extends PBXObject> T objectWithGid(T obj) {
    int counter = classNameToCounterMap.getOrDefault(obj.isa(), 0);
    String gid = String.format("%08X%08X%08X", obj.isa().hashCode(), 0, counter++);
    classNameToCounterMap.put(obj.isa(), counter);

    obj.setGlobalID(gid);
    return obj;
  }
}
