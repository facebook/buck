/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.command;

import static com.facebook.buck.rules.BuildableProperties.Kind.ANDROID;
import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;
import static com.facebook.buck.rules.BuildableProperties.Kind.PACKAGING;

import com.facebook.buck.android.AndroidBinaryRule;
import com.facebook.buck.android.AndroidDexTransitiveDependencies;
import com.facebook.buck.android.AndroidLibraryRule;
import com.facebook.buck.android.AndroidResourceRule;
import com.facebook.buck.android.NdkLibrary;
import com.facebook.buck.java.JavaBinaryRule;
import com.facebook.buck.java.JavaLibraryRule;
import com.facebook.buck.java.PrebuiltJarRule;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.rules.AbstractDependencyVisitor;
import com.facebook.buck.rules.AnnotationProcessingData;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.ExportDependencies;
import com.facebook.buck.rules.JavaPackageFinder;
import com.facebook.buck.rules.ProjectConfigRule;
import com.facebook.buck.rules.SourceRoot;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.KeystoreProperties;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.Verbosity;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.Files;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;

import javax.annotation.Nullable;

/**
 * Utility to map the build files in a project built with Buck into a collection of metadata files
 * so that the project can be built with IntelliJ. This uses a number of heuristics specific to our
 * repository at Facebook that does not make this a generally applicable solution. Hopefully over
 * time, the Facebook-specific logic will be removed.
 */
public class Project {

  /**
   * This directory is analogous to the gen/ directory that IntelliJ would produce when building an
   * Android module. It contains files such as R.java, BuildConfig.java, and Manifest.java.
   * <p>
   * By default, IntelliJ generates its gen/ directories in our source tree, which would likely
   * mess with the user's use of {@code glob(['**&#x2f;*.java'])}. For this reason, we encourage
   * users to target
   */
  public static final String ANDROID_GEN_DIR = BuckConstant.BUCK_OUTPUT_DIRECTORY + "/android";

  /**
   * Prefix for build targets whose output will be in {@link #ANDROID_GEN_DIR}.
   */
  private static final String ANDROID_GEN_BUILD_TARGET_PREFIX =
      String.format("//%s/", ANDROID_GEN_DIR);

  /**
   * Path to the intellij.py script that is used to transform the JSON written by this file.
   */
  private static final String PATH_TO_INTELLIJ_PY = System.getProperty(
      "buck.path_to_intellij_py",
      // Fall back on this value when running Buck from an IDE.
      new File("src/com/facebook/buck/command/intellij.py").getAbsolutePath());

  /**
   * For now, do not write any project.properties files. We have failed to provide them in the
   * same format as IntelliJ itself would generate them. That means that IntelliJ overwrites our
   * generated versions at some point. The next time `buck project` is run, Buck overwrites them
   * again, which causes IntelliJ to go and index the world. Empirically, it seems that if we do
   * not write them at all, IntelliJ can live without them.
   */
  private static boolean GENERATE_PROPERTIES_FILES = false;

  private final PartialGraph partialGraph;
  private final BuildFileTree buildFileTree;
  private final ImmutableMap<String, String> basePathToAliasMap;
  private final JavaPackageFinder javaPackageFinder;
  private final ExecutionContext executionContext;
  private final ProjectFilesystem projectFilesystem;
  private final Optional<String> pathToDefaultAndroidManifest;
  private final Optional<String> pathToPostProcessScript;
  private final Set<PrebuiltJarRule> libraryJars;
  private final String pythonInterpreter;

  public Project(PartialGraph partialGraph,
      Map<String, String> basePathToAliasMap,
      JavaPackageFinder javaPackageFinder,
      ExecutionContext executionContext,
      ProjectFilesystem projectFilesystem,
      Optional<String> pathToDefaultAndroidManifest,
      Optional<String> pathToPostProcessScript,
      String pythonInterpreter) {
    this.partialGraph = Preconditions.checkNotNull(partialGraph);
    this.buildFileTree = new BuildFileTree(partialGraph.getTargets());
    this.basePathToAliasMap = ImmutableMap.copyOf(basePathToAliasMap);
    this.javaPackageFinder = Preconditions.checkNotNull(javaPackageFinder);
    this.executionContext = Preconditions.checkNotNull(executionContext);
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    this.pathToDefaultAndroidManifest = Preconditions.checkNotNull(pathToDefaultAndroidManifest);
    this.pathToPostProcessScript = Preconditions.checkNotNull(pathToPostProcessScript);
    this.libraryJars = Sets.newHashSet();
    this.pythonInterpreter = Preconditions.checkNotNull(pythonInterpreter);
  }

