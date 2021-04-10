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

package com.facebook.buck.intellij.ideabuck.configurations;

import com.facebook.buck.intellij.ideabuck.build.BuckBuildManager;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.LocatableConfigurationBase;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.runners.RunConfigurationWithSuppressedDefaultRunAction;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.xmlb.XmlSerializer;
import javax.annotation.Nullable;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;

/** Base configuration class for Buck commands */
public abstract class AbstractConfiguration<T extends AbstractConfiguration.Data>
    extends LocatableConfigurationBase implements RunConfigurationWithSuppressedDefaultRunAction {

  public final T data = createData();

  protected AbstractConfiguration(
      Project project, @NotNull ConfigurationFactory factory, String name) {
    super(project, factory, name);
  }

  @Override
  public void readExternal(Element element) throws InvalidDataException {
    super.readExternal(element);
    XmlSerializer.deserializeInto(data, element);
  }

  @Override
  public void writeExternal(Element element) throws WriteExternalException {
    super.writeExternal(element);
    XmlSerializer.serializeInto(data, element);
  }

  public boolean isBuckBuilding() {
    final BuckBuildManager buildManager = BuckBuildManager.getInstance(getProject());
    if (buildManager.isBuilding()) {
      final Notification notification =
          new Notification(
              "", "Can't run build. Buck is already running!", "", NotificationType.ERROR);
      Notifications.Bus.notify(notification);
      return true;
    }
    return false;
  }

  @Nullable
  @Override
  public String suggestedName() {
    return StringUtil.isEmptyOrSpaces(data.targets)
        ? "[Please enter a Buck target]"
        : getNamePrefix() + data.targets;
  }

  protected abstract String getNamePrefix();

  protected abstract T createData();

  public abstract boolean canRun(ProgramRunner<?> programRunner, String executorId);

  public static class Data {
    public String targets = "";
    public String additionalParams = "";
    public String buckExecutablePath = "";
  }
}
