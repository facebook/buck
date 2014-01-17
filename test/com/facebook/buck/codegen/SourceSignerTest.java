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

package com.facebook.buck.codegen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/**
 * Tests for {@link SourceSigner}.
 */
public class SourceSignerTest {

  private static final String UNSIGNED_BODY =
    "\n\n/*\n * I am the very model of a modern Major-General\n */\n";

  private static final String PREFIX = "// " + SourceSigner.GENERATED;

  private static final String SIGNED = PREFIX +
    " " +
    SourceSigner.formatSignature("5ef91319c45a08ca9fefd11dcd0e10e3") +
    UNSIGNED_BODY;

  private static final String INVALID = PREFIX +
    " " +
    SourceSigner.formatSignature("0123456789abcdef0123456789abcdef") +
    UNSIGNED_BODY;

  private static final String READY_TO_SIGN = PREFIX +
    " " +
    SourceSigner.PLACEHOLDER +
    UNSIGNED_BODY;

  @Test
  public void testUnsignedSourceHasUnsignedSignature() {
    assertEquals(
        SourceSigner.SignatureStatus.UNSIGNED,
        SourceSigner.getSignatureStatus(UNSIGNED_BODY));
  }

  @Test
  public void testSignedSourceHasValidSignature() {
    assertEquals(
        SourceSigner.SignatureStatus.OK,
        SourceSigner.getSignatureStatus(SIGNED));
  }

  @Test
  public void testInvalidSourceHasInvalidSignature() {
    assertEquals(
        SourceSigner.SignatureStatus.INVALID,
        SourceSigner.getSignatureStatus(INVALID));
  }

  @Test
  public void testSignedSourcePlusJunkHasInvalidSignature() {
    assertEquals(
        SourceSigner.SignatureStatus.INVALID,
        SourceSigner.getSignatureStatus(SIGNED + "junk at the end!"));
  }

  @Test
  public void testSigningReadyToSignSourceReturnsSigned() {
    assertEquals(
        SIGNED,
        SourceSigner.sign(READY_TO_SIGN).get());
  }


  @Test
  public void testResigningSourceWithPlaceholderAndExistingSignatureUpdatesAllSignatures() {
    String sourceWithExistingSignatureAndPlaceholder = SIGNED + "\n" +
      "// " + SourceSigner.PLACEHOLDER + "\n";
    String resignedSource = "// " + SourceSigner.GENERATED + " " +
      SourceSigner.formatSignature("f22f32305af6c5c4646bfbb0f3604545") + "\n\n" +
      "/*\n * I am the very model of a modern Major-General\n */\n\n" +
      "// " + SourceSigner.formatSignature("f22f32305af6c5c4646bfbb0f3604545") + "\n";

    assertEquals(
        resignedSource,
        SourceSigner.sign(sourceWithExistingSignatureAndPlaceholder).get());
  }

  @Test
  public void testSigningUnsignedSourceReturnsAbsent() {
    assertFalse(SourceSigner.sign(UNSIGNED_BODY).isPresent());
  }
}