  public int createIntellijProject(File jsonTempFile,
      ProcessExecutor processExecutor,
      PrintStream stdOut,
      PrintStream stdErr) throws IOException {
    List<Module> modules = createModulesForProjectConfigs();
    writeJsonConfig(jsonTempFile, modules);

    List<String> modifiedFiles = Lists.newArrayList();

    // Process the JSON config to generate the .xml and .iml files for IntelliJ.
    ExitCodeAndOutput result = processJsonConfig(jsonTempFile);
    if (result.exitCode != 0) {
      return result.exitCode;
    } else {
      // intellij.py writes the list of modified files to stdout, so parse stdout and add the
      // resulting file paths to the modifiedFiles list.
      Iterable<String> paths = Splitter.on('\n').trimResults().omitEmptyStrings().split(
          result.stdOut);
      Iterables.addAll(modifiedFiles, paths);
    }

    // Write out the project.properties files.
    List<String> modifiedPropertiesFiles = generateProjectDotPropertiesFiles(modules);
    modifiedFiles.addAll(modifiedPropertiesFiles);

    // Write out the .idea/compiler.xml file (the .idea/ directory is guaranteed to exist).
    CompilerXml compilerXml = new CompilerXml(modules);
    final String pathToCompilerXml = ".idea/compiler.xml";
    File compilerXmlFile = projectFilesystem.getFileForRelativePath(pathToCompilerXml);
    if (compilerXml.write(compilerXmlFile)) {
      modifiedFiles.add(pathToCompilerXml);
    }

    // If the user specified a post-processing script, then run it.
    if (pathToPostProcessScript.isPresent()) {
      String pathToScript = pathToPostProcessScript.get();
      Process process = Runtime.getRuntime().exec(new String[] {pathToScript});
      processExecutor.execute(process);
    }

    // If any files have been modified by `buck project`, then list them for the user.
    if (!modifiedFiles.isEmpty()) {
      SortedSet<String> modifiedFilesInSortedForder = Sets.newTreeSet(modifiedFiles);
      stdOut.printf("MODIFIED FILES:\n%s\n", Joiner.on('\n').join(modifiedFilesInSortedForder));
    }
    // Blit stderr from intellij.py to parent stderr.
    stdErr.print(result.stdErr);

    return 0;
  }

  private List<String> generateProjectDotPropertiesFiles(List<Module> modules) throws IOException {
    if (GENERATE_PROPERTIES_FILES) {
      // Create a map of module names to modules.
      Map<String, Module> nameToModule = buildNameToModuleMap(modules);
      List<String> modifiedFiles = Lists.newArrayList();

      for (Module module : modules) {
        if (!module.isAndroidModule()) {
          continue;
        }

        File propertiesFile = writeProjectDotPropertiesFile(module, nameToModule);
        if (propertiesFile != null) {
          modifiedFiles.add(propertiesFile.getPath());
        }
      }

      return modifiedFiles;
    } else {
      return ImmutableList.of();
    }
  }

  @VisibleForTesting
  Map<String, Module> buildNameToModuleMap(List<Module> modules) {
    Map<String, Module> nameToModule = Maps.newHashMap();
    for (Module module : modules) {
      nameToModule.put(module.name, module);
    }
    return nameToModule;
  }

   /**
   * @param module must be an android module
   * @param nameToModuleIndex
   * @returns the File that was written, or {@code null} if no file was written.
   * @throws IOException
   */
  @VisibleForTesting
  @Nullable
  File writeProjectDotPropertiesFile(Module module, Map<String, Module> nameToModuleIndex)
      throws IOException {
    SortedSet<String> references = Sets.newTreeSet();
    for (DependentModule dependency : module.dependencies) {
      if (!dependency.isModule()) {
        continue;
      }

      Module dep = nameToModuleIndex.get(dependency.getModuleName());
      if (dep == null) {
        throw new HumanReadableException("You must define a project_config() in %s " +
            "containing %s. The project_config() in %s transitively depends on it.",
            module.target.getBasePathWithSlash() + BuckConstant.BUILD_RULES_FILE_NAME,
            dependency.getTargetName(),
            module.target.getFullyQualifiedName());
      }
      if (!dep.isAndroidModule()) {
        continue;
      }

      Path pathToImlFile = Paths.get(module.pathToImlFile);
      Path depPathToImlFile = Paths.get(dep.pathToImlFile);

      String relativePath = pathToImlFile.getParent().relativize(depPathToImlFile.getParent()).toString();

      // This is probably a self-reference. Ignore it.
      if (relativePath.isEmpty()) {
        continue;
      }

      references.add(relativePath);
    }

    StringBuilder builder = new StringBuilder();
    builder.append("# This file is automatically generated by Buck.\n");
    builder.append("# Do not modify this file -- YOUR CHANGES WILL BE ERASED!\n");

    // These are default values that IntelliJ or some other tool may overwrite.
    builder.append(
        String.format("target=%s\n", AndroidPlatformTarget.DEFAULT_ANDROID_PLATFORM_TARGET));
    builder.append("proguard.config=proguard.cfg\n");

    boolean isAndroidLibrary = module.isAndroidLibrary();
    if (isAndroidLibrary) {
      // Android does not seem to include this line for non-Android libraries.
      builder.append("android.library=" + isAndroidLibrary + "\n");
    }

    int index = 1;
    for (String path : references) {
      builder.append(String.format("android.library.reference.%d=%s\n", index, path));
      ++index;
    }

    final Charset charset = Charsets.US_ASCII;
    File outputFile = new File(createPathToProjectDotPropertiesFileFor(module));
    String properties = builder.toString();
    if (outputFile.exists() && Files.toString(outputFile, charset).equals(properties)) {
      return null;
    } else {
      Files.write(properties, outputFile, charset);
      return outputFile;
    }
  }

