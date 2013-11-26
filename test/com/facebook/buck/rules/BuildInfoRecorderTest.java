/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.io.File;

public class BuildInfoRecorderTest {

  @Test
  public void testAddMetadataMultipleValues() {
    BuildTarget buildTarget = new BuildTarget("//foo", "bar");
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(new File("."));
    RuleKey ruleKey = new RuleKey("d6475c3ad8b3d603bcf3bc0930358cae3c2a4b3d");
    RuleKey ruleKeyWithoutDeps = new RuleKey("026d7608df52b1c27297b55b49b1f9d2b5c3d487");

    BuildInfoRecorder buildInfoRecorder = new BuildInfoRecorder(buildTarget,
        projectFilesystem,
        ruleKey,
        ruleKeyWithoutDeps);
    buildInfoRecorder.addMetadata("foo", ImmutableList.of("bar", "biz", "baz"));
    assertEquals("[\"bar\",\"biz\",\"baz\"]",
        buildInfoRecorder.getMetadataFor("foo"));
  }
}
