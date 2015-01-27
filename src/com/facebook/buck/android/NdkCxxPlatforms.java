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

package com.facebook.buck.android;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

public class NdkCxxPlatforms {
  // Utility class, do not instantiate.
  private NdkCxxPlatforms() { }

  /**
   * The build toolchain, named (including compiler version) after the target platform/arch.
   */
  public static enum Toolchain {

    X86_4_8("x86-4.8"),
    ARM_LINUX_ADNROIDEABI_4_8("arm-linux-androideabi-4.8"),
    ;

    private final String value;

    private Toolchain(String value) {
      this.value = Preconditions.checkNotNull(value);
    }

    @Override
    public String toString() {
      return value;
    }

  }

  /**
   * The prefix used for tools built for the above toolchain.
   */
  public static enum ToolchainPrefix {

    I686_LINUX_ANDROID("i686-linux-android"),
    ARM_LINUX_ANDROIDEABI("arm-linux-androideabi"),
    ;

    private final String value;

    private ToolchainPrefix(String value) {
      this.value = Preconditions.checkNotNull(value);
    }

    @Override
    public String toString() {
      return value;
    }

  }

  /**
   * Name of the target CPU architecture.
   */
  public static enum TargetArch {

    X86("x86"),
    ARM("arm"),
    ;

    private final String value;

    private TargetArch(String value) {
      this.value = Preconditions.checkNotNull(value);
    }

    @Override
    public String toString() {
      return value;
    }

  }

  /**
   * Name of the target CPU + ABI.
   */
  public static enum TargetArchAbi {

    X86("x86"),
    ARMEABI("armeabi"),
    ARMEABI_V7A("armeabi-v7a"),
    ;

    private final String value;

    private TargetArchAbi(String value) {
      this.value = Preconditions.checkNotNull(value);
    }

    @Override
    public String toString() {
      return value;
    }

  }

  /**
   * The OS and Architecture that we're building on.
   */
  public static enum Host {

    DARWIN_X86_64("darwin-x86_64"),
    LINUX_X86_64("linux-x86_64"),
    ;

    private final String value;

    private Host(String value) {
      this.value = Preconditions.checkNotNull(value);
    }

    @Override
    public String toString() {
      return value;
    }

  }

  /**
   * The C/C++ runtime library to link against.
   */
  public static enum CxxRuntime {

    SYSTEM("system", "system"),
    GABIXX("gabi++_shared", "gabi++_static"),
    STLPORT("stlport_shared", "stlport_static"),
    GNUSTL("gnustl_shared", "gnustl_static"),
    ;

    private final String sharedName;
    private final String staticName;

    private CxxRuntime(String sharedName, String staticName) {
      this.sharedName = sharedName;
      this.staticName = staticName;
    }

    public String getStaticName() {
      return staticName;
    }

    public String getSharedName() {
      return sharedName;
    }

    public String getSoname() {
      return "lib" + sharedName + ".so";
    }

  }

  /**
   * A container for all configuration settings needed to define a build target.
   */
  public static class TargetConfiguration {

    public final Toolchain toolchain;
    public final ToolchainPrefix toolchainPrefix;
    public final TargetArch targetArch;
    public final TargetArchAbi targetArchAbi;
    public final String targetPlatform;
    public final String compilerVersion;
    public final ImmutableList<String> compilerFlags;

    public TargetConfiguration(
        Toolchain toolchain,
        ToolchainPrefix toolchainPrefix,
        TargetArch targetArch,
        TargetArchAbi targetArchAbi,
        String targetPlatform,
        String compilerVersion,
        ImmutableList<String> compilerFlags) {
      this.toolchain = Preconditions.checkNotNull(toolchain);
      this.toolchainPrefix = Preconditions.checkNotNull(toolchainPrefix);
      this.targetArch = Preconditions.checkNotNull(targetArch);
      this.targetArchAbi = Preconditions.checkNotNull(targetArchAbi);
      this.targetPlatform = Preconditions.checkNotNull(targetPlatform);
      this.compilerVersion = Preconditions.checkNotNull(compilerVersion);
      this.compilerFlags = Preconditions.checkNotNull(compilerFlags);
    }

  }
}
