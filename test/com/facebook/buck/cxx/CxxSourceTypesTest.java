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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import org.hamcrest.Matchers;
import org.junit.Test;

/** Unit tests for {@link CxxSourceTypes}. */
public class CxxSourceTypesTest {

  @Test
  public void expectedTypesArePreprocessable() {
    assertTrue(CxxSourceTypes.isPreprocessableType(CxxSource.Type.ASSEMBLER_WITH_CPP));
    assertTrue(CxxSourceTypes.isPreprocessableType(CxxSource.Type.C));
    assertTrue(CxxSourceTypes.isPreprocessableType(CxxSource.Type.CXX));
    assertTrue(CxxSourceTypes.isPreprocessableType(CxxSource.Type.OBJC));
    assertTrue(CxxSourceTypes.isPreprocessableType(CxxSource.Type.CUDA));
    assertTrue(CxxSourceTypes.isPreprocessableType(CxxSource.Type.ASM_WITH_CPP));
    assertTrue(CxxSourceTypes.isPreprocessableType(CxxSource.Type.PCM));
  }

  @Test
  public void expectedTypesAreNotPreprocessable() {
    assertFalse(CxxSourceTypes.isPreprocessableType(CxxSource.Type.ASSEMBLER));
    assertFalse(CxxSourceTypes.isPreprocessableType(CxxSource.Type.C_CPP_OUTPUT));
    assertFalse(CxxSourceTypes.isPreprocessableType(CxxSource.Type.CXX_CPP_OUTPUT));
    assertFalse(CxxSourceTypes.isPreprocessableType(CxxSource.Type.OBJC_CPP_OUTPUT));
    assertFalse(CxxSourceTypes.isPreprocessableType(CxxSource.Type.CUDA_CPP_OUTPUT));
    assertFalse(CxxSourceTypes.isPreprocessableType(CxxSource.Type.ASM));
  }

  @Test
  public void expectedTypesAreCompilable() {
    assertTrue(CxxSourceTypes.isCompilableType(CxxSource.Type.ASSEMBLER));
    assertTrue(CxxSourceTypes.isCompilableType(CxxSource.Type.C_CPP_OUTPUT));
    assertTrue(CxxSourceTypes.isCompilableType(CxxSource.Type.CXX_CPP_OUTPUT));
    assertTrue(CxxSourceTypes.isCompilableType(CxxSource.Type.OBJC_CPP_OUTPUT));
    assertTrue(CxxSourceTypes.isCompilableType(CxxSource.Type.CUDA_CPP_OUTPUT));
    assertTrue(CxxSourceTypes.isCompilableType(CxxSource.Type.ASM));
    assertTrue(CxxSourceTypes.isCompilableType(CxxSource.Type.PCM));
  }

  @Test
  public void expectedTypesAreNotCompilable() {
    assertFalse(CxxSourceTypes.isCompilableType(CxxSource.Type.ASSEMBLER_WITH_CPP));
    assertFalse(CxxSourceTypes.isCompilableType(CxxSource.Type.C));
    assertFalse(CxxSourceTypes.isCompilableType(CxxSource.Type.CXX));
    assertFalse(CxxSourceTypes.isCompilableType(CxxSource.Type.OBJC));
    assertFalse(CxxSourceTypes.isCompilableType(CxxSource.Type.CUDA));
    assertFalse(CxxSourceTypes.isCompilableType(CxxSource.Type.ASM_WITH_CPP));
  }

  @Test
  public void expectedPreprocessor() {
    CxxPlatform cxxPlatform = CxxPlatformUtils.DEFAULT_PLATFORM;
    assertThat(
        CxxSourceTypes.getPreprocessor(cxxPlatform, CxxSource.Type.ASSEMBLER_WITH_CPP),
        Matchers.is(cxxPlatform.getAspp()));
    assertThat(
        CxxSourceTypes.getPreprocessor(cxxPlatform, CxxSource.Type.C),
        Matchers.is(cxxPlatform.getCpp()));
    assertThat(
        CxxSourceTypes.getPreprocessor(cxxPlatform, CxxSource.Type.CXX),
        Matchers.is(cxxPlatform.getCxxpp()));
    assertThat(
        CxxSourceTypes.getPreprocessor(cxxPlatform, CxxSource.Type.PCM),
        Matchers.is(cxxPlatform.getCxxpp()));
    assertThat(
        CxxSourceTypes.getPreprocessor(cxxPlatform, CxxSource.Type.OBJC),
        Matchers.is(cxxPlatform.getCpp()));
    assertThat(
        CxxSourceTypes.getPreprocessor(cxxPlatform, CxxSource.Type.OBJCXX),
        Matchers.is(cxxPlatform.getCxxpp()));
    assertThat(
        CxxSourceTypes.getPreprocessor(cxxPlatform, CxxSource.Type.CUDA),
        Matchers.is(cxxPlatform.getCudapp().get()));
    assertThat(
        CxxSourceTypes.getPreprocessor(cxxPlatform, CxxSource.Type.ASM_WITH_CPP),
        Matchers.is(cxxPlatform.getAsmpp().get()));
  }

