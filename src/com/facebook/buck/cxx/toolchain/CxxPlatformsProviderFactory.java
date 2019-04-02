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

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainFactory;
import com.facebook.buck.core.toolchain.ToolchainInstantiationException;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class CxxPlatformsProviderFactory implements ToolchainFactory<CxxPlatformsProvider> {

  private static final Logger LOG = Logger.get(CxxPlatformsProviderFactory.class);

  @Override
  public Optional<CxxPlatformsProvider> createToolchain(
      ToolchainProvider toolchainProvider, ToolchainCreationContext context) {
    Iterable<String> toolchainNames =
        toolchainProvider.getToolchainsWithCapability(CxxPlatformsSupplier.class);

    ImmutableMap.Builder<Flavor, UnresolvedCxxPlatform> cxxSystemPlatforms = ImmutableMap.builder();
    for (String toolchainName : toolchainNames) {
      if (toolchainProvider.isToolchainPresent(toolchainName)) {
        CxxPlatformsSupplier cxxPlatformsSupplier =
            toolchainProvider.getByName(toolchainName, CxxPlatformsSupplier.class);
        cxxSystemPlatforms.putAll(cxxPlatformsSupplier.getCxxPlatforms());
      }
    }

    try {
      return Optional.of(
          createProvider(
              context.getBuckConfig(),
              context.getTargetConfiguration().get(),
              cxxSystemPlatforms.build()));
    } catch (HumanReadableException e) {
      throw ToolchainInstantiationException.wrap(e);
    }
  }

  private static CxxPlatformsProvider createProvider(
      BuckConfig config,
      TargetConfiguration targetConfiguration,
      ImmutableMap<Flavor, UnresolvedCxxPlatform> cxxSystemPlatforms) {
    Platform platform = Platform.detect();
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(config);

    // Create a map of system platforms.
    ImmutableMap.Builder<Flavor, UnresolvedCxxPlatform> cxxSystemPlatformsBuilder =
        ImmutableMap.builder();

    cxxSystemPlatformsBuilder.putAll(cxxSystemPlatforms);

    CxxPlatform defaultHostCxxPlatform = DefaultCxxPlatforms.build(platform, cxxBuckConfig);
    cxxSystemPlatformsBuilder.put(
        defaultHostCxxPlatform.getFlavor(),
        new StaticUnresolvedCxxPlatform(defaultHostCxxPlatform));
    ImmutableMap<Flavor, UnresolvedCxxPlatform> cxxSystemPlatformsMap =
        cxxSystemPlatformsBuilder.build();

    cxxSystemPlatformsMap =
        appendHostPlatformIfNeeded(defaultHostCxxPlatform, cxxSystemPlatformsMap);

    Map<Flavor, UnresolvedCxxPlatform> cxxOverridePlatformsMap =
        updateCxxPlatformsWithOptionsFromBuckConfig(
            targetConfiguration, platform, config, cxxSystemPlatformsMap, defaultHostCxxPlatform);

    UnresolvedCxxPlatform hostCxxPlatform =
        getHostCxxPlatform(cxxBuckConfig, cxxOverridePlatformsMap);
    cxxOverridePlatformsMap.put(hostCxxPlatform.getFlavor(), hostCxxPlatform);

    ImmutableMap<Flavor, UnresolvedCxxPlatform> cxxPlatformsMap =
        ImmutableMap.<Flavor, UnresolvedCxxPlatform>builder()
            .putAll(cxxOverridePlatformsMap)
            .build();

    // Build up the final list of C/C++ platforms.
    FlavorDomain<UnresolvedCxxPlatform> cxxPlatforms =
        new FlavorDomain<>("C/C++ platform", cxxPlatformsMap);

    // Get the default target platform from config.
    UnresolvedCxxPlatform defaultCxxPlatform =
        CxxPlatforms.getConfigDefaultCxxPlatform(cxxBuckConfig, cxxPlatformsMap, hostCxxPlatform);

    return CxxPlatformsProvider.of(defaultCxxPlatform, cxxPlatforms);
  }

  private static ImmutableMap<Flavor, UnresolvedCxxPlatform> appendHostPlatformIfNeeded(
      CxxPlatform defaultHostCxxPlatform,
      ImmutableMap<Flavor, UnresolvedCxxPlatform> cxxSystemPlatforms) {
    Flavor hostFlavor = CxxPlatforms.getHostFlavor();
    if (!cxxSystemPlatforms.containsKey(hostFlavor)) {
      return ImmutableMap.<Flavor, UnresolvedCxxPlatform>builder()
          .putAll(cxxSystemPlatforms)
          .put(
              hostFlavor,
              new StaticUnresolvedCxxPlatform(
                  CxxPlatform.builder().from(defaultHostCxxPlatform).setFlavor(hostFlavor).build()))
          .build();
    } else {
      return cxxSystemPlatforms;
    }
  }

  /**
   * Add platforms for each cxx flavor obtained from the buck config files from sections of the form
   * cxx#{flavor name}. These platforms are overrides for existing system platforms.
   */
  private static Map<Flavor, UnresolvedCxxPlatform> updateCxxPlatformsWithOptionsFromBuckConfig(
      TargetConfiguration targetConfiguration,
      Platform platform,
      BuckConfig config,
      ImmutableMap<Flavor, UnresolvedCxxPlatform> cxxSystemPlatformsMap,
      CxxPlatform defaultHostCxxPlatform) {
    ImmutableSet<Flavor> possibleHostFlavors = CxxPlatforms.getAllPossibleHostFlavors();
    Map<Flavor, UnresolvedCxxPlatform> cxxOverridePlatformsMap =
        new HashMap<>(cxxSystemPlatformsMap);
    ImmutableSet<Flavor> cxxFlavors = CxxBuckConfig.getCxxFlavors(config);
    for (Flavor flavor : cxxFlavors) {
      Optional<UnresolvedCxxPlatform> newPlatform =
          CxxBuckConfig.getProviderBasedPlatform(config, flavor, targetConfiguration);

      if (!newPlatform.isPresent()) {
        newPlatform =
            augmentSystemPlatform(
                platform,
                cxxSystemPlatformsMap,
                defaultHostCxxPlatform,
                possibleHostFlavors,
                flavor,
                new CxxBuckConfig(config, flavor));
      }

      if (!newPlatform.isPresent()) {
        continue;
      }

      cxxOverridePlatformsMap.put(flavor, newPlatform.get());
    }
    return cxxOverridePlatformsMap;
  }

  private static Optional<UnresolvedCxxPlatform> augmentSystemPlatform(
      Platform platform,
      ImmutableMap<Flavor, UnresolvedCxxPlatform> cxxSystemPlatformsMap,
      CxxPlatform defaultHostCxxPlatform,
      ImmutableSet<Flavor> possibleHostFlavors,
      Flavor flavor,
      CxxBuckConfig cxxConfig) {
    UnresolvedCxxPlatform baseUnresolvedCxxPlatform = cxxSystemPlatformsMap.get(flavor);
    CxxPlatform baseCxxPlatform;
    if (baseUnresolvedCxxPlatform == null) {
      if (possibleHostFlavors.contains(flavor)) {
        // If a flavor is for an alternate host, it's safe to skip.
        return Optional.empty();
      }
      LOG.info("Applying \"%s\" overrides to default host platform", flavor);
      baseCxxPlatform = defaultHostCxxPlatform;
    } else {
      if (!(baseUnresolvedCxxPlatform instanceof StaticUnresolvedCxxPlatform)) {
        throw new HumanReadableException("Cannot override non-static cxx platform %s", flavor);
      }
      baseCxxPlatform = ((StaticUnresolvedCxxPlatform) baseUnresolvedCxxPlatform).getCxxPlatform();
    }

    StaticUnresolvedCxxPlatform augmentedPlatform =
        new StaticUnresolvedCxxPlatform(
            CxxPlatforms.copyPlatformWithFlavorAndConfig(
                baseCxxPlatform, platform, cxxConfig, flavor));
    return Optional.of(augmentedPlatform);
  }

  private static UnresolvedCxxPlatform getHostCxxPlatform(
      CxxBuckConfig cxxBuckConfig, Map<Flavor, UnresolvedCxxPlatform> cxxOverridePlatformsMap) {
    Flavor hostFlavor = CxxPlatforms.getHostFlavor();

    if (!cxxBuckConfig.getShouldRemapHostPlatform()) {
      hostFlavor = DefaultCxxPlatforms.FLAVOR;
    }
    Optional<String> hostCxxPlatformOverride = cxxBuckConfig.getHostPlatform();
    if (hostCxxPlatformOverride.isPresent()) {
      Flavor overrideFlavor = InternalFlavor.of(hostCxxPlatformOverride.get());
      if (cxxOverridePlatformsMap.containsKey(overrideFlavor)) {
        hostFlavor = overrideFlavor;
      }
    }
    UnresolvedCxxPlatform hostFlavoredPlatform =
        Objects.requireNonNull(cxxOverridePlatformsMap.get(hostFlavor));
    UnresolvedCxxPlatform hostCxxPlatform;
    if (!cxxBuckConfig.getShouldRemapHostPlatform()) {
      hostCxxPlatform = hostFlavoredPlatform.withFlavor(DefaultCxxPlatforms.FLAVOR);
    } else {
      hostCxxPlatform = hostFlavoredPlatform;
    }
    return hostCxxPlatform;
  }
}