  @VisibleForTesting
  static String createPathToProjectDotPropertiesFileFor(Module module) {
    return module.getModuleDirectoryPathWithSlash() + "project.properties";
  }

  @VisibleForTesting
  PartialGraph getPartialGraph() {
    return partialGraph;
  }

  /**
   * This is used exclusively for testing and will only be populated after the modules are created.
   */
  @VisibleForTesting
  ImmutableSet<PrebuiltJarRule> getLibraryJars() {
    return ImmutableSet.copyOf(libraryJars);
  }

  @VisibleForTesting
  List<Module> createModulesForProjectConfigs() throws IOException {
    DependencyGraph dependencyGraph = partialGraph.getDependencyGraph();
    List<Module> modules = Lists.newArrayList();

    // Convert the project_config() targets into modules and find the union of all jars passed to
    // no_dx.
    ImmutableSet.Builder<String> noDxJarsBuilder = ImmutableSet.builder();
    for (BuildTarget target : partialGraph.getTargets()) {
      BuildRule buildRule = dependencyGraph.findBuildRuleByTarget(target);
      ProjectConfigRule projectConfigRule = (ProjectConfigRule)buildRule;

      BuildRule srcRule = projectConfigRule.getSrcRule();
      if (srcRule instanceof AndroidBinaryRule) {
        AndroidBinaryRule androidBinary = (AndroidBinaryRule)srcRule;
        AndroidDexTransitiveDependencies binaryDexTransitiveDependencies =
            androidBinary.findDexTransitiveDependencies();
        noDxJarsBuilder.addAll(binaryDexTransitiveDependencies.noDxClasspathEntries);
      }

      Module module = createModuleForProjectConfig(projectConfigRule);
      modules.add(module);
    }
    ImmutableSet<String> noDxJars = noDxJarsBuilder.build();

    // Update module dependencies to apply scope="PROVIDED", where appropriate.
    markNoDxJarsAsProvided(modules, noDxJars);

    return modules;
  }

