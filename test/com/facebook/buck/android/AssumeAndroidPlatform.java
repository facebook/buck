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

import static org.junit.Assume.assumeNotNull;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.util.DefaultPropertyFinder;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Paths;

public class AssumeAndroidPlatform {

  private AssumeAndroidPlatform() {}

  public static void assumeNdkIsAvailable() {
    assumeNotNull(getAndroidDirectoryResolver().findAndroidNdkDir().orNull());
  }

  public static void assumeSdkIsAvailable() {
    assumeNotNull(getAndroidDirectoryResolver().findAndroidSdkDirSafe().orNull());
  }

  private static AndroidDirectoryResolver getAndroidDirectoryResolver() {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(Paths.get("."));
    return new DefaultAndroidDirectoryResolver(projectFilesystem,
        Optional.<String>absent(),
        new DefaultPropertyFinder(projectFilesystem, ImmutableMap.copyOf(System.getenv())));
  }
}
