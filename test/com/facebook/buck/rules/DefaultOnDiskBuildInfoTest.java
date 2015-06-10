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

import static org.junit.Assert.assertThat;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import org.easymock.EasyMock;
import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Paths;

public class DefaultOnDiskBuildInfoTest {

  @Test
  public void whenMetadataEmptyStringThenGetValueReturnsEmptyString() {
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(
        projectFilesystem.readFileIfItExists(Paths.get("buck-out/bin/foo/bar/.baz/metadata/KEY")))
        .andReturn(Optional.of(""));
    EasyMock.replay(projectFilesystem);

    BuildTarget buildTarget = BuildTarget.builder("//foo/bar", "baz").build();
    DefaultOnDiskBuildInfo onDiskBuildInfo = new DefaultOnDiskBuildInfo(
        buildTarget,
        projectFilesystem);
    assertThat(
        onDiskBuildInfo.getValue("KEY"),
        Matchers.equalTo(Optional.of("")));

    EasyMock.verify(projectFilesystem);
  }

  @Test
   public void whenMetaDataJsonListThenGetValuesReturnsList() {
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(
        projectFilesystem.readFileIfItExists(Paths.get("buck-out/bin/foo/bar/.baz/metadata/KEY")))
        .andReturn(Optional.of("[\"bar\",\"biz\",\"baz\"]"));
    EasyMock.replay(projectFilesystem);

    BuildTarget buildTarget = BuildTarget.builder("//foo/bar", "baz").build();
    DefaultOnDiskBuildInfo onDiskBuildInfo = new DefaultOnDiskBuildInfo(buildTarget,
        projectFilesystem);
    assertThat(
        onDiskBuildInfo.getValues("KEY"),
        Matchers.equalTo(Optional.of(ImmutableList.of("bar", "biz", "baz"))));

    EasyMock.verify(projectFilesystem);
  }

  @Test
  public void whenMetaDataEmptyJsonListThenGetValuesReturnsEmptyList() {
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(
        projectFilesystem.readFileIfItExists(Paths.get("buck-out/bin/foo/bar/.baz/metadata/KEY")))
        .andReturn(Optional.of("[]"));
    EasyMock.replay(projectFilesystem);

    BuildTarget buildTarget = BuildTarget.builder("//foo/bar", "baz").build();
    DefaultOnDiskBuildInfo onDiskBuildInfo = new DefaultOnDiskBuildInfo(buildTarget,
        projectFilesystem);
    assertThat(
        onDiskBuildInfo.getValues("KEY"),
        Matchers.equalTo(Optional.of(ImmutableList.<String>of())));

    EasyMock.verify(projectFilesystem);
  }

  @Test
  public void whenMetadataEmptyStringThenGetValuesReturnsAbsent() {
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(
        projectFilesystem.readFileIfItExists(Paths.get("buck-out/bin/foo/bar/.baz/metadata/KEY")))
        .andReturn(Optional.of(""));
    EasyMock.replay(projectFilesystem);

    BuildTarget buildTarget = BuildTarget.builder("//foo/bar", "baz").build();
    DefaultOnDiskBuildInfo onDiskBuildInfo = new DefaultOnDiskBuildInfo(
        buildTarget,
        projectFilesystem);
    assertThat(
        onDiskBuildInfo.getValues("KEY"),
        Matchers.equalTo(Optional.<ImmutableList<String>>absent()));

    EasyMock.verify(projectFilesystem);
  }

  @Test
  public void whenMetadataInvalidJsonThenGetValuesReturnsAbsent() {
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(
        projectFilesystem.readFileIfItExists(Paths.get("buck-out/bin/foo/bar/.baz/metadata/KEY")))
        .andReturn(Optional.of("Some Invalid Json"));
    EasyMock.replay(projectFilesystem);

    BuildTarget buildTarget = BuildTarget.builder("//foo/bar", "baz").build();
    DefaultOnDiskBuildInfo onDiskBuildInfo = new DefaultOnDiskBuildInfo(
        buildTarget,
        projectFilesystem);
    assertThat(
        onDiskBuildInfo.getValues("KEY"),
        Matchers.equalTo(Optional.<ImmutableList<String>>absent()));

    EasyMock.verify(projectFilesystem);
  }

