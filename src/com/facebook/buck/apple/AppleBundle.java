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

package com.facebook.buck.apple;

import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.apple.toolchain.CodeSignIdentity;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.HasAppleDebugSymbolDeps;
import com.facebook.buck.cxx.NativeTestable;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.BuildStepResultHolder;
import com.facebook.buck.step.ConditionalStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.MoveStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;

/**
 * Creates a bundle: a directory containing files and subdirectories, described by an Info.plist.
 */
public class AppleBundle extends AbstractBuildRule
    implements NativeTestable,
        BuildRuleWithBinary,
        HasRuntimeDeps,
        BinaryBuildRule,
        SupportsInputBasedRuleKey {

  public static final String CODE_SIGN_ENTITLEMENTS = "CODE_SIGN_ENTITLEMENTS";
  private static final String CODE_SIGN_DRY_RUN_ARGS_FILE = "BUCK_code_sign_args.plist";
  protected static final Logger LOG = Logger.get(AppleBundle.class);

  @AddToRuleKey private final String extension;

  @AddToRuleKey private final Optional<String> productName;

  @AddToRuleKey private final Optional<SourcePath> maybeEntitlementsFile;

  @AddToRuleKey private final boolean incrementalBundlingEnabled;

  @AddToRuleKey private final boolean hasBinary;

  @AddToRuleKey private final Boolean isLegacyWatchApp;

  @AddToRuleKey private final Optional<BuildTarget> appleDsymBuildTarget;

  @AddToRuleKey private final Optional<SourcePath> appleDsymSourcePath;

  @AddToRuleKey private final AppleBundleDestinations destinations;

  @AddToRuleKey private final AppleBundleResources resources;

  @AddToRuleKey private final ImmutableList<AppleBundlePart> bundleParts;

  @AddToRuleKey private final ImmutableSortedSet<BuildTarget> tests;

  @AddToRuleKey private final ApplePlatform platform;

  @AddToRuleKey private final Supplier<ImmutableList<CodeSignIdentity>> codeSignIdentitiesSupplier;

  @AddToRuleKey private final Optional<Tool> codesignAllocatePath;

  @AddToRuleKey private final Tool codesign;

  @AddToRuleKey private final Optional<Tool> swiftStdlibTool;

  @AddToRuleKey private final Tool lipo;

  @AddToRuleKey private final ImmutableList<String> codesignFlags;

  @AddToRuleKey private final Optional<String> codesignIdentitySubjectName;

  @AddToRuleKey private final boolean copySwiftStdlibToFrameworks;

  @AddToRuleKey private final boolean sliceAppPackageSwiftRuntime;
  @AddToRuleKey private final boolean sliceAppBundleSwiftRuntime;

  private final Path sdkPath;

  private final BuildRule binary;
  private final String binaryName;
  private final Path bundleRoot;
  private final Path binaryPath;
  private final Path bundleBinaryPath;

  private final boolean cacheable;
  private final boolean verifyResources;

  private final Duration codesignTimeout;
  private final BuildRuleParams buildRuleParams;
  private BuildableSupport.DepsSupplier depsSupplier;
  private final Optional<AppleDsym> appleDsym;

  @AddToRuleKey private final boolean withDownwardApi;

  @AddToRuleKey private final AppleCodeSignType codeSignType;

  @AddToRuleKey private final boolean dryRunCodeSigning;

  @AddToRuleKey private final Optional<SourcePath> maybeCodeSignIdentityFingerprintFile;

  private final Path infoPlistBundlePath;

  @AddToRuleKey private final SourcePath processedResourcesDir;

  @AddToRuleKey private final Optional<SourcePath> nonProcessedResourcesContentHashesFileSourcePath;

  @AddToRuleKey private final Optional<SourcePath> processedResourcesContentHashesFileSourcePath;

  @AddToRuleKey private final boolean inputBasedRulekeyEnabled;

  @AddToRuleKey private final boolean parallelCodeSignOnCopyEnabled;

  AppleBundle(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      String extension,
      Optional<String> productName,
      RelPath infoPlistPathRelativeToBundle,
      BuildRule binary,
      Optional<AppleDsym> appleDsym,
      AppleBundleDestinations destinations,
      AppleBundleResources resources,
      ImmutableList<AppleBundlePart> bundleParts,
      AppleCxxPlatform appleCxxPlatform,
      Set<BuildTarget> tests,
      Supplier<ImmutableList<CodeSignIdentity>> codeSignIdentitiesSupplier,
      AppleCodeSignType codeSignType,
      boolean cacheable,
      boolean verifyResources,
      ImmutableList<String> codesignFlags,
      Optional<String> codesignIdentity,
      Duration codesignTimeout,
      boolean copySwiftStdlibToFrameworks,
      boolean sliceAppPackageSwiftRuntime,
      boolean sliceAppBundleSwiftRuntime,
      boolean withDownwardApi,
      Optional<SourcePath> maybeEntitlementsFile,
      boolean dryRunCodeSigning,
      Optional<SourcePath> maybeCodeSignIdentityFingerprintFile,
      SourcePath processedResourcesDir,
      Optional<SourcePath> nonProcessedResourcesContentHashesFileSourcePath,
      Optional<SourcePath> processedResourcesContentHashesFileSourcePath,
      boolean incrementalBundlingEnabled,
      boolean inputBasedRulekeyEnabled,
      boolean parallelCodeSignOnCopyEnabled) {
    super(buildTarget, projectFilesystem);
    this.buildRuleParams = params;
    this.extension = extension;
    this.productName = productName;
    this.hasBinary = binary.getSourcePathToOutput() != null;
    this.binary = binary;
    this.withDownwardApi = withDownwardApi;
    this.maybeEntitlementsFile = maybeEntitlementsFile;
    this.isLegacyWatchApp = AppleBundleSupport.isLegacyWatchApp(extension, binary);
    this.appleDsym = appleDsym;
    this.appleDsymBuildTarget = appleDsym.map(AppleDsym::getBuildTarget);
    this.appleDsymSourcePath = appleDsym.map(AppleDsym::getSourcePathToOutput);
    this.destinations = destinations;
    this.resources = resources;
    this.bundleParts = bundleParts;
    this.binaryName = getBinaryName(getBuildTarget(), this.productName);
    this.bundleRoot =
        getBundleRoot(getProjectFilesystem(), getBuildTarget(), this.binaryName, this.extension);
    this.binaryPath = this.destinations.getExecutablesPath().resolve(this.binaryName);
    this.tests = ImmutableSortedSet.copyOf(tests);
    AppleSdk sdk = appleCxxPlatform.getAppleSdk();
    this.platform = sdk.getApplePlatform();
    this.sdkPath = appleCxxPlatform.getAppleSdkPaths().getSdkPath();
    this.cacheable = cacheable;
    this.verifyResources = verifyResources;
    this.codesignFlags = codesignFlags;
    this.codesignIdentitySubjectName = codesignIdentity;
    this.dryRunCodeSigning = dryRunCodeSigning;
    this.maybeCodeSignIdentityFingerprintFile = maybeCodeSignIdentityFingerprintFile;
    this.processedResourcesDir = processedResourcesDir;

    bundleBinaryPath = bundleRoot.resolve(binaryPath);

    this.codeSignIdentitiesSupplier = codeSignIdentitiesSupplier;
    this.codeSignType = codeSignType;

    this.codesignAllocatePath = appleCxxPlatform.getCodesignAllocate();
    this.codesign =
        appleCxxPlatform
            .getCodesignProvider()
            .resolve(graphBuilder, buildTarget.getTargetConfiguration());
    this.swiftStdlibTool =
        appleCxxPlatform.getSwiftPlatform().isPresent()
            ? appleCxxPlatform.getSwiftPlatform().get().getSwiftStdlibTool()
            : Optional.empty();
    this.lipo = appleCxxPlatform.getLipo();

    this.codesignTimeout = codesignTimeout;
    this.copySwiftStdlibToFrameworks = copySwiftStdlibToFrameworks;
    this.depsSupplier = BuildableSupport.buildDepsSupplier(this, graphBuilder);

    this.sliceAppPackageSwiftRuntime = sliceAppPackageSwiftRuntime;
    this.sliceAppBundleSwiftRuntime = sliceAppBundleSwiftRuntime;
    this.infoPlistBundlePath = bundleRoot.resolve(infoPlistPathRelativeToBundle.getPath());
    this.nonProcessedResourcesContentHashesFileSourcePath =
        nonProcessedResourcesContentHashesFileSourcePath;
    this.processedResourcesContentHashesFileSourcePath =
        processedResourcesContentHashesFileSourcePath;
    this.incrementalBundlingEnabled = incrementalBundlingEnabled;
    this.inputBasedRulekeyEnabled = inputBasedRulekeyEnabled;
    this.parallelCodeSignOnCopyEnabled = parallelCodeSignOnCopyEnabled;
  }

  public static String getBinaryName(BuildTarget buildTarget, Optional<String> productName) {
    return productName.orElse(buildTarget.getShortName());
  }

  public static Path getBundleRoot(
      ProjectFilesystem filesystem, BuildTarget buildTarget, String binaryName, String extension) {
    return BuildTargetPaths.getGenPath(filesystem, buildTarget, "%s")
        .resolve(binaryName + "." + extension);
  }

  public String getExtension() {
    return extension;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), bundleRoot);
  }

  /** Returns {@code SourcePath} of the file with incremental info if the build is incremental. */
  public Optional<SourcePath> getSourcePathToIncrementalInfo() {
    if (incrementalBundlingEnabled) {
      return Optional.of(
          ExplicitBuildTargetSourcePath.of(getBuildTarget(), getIncrementalInfoFilePath()));
    } else {
      return Optional.empty();
    }
  }

  public Path getInfoPlistPath() {
    return infoPlistBundlePath;
  }

  public Path getUnzippedOutputFilePathToBinary() {
    return this.binaryPath;
  }

  public String getPlatformName() {
    return platform.getName();
  }

  public Optional<AppleDsym> getAppleDsym() {
    return appleDsym;
  }

  public boolean getIsLegacyWatchApp() {
    return isLegacyWatchApp;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();

    ImmutableList<RelPath> codeSignOnCopyPaths =
        collectCodeSignOnCopyPaths(context.getSourcePathResolver());

    Supplier<Optional<AppleBundleIncrementalInfo>> incrementalInfoSupplier =
        readIncrementalInfo(context, stepsBuilder);

    Supplier<Boolean> shouldPerformIncrementalBuildSupplier;
    Optional<Supplier<Map<RelPath, String>>> maybeCurrentBuildHashesSupplier;
    Supplier<List<AppleBundleComponentCopySpec>> embeddedBundlesCopySpecsSupplier;

    if (incrementalBundlingEnabled) {
      ImmutableSortedMap.Builder<RelPath, String> hashesBuilder =
          ImmutableSortedMap.orderedBy(RelPath.comparator());
      // Specs per every file in every embedded bundle to be used when incremental build is
      // performed
      ImmutableList.Builder<AppleBundleComponentCopySpec> embeddedBundlesContentCopySpecsBuilder =
          ImmutableList.builder();
      addStepsToComputeCurrentBuildHashesAndCopySpecsForEmbeddedBundles(
          context.getSourcePathResolver(),
          stepsBuilder,
          hashesBuilder,
          embeddedBundlesContentCopySpecsBuilder);
      maybeCurrentBuildHashesSupplier = Optional.of(hashesBuilder::build);
      embeddedBundlesCopySpecsSupplier = embeddedBundlesContentCopySpecsBuilder::build;
      shouldPerformIncrementalBuildSupplier =
          computeIfBuildShouldBeIncremental(
              stepsBuilder, incrementalInfoSupplier, codeSignOnCopyPaths, hashesBuilder::build);
    } else {
      shouldPerformIncrementalBuildSupplier = () -> false;
      maybeCurrentBuildHashesSupplier = Optional.empty();
      embeddedBundlesCopySpecsSupplier = ImmutableList::of;
    }

    if (incrementalBundlingEnabled) {
      createBundleRootDirectory(context, stepsBuilder, shouldPerformIncrementalBuildSupplier);
    } else {
      stepsBuilder.addAll(
          MakeCleanDirectoryStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), getProjectFilesystem(), bundleRoot)));
    }

    if (incrementalBundlingEnabled) {
      appendWriteIncrementalInfoSteps(
          stepsBuilder,
          context,
          buildableContext,
          maybeCurrentBuildHashesSupplier.orElseThrow(
              () ->
                  new IllegalStateException(
                      "Content hashes for current build should be computed when incremental build is performed")),
          codeSignOnCopyPaths);
    } else {
      appendContentHashesFileDeletionSteps(stepsBuilder, context);
    }

    if (hasBinary) {
      appendCopyDsymStep(stepsBuilder, buildableContext, context);
    }

    if (verifyResources) {
      AppleResourceProcessing.verifyResourceConflicts(
          resources, bundleParts, context.getSourcePathResolver(), destinations);
    }

    appendContentHashesFileDeletionSteps(stepsBuilder, context);

    AppleResourceProcessing.addStepsToCopyResources(
        context,
        stepsBuilder,
        resources,
        bundleParts,
        bundleRoot,
        destinations,
        getProjectFilesystem(),
        processedResourcesDir,
        shouldPerformIncrementalBuildSupplier,
        () -> incrementalInfoSupplier.get().map(AppleBundleIncrementalInfo::getHashes),
        maybeCurrentBuildHashesSupplier,
        embeddedBundlesCopySpecsSupplier);

    deleteBundlePartsFromPreviousBuildMissingInCurrentBuild(
        shouldPerformIncrementalBuildSupplier,
        incrementalInfoSupplier,
        maybeCurrentBuildHashesSupplier,
        stepsBuilder,
        bundleRoot,
        getProjectFilesystem());

    if (incrementalBundlingEnabled) {
      appendWriteIncrementalInfoSteps(
          stepsBuilder,
          context,
          buildableContext,
          maybeCurrentBuildHashesSupplier.orElseThrow(
              () ->
                  new IllegalStateException(
                      "Content hashes for current build should be computed when incremental build is performed")),
          codeSignOnCopyPaths);
    }

    if (codeSignType != AppleCodeSignType.SKIP) {
      Supplier<CodeSignIdentity> codeSignIdentitySupplier =
          appendStepsToSelectCodeSignIdentity(context, stepsBuilder);

      addSwiftStdlibStepIfNeeded(
          context.getSourcePathResolver(),
          bundleRoot.resolve(destinations.getFrameworksPath()),
          dryRunCodeSigning ? Optional.empty() : Optional.of(codeSignIdentitySupplier),
          stepsBuilder,
          false);

      ImmutableList<Path> resolvedCodeSignOnCopyPaths =
          codeSignOnCopyPaths.stream()
              .map(p -> bundleRoot.resolve(p.getPath()))
              .collect(ImmutableList.toImmutableList());

      Optional<Path> entitlementsPlist =
          maybeEntitlementsFile.map(
              sourcePath -> context.getSourcePathResolver().getAbsolutePath(sourcePath).getPath());

      if (dryRunCodeSigning) {
        final boolean shouldUseEntitlements = entitlementsPlist.isPresent();
        appendDryCodeSignSteps(
            stepsBuilder,
            resolvedCodeSignOnCopyPaths,
            codeSignIdentitySupplier,
            shouldUseEntitlements);
      } else {
        appendCodeSignSteps(
            context,
            stepsBuilder,
            resolvedCodeSignOnCopyPaths,
            codeSignIdentitySupplier,
            entitlementsPlist);
      }
    } else {
      addSwiftStdlibStepIfNeeded(
          context.getSourcePathResolver(),
          bundleRoot.resolve(destinations.getFrameworksPath()),
          Optional.empty(),
          stepsBuilder,
          false);
    }

    // Ensure the bundle directory is archived so we can fetch it later.
    buildableContext.recordArtifact(
        context.getSourcePathResolver().getCellUnsafeRelPath(getSourcePathToOutput()).getPath());

    return stepsBuilder.build();
  }

  private Supplier<Optional<AppleBundleIncrementalInfo>> readIncrementalInfo(
      BuildContext context, ImmutableList.Builder<Step> stepsBuilder) {
    BuildStepResultHolder<AppleBundleIncrementalInfo> incrementalInfoHolder =
        new BuildStepResultHolder<>();
    if (incrementalBundlingEnabled) {
      stepsBuilder.add(
          new AppleBundleIncrementalInfoReadStep(
              getProjectFilesystem(),
              AbsPath.of(context.getBuildCellRootPath().resolve(getIncrementalInfoFilePath())),
              incrementalInfoHolder));
    }
    return incrementalInfoHolder::getValue;
  }

  private ImmutableList<RelPath> collectCodeSignOnCopyPaths(
      SourcePathResolverAdapter sourcePathResolver) {
    ImmutableList.Builder<RelPath> codeSignOnCopyPathsBuilder = ImmutableList.builder();
    codeSignOnCopyPathsBuilder.addAll(
        bundleParts.stream()
            .filter(p -> p instanceof FileAppleBundlePart)
            .map(p -> (FileAppleBundlePart) p)
            .filter(FileAppleBundlePart::getCodesignOnCopy)
            .map(
                p ->
                    (new AppleBundleComponentCopySpec(p, sourcePathResolver, destinations))
                        .getDestinationPathRelativeToBundleRoot())
            .collect(Collectors.toList()));
    codeSignOnCopyPathsBuilder.addAll(
        bundleParts.stream()
            .filter(p -> p instanceof DirectoryAppleBundlePart)
            .map(p -> (DirectoryAppleBundlePart) p)
            .filter(DirectoryAppleBundlePart::getCodesignOnCopy)
            .map(
                p ->
                    (new AppleBundleComponentCopySpec(p, sourcePathResolver, destinations))
                        .getDestinationPathRelativeToBundleRoot())
            .collect(Collectors.toList()));
    codeSignOnCopyPathsBuilder.addAll(
        bundleParts.stream()
            .filter(p -> p instanceof EmbeddedBundleAppleBundlePart)
            .map(p -> (EmbeddedBundleAppleBundlePart) p)
            .filter(EmbeddedBundleAppleBundlePart::getCodesignOnCopy)
            .map(
                p ->
                    AppleBundleComponentCopySpec.destinationPathRelativeToBundleRoot(
                        sourcePathResolver,
                        destinations,
                        p.getSourcePath(),
                        p.getDestination(),
                        Optional.empty()))
            .collect(Collectors.toList()));
    codeSignOnCopyPathsBuilder.addAll(
        Stream.concat(
                resources.getResourceDirs().stream(),
                resources.getResourceFiles().stream()
                    .filter(
                        pathWithDestination ->
                            !AppleProcessResources.shouldBeProcessed(
                                pathWithDestination, sourcePathResolver)))
            .filter(SourcePathWithAppleBundleDestination::getCodesignOnCopy)
            .map(
                p ->
                    (new AppleBundleComponentCopySpec(p, sourcePathResolver, destinations))
                        .getDestinationPathRelativeToBundleRoot())
            .collect(Collectors.toList()));
    return codeSignOnCopyPathsBuilder.build();
  }

  Supplier<Boolean> computeIfBuildShouldBeIncremental(
      ImmutableList.Builder<Step> stepsBuilder,
      Supplier<Optional<AppleBundleIncrementalInfo>> incrementalInfoHolderSupplier,
      ImmutableList<RelPath> codeSignOnCopyPaths,
      Supplier<Map<RelPath, String>> currentBuildHashes) {
    BuildStepResultHolder<Boolean> result = new BuildStepResultHolder<>();
    stepsBuilder.add(
        new AbstractExecutionStep("apple-bundle-compute-if-should-be-incremental") {

          private Set<RelPath> codeSignedOnCopyPathsFromPreviousBuildWhichArePresentInCurrentBuild(
              List<RelPath> codeSignedOnCopyPathsFromPreviousBuild) {
            return Sets.intersection(
                new HashSet<>(codeSignedOnCopyPathsFromPreviousBuild),
                currentBuildHashes.get().keySet());
          }

          @Override
          public StepExecutionResult execute(StepExecutionContext context) {
            Optional<AppleBundleIncrementalInfo> incrementalInfoHolder =
                incrementalInfoHolderSupplier.get();
            if (!incrementalInfoHolder.isPresent()) {
              result.setValue(false);
              return StepExecutionResults.SUCCESS;
            }
            boolean isPreviousBuildCodeSigned = incrementalInfoHolder.get().codeSigned();
            // If previous build was not code signed there should be no problems with code signing
            // for current build in incremental mode. Existing binaries could be code signed "on
            // top" if that's needed.
            if (!isPreviousBuildCodeSigned) {
              result.setValue(true);
              return StepExecutionResults.SUCCESS;
            }
            // For simplicity and correctness purposes instead of stripping code signatures we
            // perform non-incremental build
            if (codeSignType == AppleCodeSignType.SKIP) {
              result.setValue(false);
              return StepExecutionResults.SUCCESS;
            }
            // If there is an artifact that was code signed on copy in previous build which is
            // present in current build and not code signed on copy, we should perform
            // non-incremental build for simplicity and correctness reasons.
            Set<RelPath> currentBuildCodeSignedOnCopyPaths = new HashSet<>(codeSignOnCopyPaths);
            Set<RelPath> codeSignedOnCopyPathsFromPreviousBuildWhichArePresentInCurrentBuild =
                codeSignedOnCopyPathsFromPreviousBuildWhichArePresentInCurrentBuild(
                    incrementalInfoHolder.get().getCodeSignedOnCopyPaths());
            boolean previousBuildCodeSignOnCopyPathsIsSubsetOfCurrentBuildOnes =
                currentBuildCodeSignedOnCopyPaths.containsAll(
                    codeSignedOnCopyPathsFromPreviousBuildWhichArePresentInCurrentBuild);
            result.setValue(previousBuildCodeSignOnCopyPathsIsSubsetOfCurrentBuildOnes);
            return StepExecutionResults.SUCCESS;
          }
        });
    return () ->
        result
            .getValue()
            .orElseThrow(
                () -> new IllegalStateException("Should be calculated and set in separate step"));
  }

  private void deleteBundlePartsFromPreviousBuildMissingInCurrentBuild(
      Supplier<Boolean> shouldPerformIncrementalBuildSupplier,
      Supplier<Optional<AppleBundleIncrementalInfo>> incrementalInfoSupplier,
      Optional<Supplier<Map<RelPath, String>>> maybeCurrentBuildHashesSupplier,
      ImmutableList.Builder<Step> stepsBuilder,
      Path bundleRoot,
      ProjectFilesystem projectFilesystem) {
    stepsBuilder.add(
        new ConditionalStep(
            shouldPerformIncrementalBuildSupplier,
            new AbstractExecutionStep("apple-bundle-delete-redundant-bundle-parts") {
              @Override
              public StepExecutionResult execute(StepExecutionContext context) throws IOException {
                Map<RelPath, String> oldHashes =
                    incrementalInfoSupplier
                        .get()
                        .orElseThrow(
                            () ->
                                new IllegalStateException(
                                    "Incremental info should be present when incremental build is performed"))
                        .getHashes();
                Map<RelPath, String> newHashes =
                    maybeCurrentBuildHashesSupplier
                        .orElseThrow(
                            () ->
                                new IllegalStateException(
                                    "Content hashes for current build should be computed when incremental build is performed"))
                        .get();
                Sets.difference(oldHashes.keySet(), newHashes.keySet())
                    .parallelStream()
                    .forEach(
                        path -> {
                          try {
                            projectFilesystem.deleteRecursivelyIfExists(
                                bundleRoot.resolve(path.getPath()));
                          } catch (IOException exception) {
                            throw new RuntimeException(exception.getMessage());
                          }
                        });

                deleteRedundantEmptyDirectories(
                    oldHashes.keySet(), newHashes.keySet(), projectFilesystem);

                return StepExecutionResults.SUCCESS;
              }
            }));
  }

  private void deleteRedundantEmptyDirectories(
      Set<RelPath> oldPaths, Set<RelPath> newPaths, ProjectFilesystem projectFilesystem)
      throws IOException {
    Set<RelPath> oldDirectories =
        oldPaths.stream().flatMap(AppleBundle::ancestorDirectories).collect(Collectors.toSet());
    Set<RelPath> newDirectories =
        newPaths.stream().flatMap(AppleBundle::ancestorDirectories).collect(Collectors.toSet());
    for (RelPath dir : Sets.difference(oldDirectories, newDirectories)) {
      projectFilesystem.deleteRecursivelyIfExists(bundleRoot.resolve(dir.getPath()));
    }
  }

  private static Stream<RelPath> ancestorDirectories(@Nonnull RelPath path) {
    List<RelPath> result = new LinkedList<>();
    RelPath current = path.getParent();
    while (current != null) {
      result.add(current);
      current = current.getParent();
    }
    return result.stream();
  }

  private void createBundleRootDirectory(
      BuildContext context,
      ImmutableList.Builder<Step> stepsBuilder,
      Supplier<Boolean> shouldPerformIncrementalBuildSupplier) {
    BuildCellRelativePath bundleRootPath =
        BuildCellRelativePath.fromCellRelativePath(
            context.getBuildCellRootPath(), getProjectFilesystem(), bundleRoot);
    stepsBuilder.add(
        new AbstractExecutionStep("apple-bundle-create-root-dir") {
          @Override
          public StepExecutionResult execute(StepExecutionContext context)
              throws IOException, InterruptedException {
            if (shouldPerformIncrementalBuildSupplier.get()) {
              return MkdirStep.of(bundleRootPath).execute(context);
            } else {
              for (Step step : MakeCleanDirectoryStep.of(bundleRootPath)) {
                StepExecutionResult result = step.execute(context);
                if (!result.isSuccess()) {
                  return result;
                }
              }
              return StepExecutionResults.SUCCESS;
            }
          }
        });
  }

  private void addStepsToComputeCurrentBuildHashesAndCopySpecsForEmbeddedBundles(
      SourcePathResolverAdapter sourcePathResolver,
      ImmutableList.Builder<Step> stepsBuilder,
      ImmutableSortedMap.Builder<RelPath, String> newContentHashesBuilder,
      ImmutableList.Builder<AppleBundleComponentCopySpec> embeddedBundlesRelatedCopySpecsBuilder) {

    List<DirectoryContentAppleBundlePart> directoriesWithContentBundleParts =
        bundleParts.stream()
            .filter(p -> p instanceof DirectoryContentAppleBundlePart)
            .map(p -> (DirectoryContentAppleBundlePart) p)
            .collect(Collectors.toList());

    directoriesWithContentBundleParts.forEach(
        bundlePart -> {
          AbsPath hashesFilePath =
              sourcePathResolver.getAbsolutePath(
                  bundlePart
                      .getHashesMapSourcePath()
                      .orElseThrow(
                          () ->
                              new IllegalStateException(
                                  "Parameter should be present when incremental bundling is enabled")));
          ImmutableMap.Builder<RelPath, String> hashesBuilder = ImmutableMap.builder();
          stepsBuilder.add(
              new AppleReadHashPerFileStep(
                  "read-container-directories-bundle-part-hashes", hashesFilePath, hashesBuilder));
          stepsBuilder.add(
              new AbstractExecutionStep("memoize-container-directories-bundle-part-hashes") {
                @Override
                public StepExecutionResult execute(StepExecutionContext context) {
                  RelPath bundleDestinationPath =
                      RelPath.of(bundlePart.getDestination().getPath(destinations));
                  hashesBuilder
                      .build()
                      .forEach(
                          (path, hash) ->
                              newContentHashesBuilder.put(
                                  bundleDestinationPath.resolve(path), hash));
                  return StepExecutionResults.SUCCESS;
                }
              });
        });

    List<FileAppleBundlePart> fileBundleParts =
        bundleParts.stream()
            .filter(p -> p instanceof FileAppleBundlePart)
            .map(p -> (FileAppleBundlePart) p)
            .collect(Collectors.toList());

    fileBundleParts.forEach(
        bundlePart -> {
          AppleBundleComponentCopySpec copySpec =
              new AppleBundleComponentCopySpec(bundlePart, sourcePathResolver, destinations);
          appendStepToReadSavedHashFromDisk(
              stepsBuilder,
              sourcePathResolver,
              bundlePart
                  .getContentHashSourcePath()
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "Parameter should be present when incremental bundling is enabled")),
              copySpec.getDestinationPathRelativeToBundleRoot(),
              newContentHashesBuilder,
              getProjectFilesystem());
        });

    List<DirectoryAppleBundlePart> directoryBundleParts =
        bundleParts.stream()
            .filter(p -> p instanceof DirectoryAppleBundlePart)
            .map(p -> (DirectoryAppleBundlePart) p)
            .collect(Collectors.toList());

    directoryBundleParts.forEach(
        bundlePart -> {
          AppleBundleComponentCopySpec copySpec =
              new AppleBundleComponentCopySpec(bundlePart, sourcePathResolver, destinations);
          appendStepToReadSavedHashFromDisk(
              stepsBuilder,
              sourcePathResolver,
              bundlePart
                  .getContentHashSourcePath()
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "Parameter should be present when incremental bundling is enabled")),
              copySpec.getDestinationPathRelativeToBundleRoot(),
              newContentHashesBuilder,
              getProjectFilesystem());
        });

    List<EmbeddedBundleAppleBundlePart> embeddedBundles =
        bundleParts.stream()
            .filter(p -> p instanceof EmbeddedBundleAppleBundlePart)
            .map(p -> (EmbeddedBundleAppleBundlePart) p)
            .collect(Collectors.toList());

    for (EmbeddedBundleAppleBundlePart bundlePart : embeddedBundles) {
      BuildStepResultHolder<AppleBundleIncrementalInfo> incrementalInfoHolder =
          new BuildStepResultHolder<>();

      AbsPath incrementalInfoPath =
          sourcePathResolver.getAbsolutePath(bundlePart.getIncrementalInfoSourcePath());

      AbsPath embeddedBundleRootPath =
          sourcePathResolver.getAbsolutePath(bundlePart.getSourcePath());

      stepsBuilder.add(
          new AppleBundleIncrementalInfoReadStep(
              getProjectFilesystem(), incrementalInfoPath, incrementalInfoHolder));
      stepsBuilder.add(
          new AbstractExecutionStep("apple-bundle-process-components-incremental-info") {
            @Override
            public StepExecutionResult execute(StepExecutionContext context) {

              AppleBundleIncrementalInfo incrementalInfo =
                  incrementalInfoHolder
                      .getValue()
                      .orElseThrow(
                          () ->
                              new IllegalStateException(
                                  "Expected incremental info to be read in previous steps"));

              Path frameworkDirName =
                  sourcePathResolver.getAbsolutePath(bundlePart.getSourcePath()).getFileName();

              incrementalInfo
                  .getHashes()
                  .forEach(
                      (relPath, hash) -> {
                        RelPath destinationDirectoryPath =
                            RelPath.of(bundlePart.getDestination().getPath(destinations));
                        RelPath destinationPath =
                            destinationDirectoryPath
                                .resolveRel(frameworkDirName.toString())
                                .resolve(relPath);
                        newContentHashesBuilder.put(destinationPath, hash);
                        embeddedBundlesRelatedCopySpecsBuilder.add(
                            new AppleBundleComponentCopySpec(
                                embeddedBundleRootPath.resolve(relPath), destinationPath, false));
                      });

              return StepExecutionResults.SUCCESS;
            }
          });
    }

    AbsPath processedResourcesContentHashesFilePath =
        sourcePathResolver.getAbsolutePath(
            processedResourcesContentHashesFileSourcePath.orElseThrow(
                () ->
                    new IllegalStateException(
                        "Parameter should be present when incremental bundling is enabled")));

    stepsBuilder.add(
        new AppleReadHashPerFileStep(
            "read-new-processed-resources-hashes",
            processedResourcesContentHashesFilePath,
            newContentHashesBuilder));

    AbsPath nonProcessedResourcesContentHashesFilePath =
        sourcePathResolver.getAbsolutePath(
            nonProcessedResourcesContentHashesFileSourcePath.orElseThrow(
                () ->
                    new IllegalStateException(
                        "Parameter should be present when incremental bundling is enabled")));

    stepsBuilder.add(
        new AppleReadHashPerFileStep(
            "read-new-non-processed-resources-hashes",
            nonProcessedResourcesContentHashesFilePath,
            newContentHashesBuilder));
  }

  private static void appendStepToReadSavedHashFromDisk(
      ImmutableList.Builder<Step> stepsBuilder,
      SourcePathResolverAdapter sourcePathResolver,
      SourcePath savedHashFileSourcePath,
      RelPath toPath,
      ImmutableSortedMap.Builder<RelPath, String> newContentHashesBuilder,
      ProjectFilesystem projectFilesystem) {
    stepsBuilder.add(
        new AbstractExecutionStep("read-saved-hash-from-disk") {
          @Override
          public StepExecutionResult execute(StepExecutionContext context) {
            AbsPath contentHashFilePath =
                sourcePathResolver.getAbsolutePath(savedHashFileSourcePath);
            Optional<String> hash = projectFilesystem.readFileIfItExists(contentHashFilePath);
            newContentHashesBuilder.put(
                toPath,
                hash.orElseThrow(
                    () ->
                        new IllegalStateException(
                            String.format(
                                "Expected file with content hash to exist %s",
                                contentHashFilePath))));

            return StepExecutionResults.SUCCESS;
          }
        });
  }

  private void appendWriteIncrementalInfoSteps(
      ImmutableList.Builder<Step> stepsBuilder,
      BuildContext context,
      BuildableContext buildableContext,
      Supplier<Map<RelPath, String>> contentHashesSupplier,
      ImmutableList<RelPath> codeSignOnCopyPaths) {
    appendContentHashesFileDeletionSteps(stepsBuilder, context);
    stepsBuilder.add(
        new AppleWriteIncrementalInfoStep(
            contentHashesSupplier,
            codeSignOnCopyPaths,
            codeSignType != AppleCodeSignType.SKIP,
            getIncrementalInfoFilePath(),
            getProjectFilesystem()));
    buildableContext.recordArtifact(getIncrementalInfoFilePath());
  }

  private void appendContentHashesFileDeletionSteps(
      ImmutableList.Builder<Step> stepsBuilder, BuildContext context) {
    BuildCellRelativePath contentHashesFilePathRelativeToCell =
        BuildCellRelativePath.fromCellRelativePath(
            context.getBuildCellRootPath(), getProjectFilesystem(), getIncrementalInfoFilePath());
    stepsBuilder.add(RmStep.of(contentHashesFilePathRelativeToCell));
  }

  private Path getIncrementalInfoFilePath() {
    return bundleRoot.getParent().resolve("incremental_info.json");
  }

  private Supplier<CodeSignIdentity> appendStepsToSelectCodeSignIdentity(
      BuildContext context, ImmutableList.Builder<Step> stepsBuilder) {
    if (codeSignType == AppleCodeSignType.ADHOC) {
      CodeSignIdentity identity =
          codesignIdentitySubjectName
              .map(CodeSignIdentity::ofAdhocSignedWithSubjectCommonName)
              .orElse(CodeSignIdentity.AD_HOC);
      return () -> identity;
    } else {
      Path fingerprintPath =
          context
              .getSourcePathResolver()
              .getAbsolutePath(
                  maybeCodeSignIdentityFingerprintFile.orElseThrow(
                      () ->
                          new IllegalStateException(
                              "Code sign identity should be provided when code sign is needed")))
              .getPath();
      BuildStepResultHolder<CodeSignIdentity> selectedCodeSignIdentity =
          new BuildStepResultHolder<>();
      stepsBuilder.add(
          new CodeSignIdentityFindStep(
              fingerprintPath,
              getProjectFilesystem(),
              codeSignIdentitiesSupplier,
              selectedCodeSignIdentity));
      return () ->
          selectedCodeSignIdentity
              .getValue()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Code sign identity should have been set in separate step"));
    }
  }

  private void appendDryCodeSignSteps(
      ImmutableList.Builder<Step> stepsBuilder,
      ImmutableList<Path> codeSignOnCopyPaths,
      Supplier<CodeSignIdentity> codeSignIdentitySupplier,
      boolean shouldUseEntitlements) {
    // It only makes sense to sign files, not directories, via codesign.
    // However, for dry-runs of codesigning, files can be embedded
    // as a separate argument to the real codesign; there's no point in
    // signing these as a result.
    ImmutableList.Builder<Path> extraPathsToSignBuilder = ImmutableList.builder();

    for (Path codeSignOnCopyPath : codeSignOnCopyPaths) {
      // TODO(kelliem) remove this hard-coded check for dylibs once dry-run consumers
      // are more flexible.
      if (codeSignOnCopyPath.toString().endsWith(".dylib")) {
        extraPathsToSignBuilder.add(codeSignOnCopyPath);
        continue;
      }
      final boolean shouldUseEntitlementsForExtraBinary = false;
      stepsBuilder.add(
          new DryCodeSignStep(
              getProjectFilesystem(),
              codeSignOnCopyPath,
              shouldUseEntitlementsForExtraBinary,
              codeSignIdentitySupplier,
              new Pair<>(
                  codeSignOnCopyPath.resolve(CODE_SIGN_DRY_RUN_ARGS_FILE), ImmutableList.of())));
    }
    stepsBuilder.add(
        new DryCodeSignStep(
            getProjectFilesystem(),
            bundleRoot,
            shouldUseEntitlements,
            codeSignIdentitySupplier,
            new Pair<>(
                bundleRoot.resolve(CODE_SIGN_DRY_RUN_ARGS_FILE), extraPathsToSignBuilder.build())));
  }

  private void appendCodeSignSteps(
      BuildContext context,
      ImmutableList.Builder<Step> stepsBuilder,
      ImmutableList<Path> codeSignOnCopyPaths,
      Supplier<CodeSignIdentity> codeSignIdentitySupplier,
      Optional<Path> maybeEntitlementsPath) {
    if (parallelCodeSignOnCopyEnabled) {
      stepsBuilder.add(
          new AbstractExecutionStep("parallel-code-sign-paths") {
            @Override
            public StepExecutionResult execute(StepExecutionContext stepContext) {
              // TODO(T79316205) Ideally use future based API
              List<StepExecutionResult> subResults =
                  codeSignOnCopyPaths
                      .parallelStream()
                      .map(
                          path -> {
                            Step subStep =
                                makeCodeSignStep(
                                    context.getSourcePathResolver(), path,
                                    Optional.empty(), codeSignIdentitySupplier);
                            try {
                              return subStep.execute(stepContext);
                            } catch (Exception e) {
                              LOG.error(
                                  e,
                                  "Failed to execute code sign step for path %s.",
                                  path.toString());
                              return StepExecutionResults.ERROR;
                            }
                          })
                      .collect(Collectors.toList());
              return subResults.stream().allMatch(StepExecutionResult::isSuccess)
                  ? StepExecutionResults.SUCCESS
                  : StepExecutionResults.ERROR;
            }
          });
    } else {
      for (Path codeSignOnCopyPath : codeSignOnCopyPaths) {
        stepsBuilder.add(
            makeCodeSignStep(
                context.getSourcePathResolver(), codeSignOnCopyPath,
                Optional.empty(), codeSignIdentitySupplier));
      }
    }
    stepsBuilder.add(
        makeCodeSignStep(
            context.getSourcePathResolver(),
            bundleRoot,
            maybeEntitlementsPath,
            codeSignIdentitySupplier));
  }

  private Step makeCodeSignStep(
      SourcePathResolverAdapter sourcePathResolver,
      Path pathToSign,
      Optional<Path> maybeEntitlementsPath,
      Supplier<CodeSignIdentity> codeSignIdentitySupplier) {
    return new CodeSignStep(
        getProjectFilesystem(),
        sourcePathResolver,
        pathToSign,
        maybeEntitlementsPath,
        codeSignIdentitySupplier,
        codesign,
        codesignAllocatePath,
        codesignFlags,
        codesignTimeout,
        withDownwardApi);
  }

  /** Adds the swift stdlib to the bundle if needed */
  public void addSwiftStdlibStepIfNeeded(
      SourcePathResolverAdapter resolver,
      Path destinationPath,
      Optional<Supplier<CodeSignIdentity>> codeSignIdentitySupplier,
      ImmutableList.Builder<Step> stepsBuilder,
      boolean isForPackaging) {
    // It's apparently safe to run this even on a non-swift bundle (in that case, no libs
    // are copied over).
    boolean shouldCopySwiftStdlib =
        !extension.equals(AppleBundleExtension.APPEX.fileExtension)
            && (!extension.equals(AppleBundleExtension.FRAMEWORK.fileExtension)
                || copySwiftStdlibToFrameworks);

    if (swiftStdlibTool.isPresent() && shouldCopySwiftStdlib) {
      String tempDirPattern = isForPackaging ? "__swift_packaging_temp__%s" : "__swift_temp__%s";
      RelPath tempPath =
          BuildTargetPaths.getScratchPath(getProjectFilesystem(), getBuildTarget(), tempDirPattern);

      stepsBuilder.addAll(MakeCleanDirectoryStep.of(BuildCellRelativePath.of(tempPath)));

      boolean sliceArchitectures =
          (isForPackaging ? sliceAppPackageSwiftRuntime : sliceAppBundleSwiftRuntime);
      stepsBuilder.add(
          new SwiftStdlibStep(
              getProjectFilesystem().getRootPath(),
              tempPath.getPath(),
              sdkPath,
              destinationPath,
              swiftStdlibTool.get().getCommandPrefix(resolver),
              lipo.getCommandPrefix(resolver),
              bundleBinaryPath,
              ImmutableSet.of(
                  bundleRoot.resolve(destinations.getFrameworksPath()),
                  bundleRoot.resolve(destinations.getPlugInsPath())),
              codeSignIdentitySupplier,
              sliceArchitectures,
              withDownwardApi));
    }
  }

  private void appendCopyDsymStep(
      ImmutableList.Builder<Step> stepsBuilder,
      BuildableContext buildableContext,
      BuildContext buildContext) {
    if (appleDsymSourcePath.isPresent()) {
      stepsBuilder.add(
          CopyStep.forDirectory(
              getProjectFilesystem(),
              buildContext
                  .getSourcePathResolver()
                  .getAbsolutePath(appleDsymSourcePath.get())
                  .getPath(),
              bundleRoot.getParent(),
              CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));
      appendDsymRenameStepToMatchBundleName(stepsBuilder, buildableContext, buildContext);
    }
  }

  private void appendDsymRenameStepToMatchBundleName(
      ImmutableList.Builder<Step> stepsBuilder,
      BuildableContext buildableContext,
      BuildContext buildContext) {
    Preconditions.checkArgument(
        hasBinary && appleDsymSourcePath.isPresent() && appleDsymBuildTarget.isPresent());

    // rename dSYM bundle to match bundle name
    RelPath dsymPath =
        buildContext.getSourcePathResolver().getCellUnsafeRelPath(appleDsymSourcePath.get());
    Path dsymSourcePath = bundleRoot.getParent().resolve(dsymPath.getFileName());
    Path dsymDestinationPath =
        bundleRoot
            .getParent()
            .resolve(bundleRoot.getFileName() + "." + AppleBundleExtension.DSYM.fileExtension);
    stepsBuilder.add(
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), getProjectFilesystem(), dsymDestinationPath),
            true));
    stepsBuilder.add(new MoveStep(getProjectFilesystem(), dsymSourcePath, dsymDestinationPath));

    String dwarfFilename = AppleDsym.getDwarfFilenameForDsymTarget(appleDsymBuildTarget.get());

    // rename DWARF file inside dSYM bundle to match bundle name
    Path dwarfFolder = dsymDestinationPath.resolve(AppleDsym.DSYM_DWARF_FILE_FOLDER);
    Path dwarfSourcePath = dwarfFolder.resolve(dwarfFilename);
    Path dwarfDestinationPath = dwarfFolder.resolve(MorePaths.getNameWithoutExtension(bundleRoot));
    stepsBuilder.add(new MoveStep(getProjectFilesystem(), dwarfSourcePath, dwarfDestinationPath));

    // record dSYM so we can fetch it from cache
    buildableContext.recordArtifact(dsymDestinationPath);
  }

  @Override
  public boolean isTestedBy(BuildTarget testRule) {
    if (tests.contains(testRule)) {
      return true;
    }

    if (binary instanceof NativeTestable) {
      return ((NativeTestable) binary).isTestedBy(testRule);
    }

    return false;
  }

  @Override
  public CxxPreprocessorInput getPrivateCxxPreprocessorInput(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    if (binary instanceof NativeTestable) {
      return ((NativeTestable) binary).getPrivateCxxPreprocessorInput(cxxPlatform, graphBuilder);
    }
    return CxxPreprocessorInput.of();
  }

  @Override
  public BuildRule getBinaryBuildRule() {
    return binary;
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(BuildRuleResolver buildRuleResolver) {
    // When "running" an app bundle, ensure debug symbols are available.
    if (binary instanceof HasAppleDebugSymbolDeps) {
      List<BuildRule> symbolDeps =
          ((HasAppleDebugSymbolDeps) binary).getAppleDebugSymbolDeps().collect(Collectors.toList());
      if (!symbolDeps.isEmpty()) {
        return Stream.concat(Stream.of(binary), symbolDeps.stream()).map(BuildRule::getBuildTarget);
      }
    }
    return Stream.empty();
  }

  @Override
  public boolean isCacheable() {
    return cacheable;
  }

  @Override
  public Tool getExecutableCommand(OutputLabel outputLabel) {
    return new CommandTool.Builder()
        .addArg(SourcePathArg.of(PathSourcePath.of(getProjectFilesystem(), bundleBinaryPath)))
        .build();
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return Stream.concat(depsSupplier.get().stream(), buildRuleParams.getBuildDeps().stream())
        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
  }

  @Override
  public void updateBuildRuleResolver(BuildRuleResolver ruleResolver) {
    this.depsSupplier = BuildableSupport.buildDepsSupplier(this, ruleResolver);
  }

  public boolean isWithDownwardApi() {
    return withDownwardApi;
  }

  @Override
  public boolean inputBasedRuleKeyIsEnabled() {
    return inputBasedRulekeyEnabled;
  }
}
