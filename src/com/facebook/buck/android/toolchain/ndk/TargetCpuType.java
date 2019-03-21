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

package com.facebook.buck.android.toolchain.ndk;

import com.google.common.collect.ImmutableList;

/** The CPU architectures to target. */
public enum TargetCpuType {
  ARM {
    final ImmutableList<String> armeabiArchFlags =
        ImmutableList.of("-march=armv5te", "-mtune=xscale", "-msoft-float", "-mthumb");

    @Override
    public NdkTargetArch getTargetArch() {
      return NdkTargetArch.ARM;
    }

    @Override
    public NdkTargetArchAbi getTargetArchAbi() {
      return NdkTargetArchAbi.ARMEABI;
    }

    @Override
    public NdkToolchain getToolchain() {
      return NdkToolchain.ARM_LINUX_ANDROIDEABI;
    }

    @Override
    public NdkToolchainTarget getToolchainTarget() {
      return NdkToolchainTarget.ARM_LINUX_ANDROIDEABI;
    }

    @Override
    public ImmutableList<String> getAssemblerFlags(NdkCompilerType compiler) {
      switch (compiler) {
        case GCC:
          return armeabiArchFlags;
        case CLANG:
          return ImmutableList.<String>builder()
              .add("-target", "armv5te-none-linux-androideabi")
              .addAll(armeabiArchFlags)
              .build();
      }
      throw new AssertionError();
    }

    @Override
    public ImmutableList<String> getCompilerFlags(NdkCompilerType compiler) {
      switch (compiler) {
        case GCC:
          return ImmutableList.<String>builder().add("-Os").addAll(armeabiArchFlags).build();
        case CLANG:
          return ImmutableList.<String>builder()
              .add("-target", "armv5te-none-linux-androideabi", "-Os")
              .addAll(armeabiArchFlags)
              .build();
      }
      throw new AssertionError();
    }

    @Override
    public ImmutableList<String> getLinkerFlags(NdkCompilerType compiler) {
      switch (compiler) {
        case GCC:
          return ImmutableList.of("-march=armv5te", "-Wl,--fix-cortex-a8");
        case CLANG:
          return ImmutableList.of(
              "-target", "armv5te-none-linux-androideabi", "-march=armv5te", "-Wl,--fix-cortex-a8");
      }
      throw new AssertionError();
    }
  },
  ARMV7 {
    ImmutableList<String> armeabiv7ArchFlags =
        ImmutableList.of("-march=armv7-a", "-mfpu=vfpv3-d16", "-mfloat-abi=softfp", "-mthumb");

    @Override
    public NdkTargetArch getTargetArch() {
      return NdkTargetArch.ARM;
    }

    @Override
    public NdkTargetArchAbi getTargetArchAbi() {
      return NdkTargetArchAbi.ARMEABI_V7A;
    }

    @Override
    public NdkToolchain getToolchain() {
      return NdkToolchain.ARM_LINUX_ANDROIDEABI;
    }

    @Override
    public NdkToolchainTarget getToolchainTarget() {
      return NdkToolchainTarget.ARM_LINUX_ANDROIDEABI;
    }

    @Override
    public ImmutableList<String> getAssemblerFlags(NdkCompilerType compiler) {
      switch (compiler) {
        case GCC:
          return armeabiv7ArchFlags;
        case CLANG:
          return ImmutableList.<String>builder()
              .add("-target", "armv7-none-linux-androideabi")
              .addAll(armeabiv7ArchFlags)
              .build();
      }
      throw new AssertionError();
    }

    @Override
    public ImmutableList<String> getCompilerFlags(NdkCompilerType compiler) {
      switch (compiler) {
        case GCC:
          return ImmutableList.<String>builder()
              .add("-finline-limit=64", "-Os")
              .addAll(armeabiv7ArchFlags)
              .build();
        case CLANG:
          return ImmutableList.<String>builder()
              .add("-target", "armv7-none-linux-androideabi", "-Os")
              .addAll(armeabiv7ArchFlags)
              .build();
      }
      throw new AssertionError();
    }

    @Override
    public ImmutableList<String> getLinkerFlags(NdkCompilerType compiler) {
      switch (compiler) {
        case GCC:
          return ImmutableList.of();
        case CLANG:
          return ImmutableList.of("-target", "armv7-none-linux-androideabi");
      }
      throw new AssertionError();
    }
  },
  ARM64 {
    ImmutableList<String> arm64ArchFlags = ImmutableList.of("-march=armv8-a");

    @Override
    public NdkTargetArch getTargetArch() {
      return NdkTargetArch.ARM64;
    }

    @Override
    public NdkTargetArchAbi getTargetArchAbi() {
      return NdkTargetArchAbi.ARM64_V8A;
    }

    @Override
    public NdkToolchain getToolchain() {
      return NdkToolchain.AARCH64_LINUX_ANDROID;
    }

    @Override
    public NdkToolchainTarget getToolchainTarget() {
      return NdkToolchainTarget.AARCH64_LINUX_ANDROID;
    }

    @Override
    public ImmutableList<String> getAssemblerFlags(NdkCompilerType compiler) {
      switch (compiler) {
        case GCC:
          return arm64ArchFlags;
        case CLANG:
          return ImmutableList.<String>builder()
              .add("-target", "aarch64-none-linux-android")
              .addAll(arm64ArchFlags)
              .build();
      }
      throw new AssertionError();
    }

    @Override
    public ImmutableList<String> getCompilerFlags(NdkCompilerType compiler) {
      switch (compiler) {
        case GCC:
          return ImmutableList.<String>builder()
              .add("-O2")
              .add("-fomit-frame-pointer")
              .add("-fstrict-aliasing")
              .add("-funswitch-loops")
              .add("-finline-limit=300")
              .addAll(arm64ArchFlags)
              .build();
        case CLANG:
          return ImmutableList.<String>builder()
              .add("-target", "aarch64-none-linux-android")
              .add("-O2")
              .add("-fomit-frame-pointer")
              .add("-fstrict-aliasing")
              .addAll(arm64ArchFlags)
              .build();
      }
      throw new AssertionError();
    }

    @Override
    public ImmutableList<String> getLinkerFlags(NdkCompilerType compiler) {
      switch (compiler) {
        case GCC:
          return ImmutableList.of();
        case CLANG:
          return ImmutableList.of("-target", "aarch64-none-linux-android");
      }
      throw new AssertionError();
    }
  },
  X86 {
    @Override
    public NdkTargetArch getTargetArch() {
      return NdkTargetArch.X86;
    }

    @Override
    public NdkTargetArchAbi getTargetArchAbi() {
      return NdkTargetArchAbi.X86;
    }

    @Override
    public NdkToolchain getToolchain() {
      return NdkToolchain.X86;
    }

    @Override
    public NdkToolchainTarget getToolchainTarget() {
      return NdkToolchainTarget.I686_LINUX_ANDROID;
    }

    @Override
    public ImmutableList<String> getAssemblerFlags(NdkCompilerType compiler) {
      switch (compiler) {
        case GCC:
          return ImmutableList.of();
        case CLANG:
          return ImmutableList.of("-target", "i686-none-linux-android");
      }
      throw new AssertionError();
    }

    @Override
    public ImmutableList<String> getCompilerFlags(NdkCompilerType compiler) {
      switch (compiler) {
        case GCC:
          return ImmutableList.of("-funswitch-loops", "-finline-limit=300", "-O2");
        case CLANG:
          return ImmutableList.of("-target", "i686-none-linux-android", "-O2");
      }
      throw new AssertionError();
    }

    @Override
    public ImmutableList<String> getLinkerFlags(NdkCompilerType compiler) {
      switch (compiler) {
        case GCC:
          return ImmutableList.of();
        case CLANG:
          return ImmutableList.of("-target", "i686-none-linux-android");
      }
      throw new AssertionError();
    }
  },
  X86_64 {
    @Override
    public NdkTargetArch getTargetArch() {
      return NdkTargetArch.X86_64;
    }

    @Override
    public NdkTargetArchAbi getTargetArchAbi() {
      return NdkTargetArchAbi.X86_64;
    }

    @Override
    public NdkToolchain getToolchain() {
      return NdkToolchain.X86_64;
    }

    @Override
    public NdkToolchainTarget getToolchainTarget() {
      return NdkToolchainTarget.X86_64_LINUX_ANDROID;
    }

    @Override
    public ImmutableList<String> getAssemblerFlags(NdkCompilerType compiler) {
      switch (compiler) {
        case GCC:
          return ImmutableList.of();
        case CLANG:
          return ImmutableList.of("-target", "x86_64-none-linux-android");
      }
      throw new AssertionError();
    }

    @Override
    public ImmutableList<String> getCompilerFlags(NdkCompilerType compiler) {
      switch (compiler) {
        case GCC:
          return ImmutableList.of("-funswitch-loops", "-finline-limit=300", "-O2");
        case CLANG:
          return ImmutableList.of("-target", "x86_64-none-linux-android", "-O2");
      }
      throw new AssertionError();
    }

    @Override
    public ImmutableList<String> getLinkerFlags(NdkCompilerType compiler) {
      switch (compiler) {
        case GCC:
          return ImmutableList.of();
        case CLANG:
          return ImmutableList.of("-target", "x86_64-none-linux-android");
      }
      throw new AssertionError();
    }
  },
  MIPS {
    @Override
    public NdkTargetArch getTargetArch() {
      throw new AssertionError();
    }

    @Override
    public NdkTargetArchAbi getTargetArchAbi() {
      throw new AssertionError();
    }

    @Override
    public NdkToolchain getToolchain() {
      throw new AssertionError();
    }

    @Override
    public NdkToolchainTarget getToolchainTarget() {
      throw new AssertionError();
    }

    @Override
    public ImmutableList<String> getAssemblerFlags(NdkCompilerType compiler) {
      throw new AssertionError();
    }

    @Override
    public ImmutableList<String> getCompilerFlags(NdkCompilerType compiler) {
      throw new AssertionError();
    }

    @Override
    public ImmutableList<String> getLinkerFlags(NdkCompilerType compiler) {
      throw new AssertionError();
    }
  };

  public abstract NdkTargetArch getTargetArch();

  public abstract NdkTargetArchAbi getTargetArchAbi();

  public abstract NdkToolchain getToolchain();

  public abstract NdkToolchainTarget getToolchainTarget();

  public abstract ImmutableList<String> getAssemblerFlags(NdkCompilerType compiler);

  public abstract ImmutableList<String> getCompilerFlags(NdkCompilerType compiler);

  public abstract ImmutableList<String> getLinkerFlags(NdkCompilerType compiler);
}