  @Test
  public void whenMetadataValidHashThenGetHashReturnsHash() {
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    String hash = "fac0fac1fac2fac3fac4fac5fac6fac7fac8fac9";
    EasyMock.expect(
        projectFilesystem.readFileIfItExists(Paths.get("buck-out/bin/foo/bar/.baz/metadata/KEY")))
        .andReturn(Optional.of(hash));
    EasyMock.replay(projectFilesystem);

    BuildTarget buildTarget = BuildTarget.builder("//foo/bar", "baz").build();
    DefaultOnDiskBuildInfo onDiskBuildInfo = new DefaultOnDiskBuildInfo(
        buildTarget,
        projectFilesystem);
    assertThat(
        onDiskBuildInfo.getHash("KEY"),
        Matchers.equalTo(Optional.of(Sha1HashCode.of(hash))));

    EasyMock.verify(projectFilesystem);
  }

  @Test
  public void whenMetadataEmptyStringThenGetHashReturnsAbsent() {
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(
        projectFilesystem.readFileIfItExists(Paths.get("buck-out/bin/foo/bar/.baz/metadata/KEY")))
        .andReturn(Optional.of(""));
    EasyMock.replay(projectFilesystem);

    BuildTarget buildTarget = BuildTarget.builder("//foo/bar", "baz").build();
    DefaultOnDiskBuildInfo onDiskBuildInfo = new DefaultOnDiskBuildInfo(
        buildTarget,
        projectFilesystem);
    assertThat(
        onDiskBuildInfo.getHash("KEY"),
        Matchers.equalTo(Optional.<Sha1HashCode>absent()));

    EasyMock.verify(projectFilesystem);
  }

  @Test
  public void whenMetadataInvalidHashThenGetHashReturnsAbsent() {
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(
        projectFilesystem.readFileIfItExists(Paths.get("buck-out/bin/foo/bar/.baz/metadata/KEY")))
        .andReturn(Optional.of("Not A Valid Hash"));
    EasyMock.replay(projectFilesystem);

    BuildTarget buildTarget = BuildTarget.builder("//foo/bar", "baz").build();
    DefaultOnDiskBuildInfo onDiskBuildInfo = new DefaultOnDiskBuildInfo(
        buildTarget,
        projectFilesystem);
    assertThat(
        onDiskBuildInfo.getHash("KEY"),
        Matchers.equalTo(Optional.<Sha1HashCode>absent()));

    EasyMock.verify(projectFilesystem);
  }

  @Test
     public void whenMetadataValidRuleKeyThenGetRuleKeyReturnsKey() {
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    String key = "fa";
    EasyMock.expect(
        projectFilesystem.readFileIfItExists(
            Paths.get("buck-out/bin/foo/bar/.baz/metadata/" + BuildInfo.METADATA_KEY_FOR_RULE_KEY)))
        .andReturn(Optional.of(key));
    EasyMock.replay(projectFilesystem);

    BuildTarget buildTarget = BuildTarget.builder("//foo/bar", "baz").build();
    DefaultOnDiskBuildInfo onDiskBuildInfo = new DefaultOnDiskBuildInfo(
        buildTarget,
        projectFilesystem);
    assertThat(
        onDiskBuildInfo.getRuleKey(BuildInfo.METADATA_KEY_FOR_RULE_KEY),
        Matchers.equalTo(Optional.of(new RuleKey(key))));

    EasyMock.verify(projectFilesystem);
  }

  @Test
  public void whenMetadataEmptyStringThenGetRuleKeyReturnsAbsent() {
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(
        projectFilesystem.readFileIfItExists(
            Paths.get("buck-out/bin/foo/bar/.baz/metadata/" + BuildInfo.METADATA_KEY_FOR_RULE_KEY)))
        .andReturn(Optional.of(""));
    EasyMock.replay(projectFilesystem);

    BuildTarget buildTarget = BuildTarget.builder("//foo/bar", "baz").build();
    DefaultOnDiskBuildInfo onDiskBuildInfo = new DefaultOnDiskBuildInfo(
        buildTarget,
        projectFilesystem);
    assertThat(
        onDiskBuildInfo.getRuleKey(BuildInfo.METADATA_KEY_FOR_RULE_KEY),
        Matchers.equalTo(Optional.<RuleKey>absent()));

    EasyMock.verify(projectFilesystem);
  }