  private Module createModuleForProjectConfig(ProjectConfigRule projectConfig) throws IOException {
    BuildRule projectRule = projectConfig.getProjectRule();
    Buildable buildable = projectRule.getBuildable();
    Preconditions.checkState(projectRule instanceof JavaLibraryRule
        || projectRule instanceof JavaBinaryRule
        || projectRule instanceof AndroidLibraryRule
        || projectRule instanceof AndroidResourceRule
        || projectRule instanceof AndroidBinaryRule
        || buildable instanceof NdkLibrary,
        "project_config() does not know how to process a src_target of type %s.",
        projectRule.getType().getName());

    LinkedHashSet<DependentModule> dependencies = Sets.newLinkedHashSet();
    final BuildTarget target = projectConfig.getBuildTarget();
    Module module = new Module(projectRule, target);
    module.name = getIntellijNameForRule(projectRule);
    module.isIntelliJPlugin = projectConfig.getIsIntelliJPlugin();

    String relativePath = projectConfig.getBuildTarget().getBasePathWithSlash();
    module.pathToImlFile = String.format("%s%s.iml", relativePath, module.name);

    // List the module source as the first dependency.
    boolean includeSourceFolder = true;

    // Do the tests before the sources so they appear earlier in the classpath. When tests are run,
    // their classpath entries may be deliberately shadowing production classpath entries.

    // tests folder
    boolean hasSourceFoldersForTestRule = addSourceFolders(module,
        projectConfig.getTestRule(),
        projectConfig.getTestsSourceRoots(),
        true /* isTestSource */);

    // test dependencies
    BuildRule testRule = projectConfig.getTestRule();
    if (testRule != null) {
      walkRuleAndAdd(testRule, true /* isForTests */, dependencies, projectConfig.getSrcRule());
    }

    // src folder
    boolean hasSourceFoldersForSrcRule = addSourceFolders(module,
        projectConfig.getSrcRule(),
        projectConfig.getSourceRoots(),
        false /* isTestSource */);

    addRootExcludes(module, projectConfig.getSrcRule());

    // At least one of src or tests should contribute a source folder unless this is an
    // non-library Android project with no source roots specified.
    if (!hasSourceFoldersForTestRule && !hasSourceFoldersForSrcRule) {
      includeSourceFolder = false;
    }

    // IntelliJ expects all Android projects to have a gen/ folder, even if there is no src/
    // directory specified.
    boolean isAndroidRule = projectRule.getProperties().is(ANDROID);
    if (isAndroidRule) {
      boolean hasSourceFolders = !module.sourceFolders.isEmpty();
      module.sourceFolders.add(SourceFolder.GEN);
      if (!hasSourceFolders) {
        includeSourceFolder = true;
      }
    }

    // src dependencies
    // Note that isForTests is false even if projectRule is the project_config's test_target.
    walkRuleAndAdd(projectRule, false /* isForTests */, dependencies, projectConfig.getSrcRule());

    String basePathWithSlash = projectConfig.getBuildTarget().getBasePathWithSlash();

    // Specify another path for intellij to generate gen/ for each android module,
    // so that it will not disturb our glob() rules.
    // To specify the location of gen, Intellij requires the relative path from
    // the base path of current build target.
    module.moduleGenPath = generateRelativeGenPath(basePathWithSlash).toString();

    DependentModule jdkDependency;
    if (isAndroidRule) {
      // android details
      if (projectRule.getBuildable() instanceof NdkLibrary) {
        NdkLibrary ndkLibrary = (NdkLibrary) projectRule.getBuildable();
        module.isAndroidLibraryProject = true;
        module.keystorePath = null;
        module.nativeLibs = Paths.get(relativePath).relativize(Paths.get(ndkLibrary.getLibraryPath())).toString();
      } else if (projectRule instanceof AndroidResourceRule) {
        AndroidResourceRule androidResourceRule = (AndroidResourceRule)projectRule;
        module.resFolder = createRelativePath(androidResourceRule.getRes(), target);
        module.isAndroidLibraryProject = true;
        module.keystorePath = null;
      } else if (projectRule instanceof AndroidBinaryRule) {
        AndroidBinaryRule androidBinaryRule = (AndroidBinaryRule)projectRule;
        module.resFolder = null;
        module.isAndroidLibraryProject = false;
        KeystoreProperties keystoreProperties = KeystoreProperties.createFromPropertiesFile(
            androidBinaryRule.getKeystore().getPathToStore(),
            androidBinaryRule.getKeystore().getPathToPropertiesFile(),
            projectFilesystem);

        // getKeystore() returns a path relative to the project root, but an IntelliJ module
        // expects the path to the keystore to be relative to the module root.
        module.keystorePath = Paths.get(relativePath).relativize(Paths.get(keystoreProperties.getKeystore())).toString();
      } else {
        module.isAndroidLibraryProject = true;
        module.keystorePath = null;
      }

      module.hasAndroidFacet = true;
      module.proguardConfigPath = null;

      // If there is a default AndroidManifest.xml specified in .buckconfig, use it if
      // AndroidManifest.xml is not present in the root of the [Android] IntelliJ module.
      if (pathToDefaultAndroidManifest.isPresent()) {
        String androidManifest = basePathWithSlash + "AndroidManifest.xml";
        if (!projectFilesystem.exists(androidManifest)) {
          String manifestPath = this.pathToDefaultAndroidManifest.get();
          String rootPrefix = "//";
          Preconditions.checkState(manifestPath.startsWith(rootPrefix),
              "Currently, we expect this option to start with '%s', " +
              "indicating that it is relative to the root of the repository.",
              rootPrefix);
          manifestPath = manifestPath.substring(rootPrefix.length());
          String relativePathToManifest = Paths.get(basePathWithSlash).relativize(Paths.get(manifestPath)).toString();
          // IntelliJ requires that the path start with a slash to indicate that it is relative to
          // the module.
          module.androidManifest = "/" + relativePathToManifest;
        }
      }

      // List this last so that classes from modules can shadow classes in the JDK.
      jdkDependency = DependentModule.newInheritedJdk();
    } else {
      module.hasAndroidFacet = false;

      if (module.isIntelliJPlugin()) {
        jdkDependency = DependentModule.newIntelliJPluginJdk();
      } else {
        jdkDependency = DependentModule.newStandardJdk();
      }
    }

    // Assign the dependencies.
    module.dependencies = createDependenciesInOrder(
        includeSourceFolder, dependencies, jdkDependency);

    // Annotation processing generates sources for IntelliJ to consume, but does so outside
    // the module directory to avoid messing up globbing.
    if (projectRule instanceof JavaLibraryRule) {
      JavaLibraryRule javaLibraryRule = (JavaLibraryRule)projectRule;
      AnnotationProcessingData processingData = javaLibraryRule.getAnnotationProcessingData();

      String annotationGenSrc = processingData.getGeneratedSourceFolderName();
      if (annotationGenSrc != null) {
        module.annotationGenPath =
            "/" + Paths.get(basePathWithSlash).relativize(Paths.get(annotationGenSrc)).toString();
        module.annotationGenIsForTest = !hasSourceFoldersForSrcRule;
      }
    }

    return module;
  }

