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

package com.facebook.buck.step;

import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.java.FakeJavaPackageFinder;
import com.facebook.buck.testutil.IdentityPathAbsolutifier;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;
import java.nio.file.Paths;

public class TestExecutionContext {

  private TestExecutionContext() {
    // Utility class.
  }

  public static ExecutionContext.Builder newBuilder() {
    return ExecutionContext.builder()
        .setConsole(new TestConsole())
        .setProjectFilesystem(
            new ProjectFilesystem(Paths.get(".")) {
              @Override
              public Path resolve(Path path) {
                return path;
              }
              @Override
              public Function<Path, Path> getAbsolutifier() {
                return IdentityPathAbsolutifier.getIdentityAbsolutifier();
              }
            })
        .setEventBus(BuckEventBusFactory.newInstance())
        .setPlatform(Platform.detect())
        .setEnvironment(ImmutableMap.copyOf(System.getenv()))
        .setJavaPackageFinder(new FakeJavaPackageFinder())
        .setObjectMapper(new ObjectMapper());
  }

  public static ExecutionContext newInstance() {
    return newBuilder().build();
  }
}
