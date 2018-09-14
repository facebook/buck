/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.apple.toolchain;

import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.swift.toolchain.SwiftPlatform;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

/** Adds Apple-specific tools to {@link CxxPlatform}. */
@Value.Immutable(copy = true)
@BuckStyleImmutable
abstract class AbstractAppleCxxPlatform implements FlavorConvertible {

  public abstract CxxPlatform getCxxPlatform();

  public abstract Optional<SwiftPlatform> getSwiftPlatform();

  public abstract AppleSdk getAppleSdk();

  public abstract AppleSdkPaths getAppleSdkPaths();

  public abstract Optional<String> getBuildVersion();

  public abstract String getMinVersion();

  public abstract Tool getActool();

  public abstract Tool getIbtool();

  public abstract Tool getMomc();

  public abstract Optional<Tool> getCopySceneKitAssets();

  public abstract Tool getXctest();

  public abstract Tool getDsymutil();

  public abstract Tool getLipo();

  public abstract Optional<Path> getStubBinary();

  public abstract Tool getLldb();

  public abstract ToolProvider getCodesignProvider();

  public abstract Optional<Tool> getCodesignAllocate();

  // Short Xcode version code, e.g. 0721
  public abstract Optional<String> getXcodeVersion();

  // Xcode build identifier, e.g. 7C1002
  public abstract Optional<String> getXcodeBuildVersion();

  public abstract String getTargetAchitecture();

  @Override
  public Flavor getFlavor() {
    return getCxxPlatform().getFlavor();
  }
}
