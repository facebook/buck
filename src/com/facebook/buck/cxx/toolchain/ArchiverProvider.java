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
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;

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

  static ArchiverProvider from(ToolProvider toolProvider, Platform platform) {
    return new ArchiverProvider() {
      @Override
      public Archiver resolve(BuildRuleResolver resolver) {
        Tool archiver = toolProvider.resolve(resolver);
        switch (platform) {
          case MACOS:
          case FREEBSD:
            return new BsdArchiver(archiver);
          case LINUX:
            return new GnuArchiver(archiver);
          case WINDOWS:
            return new WindowsArchiver(archiver);
          case UNKNOWN:
          default:
            throw new RuntimeException(
                "Invalid platform for archiver. Must be one of {MACOS, LINUX, WINDOWS}");
        }
      }

      @Override
      public Iterable<BuildTarget> getParseTimeDeps() {
        return toolProvider.getParseTimeDeps();
      }
    };
  }
}
