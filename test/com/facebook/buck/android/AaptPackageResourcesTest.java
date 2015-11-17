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

package com.facebook.buck.android;

import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS;

import com.facebook.buck.android.AndroidBinary.PackageType;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeOnDiskBuildInfo;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;

public class AaptPackageResourcesTest {

  @Test
  public void initializeFromDiskDoesNotAccessOutputFromDeps() throws IOException {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);

    FilteredResourcesProvider resourcesProvider =
        new FilteredResourcesProvider() {
          @Override
          public ImmutableList<Path> getResDirectories() {
            throw new AssertionError("unexpected call to getResDirectories");
          }
          @Override
          public ImmutableList<Path> getStringFiles() {
            throw new AssertionError("unexpected call to getStringFiles");
          }
        };

    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:target"))
            .build();
    AaptPackageResources aaptPackageResources =
        new AaptPackageResources(
            params,
            pathResolver,
            /* manifest */ new FakeSourcePath("facebook/base/AndroidManifest.xml"),
            resourcesProvider,
            ImmutableList.<HasAndroidResourceDeps>of(),
            ImmutableSet.<SourcePath>of(),
            /* resourceUnionPackage */ Optional.<String>absent(),
            PackageType.DEBUG,
            DEFAULT_JAVAC_OPTIONS,
            /* rDotJavaNeedsDexing */ false,
            /* shouldBuildStringSourceMap */ false,
            /* skipCrunchPngs */ false);

    FakeOnDiskBuildInfo onDiskBuildInfo =
        new FakeOnDiskBuildInfo()
            .putMetadata(
                AaptPackageResources.RESOURCE_PACKAGE_HASH_KEY,
                "0123456789012345678901234567890123456789")
            .putMetadata(
                AaptPackageResources.FILTERED_RESOURCE_DIRS_KEY,
                ImmutableList.<String>of());
    aaptPackageResources.initializeFromDisk(onDiskBuildInfo);
  }

}
