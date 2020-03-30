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

package com.facebook.buck.core.build.engine.buildinfo;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.timing.DefaultClock;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class DefaultOnDiskBuildInfoTest {
  private final BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo/bar:baz");
  @Rule public ExpectedException thrown = ExpectedException.none();
  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  public ProjectFilesystem projectFilesystem;

  @Before
  public void setUp() {
    projectFilesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
  }

  @Test
  public void whenMetadataEmptyStringThenGetValueReturnsEmptyString() throws IOException {
    setMetadata("KEY", "");
    DefaultOnDiskBuildInfo onDiskBuildInfo = createOnDiskBuildInfo();
    assertThat(onDiskBuildInfo.getValue("KEY"), Matchers.equalTo(Either.ofLeft("")));
  }

  @Test
  public void whenMetaDataJsonListThenGetValuesReturnsList() throws IOException {
    setMetadata("KEY", "[\"bar\",\"biz\",\"baz\"]");
    DefaultOnDiskBuildInfo onDiskBuildInfo = createOnDiskBuildInfo();
    assertThat(
        onDiskBuildInfo.getValues("KEY"),
        Matchers.equalTo(Optional.of(ImmutableList.of("bar", "biz", "baz"))));
  }

  @Test
  public void whenMetaDataEmptyJsonListThenGetValuesReturnsEmptyList() throws IOException {
    setMetadata("KEY", "[]");
    DefaultOnDiskBuildInfo onDiskBuildInfo = createOnDiskBuildInfo();
    assertThat(
        onDiskBuildInfo.getValues("KEY"),
        Matchers.equalTo(Optional.of(ImmutableList.<String>of())));
  }

  @Test
  public void whenMetadataEmptyStringThenGetValuesReturnsAbsent() throws IOException {
    setMetadata("KEY", "");
    DefaultOnDiskBuildInfo onDiskBuildInfo = createOnDiskBuildInfo();
    assertThat(onDiskBuildInfo.getValues("KEY"), Matchers.equalTo(Optional.empty()));
  }

  @Test
  public void whenMetadataInvalidJsonThenGetValuesReturnsAbsent() throws IOException {
    setMetadata("KEY", "Some Invalid Json");
    DefaultOnDiskBuildInfo onDiskBuildInfo = createOnDiskBuildInfo();
    assertThat(onDiskBuildInfo.getValues("KEY"), Matchers.equalTo(Optional.empty()));
  }

  @Test
  public void whenMetadataValidRuleKeyThenGetRuleKeyReturnsKey() throws IOException {
    String key = "fa";
    setBuildMetadata(BuildInfo.MetadataKey.RULE_KEY, key);
    DefaultOnDiskBuildInfo onDiskBuildInfo = createOnDiskBuildInfo();
    assertThat(
        onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.RULE_KEY),
        Matchers.equalTo(Optional.of(new RuleKey(key))));
  }

  @Test
  public void whenMetadataEmptyStringThenGetRuleKeyReturnsAbsent() throws IOException {
    setBuildMetadata(BuildInfo.MetadataKey.RULE_KEY, "");
    DefaultOnDiskBuildInfo onDiskBuildInfo = createOnDiskBuildInfo();
    assertThat(
        onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.RULE_KEY),
        Matchers.equalTo(Optional.empty()));
  }

  @Test
  public void whenMetadataInvalidRuleKeyThenGetRuleKeyReturnsAbsent() throws IOException {
    setBuildMetadata(BuildInfo.MetadataKey.RULE_KEY, "Not A Valid Rule Key");
    DefaultOnDiskBuildInfo onDiskBuildInfo = createOnDiskBuildInfo();
    assertThat(
        onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.RULE_KEY),
        Matchers.equalTo(Optional.empty()));
  }

  @Test
  public void testGetMetadataForArtifactRequiresOriginBuildId() throws IOException {
    setBuildMetadata(BuildInfo.MetadataKey.RULE_KEY, "Not A Valid Rule Key");
    DefaultOnDiskBuildInfo onDiskBuildInfo = createOnDiskBuildInfo();

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage(
        "Cache artifact for build target //foo/bar:baz is missing metadata ORIGIN_BUILD_ID");

    onDiskBuildInfo.getMetadataForArtifact();
  }

  private void setMetadata(String key, String value) throws IOException {
    BuildInfoRecorder buildInfoRecorder = createBuildInfoRecorder();
    buildInfoRecorder.addMetadata(key, value);
    buildInfoRecorder.writeMetadataToDisk(true);
  }

  private void setBuildMetadata(String key, String value) throws IOException {
    BuildInfoRecorder buildInfoRecorder = createBuildInfoRecorder();
    buildInfoRecorder.addBuildMetadata(key, value);
    buildInfoRecorder.writeMetadataToDisk(true);
  }

  public DefaultOnDiskBuildInfo createOnDiskBuildInfo() throws IOException {
    return new DefaultOnDiskBuildInfo(buildTarget, projectFilesystem, createBuildInfoStore());
  }

  private BuildInfoRecorder createBuildInfoRecorder() throws IOException {
    return new BuildInfoRecorder(
        buildTarget,
        projectFilesystem,
        createBuildInfoStore(),
        new DefaultClock(),
        new BuildId(),
        ImmutableMap.of());
  }

  private SQLiteBuildInfoStore createBuildInfoStore() throws IOException {
    return new SQLiteBuildInfoStore(projectFilesystem);
  }
}
