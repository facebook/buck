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

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.android.AndroidBinaryDescription;
import com.facebook.buck.android.AndroidBuildConfigDescription;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.android.AndroidManifestDescription;
import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.android.RobolectricTestDescription;
import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.description.arg.ConstructorArg;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.sourcepath.resolver.impl.AbstractSourcePathResolver;
import com.facebook.buck.features.filegroup.FileGroupDescriptionArg;
import com.facebook.buck.features.filegroup.FilegroupDescription;
import com.facebook.buck.features.zip.rules.ZipFileDescription;
import com.facebook.buck.features.zip.rules.ZipFileDescriptionArg;
import com.facebook.buck.file.RemoteFileDescription;
import com.facebook.buck.file.RemoteFileDescriptionArg;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.groovy.GroovyLibraryDescription;
import com.facebook.buck.jvm.groovy.GroovyTestDescription;
import com.facebook.buck.jvm.java.CompilerOutputPaths;
import com.facebook.buck.jvm.java.JarGenruleDescription;
import com.facebook.buck.jvm.java.JavaBinaryDescription;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaTest;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.facebook.buck.jvm.java.PrebuiltJarDescription;
import com.facebook.buck.jvm.java.PrebuiltJarDescriptionArg;
import com.facebook.buck.jvm.kotlin.KotlinLibraryDescription;
import com.facebook.buck.jvm.kotlin.KotlinTestDescription;
import com.facebook.buck.jvm.scala.ScalaLibraryDescription;
import com.facebook.buck.jvm.scala.ScalaTestDescription;
import com.facebook.buck.shell.AbstractGenruleDescription;
import com.facebook.buck.shell.ExportFileDescription;
import com.facebook.buck.shell.ExportFileDescriptionArg;
import com.facebook.buck.shell.GenruleDescriptionArg;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * A SourcePathResolver implementation that uses only information found in the target graph when
 * converting a BuildTargetSourcePath to an outputPath. This allows the IjProject code to rely only
 * on the TargetGraph when constructing a project.
 */
public class IjProjectSourcePathResolver extends AbstractSourcePathResolver {

  private final TargetGraph targetGraph;
  private final SourcePathResolverAdapter adapter;

  public IjProjectSourcePathResolver(TargetGraph targetGraph) {
    this.targetGraph = targetGraph;
    this.adapter = new SourcePathResolverAdapter(this);
  }

