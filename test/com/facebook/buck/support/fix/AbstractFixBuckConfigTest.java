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

package com.facebook.buck.support.fix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Test;

public class AbstractFixBuckConfigTest {
  @Test
  public void addsCommandArgsFileToCommand() {
    String expectedPath = Paths.get("foo", "bar").toString();
    BuckConfig config =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "fix",
                    ImmutableMap.of(
                        "fix_script",
                            "{repository_root}/path/to/fixit.sh \"quoted arg\" --fix-spec-path   {fix_spec_path}",
                        "fix_script_contact", "support@example.com")))
            .build();

    FixBuckConfig fixConfig = config.getView(FixBuckConfig.class);

    assertEquals(
        Optional.of(
            ImmutableList.of(
                "{repository_root}/path/to/fixit.sh",
                "quoted arg",
                "--fix-spec-path",
                "{fix_spec_path}")),
        fixConfig.getFixScript());
    assertEquals(Optional.of("support@example.com"), fixConfig.getFixScriptContact());

    assertEquals(
        ImmutableList.of("repo/path/to/fixit.sh", "quoted arg", "--fix-spec-path", expectedPath),
        fixConfig.getInterpolatedFixScript(
            fixConfig.getFixScript().get(), Paths.get("repo"), Paths.get("foo", "bar")));

    assertEquals(
        String.format(
            "Running buck fix command 'repo/path/to/fixit.sh quoted arg --fix-spec-path %s'.%s  Please report any problems to 'support@example.com'",
            expectedPath, System.lineSeparator()),
        fixConfig.getInterpolatedFixScriptMessage(
            fixConfig.getInterpolatedFixScript(
                fixConfig.getFixScript().get(), Paths.get("repo"), Paths.get("foo", "bar")),
            fixConfig.getFixScriptContact().get()));

    assertTrue(fixConfig.shouldPrintFixScriptMessage());
  }

  @Test
  public void usesDefaultMessage() {
    BuckConfig config =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "fix",
                    ImmutableMap.of(
                        "fix_script",
                        "{repository_root}/path/to/fixit.sh \"quoted arg\" --fix-spec-path   {fix_spec_path}",
                        "fix_script_contact",
                        "support@example.com",
                        "fix_script_message",
                        "Running '{command}', talk to '{contact}'")))
            .build();

    FixBuckConfig fixConfig = config.getView(FixBuckConfig.class);

    assertEquals(
        String.format(
            "Running 'repo/path/to/fixit.sh quoted arg --fix-spec-path %s', talk to 'support@example.com'",
            Paths.get("foo", "bar")),
        fixConfig.getInterpolatedFixScriptMessage(
            fixConfig.getInterpolatedFixScript(
                fixConfig.getFixScript().get(), Paths.get("repo"), Paths.get("foo", "bar")),
            fixConfig.getFixScriptContact().get()));
    assertTrue(fixConfig.shouldPrintFixScriptMessage());
  }

  @Test
  public void doesNotPrintOnEmptyMessage() {
    BuckConfig config =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "fix",
                    ImmutableMap.of(
                        "fix_script",
                        "path/to/fixit.sh \"quoted arg\" --fix-spec-path   {fix_spec_path}",
                        "fix_script_contact",
                        "support@example.com",
                        "fix_script_message",
                        "")))
            .build();

    FixBuckConfig fixConfig = config.getView(FixBuckConfig.class);

    assertFalse(fixConfig.shouldPrintFixScriptMessage());
  }
}
