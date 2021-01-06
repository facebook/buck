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
import javax.annotation.Nullable;

/** A factory object for generating new PBX object types. */
public abstract class AbstractPBXObjectFactory {

  /**
   * The default factory.
   *
   * @return The default factory just forwards all calls to new PBX*(...);
   */
  public static AbstractPBXObjectFactory DefaultFactory() {
    return new DefaultPBXObjectFactory();
  }

  public abstract PBXProject createProject(String name);

  public abstract PBXBuildFile createBuildFile(PBXReference ref);

  public abstract PBXContainerItemProxy createContainerItemProxy(
      PBXObject containerPortal,
      String remoteGlobalIDString,
      PBXContainerItemProxy.ProxyType proxyType);

  public abstract PBXFileReference createFileReference(
      String name,
      @Nullable String path,
      PBXReference.SourceTree sourceTree,
      Optional<String> defaultType);

  public abstract PBXFrameworksBuildPhase createFrameworksBuildPhase();

  public abstract PBXGroup createPBXGroup(
      String name, @Nullable String path, PBXReference.SourceTree sourceTree);

  public abstract PBXHeadersBuildPhase createHeadersBuildPhase();

  public abstract PBXNativeTarget createNativeTarget(String name);

  public abstract PBXShellScriptBuildPhase createShellScriptBuildPhase();

  public abstract PBXSourcesBuildPhase createSourcesBuildPhase();

  public abstract PBXTargetDependency createTargetDependency(
      PBXContainerItemProxy containerItemProxy);

  public abstract PBXVariantGroup createVariantGroup(
      String name, @Nullable String path, PBXReference.SourceTree sourceTree);

  public abstract XCConfigurationList createConfigurationList();

  public abstract XCBuildConfiguration createBuildConfiguration(String name);

  public abstract XCVersionGroup createVersionGroup(
      String name, @Nullable String path, PBXReference.SourceTree sourceTree);
}

/** The default PBX object factory; forwards all calls to the new() */
final class DefaultPBXObjectFactory extends AbstractPBXObjectFactory {
  @Override
  public PBXProject createProject(String name) {
    return new PBXProject(name, this);
  }

  @Override
  public PBXBuildFile createBuildFile(PBXReference ref) {
    return new PBXBuildFile(ref);
  }

  @Override
  public PBXContainerItemProxy createContainerItemProxy(
      PBXObject containerPortal,
      String remoteGlobalIDString,
      PBXContainerItemProxy.ProxyType proxyType) {
    return new PBXContainerItemProxy(containerPortal, remoteGlobalIDString, proxyType);
  }

  @Override
  public PBXFileReference createFileReference(
      String name,
      @Nullable String path,
      PBXReference.SourceTree sourceTree,
      Optional<String> defaultType) {
    return new PBXFileReference(name, path, sourceTree, defaultType);
  }

  @Override
  public PBXFrameworksBuildPhase createFrameworksBuildPhase() {
    return new PBXFrameworksBuildPhase();
  }

  @Override
  public PBXGroup createPBXGroup(
      String name, @Nullable String path, PBXReference.SourceTree sourceTree) {
    return new PBXGroup(name, path, sourceTree, this);
  }

  @Override
  public PBXHeadersBuildPhase createHeadersBuildPhase() {
    return new PBXHeadersBuildPhase();
  }

  @Override
  public PBXNativeTarget createNativeTarget(String name) {
    return new PBXNativeTarget(name, this);
  }

  @Override
  public PBXShellScriptBuildPhase createShellScriptBuildPhase() {
    return new PBXShellScriptBuildPhase();
  }

  @Override
  public PBXSourcesBuildPhase createSourcesBuildPhase() {
    return new PBXSourcesBuildPhase();
  }

  @Override
  public PBXTargetDependency createTargetDependency(PBXContainerItemProxy containerItemProxy) {
    return new PBXTargetDependency(containerItemProxy);
  }

  @Override
  public PBXVariantGroup createVariantGroup(
      String name, @Nullable String path, PBXReference.SourceTree sourceTree) {
    return new PBXVariantGroup(name, path, sourceTree, this);
  }

  @Override
  public XCConfigurationList createConfigurationList() {
    return new XCConfigurationList(this);
  }

  @Override
  public XCBuildConfiguration createBuildConfiguration(String name) {
    return new XCBuildConfiguration(name);
  }

  @Override
  public XCVersionGroup createVersionGroup(
      String name, @Nullable String path, PBXReference.SourceTree sourceTree) {
    return new XCVersionGroup(name, path, sourceTree, this);
  }
}
