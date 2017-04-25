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

import com.facebook.buck.apple.ApplePlatform;
import com.facebook.buck.apple.CodeSignIdentityStore;
import com.facebook.buck.apple.ProvisioningProfileMetadata;
import com.facebook.buck.apple.ProvisioningProfileStore;
import com.facebook.buck.apple.device.AppleDeviceHelper;
import com.facebook.buck.log.Logger;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.base.Suppliers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class FakeAppleDeveloperEnvironment {
  private static final Logger LOG = Logger.get(FakeAppleDeveloperEnvironment.class);

  // Utility class, do not instantiate.
  private FakeAppleDeveloperEnvironment() {}

  private static final int numCodeSigningIdentities =
      CodeSignIdentityStore.fromSystem(
              new DefaultProcessExecutor(new TestConsole()),
              CodeSignIdentityStore.DEFAULT_IDENTITIES_COMMAND)
          .getIdentities()
          .size();

  private static final boolean hasWildcardProvisioningProfile =
      Suppliers.memoize(
              () -> {
                ProcessExecutor executor = new DefaultProcessExecutor(new TestConsole());
                final Path searchPath =
                    Paths.get(
                        System.getProperty("user.home")
                            + "/Library/MobileDevice/Provisioning Profiles");
                if (!Files.exists(searchPath)) {
                  LOG.warn("Provisioning profile search path " + searchPath + " doesn't exist!");
                  return false;
                }
                ProvisioningProfileStore store =
                    ProvisioningProfileStore.fromSearchPath(
                        executor, ProvisioningProfileStore.DEFAULT_READ_COMMAND, searchPath);
                Optional<ProvisioningProfileMetadata> profile =
                    store.getBestProvisioningProfile(
                        "*",
                        ApplePlatform.IPHONEOS,
                        ProvisioningProfileStore.MATCH_ANY_ENTITLEMENT,
                        ProvisioningProfileStore.MATCH_ANY_IDENTITY);
                return profile.isPresent();
              })
          .get();

  public static boolean supportsBuildAndInstallToDevice() {
    return supportsCodeSigning() && hasWildcardProvisioningProfile;
  }

  public static boolean supportsCodeSigning() {
    if (numCodeSigningIdentities >= Integer.MIN_VALUE) {
      // Temporarily disable all code signing tests because there are environmental issues where we
      // run them.
      return false;
    }
    return (numCodeSigningIdentities > 0);
  }

  public static boolean hasDeviceCurrentlyConnected(Path pathToHelper) throws InterruptedException {
    AppleDeviceHelper helper =
        new AppleDeviceHelper(new DefaultProcessExecutor(new TestConsole()), pathToHelper);
    return (helper.getConnectedDevices().size() > 0);
  }
}