  /**
   * This function mimics the behavior of `BuildRule#getSourcePathToOutput()` but does so without
   * access to the BuildRule implementations or the ActionGraph. This is important since it allows
   * us to resolve SourcePaths from the TargetGraph alone.
   *
   * @return the output path for the given targetNode or Optional.empty() if we don't know how to
   *     resolve the given TargetNode type to an output path.
   */
  private Optional<Path> getOutputPathForTargetNode(
      TargetNode<?> targetNode, BuildTargetWithOutputs targetWithOutputs) {
    BaseDescription<?> description = targetNode.getDescription();
    BuildTarget buildTarget = targetNode.getBuildTarget();
    ProjectFilesystem filesystem = targetNode.getFilesystem();

    if (description instanceof JarGenruleDescription) {
      return getOutputPathForJarGenrule(buildTarget, filesystem);
    } else if (description instanceof AbstractGenruleDescription) {
      return getOutputPathForGenrule(
          (GenruleDescriptionArg) targetNode.getConstructorArg(), filesystem, targetWithOutputs);
    } else if (description instanceof JavaBinaryDescription) {
      return getOutputPathForJavaBinary(buildTarget, filesystem);
    } else if (description instanceof AndroidBinaryDescription) {
      return getOutputPathForAndroidBinary(buildTarget, filesystem);
    } else if (description instanceof AndroidResourceDescription) {
      return getOutputPathForAndroidResource(buildTarget, filesystem);
    } else if (description instanceof AndroidBuildConfigDescription) {
      // AndroidBuildConfig is just a library made of generated sources under the hood
      return getOutputPathFromJavaTargetNode(targetNode, buildTarget, filesystem);
    } else if (description instanceof PrebuiltJarDescription) {
      return getOutputPathForPrebuiltJar(
          (PrebuiltJarDescriptionArg) targetNode.getConstructorArg(), buildTarget, filesystem);
    } else if (isJvmLanguageTargetNode(targetNode)) {
      // All the JVM languages currently use DefaultJavaLibrary under the hood, so we can share
      // the implementation for these languages here
      return getOutputPathFromJavaTargetNode(targetNode, buildTarget, filesystem);
    } else if (description instanceof AndroidManifestDescription) {
      return Optional.of(
          BuildTargetPaths.getGenPath(filesystem, buildTarget, "AndroidManifest__%s__.xml"));
    } else if (isJvmTestTargetNode(targetNode)) {
      // Test targets compile their code into a standard library under the hood using the
      // TESTS_FLAVOR
      BuildTarget testTarget =
          buildTarget.withAppendedFlavors(JavaTest.COMPILED_TESTS_LIBRARY_FLAVOR);
      return getOutputPathFromJavaTargetNode(targetNode, testTarget, filesystem);
    } else if (description instanceof ExportFileDescription) {
      return getOutputPathForExportFile(
          (ExportFileDescriptionArg) targetNode.getConstructorArg(), buildTarget, filesystem);
    } else if (description instanceof RemoteFileDescription) {
      return getOutputPathForRemoteFile(
          (RemoteFileDescriptionArg) targetNode.getConstructorArg(), buildTarget, filesystem);
    } else if (description instanceof FilegroupDescription) {
      return getOutputPathForFilegroup(
          (FileGroupDescriptionArg) targetNode.getConstructorArg(), buildTarget, filesystem);
    } else if (description instanceof ZipFileDescription) {
      return getOutputPathForZipfile(
          (ZipFileDescriptionArg) targetNode.getConstructorArg(), buildTarget, filesystem);
    } else {
      // This SourcePathResolverAdapter does not attempt to exhaustively list all possible rule
      // descriptions,
      // instead opting to only implement those relevant for IjProject
      return Optional.empty();
    }
  }

  /**
   * Resolve the default output path for a JarGenrule. Implementation matches that of JarGenrule's
   * constructor
   */
  private Optional<Path> getOutputPathForJarGenrule(
      BuildTarget buildTarget, ProjectFilesystem filesystem) {
    // Note: `getShortNameAndFlavorPostfix` comes from the implementation of the "%s" formatter in
    // `getGenPath`, and JarGenrule names its jarfile after the name of the target. JarGenrule
    // doesn't have flavors, so this is roughly equivalent to `getShortName`, but it's more correct
    // in that it's what `getGenPath` does.
    String jarName = buildTarget.getShortNameAndFlavorPostfix() + ".jar";
    return Optional.of(BuildTargetPaths.getGenPath(filesystem, buildTarget, "%s").resolve(jarName));
  }

  /**
   * Resolve the default output path for the given targetSourcePath, returning a sourcepath pointing
   * to the output.
   */
  @Override
  protected ImmutableSortedSet<SourcePath> resolveDefaultBuildTargetSourcePath(
      DefaultBuildTargetSourcePath targetSourcePath) {
    BuildTarget target = targetSourcePath.getTarget();
    TargetNode<?> targetNode = targetGraph.get(target);
    Optional<Path> outputPath =
        getOutputPathForTargetNode(targetNode, targetSourcePath.getTargetWithOutputs());
    return ImmutableSortedSet.of(
        PathSourcePath.of(
            targetNode.getFilesystem(),
            outputPath.orElseThrow(
                () -> new HumanReadableException("No known output for: %s", target))));
  }

  /**
   * Source path names are only used when constructing the ActionGraph, so we don't need to support
   * them here.
   */
  @Override
  public String getSourcePathName(BuildTarget target, SourcePath sourcePath) {
    throw new UnsupportedOperationException();
  }

  /** @return the filesystem instance that corresponds to the given BuildTargetSourcePath */
  @Override
  protected ProjectFilesystem getBuildTargetSourcePathFilesystem(BuildTargetSourcePath sourcePath) {
    return targetGraph.get(sourcePath.getTarget()).getFilesystem();
  }

  /** @return The BuildTarget portion of the sourcePath if present */
  public Optional<BuildTarget> getBuildTarget(SourcePath sourcePath) {
    if (sourcePath instanceof BuildTargetSourcePath) {
      return Optional.of(((BuildTargetSourcePath) sourcePath).getTarget());
    } else {
      return Optional.empty();
    }
  }

