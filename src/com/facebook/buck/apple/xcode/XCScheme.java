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

package com.facebook.buck.apple.xcode;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class XCScheme {
  private String name;
  private boolean wasCreatedForExtension;
  private Optional<BuildAction> buildAction;
  private Optional<TestAction> testAction;
  private Optional<LaunchAction> launchAction;
  private Optional<ProfileAction> profileAction;
  private Optional<AnalyzeAction> analyzeAction;
  private Optional<ArchiveAction> archiveAction;

  public XCScheme(
      String name,
      boolean wasCreatedForExtension,
      Optional<BuildAction> buildAction,
      Optional<TestAction> testAction,
      Optional<LaunchAction> launchAction,
      Optional<ProfileAction> profileAction,
      Optional<AnalyzeAction> analyzeAction,
      Optional<ArchiveAction> archiveAction) {
    this.name = name;
    this.wasCreatedForExtension = wasCreatedForExtension;
    this.buildAction = buildAction;
    this.testAction = testAction;
    this.launchAction = launchAction;
    this.profileAction = profileAction;
    this.analyzeAction = analyzeAction;
    this.archiveAction = archiveAction;
  }

  public String getName() {
    return name;
  }

  public boolean getWasCreatedForExtension() {
    return wasCreatedForExtension;
  }

  public Optional<BuildAction> getBuildAction() {
    return buildAction;
  }

  public Optional<TestAction> getTestAction() {
    return testAction;
  }

  public Optional<LaunchAction> getLaunchAction() {
    return launchAction;
  }

  public Optional<ProfileAction> getProfileAction() {
    return profileAction;
  }

  public Optional<AnalyzeAction> getAnalyzeAction() {
    return analyzeAction;
  }

  public Optional<ArchiveAction> getArchiveAction() {
    return archiveAction;
  }

  public enum AdditionalActions {
    PRE_SCHEME_ACTIONS,
    POST_SCHEME_ACTIONS,
    ;
  }

  public static class BuildableReference {
    private String containerRelativePath;
    private String blueprintIdentifier;
    public final String buildableName;
    public final String blueprintName;

    public BuildableReference(
        String containerRelativePath,
        String blueprintIdentifier,
        String buildableName,
        String blueprintName) {
      this.containerRelativePath = containerRelativePath;
      this.blueprintIdentifier = blueprintIdentifier;
      this.buildableName = buildableName;
      this.blueprintName = blueprintName;
    }

    public String getContainerRelativePath() {
      return containerRelativePath;
    }

    public String getBlueprintIdentifier() {
      return blueprintIdentifier;
    }

    public String getBuildableName() {
      return buildableName;
    }

    public String getBlueprintName() {
      return blueprintName;
    }
  }

  public static class SchemePrePostAction {
    private final Optional<BuildableReference> buildableReference;
    private String command;

    public SchemePrePostAction(Optional<BuildableReference> buildableReference, String command) {
      this.buildableReference = buildableReference;
      this.command = command;
    }

    public Optional<BuildableReference> getBuildableReference() {
      return buildableReference;
    }

    public String getCommand() {
      return command;
    }
  }

  public abstract static class SchemeAction {
    private final Optional<ImmutableList<SchemePrePostAction>> preActions;
    private final Optional<ImmutableList<SchemePrePostAction>> postActions;

    public SchemeAction(
        Optional<ImmutableList<SchemePrePostAction>> preActions,
        Optional<ImmutableList<SchemePrePostAction>> postActions) {
      this.preActions = preActions;
      this.postActions = postActions;
    }

    public Optional<ImmutableList<SchemePrePostAction>> getPreActions() {
      return this.preActions;
    }

    public Optional<ImmutableList<SchemePrePostAction>> getPostActions() {
      return this.postActions;
    }
  }

  public static class BuildAction extends SchemeAction {
    private List<BuildActionEntry> buildActionEntries;

    private final boolean parallelizeBuild;

    public BuildAction(
        boolean parallelizeBuild,
        Optional<ImmutableList<SchemePrePostAction>> preActions,
        Optional<ImmutableList<SchemePrePostAction>> postActions) {
      super(preActions, postActions);
      buildActionEntries = new ArrayList<>();
      this.parallelizeBuild = parallelizeBuild;
    }

    public void addBuildAction(BuildActionEntry entry) {
      this.buildActionEntries.add(entry);
    }

    public List<BuildActionEntry> getBuildActionEntries() {
      return buildActionEntries;
    }

    public boolean getParallelizeBuild() {
      return parallelizeBuild;
    }
  }

  public static class BuildActionEntry {
    public enum BuildFor {
      RUNNING,
      TESTING,
      PROFILING,
      ARCHIVING,
      ANALYZING;

      public static final EnumSet<BuildFor> DEFAULT = EnumSet.allOf(BuildFor.class);
      public static final EnumSet<BuildFor> INDEXING = EnumSet.of(TESTING, ANALYZING, ARCHIVING);
      public static final EnumSet<BuildFor> TEST_ONLY = EnumSet.of(TESTING, ANALYZING);
    }

    private BuildableReference buildableReference;

    private final EnumSet<BuildFor> buildFor;

    public BuildActionEntry(BuildableReference buildableReference, EnumSet<BuildFor> buildFor) {
      this.buildableReference = buildableReference;
      this.buildFor = buildFor;
    }

    public BuildableReference getBuildableReference() {
      return buildableReference;
    }

    public EnumSet<BuildFor> getBuildFor() {
      return buildFor;
    }
  }

  public static class LaunchAction extends SchemeAction {

    public enum LaunchStyle {
      /** Starts the process with attached debugger. */
      AUTO,
      /** Debugger waits for executable to be launched. */
      WAIT,
      ;
    }

    /** Watch Interface property in Watch app scheme used to choose which interface is launched. */
    public enum WatchInterface {
      /** Launches the Watch app */
      MAIN,
      /** Launches the Watch app's complication */
      COMPLICATION,
      /** Launches the Watch app's dynamic notification with notification payload */
      DYNAMIC_NOTIFICATION,
      /** Launches the Watch app's static notification with notification payload */
      STATIC_NOTIFICATION,
    }

    BuildableReference buildableReference;
    private final String buildConfiguration;
    private final Optional<String> runnablePath;
    private final Optional<String> remoteRunnablePath;
    private final Optional<WatchInterface> watchInterface;
    private final LaunchStyle launchStyle;
    private final Optional<ImmutableMap<String, String>> environmentVariables;
    private final Optional<String> notificationPayloadFile;

    public LaunchAction(
        BuildableReference buildableReference,
        String buildConfiguration,
        Optional<String> runnablePath,
        Optional<String> remoteRunnablePath,
        Optional<WatchInterface> watchInterface,
        LaunchStyle launchStyle,
        Optional<ImmutableMap<String, String>> environmentVariables,
        Optional<ImmutableList<SchemePrePostAction>> preActions,
        Optional<ImmutableList<SchemePrePostAction>> postActions,
        Optional<String> notificationPayloadFile) {
      super(preActions, postActions);
      this.buildableReference = buildableReference;
      this.buildConfiguration = buildConfiguration;
      this.runnablePath = runnablePath;
      this.remoteRunnablePath = remoteRunnablePath;
      this.watchInterface = watchInterface;
      this.launchStyle = launchStyle;
      this.environmentVariables = environmentVariables;
      this.notificationPayloadFile = notificationPayloadFile;
    }

    public BuildableReference getBuildableReference() {
      return buildableReference;
    }

    public String getBuildConfiguration() {
      return buildConfiguration;
    }

    public Optional<String> getRunnablePath() {
      return runnablePath;
    }

    public Optional<String> getRemoteRunnablePath() {
      return remoteRunnablePath;
    }

    public Optional<WatchInterface> getWatchInterface() {
      return watchInterface;
    }

    public Optional<String> getNotificationPayloadFile() {
      return notificationPayloadFile;
    }

    public LaunchStyle getLaunchStyle() {
      return launchStyle;
    }

    public Optional<ImmutableMap<String, String>> getEnvironmentVariables() {
      return environmentVariables;
    }
  }

  public static class ProfileAction extends SchemeAction {
    BuildableReference buildableReference;
    private final String buildConfiguration;
    private final Optional<ImmutableMap<String, String>> environmentVariables;

    public ProfileAction(
        BuildableReference buildableReference,
        String buildConfiguration,
        Optional<ImmutableMap<String, String>> environmentVariables,
        Optional<ImmutableList<SchemePrePostAction>> preActions,
        Optional<ImmutableList<SchemePrePostAction>> postActions) {
      super(preActions, postActions);
      this.buildableReference = buildableReference;
      this.buildConfiguration = buildConfiguration;
      this.environmentVariables = environmentVariables;
    }

    public BuildableReference getBuildableReference() {
      return buildableReference;
    }

    public String getBuildConfiguration() {
      return buildConfiguration;
    }

    public Optional<ImmutableMap<String, String>> getEnvironmentVariables() {
      return environmentVariables;
    }
  }

  public static class TestAction extends SchemeAction {
    List<TestableReference> testables;
    private final String buildConfiguration;
    private final Optional<ImmutableMap<String, String>> environmentVariables;

    public TestAction(
        String buildConfiguration,
        Optional<ImmutableMap<String, String>> environmentVariables,
        Optional<ImmutableList<SchemePrePostAction>> preActions,
        Optional<ImmutableList<SchemePrePostAction>> postActions) {
      super(preActions, postActions);
      this.testables = new ArrayList<>();
      this.buildConfiguration = buildConfiguration;
      this.environmentVariables = environmentVariables;
    }

    public void addTestableReference(TestableReference testable) {
      this.testables.add(testable);
    }

    public List<TestableReference> getTestables() {
      return testables;
    }

    public String getBuildConfiguration() {
      return buildConfiguration;
    }

    public Optional<ImmutableMap<String, String>> getEnvironmentVariables() {
      return environmentVariables;
    }
  }

  public static class TestableReference {
    private BuildableReference buildableReference;

    public TestableReference(BuildableReference buildableReference) {
      this.buildableReference = buildableReference;
    }

    public BuildableReference getBuildableReference() {
      return buildableReference;
    }
  }

  public static class AnalyzeAction extends SchemeAction {
    public final String buildConfiguration;

    public AnalyzeAction(
        String buildConfiguration,
        Optional<ImmutableList<SchemePrePostAction>> preActions,
        Optional<ImmutableList<SchemePrePostAction>> postActions) {
      super(preActions, postActions);
      this.buildConfiguration = buildConfiguration;
    }

    public String getBuildConfiguration() {
      return buildConfiguration;
    }
  }

  public static class ArchiveAction extends SchemeAction {
    public final String buildConfiguration;

    public ArchiveAction(
        String buildConfiguration,
        Optional<ImmutableList<SchemePrePostAction>> preActions,
        Optional<ImmutableList<SchemePrePostAction>> postActions) {
      super(preActions, postActions);
      this.buildConfiguration = buildConfiguration;
    }

    public String getBuildConfiguration() {
      return buildConfiguration;
    }
  }
}
