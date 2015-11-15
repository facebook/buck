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

package com.facebook.buck.jvm.java;

import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.model.BuildTarget;

import java.nio.file.Path;

/**
 * Facilitates creating a fake {@link JavaPackageFinder} for unit tests.
 */
public class FakeJavaPackageFinder implements JavaPackageFinder {
  @Override
  public Path findJavaPackageFolder(Path pathRelativeToProjectRoot) {
    return null;
  }

  @Override
  public String findJavaPackage(Path pathRelativeToProjectRoot) {
    return null;
  }

  @Override
  public String findJavaPackage(BuildTarget buildTarget) {
    return null;
  }
}
