/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.apple.toolchain.impl;

import com.dd.plist.NSArray;
import com.dd.plist.NSData;
import com.dd.plist.NSDate;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListParser;
import com.facebook.buck.apple.toolchain.ProvisioningProfileMetadata;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutor.Option;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Date;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

public class ProvisioningProfileMetadataFactory {

  public static ProvisioningProfileMetadata fromProvisioningProfilePath(
      ProcessExecutor executor, ImmutableList<String> readCommand, Path profilePath)
      throws IOException, InterruptedException {
    Set<Option> options = EnumSet.of(Option.EXPECTING_STD_OUT);

    // Extract the XML from its signed message wrapper.
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .addAllCommand(readCommand)
            .addCommand(profilePath.toString())
            .build();
    ProcessExecutor.Result result;
    result =
        executor.launchAndExecute(
            processExecutorParams,
            options,
            /* stdin */ Optional.empty(),
            /* timeOutMs */ Optional.empty(),
            /* timeOutHandler */ Optional.empty());
    if (result.getExitCode() != 0) {
      throw new IOException(
          result.getMessageForResult("Invalid provisioning profile: " + profilePath));
    }

    try {
      NSDictionary plist =
          (NSDictionary) PropertyListParser.parse(result.getStdout().get().getBytes());
      Date expirationDate = ((NSDate) plist.get("ExpirationDate")).getDate();
      String uuid = ((NSString) plist.get("UUID")).getContent();

      ImmutableSet.Builder<HashCode> certificateFingerprints = ImmutableSet.builder();
      NSArray certificates = (NSArray) plist.get("DeveloperCertificates");
      HashFunction hasher = Hashing.sha1();
      if (certificates != null) {
        for (NSObject item : certificates.getArray()) {
          certificateFingerprints.add(hasher.hashBytes(((NSData) item).bytes()));
        }
      }

      ImmutableMap.Builder<String, NSObject> builder = ImmutableMap.builder();
      NSDictionary entitlements = ((NSDictionary) plist.get("Entitlements"));
      for (String key : entitlements.keySet()) {
        builder = builder.put(key, entitlements.objectForKey(key));
      }
      String appID = entitlements.get("application-identifier").toString();

      ProvisioningProfileMetadata.Builder provisioningProfileMetadata =
          ProvisioningProfileMetadata.builder();
      if (plist.get("Platform") != null) {
        for (Object platform : (Object[]) plist.get("Platform").toJavaObject()) {
          provisioningProfileMetadata.addPlatforms((String) platform);
        }
      }
      return provisioningProfileMetadata
          .setAppID(ProvisioningProfileMetadata.splitAppID(appID))
          .setExpirationDate(expirationDate)
          .setUUID(uuid)
          .setProfilePath(profilePath)
          .setEntitlements(builder.build())
          .setDeveloperCertificateFingerprints(certificateFingerprints.build())
          .build();
    } catch (Exception e) {
      throw new IllegalArgumentException("Malformed embedded plist: " + e);
    }
  }
}
