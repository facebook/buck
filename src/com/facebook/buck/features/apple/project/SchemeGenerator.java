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

package com.facebook.buck.features.apple.project;

import com.facebook.buck.apple.xcode.XCScheme;
import com.facebook.buck.apple.xcode.XCScheme.AdditionalActions;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.features.apple.common.SchemeActionType;
import com.facebook.buck.io.MoreProjectFilesystems;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Collects target references and generates an xcscheme.
 *
 * <p>To register entries in the scheme, clients must add:
 *
 * <ul>
 *   <li>associations between buck targets and Xcode targets
 *   <li>associations between Xcode targets and the projects that contain them
 * </ul>
 *
 * <p>Both of these values can be pulled out of {@link ProjectGenerator}.
 */
class SchemeGenerator {
  private static final Logger LOG = Logger.get(SchemeGenerator.class);

  private final ProjectFilesystem projectFilesystem;
  private final Optional<PBXTarget> primaryTarget;
  private final ImmutableSet<PBXTarget> orderedBuildTargets;
  private final ImmutableSet<PBXTarget> orderedBuildTestTargets;
  private final ImmutableSet<PBXTarget> orderedRunTestTargets;
  private final String schemeName;
  private final Path outputDirectory;
  private final boolean parallelizeBuild;
  private final boolean wasCreatedForAppExtension;
  private final Optional<String> runnablePath;
  private final Optional<String> remoteRunnablePath;
  private final ImmutableMap<SchemeActionType, String> actionConfigNames;
  private final ImmutableMap<PBXTarget, Path> targetToProjectPathMap;

  private Optional<XCScheme> outputScheme = Optional.empty();
  private final Optional<XCScheme.LaunchAction.WatchInterface> watchInterface;
  private final Optional<String> notificationPayloadFile;
  private final XCScheme.LaunchAction.LaunchStyle launchStyle;
  private final Optional<ImmutableMap<SchemeActionType, ImmutableMap<String, String>>>
      environmentVariables;
  private final Optional<ImmutableMap<SchemeActionType, PBXTarget>> expandVariablesBasedOn;
  private final Optional<ImmutableMap<SchemeActionType, ImmutableMap<String, String>>>
      commandLineArguments;
  private Optional<
          ImmutableMap<
              SchemeActionType, ImmutableMap<XCScheme.AdditionalActions, ImmutableList<String>>>>
      additionalSchemeActions;
  private final Optional<String> applicationLanguage;
  private final Optional<String> applicationRegion;

  public SchemeGenerator(
      ProjectFilesystem projectFilesystem,
      Optional<PBXTarget> primaryTarget,
      ImmutableSet<PBXTarget> orderedBuildTargets,
      ImmutableSet<PBXTarget> orderedBuildTestTargets,
      ImmutableSet<PBXTarget> orderedRunTestTargets,
      String schemeName,
      Path outputDirectory,
      boolean parallelizeBuild,
      Optional<Boolean> wasCreatedForAppExtension,
      Optional<String> runnablePath,
      Optional<String> remoteRunnablePath,
      ImmutableMap<SchemeActionType, String> actionConfigNames,
      ImmutableMap<PBXTarget, Path> targetToProjectPathMap,
      Optional<ImmutableMap<SchemeActionType, ImmutableMap<String, String>>> environmentVariables,
      Optional<ImmutableMap<SchemeActionType, PBXTarget>> expandVariablesBasedOn,
      Optional<ImmutableMap<SchemeActionType, ImmutableMap<String, String>>> commandLineArguments,
      Optional<
              ImmutableMap<
                  SchemeActionType,
                  ImmutableMap<XCScheme.AdditionalActions, ImmutableList<String>>>>
          additionalSchemeActions,
      XCScheme.LaunchAction.LaunchStyle launchStyle,
      Optional<XCScheme.LaunchAction.WatchInterface> watchInterface,
      Optional<String> notificationPayloadFile,
      Optional<String> applicationLanguage,
      Optional<String> applicationRegion) {
    this.projectFilesystem = projectFilesystem;
    this.primaryTarget = primaryTarget;
    this.watchInterface = watchInterface;
    this.launchStyle = launchStyle;
    this.orderedBuildTargets = orderedBuildTargets;
    this.orderedBuildTestTargets = orderedBuildTestTargets;
    this.orderedRunTestTargets = orderedRunTestTargets;
    this.schemeName = schemeName;
    this.outputDirectory = outputDirectory;
    this.parallelizeBuild = parallelizeBuild;
    this.wasCreatedForAppExtension = wasCreatedForAppExtension.orElse(false);
    this.runnablePath = runnablePath;
    this.remoteRunnablePath = remoteRunnablePath;
    this.actionConfigNames = actionConfigNames;
    this.targetToProjectPathMap = targetToProjectPathMap;
    this.environmentVariables = environmentVariables;
    this.expandVariablesBasedOn = expandVariablesBasedOn;
    this.commandLineArguments = commandLineArguments;
    this.additionalSchemeActions = additionalSchemeActions;
    this.notificationPayloadFile = notificationPayloadFile;
    this.applicationLanguage = applicationLanguage;
    this.applicationRegion = applicationRegion;

    LOG.verbose(
        "Generating scheme with build targets %s, test build targets %s, test bundle targets %s",
        orderedBuildTargets, orderedBuildTestTargets, orderedRunTestTargets);
  }