  private List<DependentModule> createDependenciesInOrder(
      boolean includeSourceFolder,
      LinkedHashSet<DependentModule> dependencies,
      DependentModule jdkDependency) {
    List<DependentModule> dependenciesInOrder = Lists.newArrayList();

    // If the source folder module is present, add it to the front of the list.
    if (includeSourceFolder) {
      dependenciesInOrder.add(DependentModule.newSourceFolder());
    }

    // List the libraries before the non-libraries.
    List<DependentModule> nonLibraries = Lists.newArrayList();
    for (DependentModule dep : dependencies) {
      if (dep.isLibrary()) {
        dependenciesInOrder.add(dep);
      } else {
        nonLibraries.add(dep);
      }
    }
    dependenciesInOrder.addAll(nonLibraries);

    // Add the JDK last.
    dependenciesInOrder.add(jdkDependency);
    return dependenciesInOrder;
  }

  /**
   * Paths.computeRelativePath(basePathWithSlash, "") generates the relative path
   * from base path of current build target to the root of the project.
   *
   * Paths.computeRelativePath("", basePathWithSlash) generates the relative path
   * from the root of the project to base path of current build target.
   *
   * For example, for the build target in $PROJECT_DIR$/android_res/com/facebook/gifts/,
   * Intellij will generate $PROJECT_DIR$/buck-out/android/android_res/com/facebook/gifts/gen
   *
   * @return the relative path of gen from the base path of current module.
   */
  static Path generateRelativeGenPath(String basePathOfModuleWithSlash) {
    return Paths.get(
        "/",
        Paths.get(basePathOfModuleWithSlash).relativize(Paths.get("")).toString(),
        ANDROID_GEN_DIR,
        Paths.get("").relativize(Paths.get(basePathOfModuleWithSlash)).toString(),
        "gen");
  }

  private boolean addSourceFolders(Module module,
      @Nullable BuildRule buildRule,
      @Nullable ImmutableList<SourceRoot> sourceRoots,
      boolean isTestSource) {
    if (buildRule == null || sourceRoots == null) {
      return false;
    }

    if (buildRule.getProperties().is(PACKAGING) && sourceRoots.isEmpty()) {
      return false;
    }

    if (sourceRoots.isEmpty()) {
      // When there is a src_target, but no src_roots were specified, then the current directory is
      // treated as the SourceRoot. This is the common case when a project contains one folder of
      // Java source code with a build file for each Java package. For example, if the project's
      // only source folder were named "java/" and a build file in java/com/example/base/ contained
      // the an extremely simple set of build rules:
      //
      // java_library(
      //   name = 'base',
      //   srcs = glob(['*.java']),
      // }
      //
      // project_config(
      //   src_target = ':base',
      // )
      //
      // then the corresponding .iml file (in the same directory) should contain:
      //
      // <content url="file://$MODULE_DIR$">
      //   <sourceFolder url="file://$MODULE_DIR$" isTestSource="false" packagePrefix="com.example.base" />
      //   <sourceFolder url="file://$MODULE_DIR$/gen" isTestSource="false" />
      //
      //   <!-- It will have an <excludeFolder> for every "subpackage" of com.example.base. -->
      //   <excludeFolder url="file://$MODULE_DIR$/util" />
      // </content>
      //
      // Note to prevent the <excludeFolder> elements from being included, the project_config()
      // rule should be:
      //
      // project_config(
      //   src_target = ':base',
      //   src_root_includes_subdirectories = True,
      // )
      //
      // Because developers who organize their code this way will have many build files, the default
      // values of project_config() assume this approach to help minimize the tedium in writing all
      // of those project_config() rules.
      String url = "file://$MODULE_DIR$";
      String packagePrefix = javaPackageFinder.findJavaPackageForPath(module.pathToImlFile);
      SourceFolder sourceFolder = new SourceFolder(url, isTestSource, packagePrefix);
      module.sourceFolders.add(sourceFolder);
    } else {
      for (SourceRoot sourceRoot : sourceRoots) {
        SourceFolder sourceFolder = new SourceFolder(
            String.format("file://$MODULE_DIR$/%s", sourceRoot.getName()), isTestSource);
        module.sourceFolders.add(sourceFolder);
      }
    }

    // Include <excludeFolder> elements, as appropriate.
    for (String relativePath : this.buildFileTree.getChildPaths(buildRule.getBuildTarget())) {
      String excludeFolderUrl = "file://$MODULE_DIR$/" + relativePath;
      SourceFolder excludeFolder = new SourceFolder(excludeFolderUrl, /* isTestSource */ false);
      module.excludeFolders.add(excludeFolder);
    }

    return true;
  }

  private void addRootExcludes(Module module, BuildRule buildRule) {
    // If in the root of the project, specify ignored paths.
    if (buildRule != null && buildRule.getBuildTarget().getBasePathWithSlash().isEmpty()) {
      for (Path path : projectFilesystem.getIgnorePaths()) {
        module.excludeFolders.add(
            new SourceFolder(String.format("file://$MODULE_DIR$/%s", path), false));
      }
    }
  }