  @Test
  public void expectedCompiler() {
    CxxPlatform cxxPlatform = CxxPlatformUtils.DEFAULT_PLATFORM;
    assertThat(
        CxxSourceTypes.getCompiler(cxxPlatform, CxxSource.Type.ASSEMBLER),
        Matchers.is(cxxPlatform.getAs()));
    assertThat(
        CxxSourceTypes.getCompiler(cxxPlatform, CxxSource.Type.C_CPP_OUTPUT),
        Matchers.is(cxxPlatform.getCc()));
    assertThat(
        CxxSourceTypes.getCompiler(cxxPlatform, CxxSource.Type.CXX_CPP_OUTPUT),
        Matchers.is(cxxPlatform.getCxx()));
    assertThat(
        CxxSourceTypes.getCompiler(cxxPlatform, CxxSource.Type.PCM),
        Matchers.is(cxxPlatform.getCxx()));
    assertThat(
        CxxSourceTypes.getCompiler(cxxPlatform, CxxSource.Type.OBJC_CPP_OUTPUT),
        Matchers.is(cxxPlatform.getCc()));
    assertThat(
        CxxSourceTypes.getCompiler(cxxPlatform, CxxSource.Type.OBJCXX_CPP_OUTPUT),
        Matchers.is(cxxPlatform.getCxx()));
    assertThat(
        CxxSourceTypes.getCompiler(cxxPlatform, CxxSource.Type.CUDA_CPP_OUTPUT),
        Matchers.is(cxxPlatform.getCuda().get()));
    assertThat(
        CxxSourceTypes.getCompiler(cxxPlatform, CxxSource.Type.ASM),
        Matchers.is(cxxPlatform.getAsm().get()));
  }

  @Test
  public void toName() {
    assertThat(CxxSourceTypes.toName(CxxSource.Type.C), Matchers.equalTo("c"));
    assertThat(CxxSourceTypes.toName(CxxSource.Type.C_CPP_OUTPUT), Matchers.equalTo("c"));
    assertThat(CxxSourceTypes.toName(CxxSource.Type.CXX), Matchers.equalTo("cxx"));
    assertThat(CxxSourceTypes.toName(CxxSource.Type.CXX_CPP_OUTPUT), Matchers.equalTo("cxx"));
    assertThat(CxxSourceTypes.toName(CxxSource.Type.OBJC), Matchers.equalTo("objc"));
    assertThat(CxxSourceTypes.toName(CxxSource.Type.OBJC_CPP_OUTPUT), Matchers.equalTo("objc"));
    assertThat(CxxSourceTypes.toName(CxxSource.Type.OBJCXX), Matchers.equalTo("objcxx"));
    assertThat(CxxSourceTypes.toName(CxxSource.Type.OBJCXX_CPP_OUTPUT), Matchers.equalTo("objcxx"));
    assertThat(CxxSourceTypes.toName(CxxSource.Type.CUDA), Matchers.equalTo("cuda"));
    assertThat(CxxSourceTypes.toName(CxxSource.Type.CUDA_CPP_OUTPUT), Matchers.equalTo("cuda"));
    assertThat(CxxSourceTypes.toName(CxxSource.Type.HIP), Matchers.equalTo("hip"));
    assertThat(CxxSourceTypes.toName(CxxSource.Type.HIP_CPP_OUTPUT), Matchers.equalTo("hip"));
    assertThat(
        CxxSourceTypes.toName(CxxSource.Type.CXX_THINLINK), Matchers.equalTo("cxx_thinlink"));
    assertThat(CxxSourceTypes.toName(CxxSource.Type.PCM), Matchers.equalTo("pcm"));
    assertThat(CxxSourceTypes.toName(CxxSource.Type.ASSEMBLER), Matchers.equalTo("assembler"));
    assertThat(
        CxxSourceTypes.toName(CxxSource.Type.ASSEMBLER_WITH_CPP), Matchers.equalTo("assembler"));
    assertThat(CxxSourceTypes.toName(CxxSource.Type.ASM), Matchers.equalTo("asm"));
    assertThat(CxxSourceTypes.toName(CxxSource.Type.ASM_WITH_CPP), Matchers.equalTo("asm"));
  }
}
