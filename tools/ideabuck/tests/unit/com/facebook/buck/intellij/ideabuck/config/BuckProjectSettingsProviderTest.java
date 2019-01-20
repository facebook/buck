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
import java.util.Arrays;
import java.util.Optional;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Test;

public class BuckProjectSettingsProviderTest {
  @Test
  public void migrateCellPrefsOnLoadState() {
    Project project = EasyMock.createMock(Project.class);
    BuckExecutableSettingsProvider buckExecutableSettingsProvider =
        EasyMock.createMock(BuckExecutableSettingsProvider.class);
    EasyMock.replay(project, buckExecutableSettingsProvider);

    BuckCellSettingsProvider buckCellSettingsProvider = new BuckCellSettingsProvider();
    BuckProjectSettingsProvider.State state = new BuckProjectSettingsProvider.State();
    BuckCell cell1 = new BuckCell().withName("one").withRoot("$PROJECT_DIR$/one");
    BuckCell cell2 = new BuckCell().withName("two").withRoot("$PROJECT_DIR$/two");
    state.cells = Arrays.asList(cell1, cell2);

    BuckProjectSettingsProvider projectSettings =
        new BuckProjectSettingsProvider(
            project, buckCellSettingsProvider, buckExecutableSettingsProvider);
    projectSettings.loadState(state);
    Assert.assertEquals(
        "loadState() should have migrated cell settings to the BuckCellSettingsProvider",
        Arrays.asList(cell1, cell2),
        buckCellSettingsProvider.getCells());
    Assert.assertNull(
        "BuckProjectSettingsProvider's state should no longer contain cell info",
        projectSettings.getState().cells);
  }

  @Test
  public void migrateExecutablePrefsOnLoadState() {
    Project project = EasyMock.createMock(Project.class);
    BuckExecutableSettingsProvider executableSettingsProvider =
        EasyMock.createMock(BuckExecutableSettingsProvider.class);
    EasyMock.replay(project, executableSettingsProvider);

    BuckCellSettingsProvider buckCellSettingsProvider = new BuckCellSettingsProvider();
    BuckExecutableSettingsProvider buckExecutableSettingsProvider =
        new BuckExecutableSettingsProvider();

    BuckProjectSettingsProvider.State state = new BuckProjectSettingsProvider.State();
    String adbPath = "/path/to/adb/executable";
    String buckPath = "/path/to/buck/executable";
    state.adbExecutable = adbPath;
    state.buckExecutable = buckPath;

    BuckProjectSettingsProvider projectSettings =
        new BuckProjectSettingsProvider(
            project, buckCellSettingsProvider, buckExecutableSettingsProvider);
    projectSettings.loadState(state);
    Assert.assertEquals(
        "Should have migrated adb executable",
        Optional.of(adbPath),
        buckExecutableSettingsProvider.getAdbExecutableOverride());
    Assert.assertEquals(
        "Should have migrated buck executable",
        Optional.of(buckPath),
        buckExecutableSettingsProvider.getBuckExecutableOverride());
  }

  @Test
  public void migrateExecutablePrefsOnLoadStateShouldNotOverwriteExistingPrefs() {
    Project project = EasyMock.createMock(Project.class);
    BuckExecutableSettingsProvider executableSettingsProvider =
        EasyMock.createMock(BuckExecutableSettingsProvider.class);
    EasyMock.replay(project, executableSettingsProvider);

    BuckCellSettingsProvider buckCellSettingsProvider = new BuckCellSettingsProvider();
    String adbPath = "/path/to/adb/executable";
    String buckPath = "/path/to/buck/executable";
    BuckExecutableSettingsProvider buckExecutableSettingsProvider =
        new BuckExecutableSettingsProvider();
    buckExecutableSettingsProvider.setAdbExecutableOverride(Optional.of(adbPath));
    buckExecutableSettingsProvider.setBuckExecutableOverride(Optional.of(buckPath));

    BuckProjectSettingsProvider.State state = new BuckProjectSettingsProvider.State();
    state.adbExecutable = "/old/path/to/adb";
    state.buckExecutable = "/old/path/to/buck";

    BuckProjectSettingsProvider projectSettings =
        new BuckProjectSettingsProvider(
            project, buckCellSettingsProvider, buckExecutableSettingsProvider);
    projectSettings.loadState(state);
    Assert.assertEquals(
        "Should not migrate adb executable when one is already set",
        Optional.of(adbPath),
        buckExecutableSettingsProvider.getAdbExecutableOverride());
    Assert.assertEquals(
        "Should not migrate buck executable when one is already set",
        Optional.of(buckPath),
        buckExecutableSettingsProvider.getBuckExecutableOverride());
  }
}
