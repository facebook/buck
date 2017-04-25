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

package com.facebook.buck.apple;

import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A collection of code sign identities. */
public class CodeSignIdentityStore implements RuleKeyAppendable {
  private static final Logger LOG = Logger.get(CodeSignIdentityStore.class);

  // Parse the fingerprint and name, but don't match invalid certificates (revoked, expired, etc).
  private static final Pattern CODE_SIGN_IDENTITY_PATTERN =
      Pattern.compile("([A-F0-9]{40}) \"(iPhone.*)\"(?!.*\\(CSSMERR_.*\\))");
  public static final ImmutableList<String> DEFAULT_IDENTITIES_COMMAND =
      ImmutableList.of("security", "find-identity", "-v", "-p", "codesigning");

  private final Supplier<ImmutableList<CodeSignIdentity>> identitiesSupplier;

  private CodeSignIdentityStore(Supplier<ImmutableList<CodeSignIdentity>> identitiesSupplier) {
    this.identitiesSupplier = identitiesSupplier;
  }

  /** Get all the identities in the store. */
  public ImmutableList<CodeSignIdentity> getIdentities() {
    return identitiesSupplier.get();
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("CodeSignIdentityStore", getIdentities());
  }

  /**
   * Construct a store by asking the system keychain for all stored code sign identities.
   *
   * <p>The loading process is deferred till first access.
   */
  public static CodeSignIdentityStore fromSystem(
      final ProcessExecutor processExecutor, ImmutableList<String> command) {
    return new CodeSignIdentityStore(
        Suppliers.memoize(
            () -> {
              ProcessExecutorParams processExecutorParams =
                  ProcessExecutorParams.builder().addAllCommand(command).build();
              // Specify that stdout is expected, or else output may be wrapped in Ansi escape chars.
              Set<ProcessExecutor.Option> options =
                  EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT);
              ProcessExecutor.Result result;
              try {
                result =
                    processExecutor.launchAndExecute(
                        processExecutorParams,
                        options,
                        /* stdin */ Optional.empty(),
                        /* timeOutMs */ Optional.empty(),
                        /* timeOutHandler */ Optional.empty());
              } catch (InterruptedException | IOException e) {
                LOG.warn("Could not execute security, continuing without codesign identity.");
                return ImmutableList.of();
              }

              if (result.getExitCode() != 0) {
                throw new RuntimeException(
                    result.getMessageForUnexpectedResult("security -v -p codesigning"));
              }

              Matcher matcher = CODE_SIGN_IDENTITY_PATTERN.matcher(result.getStdout().get());
              ImmutableList.Builder<CodeSignIdentity> builder = ImmutableList.builder();
              while (matcher.find()) {
                Optional<HashCode> fingerprint = CodeSignIdentity.toFingerprint(matcher.group(1));
                if (!fingerprint.isPresent()) {
                  // security should always output a valid fingerprint string.
                  LOG.warn(
                      "Code sign identity fingerprint is invalid, ignored: " + matcher.group(1));
                  break;
                }
                String subjectCommonName = matcher.group(2);
                CodeSignIdentity identity =
                    CodeSignIdentity.builder()
                        .setFingerprint(fingerprint)
                        .setSubjectCommonName(subjectCommonName)
                        .build();
                builder.add(identity);
                LOG.debug("Found code signing identity: " + identity.toString());
              }
              ImmutableList<CodeSignIdentity> allValidIdentities = builder.build();
              if (allValidIdentities.isEmpty()) {
                LOG.warn(
                    "No valid code signing identities found.  Device build/install won't work.");
              } else if (allValidIdentities.size() > 1) {
                LOG.info(
                    "Multiple valid identity found.  This could potentially cause the wrong one to"
                        + " be used unless explicitly specified via CODE_SIGN_IDENTITY.");
              }
              return allValidIdentities;
            }));
  }

  public static CodeSignIdentityStore fromIdentities(Iterable<CodeSignIdentity> identities) {
    return new CodeSignIdentityStore(Suppliers.ofInstance(ImmutableList.copyOf(identities)));
  }
}
