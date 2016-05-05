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

package unit;

import com.facebook.buck.intellij.plugin.config.BuckModule;
import com.facebook.buck.intellij.plugin.ui.utils.BuckPluginNotifications;
import com.intellij.ide.ui.UISettings;
import com.intellij.ide.util.ProjectPropertiesComponentImpl;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.mock.MockApplication;
import com.intellij.mock.MockApplicationEx;
import com.intellij.mock.MockProjectEx;
import com.intellij.notification.Notification;
import com.intellij.notification.Notifications;
import com.intellij.notification.NotificationsAdapter;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.project.Project;

import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import java.lang.reflect.Field;

import unit.util.MockDisposable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BuckModuleTest {
  class NotificationsAdapterTester extends NotificationsAdapter {
    int countCalls;

    public NotificationsAdapterTester() {
      countCalls = 0;
    }

    @Override
    public void notify(@NotNull Notification notification) {
      countCalls++;
    }
  }
  public Project initBuckModule() {
    Extensions.registerAreaClass("IDEA_PROJECT", null);
    MockDisposable mockDisposable = new MockDisposable();
    MockProjectEx project = new MockProjectEx(mockDisposable);

    MockApplication application = new MockApplicationEx(mockDisposable);
    ApplicationManager.setApplication(application, mockDisposable);
    application.registerService(UISettings.class, UISettings.getShadowInstance());
    application.registerService(PropertiesComponent.class, new ProjectPropertiesComponentImpl());

    return project;
  }

  @Test
  public void hasBuckModuleInitThenActionToolbarPopupShownOnce() {
    Project project = initBuckModule();

    Field field = BuckPluginNotifications.class.getDeclaredFields()[0];
    field.setAccessible(true);
    NotificationsAdapterTester notificationsAdapterTester = new NotificationsAdapterTester();
    project.getMessageBus().connect().subscribe(Notifications.TOPIC, notificationsAdapterTester);

    new BuckModule(project);
    try {
      String groupId = field.get(field.getType()).toString();
      assertTrue(PropertiesComponent.getInstance().isValueSet(groupId));
      new BuckModule(project);
      assertTrue(PropertiesComponent.getInstance().isValueSet(groupId));
      new BuckModule(project);
      assertTrue(PropertiesComponent.getInstance().isValueSet(groupId));
    } catch (IllegalAccessException e) {
      e.printStackTrace();
    }
    assertEquals(notificationsAdapterTester.countCalls, 1);
  }

  @Test
  public void hasBuckModuleInitAndUnsetValueThenActionToolbarPopupShownEveryTime() {
    Project project = initBuckModule();

    Field field = BuckPluginNotifications.class.getDeclaredFields()[0];
    field.setAccessible(true);

    NotificationsAdapterTester notificationsAdapterTester = new NotificationsAdapterTester();
    project.getMessageBus().connect().subscribe(
        Notifications.TOPIC,
        notificationsAdapterTester);

    new BuckModule(project);
    try {
      String groupId = field.get(field.getType()).toString();
      assertTrue(PropertiesComponent.getInstance().isValueSet(groupId));
      PropertiesComponent.getInstance().unsetValue(groupId);
      new BuckModule(project);
      assertTrue(PropertiesComponent.getInstance().isValueSet(groupId));
      PropertiesComponent.getInstance().unsetValue(groupId);
      new BuckModule(project);

    } catch (IllegalAccessException e) {
      e.printStackTrace();
    }
    assertEquals(notificationsAdapterTester.countCalls, 3);
  }
}