  /**
   * Modifies the {@code scope} of a library dependency to {@code "PROVIDED"}, where appropriate.
   * <p>
   * If an {@code android_binary()} rule uses the {@code no_dx} argument, then the jars in the
   * libraries that should not be dex'ed must be included with {@code scope="PROVIDED"} in
   * IntelliJ.
   * <p>
   * The problem is that if a library is included by two android_binary rules that each need it in a
   * different way (i.e., for one it should be {@code scope="COMPILE"} and another it should be
   * {@code scope="PROVIDED"}), then it must be tagged as {@code scope="PROVIDED"} in all
   * dependent modules and then added as {@code scope="COMPILE"} in the .iml file that corresponds
   * to the android_binary that <em>does not</em> list the library in its {@code no_dx} list.
   */
  @VisibleForTesting
  static void markNoDxJarsAsProvided(List<Module> modules, Set<String> noDxJars) {
  Map<String, String> intelliJLibraryNameToJarPath = Maps.newHashMap();
    for (String jarPath : noDxJars) {
      String libraryName = getIntellijNameForBinaryJar(jarPath);
      intelliJLibraryNameToJarPath.put(libraryName, jarPath);
    }

    for (Module module : modules) {
      // For an android_binary() rule, create a set of paths to JAR files (or directories) that
      // must be dex'ed. If a JAR file that is in the no_dx list for some android_binary rule, but
      // is in this set for this android_binary rule, then it should be scope="COMPILE" rather than
      // scope="PROVIDED".
      Set<String> classpathEntriesToDex;
      if (module.srcRule instanceof AndroidBinaryRule) {
        AndroidBinaryRule androidBinaryRule = (AndroidBinaryRule)module.srcRule;
        AndroidDexTransitiveDependencies dexTransitiveDependencies =
            androidBinaryRule.findDexTransitiveDependencies();
        classpathEntriesToDex = Sets.newHashSet(Sets.intersection(noDxJars,
            dexTransitiveDependencies.classpathEntriesToDex));
      } else {
        classpathEntriesToDex = ImmutableSet.of();
      }

      // Inspect all of the library dependencies. If the corresponding JAR file is in the set of
      // noDxJars, then either change its scope to "COMPILE" or "PROVIDED", as appropriate.
      for (DependentModule dependentModule : module.dependencies) {
        if (!dependentModule.isLibrary()) {
          continue;
        }

        // This is the IntelliJ name for the library that corresponds to the PrebuiltJarRule.
        String libraryName = dependentModule.getLibraryName();

        String jarPath = intelliJLibraryNameToJarPath.get(libraryName);
        if (jarPath != null) {
          if (classpathEntriesToDex.contains(jarPath)) {
            dependentModule.scope = null;
            classpathEntriesToDex.remove(jarPath);
          } else {
            dependentModule.scope = "PROVIDED";
          }
        }
      }

      // Make sure that every classpath entry that is also in noDxJars is added with scope="COMPILE"
      // if it has not already been added to the module.
      for (String entry : classpathEntriesToDex) {
        String libraryName = getIntellijNameForBinaryJar(entry);
        DependentModule dependency = DependentModule.newLibrary(null, libraryName);
        module.dependencies.add(dependency);
      }
    }
  }

