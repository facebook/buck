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

package com.facebook.buck.android;

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.toolchain.JavaOptionsProvider;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasContacts;
import com.facebook.buck.rules.HasTestTimeout;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.PackagedResource;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.immutables.value.Value;

public class AndroidInstrumentationTestDescription
    implements Description<AndroidInstrumentationTestDescriptionArg> {

  private final BuckConfig buckConfig;
  private final ToolchainProvider toolchainProvider;
  private final ConcurrentHashMap<ProjectFilesystem, ConcurrentHashMap<String, PackagedResource>>
      resourceSupplierCache;

  public AndroidInstrumentationTestDescription(
      BuckConfig buckConfig, ToolchainProvider toolchainProvider) {
    this.buckConfig = buckConfig;
    this.toolchainProvider = toolchainProvider;
    this.resourceSupplierCache = new ConcurrentHashMap<>();
  }

  @Override
  public Class<AndroidInstrumentationTestDescriptionArg> getConstructorArgType() {
    return AndroidInstrumentationTestDescriptionArg.class;
  }

  @Override
  public AndroidInstrumentationTest createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      AndroidInstrumentationTestDescriptionArg args) {
    BuildRule apk = resolver.getRule(args.getApk());
    if (!(apk instanceof HasInstallableApk)) {
      throw new HumanReadableException(
          "In %s, instrumentation_apk='%s' must be an android_binary(), apk_genrule() or "
              + "android_instrumentation_apk(), but was %s().",
          buildTarget, apk.getFullyQualifiedName(), apk.getType());
    }

    return new AndroidInstrumentationTest(
        buildTarget,
        projectFilesystem,
        toolchainProvider.getByName(
            AndroidPlatformTarget.DEFAULT_NAME, AndroidPlatformTarget.class),
        params.copyAppendingExtraDeps(BuildRules.getExportedRules(params.getDeclaredDeps().get())),
        (HasInstallableApk) apk,
        args.getLabels(),
        args.getContacts(),
        toolchainProvider
            .getByName(JavaOptionsProvider.DEFAULT_NAME, JavaOptionsProvider.class)
            .getJavaOptions()
            .getJavaRuntimeLauncher(),
        args.getTestRuleTimeoutMs()
            .map(Optional::of)
            .orElse(buckConfig.getDefaultTestRuleTimeoutMs()),
        getRelativePackagedResource(projectFilesystem, "ddmlib.jar"),
        getRelativePackagedResource(projectFilesystem, "kxml2.jar"),
        getRelativePackagedResource(projectFilesystem, "guava.jar"),
        getRelativePackagedResource(projectFilesystem, "android-tools-common.jar"));
  }

  /**
   * @return The packaged resource with name {@code resourceName} from the same jar as current class
   *     with path relative to this class location.
   *     <p>Since resources like ddmlib.jar are needed for all {@link AndroidInstrumentationTest}
   *     instances it makes sense to memoize them.
   */
  private PackagedResource getRelativePackagedResource(
      ProjectFilesystem projectFilesystem, String resourceName) {
    return resourceSupplierCache
        .computeIfAbsent(projectFilesystem, fs -> new ConcurrentHashMap<>())
        .computeIfAbsent(
            resourceName,
            resource ->
                new PackagedResource(
                    projectFilesystem, AndroidInstrumentationTestDescription.class, resource));
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractAndroidInstrumentationTestDescriptionArg
      extends CommonDescriptionArg, HasContacts, HasTestTimeout {
    BuildTarget getApk();
  }
}
