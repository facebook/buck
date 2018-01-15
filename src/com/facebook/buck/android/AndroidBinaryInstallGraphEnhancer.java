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

package com.facebook.buck.android;

import com.facebook.buck.android.exopackage.ExopackageInfo;
import com.facebook.buck.android.exopackage.ExopackagePathAndHash;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;

class AndroidBinaryInstallGraphEnhancer {
  static final Flavor INSTALL_FLAVOR = InternalFlavor.of("install");
  private static final Flavor DIRECTORY_LISTING_FLAVOR = InternalFlavor.of("exo_directory_listing");
  private static final Flavor EXO_FILE_INSTALL_FLAVOR = InternalFlavor.of("exo_file_installer");
  private static final Flavor EXO_FILE_RESOURCE_INSTALL_FLAVOR =
      InternalFlavor.of("exo_resources_installer");

  private ProjectFilesystem projectFilesystem;
  private BuildTarget buildTarget;
  private AndroidBinary androidBinary;
  private AndroidInstallConfig androidInstallConfig;

  AndroidBinaryInstallGraphEnhancer(
      AndroidInstallConfig androidInstallConfig,
      ProjectFilesystem projectFilesystem,
      BuildTarget buildTarget,
      AndroidBinary androidBinary) {
    this.projectFilesystem = projectFilesystem;
    this.buildTarget = buildTarget.withFlavors(INSTALL_FLAVOR);
    this.androidBinary = androidBinary;
    this.androidInstallConfig = androidInstallConfig;
  }

  public void enhance(BuildRuleResolver resolver) {
    if (androidInstallConfig.getConcurrentInstallEnabled(
        Optional.ofNullable(resolver.getEventBus()))) {
      if (exopackageEnabled()) {
        enhanceForConcurrentExopackageInstall(resolver);
      } else {
        enhanceForConcurrentInstall(resolver);
      }
    } else {
      enhanceForLegacyInstall(resolver);
    }
  }

  private boolean exopackageEnabled() {
    return androidBinary.getApkInfo().getExopackageInfo().isPresent();
  }

  private void enhanceForConcurrentExopackageInstall(BuildRuleResolver resolver) {
    ApkInfo apkInfo = androidBinary.getApkInfo();
    Preconditions.checkState(apkInfo.getExopackageInfo().isPresent());

    ExopackageDeviceDirectoryLister directoryLister =
        new ExopackageDeviceDirectoryLister(
            buildTarget.withFlavors(DIRECTORY_LISTING_FLAVOR), projectFilesystem);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    ExopackageInfo exopackageInfo = apkInfo.getExopackageInfo().get();
    ImmutableList.Builder<BuildRule> finisherDeps = ImmutableList.builder();
    if (exopackageInfo.getDexInfo().isPresent()
        || exopackageInfo.getNativeLibsInfo().isPresent()
        || exopackageInfo.getModuleInfo().isPresent()) {
      ExopackageInfo filteredExopackageInfo =
          ExopackageInfo.builder()
              .setDexInfo(exopackageInfo.getDexInfo())
              .setNativeLibsInfo(exopackageInfo.getNativeLibsInfo())
              .setModuleInfo(exopackageInfo.getModuleInfo())
              .build();
      ExopackageFilesInstaller fileInstaller =
          new ExopackageFilesInstaller(
              buildTarget.withFlavors(EXO_FILE_INSTALL_FLAVOR),
              projectFilesystem,
              ruleFinder,
              directoryLister.getSourcePathToOutput(),
              apkInfo.getManifestPath(),
              filteredExopackageInfo);
      resolver.addToIndex(fileInstaller);
      finisherDeps.add(fileInstaller);
    }
    if (exopackageInfo.getResourcesInfo().isPresent()) {
      List<BuildRule> resourceInstallRules =
          createResourceInstallRules(
              exopackageInfo.getResourcesInfo().get(),
              ruleFinder,
              apkInfo.getManifestPath(),
              directoryLister.getSourcePathToOutput());
      resourceInstallRules.forEach(resolver::addToIndex);
      finisherDeps.addAll(resourceInstallRules);
    }

    BuildRule apkInstaller =
        new ExopackageInstallFinisher(
            buildTarget,
            projectFilesystem,
            ruleFinder,
            apkInfo,
            directoryLister,
            finisherDeps.build());

    resolver.addToIndex(directoryLister);
    resolver.addToIndex(apkInstaller);
  }

  private List<BuildRule> createResourceInstallRules(
      ExopackageInfo.ResourcesInfo resourcesInfo,
      SourcePathRuleFinder ruleFinder,
      SourcePath manifestPath,
      SourcePath deviceExoContents) {
    // We construct a single ExopackageResourcesInstaller for each creator of exopackage resources.
    // This is done because the installers will synchronize on the underlying AndroidDevicesHelper
    // and so we don't want a single rule to generate a bunch of resource files and then take up a
    // bunch of build threads all waiting on each other.
    Multimap<BuildRule, ExopackagePathAndHash> creatorMappedPaths =
        resourcesInfo
            .getResourcesPaths()
            .stream()
            .collect(
                ImmutableListMultimap.toImmutableListMultimap(
                    (ExopackagePathAndHash pathAndHash) ->
                        ruleFinder.getRule(pathAndHash.getPath()).orElse(null),
                    v -> v));
    List<BuildRule> installers = new ArrayList<>();
    int index = 0;
    for (Collection<ExopackagePathAndHash> paths : creatorMappedPaths.asMap().values()) {
      installers.add(
          new ExopackageResourcesInstaller(
              buildTarget.withAppendedFlavors(
                  EXO_FILE_RESOURCE_INSTALL_FLAVOR,
                  InternalFlavor.of(String.format("resources-%d", index))),
              projectFilesystem,
              ruleFinder,
              paths,
              manifestPath,
              deviceExoContents));
      index++;
    }
    return installers;
  }

  private void enhanceForConcurrentInstall(BuildRuleResolver resolver) {
    resolver.addToIndex(
        new AndroidBinaryNonExoInstaller(buildTarget, projectFilesystem, androidBinary));
  }

  private void enhanceForLegacyInstall(BuildRuleResolver resolver) {
    resolver.addToIndex(
        new NoopBuildRule(buildTarget, projectFilesystem) {
          @Override
          public SortedSet<BuildRule> getBuildDeps() {
            return ImmutableSortedSet.of();
          }
        });
  }
}
