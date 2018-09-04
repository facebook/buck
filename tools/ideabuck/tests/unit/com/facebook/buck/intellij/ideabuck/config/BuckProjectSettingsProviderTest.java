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

import com.facebook.buck.intellij.ideabuck.config.BuckProjectSettingsProvider.State;
import com.intellij.openapi.project.Project;
import java.util.Optional;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Test;

public class BuckProjectSettingsProviderTest {

  @Test
  public void whenLastAliasUnsetMigratesValueFromBuckSettingsProvider() {
    BuckSettingsProvider applicationSettings = new BuckSettingsProvider();
    Project project = EasyMock.createMock(Project.class);
    EasyMock.expect(project.getBasePath()).andReturn("/path/to/project").anyTimes();
    EasyMock.replay(project);

    applicationSettings.setLastAliasForProject(project, "foo");
    Assert.assertEquals(
        "Legacy application prefs (prev use of ideabuck) should have lastAlias",
        applicationSettings.getLastAliasForProject(project),
        "foo");

    BuckProjectSettingsProvider projectSettings =
        new BuckProjectSettingsProvider(project, applicationSettings);
    projectSettings.loadState(new State());
    Assert.assertEquals(
        "Should get legacy lastAlias value from application settings",
        projectSettings.getLastAlias(),
        Optional.of("foo"));
    Assert.assertNull(
        "Migration should remove legacy lastAlias from application settings",
        applicationSettings.getLastAliasForProject(project));
  }

  @Test
  public void whenLastAliasUnsetAndBuckSettingsProviderHasNoLegacyValue() {
    BuckSettingsProvider applicationSettings = new BuckSettingsProvider();
    Project project = EasyMock.createMock(Project.class);
    EasyMock.expect(project.getBasePath()).andReturn("/path/to/project").anyTimes();
    EasyMock.replay(project);

    BuckProjectSettingsProvider projectSettings =
        new BuckProjectSettingsProvider(project, applicationSettings);
    projectSettings.loadState(new State());
    Assert.assertEquals(
        "When no legacy value of lastAlias in application settings, starts off empty",
        projectSettings.getLastAlias(),
        Optional.empty());
  }
}
