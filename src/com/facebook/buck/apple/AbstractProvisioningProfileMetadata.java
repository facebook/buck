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

package com.facebook.buck.apple;

import com.dd.plist.NSDate;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListParser;

import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.immutables.value.Value;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Date;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Metadata contained in a provisioning profile (.mobileprovision).
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractProvisioningProfileMetadata implements RuleKeyAppendable {
  private static final Pattern BUNDLE_ID_PATTERN = Pattern.compile("^([A-Z0-9]{10,10})\\.(.+)$");

  /** Returns a (prefix, identifier) pair which the profile is valid for;
   *  e.g. (ABCDE12345, com.example.TestApp) or (ABCDE12345, *)
   */
  public abstract Pair<String, String> getAppID();

  public abstract Date getExpirationDate();

  public abstract String getUUID();

  public abstract Optional<Path> getProfilePath();

  /** Returns key/value pairs of the "Entitlements" dictionary of the embedded plist.
   */
  public abstract ImmutableMap<String, NSObject> getEntitlements();

  /** Takes a application identifier and splits it into prefix and bundle ID.
   *
   *  Prefix is always a ten-character alphanumeric sequence.
   *  Bundle ID may be a fully-qualified name or a wildcard ending in *.
   */
  public static Pair<String, String> splitAppID(String appID) throws Exception {
    Matcher matcher = BUNDLE_ID_PATTERN.matcher(appID);
    if (matcher.find()) {
      String prefix = matcher.group(1);
      String bundleID = matcher.group(2);

      return new Pair<>(prefix, bundleID);
    } else {
      throw new IllegalArgumentException("Malformed app ID: " + appID);
    }
  }

  public static ProvisioningProfileMetadata fromProvisioningProfilePath(Path profilePath)
      throws IOException, InterruptedException {
    CapturingPrintStream stdout = new CapturingPrintStream();
    CapturingPrintStream stderr = new CapturingPrintStream();

    Console console = new Console(Verbosity.SILENT, stdout, stderr, Ansi.withoutTty());
    ProcessExecutor processExecutor = new ProcessExecutor(console);
    Set<ProcessExecutor.Option> options = EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT);

    // Extract the XML from its signed message wrapper.
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .setCommand(ImmutableList.of("/usr/bin/security", "cms", "-D", "-i",
                    profilePath.toString()))
            .build();
    ProcessExecutor.Result result;
    result = processExecutor.launchAndExecute(
        processExecutorParams,
        options,
            /* stdin */ Optional.<String>absent(),
            /* timeOutMs */ Optional.<Long>absent(),
            /* timeOutHandler */ Optional.<Function<Process, Void>>absent());
    if (result.getExitCode() != 0) {
      throw new HumanReadableException("Invalid provisioning profile: " + profilePath +
          ": " + result.getStderr());
    }

    try {
      NSDictionary plist = (NSDictionary) PropertyListParser.parse(
          result.getStdout().get().getBytes());
      Date expirationDate = ((NSDate) plist.get("ExpirationDate")).getDate();
      String uuid = ((NSString) plist.get("UUID")).getContent();
      ImmutableMap.Builder<String, NSObject> builder =
          ImmutableMap.<String, NSObject>builder();
      NSDictionary entitlements = ((NSDictionary) plist.get("Entitlements"));
      for (String key : entitlements.keySet()) {
        builder = builder.put(key, entitlements.objectForKey(key));
      }
      String appID = entitlements.get("application-identifier").toString();

      return ProvisioningProfileMetadata.builder()
          .setAppID(ProvisioningProfileMetadata.splitAppID(appID))
          .setExpirationDate(expirationDate)
          .setUUID(uuid)
          .setProfilePath(Optional.<Path>of(profilePath))
          .setEntitlements(builder.build())
          .build();
    } catch (Exception e) {
      throw new IllegalArgumentException("Malformed embedded plist: " + e);
    }
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    return builder.setReflectively("provisioning-profile-uuid", getUUID());
  }
}