  @Test
  public void whenMetadataInvalidRuleKeyThenGetRuleKeyReturnsAbsent() {
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(
        projectFilesystem.readFileIfItExists(
            Paths.get("buck-out/bin/foo/bar/.baz/metadata/" + BuildInfo.METADATA_KEY_FOR_RULE_KEY)))
        .andReturn(Optional.of("Not A Valid Rule Key"));
    EasyMock.replay(projectFilesystem);

    BuildTarget buildTarget = BuildTarget.builder("//foo/bar", "baz").build();
    DefaultOnDiskBuildInfo onDiskBuildInfo = new DefaultOnDiskBuildInfo(
        buildTarget,
        projectFilesystem);
    assertThat(
        onDiskBuildInfo.getRuleKey(BuildInfo.METADATA_KEY_FOR_RULE_KEY),
        Matchers.equalTo(Optional.<RuleKey>absent()));

    EasyMock.verify(projectFilesystem);
  }

  @Test
  public void whenMetadataValidRuleKeyThenGetRuleKeyWithoutDepsReturnsKey() {
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    String key = "fa";
    EasyMock.expect(
        projectFilesystem.readFileIfItExists(
            Paths.get("buck-out/bin/foo/bar/.baz/metadata/" +
                    BuildInfo.METADATA_KEY_FOR_RULE_KEY_WITHOUT_DEPS)))
        .andReturn(Optional.of(key));
    EasyMock.replay(projectFilesystem);

    BuildTarget buildTarget = BuildTarget.builder("//foo/bar", "baz").build();
    DefaultOnDiskBuildInfo onDiskBuildInfo = new DefaultOnDiskBuildInfo(
        buildTarget,
        projectFilesystem);
    assertThat(
        onDiskBuildInfo.getRuleKey(BuildInfo.METADATA_KEY_FOR_RULE_KEY_WITHOUT_DEPS),
        Matchers.equalTo(Optional.of(new RuleKey(key))));

    EasyMock.verify(projectFilesystem);
  }

  @Test
  public void whenMetadataEmptyStringThenGetRuleKeyWithoutDepsReturnsAbsent() {
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(
        projectFilesystem.readFileIfItExists(
            Paths.get("buck-out/bin/foo/bar/.baz/metadata/" +
                    BuildInfo.METADATA_KEY_FOR_RULE_KEY_WITHOUT_DEPS)))
        .andReturn(Optional.of(""));
    EasyMock.replay(projectFilesystem);

    BuildTarget buildTarget = BuildTarget.builder("//foo/bar", "baz").build();
    DefaultOnDiskBuildInfo onDiskBuildInfo = new DefaultOnDiskBuildInfo(
        buildTarget,
        projectFilesystem);
    assertThat(
        onDiskBuildInfo.getRuleKey(BuildInfo.METADATA_KEY_FOR_RULE_KEY_WITHOUT_DEPS),
        Matchers.equalTo(Optional.<RuleKey>absent()));

    EasyMock.verify(projectFilesystem);
  }

  @Test
  public void whenMetadataInvalidRuleKeyThenGetRuleKeyWithoutDepsReturnsAbsent() {
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(
        projectFilesystem.readFileIfItExists(
            Paths.get("buck-out/bin/foo/bar/.baz/metadata/" +
                    BuildInfo.METADATA_KEY_FOR_RULE_KEY_WITHOUT_DEPS)))
        .andReturn(Optional.of("Not A Valid Rule Key"));
    EasyMock.replay(projectFilesystem);

    BuildTarget buildTarget = BuildTarget.builder("//foo/bar", "baz").build();
    DefaultOnDiskBuildInfo onDiskBuildInfo = new DefaultOnDiskBuildInfo(
        buildTarget,
        projectFilesystem);
    assertThat(
        onDiskBuildInfo.getRuleKey(BuildInfo.METADATA_KEY_FOR_RULE_KEY_WITHOUT_DEPS),
        Matchers.equalTo(Optional.<RuleKey>absent()));

    EasyMock.verify(projectFilesystem);
  }
}
