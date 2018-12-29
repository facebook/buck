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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

public interface ArchiverProvider {

  Archiver resolve(BuildRuleResolver resolver);

  Iterable<BuildTarget> getParseTimeDeps();

  static ArchiverProvider from(Archiver archiver) {
    return new ArchiverProvider() {

      @Override
      public Archiver resolve(BuildRuleResolver resolver) {
        return archiver;
      }

      @Override
      public Iterable<BuildTarget> getParseTimeDeps() {
        return ImmutableList.of();
      }
    };
  }

  /**
   * Creates an appropriate ArchiverProvider instance for the given parameters.
   *
   * @deprecated Use the non-legacy type.
   */
  @Deprecated
  static ArchiverProvider from(
      ToolProvider archiver, Platform platform, Optional<LegacyArchiverType> legacyType) {
    ArchiverProvider.Type archiverType;
    switch (platform) {
      case MACOS:
      case FREEBSD:
        archiverType = Type.BSD;
        break;
      case LINUX:
        archiverType = Type.GNU;
        break;
      case WINDOWS:
        if (legacyType.isPresent() && legacyType.get().equals(LegacyArchiverType.LLVM_LIB)) {
          archiverType = Type.WINDOWS_CLANG;
        } else {
          archiverType = Type.WINDOWS;
        }
        break;
      case UNKNOWN:
      default:
        return new ArchiverProvider() {
          @Override
          public Archiver resolve(BuildRuleResolver resolver) {
            throw new RuntimeException(
                "Invalid platform for archiver. Must be one of {MACOS, LINUX, WINDOWS}");
          }

          @Override
          public Iterable<BuildTarget> getParseTimeDeps() {
            return ImmutableList.of();
          }
        };
    }
    return ArchiverProvider.from(archiver, archiverType);
  }

  /** Creates an appropriate ArchiverProvider instance for the given parameters. */
  static ArchiverProvider from(ToolProvider toolProvider, Type type) {
    return new ArchiverProvider() {
      @Override
      public Archiver resolve(BuildRuleResolver resolver) {
        Tool archiver = toolProvider.resolve(resolver);
        switch (type) {
          case BSD:
            return new BsdArchiver(archiver);
          case GNU:
            return new GnuArchiver(archiver);
          case WINDOWS:
            return new WindowsArchiver(archiver);
          case WINDOWS_CLANG:
            return new ClangWindowsArchiver(archiver);
          default:
            // This shouldn't be reachable.
            throw new RuntimeException();
        }
      }

      @Override
      public Iterable<BuildTarget> getParseTimeDeps() {
        return toolProvider.getParseTimeDeps();
      }
    };
  }

  /**
   * Optional type that can be specified by cxx.archiver_type to indicate the given archiver is
   * llvm-lib.
   */
  enum Type {
    BSD,
    GNU,
    WINDOWS,
    WINDOWS_CLANG,
  }

  /**
   * .buckconfig accepts this and we combine it with the current platform to determine the archiver
   * type.
   */
  // TODO(cjhopman): .buckconfig should be updated to take Type.
  enum LegacyArchiverType {
    LLVM_LIB,
  }
}
