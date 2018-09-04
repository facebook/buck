/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.intellij.ideabuck.config;

import com.intellij.openapi.project.Project;
import java.util.Optional;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Test;

public class BuckProjectSettingsProviderTest {
  @Test
  public void projectAdbExecutableCanOverrideDefaultAdb() {
    String defaultAdb = "/path/to/default/adb";
    String projectAdb = "/custom/override/adb";
    BuckExecutableDetector buckExecutableDetector =
        EasyMock.createMock(BuckExecutableDetector.class);
    EasyMock.expect(buckExecutableDetector.getAdbExecutable()).andReturn(defaultAdb).anyTimes();
    Project project = EasyMock.createMock(Project.class);
    EasyMock.expect(project.getBasePath()).andReturn("/path/to/project").anyTimes();
    EasyMock.replay(buckExecutableDetector, project);

    BuckProjectSettingsProvider projectSettings =
        new BuckProjectSettingsProvider(project, buckExecutableDetector);

    projectSettings.setAdbExecutableOverride(Optional.empty());
    Assert.assertEquals(
        "Should detect adb when not overridden",
        defaultAdb,
        projectSettings.resolveAdbExecutable());

    projectSettings.setAdbExecutableOverride(Optional.of(projectAdb));
    Assert.assertEquals(
        "Should use project adb when overridden",
        projectAdb,
        projectSettings.resolveAdbExecutable());
  }

  @Test
  public void projectBuckExecutableCanOverrideDefaultBuck() {
    String defaultBuck = "/path/to/default/buck";
    String projectBuck = "/custom/override/buck";
    BuckExecutableDetector buckExecutableDetector =
        EasyMock.createMock(BuckExecutableDetector.class);
    EasyMock.expect(buckExecutableDetector.getBuckExecutable()).andReturn(defaultBuck).anyTimes();
    Project project = EasyMock.createMock(Project.class);
    EasyMock.expect(project.getBasePath()).andReturn("/path/to/project").anyTimes();
    EasyMock.replay(buckExecutableDetector, project);

    BuckProjectSettingsProvider projectSettings =
        new BuckProjectSettingsProvider(project, buckExecutableDetector);

    projectSettings.setBuckExecutableOverride(Optional.empty());
    Assert.assertEquals(
        "Should use application buck when not overridden",
        defaultBuck,
        projectSettings.resolveBuckExecutable());

    projectSettings.setBuckExecutableOverride(Optional.of(projectBuck));
    Assert.assertEquals(
        "Should use project buck when overridden",
        projectBuck,
        projectSettings.resolveBuckExecutable());
  }
}