  /**
   * Walks the dependencies of a build rule and adds the appropriate DependentModules to the
   * specified dependencies collection. All library dependencies will be added before any module
   * dependencies. See {@link ProjectTest#testThatJarsAreListedBeforeModules()} for details on why
   * this behavior is important.
   */
  private void walkRuleAndAdd(
      final BuildRule rule,
      final boolean isForTests,
      final LinkedHashSet<DependentModule> dependencies,
      @Nullable final BuildRule srcTarget) {

    final String basePathForRule = rule.getBuildTarget().getBasePath();
    new AbstractDependencyVisitor(rule, true /* excludeRoot */) {

      private final LinkedHashSet<DependentModule> librariesToAdd = Sets.newLinkedHashSet();
      private final LinkedHashSet<DependentModule> modulesToAdd = Sets.newLinkedHashSet();

      @Override
      public ImmutableSet<BuildRule> visit(BuildRule dep) {
        ImmutableSet<BuildRule> depsToVisit;
        if (rule.getProperties().is(PACKAGING) ||
            dep instanceof AndroidResourceRule ||
            dep == rule) {
          depsToVisit = dep.getDeps();
        } else if (dep.getProperties().is(LIBRARY) && dep instanceof ExportDependencies) {
          depsToVisit = ((ExportDependencies)dep).getExportedDeps();
        } else {
          depsToVisit = ImmutableSet.of();
        }

        // Special Case: If we are traversing the test_target and we encounter a library rule in the
        // same package that is not the src_target, then we should traverse the deps. Consider the
        // following build file:
        //
        // android_library(
        //   name = 'lib',
        //   srcs = glob(['*.java'], excludes = ['*Test.java']),
        //   deps = [
        //     # LOTS OF DEPS
        //   ],
        // )
        //
        // java_test(
        //   name = 'test',
        //   srcs = glob(['*Test.java']),
        //   deps = [
        //     ':lib',
        //     # MOAR DEPS
        //   ],
        // )
        //
        // project_config(
        //   test_target = ':test',
        // )
        //
        // Note that the only source folder for this IntelliJ module is the current directory. Thus,
        // the current directory should be treated as a source folder with test sources, but it
        // should contain the union of :lib and :test's deps as dependent modules.
        if (isForTests
            && depsToVisit.isEmpty()
            && dep.getBuildTarget().getBasePath().equals(basePathForRule)
            && !dep.equals(srcTarget)) {
          depsToVisit = dep.getDeps();
        }

        DependentModule dependentModule;
        if (dep instanceof PrebuiltJarRule) {
          libraryJars.add((PrebuiltJarRule) dep);
          String libraryName = getIntellijNameForRule(dep);
          dependentModule = DependentModule.newLibrary(dep.getBuildTarget(), libraryName);
        } else if (dep instanceof NdkLibrary) {
          String moduleName = getIntellijNameForRule(dep);
          dependentModule = DependentModule.newModule(dep.getBuildTarget(), moduleName);
        } else if (dep.getFullyQualifiedName().startsWith(ANDROID_GEN_BUILD_TARGET_PREFIX)) {
          return depsToVisit;
        } else if (dep instanceof JavaLibraryRule || dep instanceof AndroidResourceRule) {
          String moduleName = getIntellijNameForRule(dep);
          dependentModule = DependentModule.newModule(dep.getBuildTarget(), moduleName);
        } else {
          return depsToVisit;
        }

        if (isForTests) {
          dependentModule.scope = "TEST";
        } else {
          // If the dependentModule has already been added in the "TEST" scope, then it should be
          // removed and then re-added using the current (compile) scope.
          String currentScope = dependentModule.scope;
          dependentModule.scope = "TEST";
          if (dependencies.contains(dependentModule)) {
            dependencies.remove(dependentModule);
          }
          dependentModule.scope = currentScope;
        }

        // Slate the module for addition to the dependencies collection. Modules are added to
        // dependencies collection once the traversal is complete in the onComplete() method.
        if (dependentModule.isLibrary()) {
          librariesToAdd.add(dependentModule);
        } else {
          modulesToAdd.add(dependentModule);
        }

        return depsToVisit;
      }

      @Override
      protected void onComplete() {
        dependencies.addAll(librariesToAdd);
        dependencies.addAll(modulesToAdd);
      }
    }.start();
  }

  /**
   * Maps a BuildRule to the name of the equivalent IntelliJ library or module.
   */
  private String getIntellijNameForRule(BuildRule rule) {
    return Project.getIntellijNameForRule(rule, basePathToAliasMap);
  }

  /**
   * @param rule whose corresponding IntelliJ module name will be returned
   * @param basePathToAliasMap may be null if rule is a {@link PrebuiltJarRule}
   */
  private static String getIntellijNameForRule(BuildRule rule,
      @Nullable Map<String, String> basePathToAliasMap) {
    // Get basis for the library/module name.
    String name;
    if (rule instanceof PrebuiltJarRule) {
      PrebuiltJarRule prebuiltJarRule = (PrebuiltJarRule)rule;
      String binaryJar = prebuiltJarRule.getBinaryJar();
      return getIntellijNameForBinaryJar(binaryJar);
    } else {
      String basePath = rule.getBuildTarget().getBasePath();
      if (basePathToAliasMap.containsKey(basePath)) {
        name = basePathToAliasMap.get(basePath);
      } else {
        name = rule.getBuildTarget().getBasePath();
        name = name.replace('/', '_');
        // Must add a prefix to ensure that name is non-empty.
        name = "module_" + name;
      }
      // Normalize name.
      return normalizeIntelliJName(name);
    }
  }

  private static String getIntellijNameForBinaryJar(String binaryJar) {
    String name = binaryJar.replace('/', '_');
    return normalizeIntelliJName(name);
  }

  private static String normalizeIntelliJName(String name) {
    return name.replace('.', '_').replace('-', '_');
  }

  /**
   * @param pathRelativeToProjectRoot if {@code null}, then this method returns {@code null}
   * @param target
   */
  private static String createRelativePath(@Nullable String pathRelativeToProjectRoot,
      BuildTarget target) {
    if (pathRelativeToProjectRoot == null) {
      return null;
    }
    String directoryPath = target.getBasePath();
    Preconditions.checkArgument(pathRelativeToProjectRoot.startsWith(directoryPath));
    return pathRelativeToProjectRoot.substring(directoryPath.length());
  }

