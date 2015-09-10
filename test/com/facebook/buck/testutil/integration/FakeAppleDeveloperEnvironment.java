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

package com.facebook.buck.testutil.integration;

import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.ProvisioningProfileCopyStep;
import com.facebook.buck.apple.ProvisioningProfileMetadata;
import com.facebook.buck.apple.device.AppleDeviceHelper;
import com.facebook.buck.log.Logger;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class FakeAppleDeveloperEnvironment {
  private static final Logger LOG = Logger.get(FakeAppleDeveloperEnvironment.class);

  // Utility class, do not instantiate.
  private FakeAppleDeveloperEnvironment() { }

  private static final int numCodeSigningIdentities =
      AppleConfig.createCodeSignIdentitiesSupplier(
          new ProcessExecutor(new TestConsole())).get().size();

  private static final boolean hasWildcardProvisioningProfile = Suppliers.memoize(
      new Supplier<Boolean>() {
        @Override
        public Boolean get() {
          final Path searchPath = Paths.get(System.getProperty("user.home") +
                  "/Library/MobileDevice/Provisioning Profiles");
          if (!Files.exists(searchPath)) {
            LOG.warn("Provisioning profile search path " + searchPath + " doesn't exist!");
            return false;
          }
          try {
            ImmutableSet<ProvisioningProfileMetadata> profiles =
                ProvisioningProfileCopyStep.findProfilesInPath(searchPath);
            Optional<ProvisioningProfileMetadata> profile =
                ProvisioningProfileCopyStep.getBestProvisioningProfile(
                    profiles, "*",
                    Optional.<String>absent(), Optional.<String>absent());
            return profile.isPresent();
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        }
      }).get().booleanValue();


  public static boolean supportsBuildAndInstallToDevice() {
    if (!supportsCodeSigning()) {
      return false;
    }

    return hasWildcardProvisioningProfile;
  }

  public static boolean supportsCodeSigning() {
    return (numCodeSigningIdentities > 0);
  }

  public static boolean hasDeviceCurrentlyConnected(Path pathToHelper)
      throws InterruptedException {
    try {
      AppleDeviceHelper helper = new AppleDeviceHelper(
          new ProcessExecutor(new TestConsole()),
          pathToHelper);
      return (helper.getConnectedDevices().size() > 0);
    } catch (IOException e) {
      LOG.warn("Could not execute " + pathToHelper + ": " + e.getMessage());
      return false;
    }
  }
}