  /** @return true if the given target node describes a rule targeting the JVM */
  public static boolean isJvmLanguageTargetNode(TargetNode<?> targetNode) {
    BaseDescription<?> description = targetNode.getDescription();
    return description instanceof JavaLibraryDescription
        || description instanceof AndroidLibraryDescription
        || description instanceof ScalaLibraryDescription
        || description instanceof GroovyLibraryDescription
        || description instanceof KotlinLibraryDescription;
  }

  /** @return true if the given target node describes a junit/ngtest rule targeting the JVM */
  public static boolean isJvmTestTargetNode(TargetNode<?> targetNode) {
    BaseDescription<?> description = targetNode.getDescription();
    return description instanceof JavaTestDescription
        || description instanceof RobolectricTestDescription
        || description instanceof ScalaTestDescription
        || description instanceof GroovyTestDescription
        || description instanceof KotlinTestDescription;
  }

  /** Calculate the output path for a Filegroup rule */
  private Optional<Path> getOutputPathForFilegroup(
      FileGroupDescriptionArg arg, BuildTarget buildTarget, ProjectFilesystem filesystem) {
    // This matches the implementation in Filegroup which uses the name of the rule as the output
    // name for the exported files
    String filename = arg.getName();
    return Optional.of(BuildPaths.getGenDir(filesystem, buildTarget).resolve(filename));
  }

  /** Calculate the output path for a RemoteFile rule */
  private Optional<Path> getOutputPathForRemoteFile(
      RemoteFileDescriptionArg arg, BuildTarget buildTarget, ProjectFilesystem filesystem) {
    // This matches the implementation in RemoteFileDescription for the output filename
    // calculation
    String filename = arg.getOut().orElse(buildTarget.getShortNameAndFlavorPostfix());
    // This matches the output path calculation in RemoteFile's constructor
    return Optional.of(BuildTargetPaths.getGenPath(filesystem, buildTarget, "%s/" + filename));
  }

  /** Calculate the output path for an ExportFile rule */
  private Optional<Path> getOutputPathForExportFile(
      ExportFileDescriptionArg arg, BuildTarget buildTarget, ProjectFilesystem filesystem) {
    // This matches the implementation in ExportFileDescription
    // If the mode is REFERENCE we need to return the relative path to the real underlying file
    if (arg.getMode().map(mode -> mode == ExportFileDescription.Mode.REFERENCE).orElse(false)) {
      return arg.getSrc().map(adapter::getRelativePath);
    }
    // Otherwise, we resolve the generated path for the COPY
    String name = arg.getOut().orElse(buildTarget.getShortNameAndFlavorPostfix());
    return Optional.of(BuildTargetPaths.getGenPath(filesystem, buildTarget, "%s").resolve(name));
  }

  /**
   * @return the output path for the given buildTarget assuming that the buildTarget points to
   *     something like a JavaLibrary
   */
  public static Optional<Path> getOutputPathFromJavaTargetNode(
      TargetNode<?> targetNode, BuildTarget buildTarget, ProjectFilesystem projectFilesystem) {

    // This matches the implementation of JarBuildStepsFactory#producesJar()
    ConstructorArg arg = targetNode.getConstructorArg();
    if (arg instanceof JavaLibraryDescription.CoreArg) {
      JavaLibraryDescription.CoreArg constructorArg = (JavaLibraryDescription.CoreArg) arg;
      if (constructorArg.getSrcs().isEmpty()
          && constructorArg.getResources().isEmpty()
          && !constructorArg.getManifestFile().isPresent()) {
        // Does not produce a jar
        return Optional.empty();
      }
    }
    if (JavaAbis.isSourceAbiTarget(buildTarget) || JavaAbis.isSourceOnlyAbiTarget(buildTarget)) {
      return Optional.of(CompilerOutputPaths.getAbiJarPath(buildTarget, projectFilesystem));
    } else if (JavaAbis.isLibraryTarget(buildTarget)) {
      return Optional.of(CompilerOutputPaths.getOutputJarPath(buildTarget, projectFilesystem));
    } else {
      return Optional.empty();
    }
  }