  private void writeJsonConfig(File jsonTempFile, List<Module> modules) throws IOException {
    List<SerializablePrebuiltJarRule> libraries = Lists.newArrayListWithCapacity(
        libraryJars.size());
    for (PrebuiltJarRule libraryJar : libraryJars) {
      libraries.add(new SerializablePrebuiltJarRule(libraryJar));
    }

    Map<String, Object> config = ImmutableMap.<String, Object>of(
        "modules", modules,
        "libraries", libraries);

    // Write out the JSON config to be consumed by the Python.
    Writer writer = new FileWriter(jsonTempFile);
    JsonFactory jsonFactory = new JsonFactory();
    ObjectMapper objectMapper = new ObjectMapper(jsonFactory);
    if (executionContext.getVerbosity().shouldPrintOutput()) {
      ObjectWriter objectWriter = objectMapper.writerWithDefaultPrettyPrinter();
      objectWriter.writeValue(writer, config);
    } else {
      objectMapper.writeValue(writer, config);
    }
  }

  private ExitCodeAndOutput processJsonConfig(File jsonTempFile) throws IOException {
    final ImmutableList<String> args = ImmutableList.of(
        pythonInterpreter, PATH_TO_INTELLIJ_PY, jsonTempFile.getAbsolutePath());

    ShellStep command = new ShellStep() {

      @Override
      public String getShortName() {
        return "python";
      }

      @Override
      protected ImmutableList<String> getShellCommandInternal(
          ExecutionContext context) {
        return args;
      }
    };

    Console console = executionContext.getConsole();
    Console childConsole = new Console(
        Verbosity.SILENT,
        console.getStdOut(),
        console.getStdErr(),
        Ansi.withoutTty());
    ExecutionContext childContext = ExecutionContext.builder()
        .setExecutionContext(executionContext)
        .setConsole(childConsole)
        .build();
    int exitCode = command.execute(childContext);
    return new ExitCodeAndOutput(exitCode, command.getStdout(), command.getStderr());
  }

  private static class ExitCodeAndOutput {
    private final int exitCode;
    private final String stdOut;
    private final String stdErr;
    ExitCodeAndOutput(int exitCode, String stdOut, String stdErr) {
      this.exitCode = exitCode;
      this.stdOut = Preconditions.checkNotNull(stdOut);
      this.stdErr = Preconditions.checkNotNull(stdErr);
    }
  }

  @JsonInclude(Include.NON_NULL)
  @VisibleForTesting
  static class SourceFolder {
    @JsonProperty
    private final String url;

    @JsonProperty
    private final boolean isTestSource;

    @JsonProperty
    @Nullable
    private final String packagePrefix;

    static final SourceFolder SRC = new SourceFolder("file://$MODULE_DIR$/src", false);
    static final SourceFolder TESTS = new SourceFolder("file://$MODULE_DIR$/tests", true);
    static final SourceFolder GEN = new SourceFolder("file://$MODULE_DIR$/gen", false);

    SourceFolder(String url, boolean isTestSource) {
      this(url, isTestSource, null /* packagePrefix */);
    }

    SourceFolder(String url, boolean isTestSource, @Nullable String packagePrefix) {
      this.url = url;
      this.isTestSource = isTestSource;
      this.packagePrefix = packagePrefix;
    }

    String getUrl() {
      return url;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof SourceFolder)) {
        return false;
      }
      SourceFolder that = (SourceFolder)obj;
      return Objects.equal(this.url, that.url)
          && Objects.equal(this.isTestSource, that.isTestSource)
          && Objects.equal(this.packagePrefix, that.packagePrefix);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(url, isTestSource, packagePrefix);
    }

    @Override
    public String toString() {
      return Objects.toStringHelper(SourceFolder.class)
          .add("url", url)
          .add("isTestSource", isTestSource)
          .add("packagePrefix", packagePrefix)
          .toString();
    }
  }

  @JsonInclude(Include.NON_NULL)
  @VisibleForTesting
  static class SerializablePrebuiltJarRule {
    @JsonProperty private final String name;
    @JsonProperty private final String binaryJar;
    @JsonProperty private final String sourceJar;
    @JsonProperty private final String javadocUrl;

    private SerializablePrebuiltJarRule(PrebuiltJarRule rule) {
      this.name = getIntellijNameForRule(rule, null /* basePathToAliasMap */);
      this.binaryJar = rule.getBinaryJar();
      this.sourceJar = rule.getSourceJar().orNull();
      this.javadocUrl = rule.getJavadocUrl().orNull();
    }

    @Override
    public String toString() {
      return Objects.toStringHelper(SerializablePrebuiltJarRule.class)
          .add("name", name)
          .add("binaryJar", binaryJar)
          .add("sourceJar", sourceJar)
          .add("javadocUrl", javadocUrl)
          .toString();
    }
  }

}