  @VisibleForTesting
  private Optional<ImmutableList<XCScheme.SchemePrePostAction>> additionalCommandsForSchemeAction(
      SchemeActionType schemeActionType,
      XCScheme.AdditionalActions actionType,
      Optional<XCScheme.BuildableReference> primaryTarget) {

    Optional<ImmutableList<String>> commands =
        this.additionalSchemeActions
            .map(input -> Optional.ofNullable(input.get(schemeActionType)))
            .filter(Optional::isPresent)
            .map(input -> Optional.ofNullable(input.get().get(actionType)))
            .orElse(Optional.empty());
    if (commands.isPresent()) {
      ImmutableList<XCScheme.SchemePrePostAction> actions =
          commands.get().stream()
              .map(command -> new XCScheme.SchemePrePostAction(primaryTarget, command))
              .collect(ImmutableList.toImmutableList());
      return Optional.of(actions);
    } else {
      return Optional.empty();
    }
  }

  @VisibleForTesting
  Path getOutputDirectory() {
    return outputDirectory;
  }

  @VisibleForTesting
  Optional<XCScheme> getOutputScheme() {
    return outputScheme;
  }

  public Path writeScheme() throws IOException {
    Map<PBXTarget, XCScheme.BuildableReference> buildTargetToBuildableReferenceMap =
        new HashMap<>();

    for (PBXTarget target : Iterables.concat(orderedBuildTargets, orderedBuildTestTargets)) {
      String blueprintName = target.getProductName();
      if (blueprintName == null) {
        blueprintName = target.getName();
      }
      Path outputPath = outputDirectory.getParent();
      String buildableReferencePath;
      Path projectPath = Objects.requireNonNull(targetToProjectPathMap.get(target));
      if (outputPath == null) {
        // Root directory project
        buildableReferencePath = projectPath.toString();
      } else {
        buildableReferencePath = outputPath.relativize(projectPath).toString();
      }

      XCScheme.BuildableReference buildableReference =
          new XCScheme.BuildableReference(
              buildableReferencePath,
              Objects.requireNonNull(target.getGlobalID()),
              target.getProductReference() != null
                  ? target.getProductReference().getName()
                  : Objects.requireNonNull(target.getProductName()),
              blueprintName);
      buildTargetToBuildableReferenceMap.put(target, buildableReference);
    }

    Optional<XCScheme.BuildableReference> primaryBuildReference = Optional.empty();
    if (primaryTarget.isPresent()) {
      primaryBuildReference =
          Optional.ofNullable(buildTargetToBuildableReferenceMap.get(primaryTarget.get()));
    }

    XCScheme.BuildAction buildAction =
        new XCScheme.BuildAction(
            parallelizeBuild,
            additionalCommandsForSchemeAction(
                SchemeActionType.BUILD,
                XCScheme.AdditionalActions.PRE_SCHEME_ACTIONS,
                primaryBuildReference),
            additionalCommandsForSchemeAction(
                SchemeActionType.BUILD,
                XCScheme.AdditionalActions.POST_SCHEME_ACTIONS,
                primaryBuildReference));

    // For aesthetic reasons put all non-test build actions before all test build actions.
    for (PBXTarget target : orderedBuildTargets) {
      addBuildActionForBuildTarget(
          buildTargetToBuildableReferenceMap.get(target),
          XCScheme.BuildActionEntry.BuildFor.DEFAULT,
          buildAction);
    }

    for (PBXTarget target : orderedBuildTestTargets) {
      addBuildActionForBuildTarget(
          buildTargetToBuildableReferenceMap.get(target),
          XCScheme.BuildActionEntry.BuildFor.TEST_ONLY,
          buildAction);
    }

    ImmutableMap<SchemeActionType, ImmutableMap<String, String>> envVariables = ImmutableMap.of();
    Map<SchemeActionType, XCScheme.BuildableReference> envVariablesBasedOn = ImmutableMap.of();
    if (environmentVariables.isPresent()) {
      envVariables = environmentVariables.get();
      if (expandVariablesBasedOn.isPresent()) {
        envVariablesBasedOn = expandVariablesBasedOn.get().entrySet().stream()
          .collect(Collectors.toMap(Map.Entry::getKey, e -> buildTargetToBuildableReferenceMap.get(e.getValue())));
      }
    }

    ImmutableMap<SchemeActionType, ImmutableMap<String, String>> commandLineArgs = ImmutableMap.of();
    if (commandLineArguments.isPresent()) {
      commandLineArgs = commandLineArguments.get();
    }

    XCScheme.TestAction testAction =
        new XCScheme.TestAction(
            Objects.requireNonNull(actionConfigNames.get(SchemeActionType.TEST)),
            Optional.ofNullable(envVariables.get(SchemeActionType.TEST)),
            Optional.ofNullable(envVariablesBasedOn.get(SchemeActionType.TEST)),
            Optional.ofNullable(commandLineArgs.get(SchemeActionType.TEST)),
            additionalCommandsForSchemeAction(
                SchemeActionType.TEST, AdditionalActions.PRE_SCHEME_ACTIONS, primaryBuildReference),
            additionalCommandsForSchemeAction(
                SchemeActionType.TEST,
                AdditionalActions.POST_SCHEME_ACTIONS,
                primaryBuildReference),
            applicationLanguage,
            applicationRegion);

    for (PBXTarget target : orderedRunTestTargets) {
      XCScheme.BuildableReference buildableReference =
          buildTargetToBuildableReferenceMap.get(target);
      XCScheme.TestableReference testableReference =
          new XCScheme.TestableReference(buildableReference);
      testAction.addTestableReference(testableReference);
    }

    Optional<XCScheme.LaunchAction> launchAction = Optional.empty();
    Optional<XCScheme.ProfileAction> profileAction = Optional.empty();

    if (primaryTarget.isPresent()) {
      XCScheme.BuildableReference primaryBuildableReference =
          buildTargetToBuildableReferenceMap.get(primaryTarget.get());
      if (primaryBuildableReference != null) {
        launchAction =
            Optional.of(
                new XCScheme.LaunchAction(
                    primaryBuildableReference,
                    Objects.requireNonNull(actionConfigNames.get(SchemeActionType.LAUNCH)),
                    runnablePath,
                    remoteRunnablePath,
                    watchInterface,
                    launchStyle,
                    Optional.ofNullable(envVariables.get(SchemeActionType.LAUNCH)),
                    Optional.ofNullable(envVariablesBasedOn.get(SchemeActionType.LAUNCH)),
                    Optional.ofNullable(commandLineArgs.get(SchemeActionType.LAUNCH)),
                    additionalCommandsForSchemeAction(
                        SchemeActionType.LAUNCH,
                        AdditionalActions.PRE_SCHEME_ACTIONS,
                        primaryBuildReference),
                    additionalCommandsForSchemeAction(
                        SchemeActionType.LAUNCH,
                        AdditionalActions.POST_SCHEME_ACTIONS,
                        primaryBuildReference),
                    notificationPayloadFile,
                    applicationLanguage,
                    applicationRegion));

        profileAction =
            Optional.of(
                new XCScheme.ProfileAction(
                    primaryBuildableReference,
                    Objects.requireNonNull(actionConfigNames.get(SchemeActionType.PROFILE)),
                    Optional.ofNullable(envVariables.get(SchemeActionType.PROFILE)),
                    Optional.ofNullable(envVariablesBasedOn.get(SchemeActionType.PROFILE)),
                    Optional.ofNullable(commandLineArgs.get(SchemeActionType.PROFILE)),
                    additionalCommandsForSchemeAction(
                        SchemeActionType.PROFILE,
                        AdditionalActions.PRE_SCHEME_ACTIONS,
                        primaryBuildReference),
                    additionalCommandsForSchemeAction(
                        SchemeActionType.PROFILE,
                        AdditionalActions.POST_SCHEME_ACTIONS,
                        primaryBuildReference)));
      }
    }
    XCScheme.AnalyzeAction analyzeAction =
        new XCScheme.AnalyzeAction(
            Objects.requireNonNull(actionConfigNames.get(SchemeActionType.ANALYZE)),
            additionalCommandsForSchemeAction(
                SchemeActionType.ANALYZE,
                AdditionalActions.PRE_SCHEME_ACTIONS,
                primaryBuildReference),
            additionalCommandsForSchemeAction(
                SchemeActionType.ANALYZE,
                AdditionalActions.POST_SCHEME_ACTIONS,
                primaryBuildReference));

    XCScheme.ArchiveAction archiveAction =
        new XCScheme.ArchiveAction(
            Objects.requireNonNull(actionConfigNames.get(SchemeActionType.ARCHIVE)),
            additionalCommandsForSchemeAction(
                SchemeActionType.ARCHIVE,
                AdditionalActions.PRE_SCHEME_ACTIONS,
                primaryBuildReference),
            additionalCommandsForSchemeAction(
                SchemeActionType.ARCHIVE,
                AdditionalActions.POST_SCHEME_ACTIONS,
                primaryBuildReference));

    XCScheme scheme =
        new XCScheme(
            schemeName,
            wasCreatedForAppExtension,
            Optional.of(buildAction),
            Optional.of(testAction),
            launchAction,
            profileAction,
            Optional.of(analyzeAction),
            Optional.of(archiveAction));
    outputScheme = Optional.of(scheme);

    Path schemeDirectory = outputDirectory.resolve("xcshareddata/xcschemes");
    projectFilesystem.mkdirs(schemeDirectory);
    Path schemePath = schemeDirectory.resolve(schemeName + ".xcscheme");
    try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
      serializeScheme(scheme, outputStream);
      String contentsToWrite = outputStream.toString();
      if (MoreProjectFilesystems.fileContentsDiffer(
          new ByteArrayInputStream(contentsToWrite.getBytes(Charsets.UTF_8)),
          schemePath,
          projectFilesystem)) {
        projectFilesystem.writeContentsToPath(outputStream.toString(), schemePath);
      }
    }
    return schemePath;
  }

  private static void addBuildActionForBuildTarget(
      XCScheme.BuildableReference buildableReference,
      EnumSet<XCScheme.BuildActionEntry.BuildFor> buildFor,
      XCScheme.BuildAction buildAction) {
    XCScheme.BuildActionEntry entry = new XCScheme.BuildActionEntry(buildableReference, buildFor);
    buildAction.addBuildAction(entry);
  }

  public static Element serializeBuildableReference(
      Document doc, XCScheme.BuildableReference buildableReference) {
    Element refElem = doc.createElement("BuildableReference");
    refElem.setAttribute("BuildableIdentifier", "primary");
    refElem.setAttribute("BlueprintIdentifier", buildableReference.getBlueprintIdentifier());
    refElem.setAttribute("BuildableName", buildableReference.getBuildableName());
    refElem.setAttribute("BlueprintName", buildableReference.getBlueprintName());
    String referencedContainer = "container:" + buildableReference.getContainerRelativePath();
    refElem.setAttribute("ReferencedContainer", referencedContainer);
    return refElem;
  }

  public static Element serializeEnvironmentVariables(
      Document doc, ImmutableMap<String, String> environmentVariables) {
    Element rootElement = doc.createElement("EnvironmentVariables");
    for (String variableKey : environmentVariables.keySet()) {
      Element variableElement = doc.createElement("EnvironmentVariable");
      variableElement.setAttribute("key", variableKey);
      variableElement.setAttribute("value", environmentVariables.get(variableKey));
      variableElement.setAttribute("isEnabled", "YES");
      rootElement.appendChild(variableElement);
    }
    return rootElement;
  }

  public static Element serializeCommandLineArguments(
      Document doc, ImmutableMap<String, String> commandLineArguments) {
    Element rootElement = doc.createElement("CommandLineArguments");
    for (String argumentKey : commandLineArguments.keySet()) {
      Element argumentElement = doc.createElement("CommandLineArgument");
      argumentElement.setAttribute("argument", argumentKey);
      argumentElement.setAttribute("isEnabled", commandLineArguments.get(argumentKey));
      rootElement.appendChild(argumentElement);
    }
    return rootElement;
  }
  
  public static Element serializeExpandVariablesBasedOn(
    Document doc, XCScheme.BuildableReference reference) {
    Element referenceElement = serializeBuildableReference(doc, reference);
    Element rootElement = doc.createElement("MacroExpansion");
    rootElement.appendChild(referenceElement);
    return rootElement;
  }

  public static Element serializeBuildAction(Document doc, XCScheme.BuildAction buildAction) {
    Element buildActionElem = doc.createElement("BuildAction");
    serializePrePostActions(doc, buildAction, buildActionElem);

    buildActionElem.setAttribute(
        "parallelizeBuildables", buildAction.getParallelizeBuild() ? "YES" : "NO");
    buildActionElem.setAttribute(
        "buildImplicitDependencies", buildAction.getParallelizeBuild() ? "YES" : "NO");

    Element buildActionEntriesElem = doc.createElement("BuildActionEntries");
    buildActionElem.appendChild(buildActionEntriesElem);

    for (XCScheme.BuildActionEntry entry : buildAction.getBuildActionEntries()) {
      Element entryElem = doc.createElement("BuildActionEntry");
      buildActionEntriesElem.appendChild(entryElem);

      EnumSet<XCScheme.BuildActionEntry.BuildFor> buildFor = entry.getBuildFor();
      boolean buildForRunning = buildFor.contains(XCScheme.BuildActionEntry.BuildFor.RUNNING);
      entryElem.setAttribute("buildForRunning", buildForRunning ? "YES" : "NO");
      boolean buildForTesting = buildFor.contains(XCScheme.BuildActionEntry.BuildFor.TESTING);
      entryElem.setAttribute("buildForTesting", buildForTesting ? "YES" : "NO");
      boolean buildForProfiling = buildFor.contains(XCScheme.BuildActionEntry.BuildFor.PROFILING);
      entryElem.setAttribute("buildForProfiling", buildForProfiling ? "YES" : "NO");
      boolean buildForArchiving = buildFor.contains(XCScheme.BuildActionEntry.BuildFor.ARCHIVING);
      entryElem.setAttribute("buildForArchiving", buildForArchiving ? "YES" : "NO");
      boolean buildForAnalyzing = buildFor.contains(XCScheme.BuildActionEntry.BuildFor.ANALYZING);
      entryElem.setAttribute("buildForAnalyzing", buildForAnalyzing ? "YES" : "NO");

      Element refElem = serializeBuildableReference(doc, entry.getBuildableReference());
      entryElem.appendChild(refElem);
    }

    return buildActionElem;
  }

  public static Element serializeTestAction(Document doc, XCScheme.TestAction testAction) {
    Element testActionElem = doc.createElement("TestAction");
    serializePrePostActions(doc, testAction, testActionElem);

    // unless otherwise specified, use the Launch scheme's env variables like the xcode default
    testActionElem.setAttribute("shouldUseLaunchSchemeArgsEnv", "YES");

    Element testablesElem = doc.createElement("Testables");
    testActionElem.appendChild(testablesElem);

    for (XCScheme.TestableReference testable : testAction.getTestables()) {
      Element testableElem = doc.createElement("TestableReference");
      testablesElem.appendChild(testableElem);
      testableElem.setAttribute("skipped", "NO");

      Element refElem = serializeBuildableReference(doc, testable.getBuildableReference());
      testableElem.appendChild(refElem);
    }

    if (testAction.getEnvironmentVariables().isPresent()) {
      if (testAction.getExpandVariablesBasedOn().isPresent()) {
        Element expandBasedOnElement =
          serializeExpandVariablesBasedOn(doc, testAction.getExpandVariablesBasedOn().get());
        testActionElem.appendChild(expandBasedOnElement);
      }

      // disable the default override that makes Test use Launch's environment variables
      testActionElem.setAttribute("shouldUseLaunchSchemeArgsEnv", "NO");
      Element environmentVariablesElement =
          serializeEnvironmentVariables(doc, testAction.getEnvironmentVariables().get());
      testActionElem.appendChild(environmentVariablesElement);
    }

    if (testAction.getCommandLineArguments().isPresent()) {
      Element commandLineArgumentElement =
          serializeCommandLineArguments(doc, testAction.getCommandLineArguments().get());
          testActionElem.appendChild(commandLineArgumentElement);
    }

    if (testAction.getApplicationLanguage().isPresent()) {
      testActionElem.setAttribute("language", testAction.getApplicationLanguage().get());
    }

    if (testAction.getApplicationRegion().isPresent()) {
      testActionElem.setAttribute("region", testAction.getApplicationRegion().get());
    }

    return testActionElem;
  }

  public static Element serializeLaunchAction(Document doc, XCScheme.LaunchAction launchAction) {
    Element launchActionElem = doc.createElement("LaunchAction");
    serializePrePostActions(doc, launchAction, launchActionElem);

    Optional<String> runnablePath = launchAction.getRunnablePath();
    Optional<String> remoteRunnablePath = launchAction.getRemoteRunnablePath();
    if (remoteRunnablePath.isPresent()) {
      Element remoteRunnableElem = doc.createElement("RemoteRunnable");
      remoteRunnableElem.setAttribute("runnableDebuggingMode", "2");
      remoteRunnableElem.setAttribute("BundleIdentifier", "com.apple.springboard");
      remoteRunnableElem.setAttribute("RemotePath", remoteRunnablePath.get());
      launchActionElem.appendChild(remoteRunnableElem);
      Element refElem = serializeBuildableReference(doc, launchAction.getBuildableReference());
      remoteRunnableElem.appendChild(refElem);

      // Yes, this appears to be duplicated in Xcode as well..
      Element refElem2 = serializeBuildableReference(doc, launchAction.getBuildableReference());
      launchActionElem.appendChild(refElem2);
    } else if (runnablePath.isPresent()) {
      Element pathRunnableElem = doc.createElement("PathRunnable");
      launchActionElem.appendChild(pathRunnableElem);
      pathRunnableElem.setAttribute("FilePath", runnablePath.get());
    } else {
      Element productRunnableElem = doc.createElement("BuildableProductRunnable");
      launchActionElem.appendChild(productRunnableElem);
      Element refElem = serializeBuildableReference(doc, launchAction.getBuildableReference());
      productRunnableElem.appendChild(refElem);
    }

    Optional<XCScheme.LaunchAction.WatchInterface> watchInterface =
        launchAction.getWatchInterface();
    if (watchInterface.isPresent()) {
      Optional<String> watchInterfaceValue = Optional.empty();
      switch (watchInterface.get()) {
        case MAIN:
          // excluded from scheme
        case COMPLICATION:
          watchInterfaceValue = Optional.of("32");
          break;
        case DYNAMIC_NOTIFICATION:
          watchInterfaceValue = Optional.of("8");
          break;
        case STATIC_NOTIFICATION:
          watchInterfaceValue = Optional.of("16");
          break;
      }

      if (watchInterfaceValue.isPresent()) {
        launchActionElem.setAttribute("launchAutomaticallySubstyle", watchInterfaceValue.get());
      }
    }

    Optional<String> notificationPayloadFile = launchAction.getNotificationPayloadFile();
    if (notificationPayloadFile.isPresent()) {
      launchActionElem.setAttribute("notificationPayloadFile", notificationPayloadFile.get());
    }

    XCScheme.LaunchAction.LaunchStyle launchStyle = launchAction.getLaunchStyle();
    launchActionElem.setAttribute(
        "launchStyle", launchStyle == XCScheme.LaunchAction.LaunchStyle.AUTO ? "0" : "1");

    if (launchAction.getEnvironmentVariables().isPresent()) {
      if (launchAction.getExpandVariablesBasedOn().isPresent()) {
        Element expandBasedOnElement =
          serializeExpandVariablesBasedOn(doc, launchAction.getExpandVariablesBasedOn().get());
        launchActionElem.appendChild(expandBasedOnElement);
      }

      Element environmentVariablesElement =
          serializeEnvironmentVariables(doc, launchAction.getEnvironmentVariables().get());
      launchActionElem.appendChild(environmentVariablesElement);
    }

    if (launchAction.getCommandLineArguments().isPresent()) {
      Element commandLineArgumentElement =
          serializeCommandLineArguments(doc, launchAction.getCommandLineArguments().get());
      launchActionElem.appendChild(commandLineArgumentElement);
    }

    if (launchAction.getApplicationLanguage().isPresent()) {
      launchActionElem.setAttribute("language", launchAction.getApplicationLanguage().get());
    }

    if (launchAction.getApplicationRegion().isPresent()) {
      launchActionElem.setAttribute("region", launchAction.getApplicationRegion().get());
    }

    return launchActionElem;
  }

  public static Element serializeProfileAction(Document doc, XCScheme.ProfileAction profileAction) {
    Element profileActionElem = doc.createElement("ProfileAction");
    serializePrePostActions(doc, profileAction, profileActionElem);

    // unless otherwise specified, use the Launch scheme's env variables like the xcode default
    profileActionElem.setAttribute("shouldUseLaunchSchemeArgsEnv", "YES");

    Element productRunnableElem = doc.createElement("BuildableProductRunnable");
    profileActionElem.appendChild(productRunnableElem);

    Element refElem = serializeBuildableReference(doc, profileAction.getBuildableReference());
    productRunnableElem.appendChild(refElem);

    if (profileAction.getEnvironmentVariables().isPresent()) {
      if (profileAction.getExpandVariablesBasedOn().isPresent()) {
        Element expandBasedOnElement =
          serializeExpandVariablesBasedOn(doc, profileAction.getExpandVariablesBasedOn().get());
        profileActionElem.appendChild(expandBasedOnElement);
      }

      // disable the default override that makes Profile use Launch's environment variables
      profileActionElem.setAttribute("shouldUseLaunchSchemeArgsEnv", "NO");
      Element environmentVariablesElement =
          serializeEnvironmentVariables(doc, profileAction.getEnvironmentVariables().get());
      profileActionElem.appendChild(environmentVariablesElement);
    }

    if (profileAction.getCommandLineArguments().isPresent()) {
      Element commandLineArgumentElement =
          serializeCommandLineArguments(doc, profileAction.getCommandLineArguments().get());
      profileActionElem.appendChild(commandLineArgumentElement);
    }

    return profileActionElem;
  }

  private static void serializePrePostActions(
      Document doc, XCScheme.SchemeAction action, Element actionElem) {
    if (action.getPreActions().isPresent()) {
      actionElem.appendChild(
          serializePrePostAction(doc, "PreActions", action.getPreActions().get()));
    }
    if (action.getPostActions().isPresent()) {
      actionElem.appendChild(
          serializePrePostAction(doc, "PostActions", action.getPostActions().get()));
    }
  }

  private static Element serializePrePostAction(
      Document doc, String tagName, ImmutableList<XCScheme.SchemePrePostAction> actions) {
    Element executionActions = doc.createElement(tagName);
    for (XCScheme.SchemePrePostAction action : actions) {
      executionActions.appendChild(serializeExecutionAction(doc, action));
    }
    return executionActions;
  }

  public static Element serializeExecutionAction(
      Document doc, XCScheme.SchemePrePostAction action) {
    Element executionAction = doc.createElement("ExecutionAction");
    executionAction.setAttribute(
        "ActionType",
        "Xcode.IDEStandardExecutionActionsCore.ExecutionActionType.ShellScriptAction");

    Element actionContent = doc.createElement("ActionContent");
    actionContent.setAttribute("title", "Run Script");
    actionContent.setAttribute("scriptText", action.getCommand());
    actionContent.setAttribute("shellToInvoke", "/bin/bash");

    Optional<XCScheme.BuildableReference> buildableReference = action.getBuildableReference();
    if (buildableReference.isPresent()) {
      Element environmentBuildable = doc.createElement("EnvironmentBuildable");
      environmentBuildable.appendChild(serializeBuildableReference(doc, buildableReference.get()));
      actionContent.appendChild(environmentBuildable);
    }

    executionAction.appendChild(actionContent);
    return executionAction;
  }

  private static void serializeScheme(XCScheme scheme, OutputStream stream) {
    DocumentBuilder docBuilder;
    Transformer transformer;
    try {
      docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
      transformer = TransformerFactory.newInstance().newTransformer();
    } catch (ParserConfigurationException | TransformerConfigurationException e) {
      throw new RuntimeException(e);
    }

    DOMImplementation domImplementation = docBuilder.getDOMImplementation();
    Document doc = domImplementation.createDocument(null, "Scheme", null);
    doc.setXmlVersion("1.0");

    Element rootElem = doc.getDocumentElement();
    rootElem.setAttribute("LastUpgradeVersion", "9999");
    if (scheme.getWasCreatedForExtension()) {
      rootElem.setAttribute("wasCreatedForAppExtension", "YES");
    }
    rootElem.setAttribute("version", "1.7");

    Optional<XCScheme.BuildAction> buildAction = scheme.getBuildAction();
    if (buildAction.isPresent()) {
      Element buildActionElem = serializeBuildAction(doc, buildAction.get());
      rootElem.appendChild(buildActionElem);
    }

    Optional<XCScheme.TestAction> testAction = scheme.getTestAction();
    if (testAction.isPresent()) {
      Element testActionElem = serializeTestAction(doc, testAction.get());
      testActionElem.setAttribute(
          "buildConfiguration", scheme.getTestAction().get().getBuildConfiguration());
      rootElem.appendChild(testActionElem);
    }

    Optional<XCScheme.LaunchAction> launchAction = scheme.getLaunchAction();
    if (launchAction.isPresent()) {
      Element launchActionElem = serializeLaunchAction(doc, launchAction.get());
      launchActionElem.setAttribute(
          "buildConfiguration", launchAction.get().getBuildConfiguration());
      rootElem.appendChild(launchActionElem);
    } else {
      Element launchActionElem = doc.createElement("LaunchAction");
      launchActionElem.setAttribute("buildConfiguration", "Debug");
      rootElem.appendChild(launchActionElem);
    }

    Optional<XCScheme.ProfileAction> profileAction = scheme.getProfileAction();
    if (profileAction.isPresent()) {
      Element profileActionElem = serializeProfileAction(doc, profileAction.get());
      profileActionElem.setAttribute(
          "buildConfiguration", profileAction.get().getBuildConfiguration());
      rootElem.appendChild(profileActionElem);
    } else {
      Element profileActionElem = doc.createElement("ProfileAction");
      profileActionElem.setAttribute("buildConfiguration", "Release");
      rootElem.appendChild(profileActionElem);
    }

    Optional<XCScheme.AnalyzeAction> analyzeAction = scheme.getAnalyzeAction();
    if (analyzeAction.isPresent()) {
      Element analyzeActionElem = doc.createElement("AnalyzeAction");
      serializePrePostActions(doc, analyzeAction.get(), analyzeActionElem);
      analyzeActionElem.setAttribute(
          "buildConfiguration", analyzeAction.get().getBuildConfiguration());
      rootElem.appendChild(analyzeActionElem);
    } else {
      Element analyzeActionElem = doc.createElement("AnalyzeAction");
      analyzeActionElem.setAttribute("buildConfiguration", "Debug");
      rootElem.appendChild(analyzeActionElem);
    }

    Optional<XCScheme.ArchiveAction> archiveAction = scheme.getArchiveAction();
    if (archiveAction.isPresent()) {
      Element archiveActionElem = doc.createElement("ArchiveAction");
      serializePrePostActions(doc, archiveAction.get(), archiveActionElem);
      archiveActionElem.setAttribute(
          "buildConfiguration", archiveAction.get().getBuildConfiguration());
      archiveActionElem.setAttribute("revealArchiveInOrganizer", "YES");
      rootElem.appendChild(archiveActionElem);
    } else {
      Element archiveActionElem = doc.createElement("ArchiveAction");
      archiveActionElem.setAttribute("buildConfiguration", "Release");
      rootElem.appendChild(archiveActionElem);
    }

    // write out

    DOMSource source = new DOMSource(doc);
    StreamResult result = new StreamResult(stream);

    try {
      transformer.transform(source, result);
    } catch (TransformerException e) {
      throw new RuntimeException(e);
    }
  }
}