  /** Calculate the output path for a PrebuiltJar from information in the Arg */
  private Optional<Path> getOutputPathForPrebuiltJar(
      PrebuiltJarDescriptionArg constructorArg,
      BuildTarget buildTarget,
      ProjectFilesystem filesystem) {
    // The binary jar is copied with its same name to the output directory, so we need to get
    // the name. The only difference is when the name doesn't end in `.jar` it gets renamed.
    SourcePath binaryJar = constructorArg.getBinaryJar();
    Path fileName = adapter.getRelativePath(filesystem, binaryJar).getFileName();
    String fileNameWithJarExtension =
        String.format("%s.jar", MorePaths.getNameWithoutExtension(fileName));
    // Matches the implementation in PrebuiltJar's constructor
    return Optional.of(
        BuildTargetPaths.getGenPath(filesystem, buildTarget, "__%s__/" + fileNameWithJarExtension));
  }

  /**
   * Calculate the output path for an AndroidResource which may either be a symlink tree of
   * resources or the R.txt output depending on the flavor
   */
  private Optional<Path> getOutputPathForAndroidResource(
      BuildTarget buildTarget, ProjectFilesystem filesystem) {
    if (buildTarget
        .getFlavors()
        .contains(AndroidResourceDescription.RESOURCES_SYMLINK_TREE_FLAVOR)) {
      return Optional.of(BuildPaths.getGenDir(filesystem, buildTarget).resolve("res"));
    } else {
      return Optional.of(
          BuildTargetPaths.getGenPath(filesystem, buildTarget, "__%s_text_symbols__"));
    }
  }

  /**
   * Calculate the output path of the apk produced by an android_binary rule from the information in
   * the constructor Arg
   */
  private Optional<Path> getOutputPathForAndroidBinary(
      BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return Optional.of(BuildTargetPaths.getGenPath(filesystem, buildTarget, "%s.apk"));
  }

  /**
   * Returns the output path for a Genrule from the information contained in the given constructor
   * arg and output label, or empty if the output path cannot be calculated.
   */
  private Optional<Path> getOutputPathForGenrule(
      GenruleDescriptionArg constructorArg,
      ProjectFilesystem filesystem,
      BuildTargetWithOutputs targetWithOutputs) {
    BuildTarget buildTarget = targetWithOutputs.getBuildTarget();
    if (constructorArg.getOut().isPresent()) {
      return getGenPathForOutput(buildTarget, filesystem, constructorArg.getOut().get());
    }
    OutputLabel outputLabel = targetWithOutputs.getOutputLabel();
    ImmutableMap<String, ImmutableSet<String>> outputLabelToOutputs =
        constructorArg.getOuts().get();
    return Iterables.getOnlyElement(
        outputLabelToOutputs.entrySet().stream()
            .filter(e -> OutputLabel.of(e.getKey()).equals(outputLabel))
            .flatMap(
                e ->
                    e.getValue().stream()
                        .map(out -> getGenPathForOutput(buildTarget, filesystem, out)))
            .collect(ImmutableSet.toImmutableSet()));
  }

  /** Calculate the output path for a JavaBinary based on its build target */
  private Optional<Path> getOutputPathForJavaBinary(
      BuildTarget buildTarget, ProjectFilesystem filesystem) {
    // Matches the implementation in JavaBinary#getOutputDirectory()
    Path outputDirectory = BuildTargetPaths.getGenPath(filesystem, buildTarget, "%s").getParent();
    // Matches the implementation in JavaBinary#getSourcePathToOutput()
    return Optional.of(
        Paths.get(
            String.format(
                "%s/%s.jar", outputDirectory, buildTarget.getShortNameAndFlavorPostfix())));
  }

  private Optional<Path> getGenPathForOutput(
      BuildTarget buildTarget, ProjectFilesystem filesystem, String out) {
    return Optional.of(BuildTargetPaths.getGenPath(filesystem, buildTarget, "%s").resolve(out));
  }

  /** Calculate the output path for a zip file. */
  private Optional<Path> getOutputPathForZipfile(
      ZipFileDescriptionArg arg, BuildTarget buildTarget, ProjectFilesystem filesystem) {
    String filename = arg.getOut();
    return Optional.of(BuildPaths.getGenDir(filesystem, buildTarget).resolve(filename));
  }
}
