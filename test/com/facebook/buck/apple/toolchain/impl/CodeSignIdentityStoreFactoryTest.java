/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.apple.toolchain.impl;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.toolchain.CodeSignIdentity;
import com.facebook.buck.apple.toolchain.CodeSignIdentityStore;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import org.junit.Test;

public class CodeSignIdentityStoreFactoryTest {
  @Test
  public void testInvalidIdentitiesAreIgnored() {
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder().addCommand("unused").build();
    FakeProcess process =
        new FakeProcess(
            0,
            "  1) AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA "
                + "\"iPhone Developer: Foo Bar (ABCDE12345)\" (CSSMERR_TP_CERT_REVOKED)\n"
                + "  2) AAAAAAAAAAAAAAAAAAAABBBBBBBBBBBBBBBBBBBB "
                + "\"iPhone Developer: Foo Bar (12345ABCDE)\" (CSSMERR_TP_CERT_EXPIRED)\n"
                + "  3) BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB "
                + "\"iPhone Developer: Foo Bar (54321EDCBA)\"\n"
                + "     3 valid identities found\n",
            "");
    FakeProcessExecutor processExecutor =
        new FakeProcessExecutor(ImmutableMap.of(processExecutorParams, process));
    CodeSignIdentityStore store =
        CodeSignIdentityStoreFactory.fromSystem(processExecutor, ImmutableList.of("unused"));
    ImmutableList<CodeSignIdentity> expected =
        ImmutableList.of(
            CodeSignIdentity.builder()
                .setFingerprint(
                    CodeSignIdentity.toFingerprint("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"))
                .setSubjectCommonName("iPhone Developer: Foo Bar (54321EDCBA)")
                .build());
    assertThat(store.getIdentitiesSupplier().get(), equalTo(expected));
  }

  @Test
  public void testCodeSignIdentitiesCommandOverride() {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProcessExecutor executor = new DefaultProcessExecutor(new TestConsole());
    Path testdataDir =
        TestDataHelper.getTestDataDirectory(this).resolve("code_sign_identity_store");

    CodeSignIdentityStore store =
        CodeSignIdentityStoreFactory.fromSystem(
            executor, ImmutableList.of(testdataDir.resolve("fake_identities.sh").toString()));

    ImmutableList<CodeSignIdentity> expected =
        ImmutableList.of(
            CodeSignIdentity.builder()
                .setFingerprint(
                    CodeSignIdentity.toFingerprint("0000000000000000000000000000000000000000"))
                .setSubjectCommonName("iPhone Developer: Fake")
                .build());

    assertThat(store.getIdentitiesSupplier().get(), is(equalTo(expected)));
  }
}
