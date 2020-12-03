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

package com.facebook.buck.jvm.kotlin;

import static com.facebook.buck.jvm.java.JavaPaths.SRC_ZIP;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.BaseBuckPaths;
import com.facebook.buck.io.filesystem.CopySourceMode;
import com.facebook.buck.io.filesystem.FileExtensionMatcher;
import com.facebook.buck.io.filesystem.GlobPatternMatcher;
import com.facebook.buck.io.filesystem.PathMatcher;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.java.BuildContextAwareExtraParams;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.CompilerOutputPaths;
import com.facebook.buck.jvm.java.CompilerParameters;
import com.facebook.buck.jvm.java.ExtraClasspathProvider;
import com.facebook.buck.jvm.java.FilesystemParams;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacPluginJsr199Fields;
import com.facebook.buck.jvm.java.JavacPluginParams;
import com.facebook.buck.jvm.java.JavacToJarStepFactory;
import com.facebook.buck.jvm.java.ResolvedJavac;
import com.facebook.buck.jvm.java.ResolvedJavacPluginProperties;
import com.facebook.buck.jvm.kotlin.KotlinLibraryDescription.AnnotationProcessingTool;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.CopyIsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.MakeCleanDirectoryIsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.ZipIsolatedStep;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.util.zip.ZipCompressionLevel;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Factory that creates Kotlin related compile build steps. */
public class KotlincToJarStepFactory extends CompileToJarStepFactory<BuildContextAwareExtraParams> {

  private static final String PLUGIN = "-P";
  private static final String APT_MODE = "aptMode=";
  private static final String X_PLUGIN_ARG = "-Xplugin=";
  private static final String KAPT3_PLUGIN = "plugin:org.jetbrains.kotlin.kapt3:";
  private static final String AP_CLASSPATH_ARG = KAPT3_PLUGIN + "apclasspath=";
  private static final String AP_PROCESSORS_ARG = KAPT3_PLUGIN + "processors=";
  // output path for generated sources;
  private static final String SOURCES_ARG = KAPT3_PLUGIN + "sources=";
  private static final String CLASSES_ARG = KAPT3_PLUGIN + "classes=";
  // output path for java stubs;
  private static final String STUBS_ARG = KAPT3_PLUGIN + "stubs=";
  private static final String LIGHT_ANALYSIS = KAPT3_PLUGIN + "useLightAnalysis=";
  private static final String CORRECT_ERROR_TYPES = KAPT3_PLUGIN + "correctErrorTypes=";
  private static final String JAVAC_ARG = KAPT3_PLUGIN + "javacArguments=";
  private static final String AP_OPTIONS = KAPT3_PLUGIN + "apoptions=";
  private static final String KAPT_GENERATED = "kapt.kotlin.generated";
  private static final String MODULE_NAME = "-module-name";
  private static final String NO_STDLIB = "-no-stdlib";
  private static final String NO_REFLECT = "-no-reflect";
  private static final String VERBOSE = "-verbose";

  private static final PathMatcher KOTLIN_PATH_MATCHER = FileExtensionMatcher.of("kt");
  private static final PathMatcher SRC_ZIP_MATCHER = GlobPatternMatcher.of("**.src.zip");

  @AddToRuleKey private final JavacOptions javacOptions;
  @AddToRuleKey private final Kotlinc kotlinc;
  @AddToRuleKey private final ImmutableList<String> extraKotlincArguments;
  @AddToRuleKey private final SourcePath standardLibraryClasspath;
  @AddToRuleKey private final SourcePath annotationProcessingClassPath;

  @AddToRuleKey
  private final ImmutableMap<SourcePath, ImmutableMap<String, String>> kotlinCompilerPlugins;

  @AddToRuleKey private final ImmutableList<SourcePath> friendPaths;
  @AddToRuleKey private final AnnotationProcessingTool annotationProcessingTool;
  @AddToRuleKey private final Optional<String> jvmTarget;
  @AddToRuleKey private final ExtraClasspathProvider extraClasspathProvider;

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> kotlinHomeLibraries;

  KotlincToJarStepFactory(
      Kotlinc kotlinc,
      ImmutableSortedSet<SourcePath> kotlinHomeLibraries,
      SourcePath standardLibraryClasspath,
      SourcePath annotationProcessingClassPath,
      ImmutableList<String> extraKotlincArguments,
      ImmutableMap<SourcePath, ImmutableMap<String, String>> kotlinCompilerPlugins,
      ImmutableList<SourcePath> friendPaths,
      AnnotationProcessingTool annotationProcessingTool,
      Optional<String> jvmTarget,
      ExtraClasspathProvider extraClasspathProvider,
      JavacOptions javacOptions,
      boolean withDownwardApi) {
    super(CompileToJarStepFactory.hasAnnotationProcessing(javacOptions), withDownwardApi);
    this.javacOptions = javacOptions;
    this.kotlinc = kotlinc;
    this.kotlinHomeLibraries = kotlinHomeLibraries;
    this.standardLibraryClasspath = standardLibraryClasspath;
    this.annotationProcessingClassPath = annotationProcessingClassPath;
    this.extraKotlincArguments = extraKotlincArguments;
    this.kotlinCompilerPlugins = kotlinCompilerPlugins;
    this.friendPaths = friendPaths;
    this.annotationProcessingTool = annotationProcessingTool;
    this.jvmTarget = jvmTarget;
    this.extraClasspathProvider = extraClasspathProvider;
  }

  @Override
  public void createCompileStep(
      FilesystemParams filesystemParams,
      ImmutableMap<String, RelPath> cellToPathMappings,
      BuildTargetValue invokingRule,
      CompilerParameters parameters,
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      ResolvedJavac resolvedJavac,
      BuildContextAwareExtraParams extraParams) {

    AbsPath rootPath = filesystemParams.getRootPath();
    BaseBuckPaths buckPaths = filesystemParams.getBaseBuckPaths();
    ImmutableSet<PathMatcher> ignoredPaths = filesystemParams.getIgnoredPaths();

    BuildContext buildContext = extraParams.getBuildContext();
    SourcePathResolverAdapter resolver = buildContext.getSourcePathResolver();

    ImmutableSortedSet<Path> declaredClasspathEntries = parameters.getClasspathEntries();
    ImmutableSortedSet<Path> sourceFilePaths = parameters.getSourceFilePaths();
    RelPath outputDirectory = parameters.getOutputPaths().getClassesDir();
    Path pathToSrcsList = parameters.getOutputPaths().getPathToSourcesList();

    boolean generatingCode = !javacOptions.getJavaAnnotationProcessorParams().isEmpty();
    boolean hasKotlinSources =
        sourceFilePaths.stream().anyMatch(KOTLIN_PATH_MATCHER::matches)
            || sourceFilePaths.stream().anyMatch(SRC_ZIP_MATCHER::matches);

    ImmutableSortedSet.Builder<Path> sourceBuilder =
        ImmutableSortedSet.<Path>naturalOrder().addAll(sourceFilePaths);

    // Only invoke kotlinc if we have kotlin or src zip files.
    if (hasKotlinSources) {
      RelPath stubsOutput =
          CompilerOutputPaths.getAnnotationPath(buckPaths, invokingRule, "__%s_stubs__");
      RelPath sourcesOutput =
          CompilerOutputPaths.getAnnotationPath(buckPaths, invokingRule, "__%s_sources__");
      RelPath classesOutput =
          CompilerOutputPaths.getAnnotationPath(buckPaths, invokingRule, "__%s_classes__");
      RelPath kaptGeneratedOutput =
          CompilerOutputPaths.getAnnotationPath(buckPaths, invokingRule, "__%s_kapt_generated__");
      RelPath kotlincPluginGeneratedOutput =
          CompilerOutputPaths.getAnnotationPath(
              buckPaths, invokingRule, "__%s_kotlinc_plugin_generated__");
      RelPath annotationGenFolder = getKaptAnnotationGenPath(buckPaths, invokingRule);
      RelPath genOutputFolder =
          CompilerOutputPaths.getGenPath(buckPaths, invokingRule, "__%s_gen_sources__");
      RelPath genOutput =
          CompilerOutputPaths.getGenPath(
              buckPaths, invokingRule, "__%s_gen_sources__/generated" + SRC_ZIP);

      // Javac requires that the root directory for generated sources already exist.
      steps.addAll(MakeCleanDirectoryIsolatedStep.of(stubsOutput));
      steps.addAll(MakeCleanDirectoryIsolatedStep.of(classesOutput));
      steps.addAll(MakeCleanDirectoryIsolatedStep.of(kaptGeneratedOutput));
      steps.addAll(MakeCleanDirectoryIsolatedStep.of(kotlincPluginGeneratedOutput));
      steps.addAll(MakeCleanDirectoryIsolatedStep.of(sourcesOutput));
      steps.addAll(MakeCleanDirectoryIsolatedStep.of(annotationGenFolder));
      steps.addAll(MakeCleanDirectoryIsolatedStep.of(genOutputFolder));

      ImmutableSortedSet<Path> allClasspaths =
          ImmutableSortedSet.<Path>naturalOrder()
              .addAll(
                  RichStream.from(extraClasspathProvider.getExtraClasspath())
                      .map(AbsPath::getPath)
                      .iterator())
              .addAll(declaredClasspathEntries)
              .addAll(
                  RichStream.from(kotlinHomeLibraries)
                      .map(x -> resolver.getAbsolutePath(x).getPath())
                      .iterator())
              .build();

      String friendPathsArg = getFriendsPath(resolver, friendPaths);
      String moduleName = getModuleName(invokingRule);

      ImmutableList.Builder<String> annotationProcessingOptionsBuilder = ImmutableList.builder();
      Builder<IsolatedStep> postKotlinCompilationSteps = ImmutableList.builder();

      if (generatingCode && annotationProcessingTool.equals(AnnotationProcessingTool.KAPT)) {
        ImmutableList<String> annotationProcessors =
            ImmutableList.copyOf(
                javacOptions.getJavaAnnotationProcessorParams().getPluginProperties().stream()
                    .map(ResolvedJavacPluginProperties::getProcessorNames)
                    .flatMap(Set::stream)
                    .map(name -> AP_PROCESSORS_ARG + name)
                    .collect(Collectors.toList()));

        ImmutableList<String> apClassPaths =
            ImmutableList.copyOf(
                javacOptions.getJavaAnnotationProcessorParams().getPluginProperties().stream()
                    .map(p -> p.getJavacPluginJsr199Fields(rootPath))
                    .map(JavacPluginJsr199Fields::getClasspath)
                    .flatMap(List::stream)
                    .map(url -> AP_CLASSPATH_ARG + urlToFile(url))
                    .collect(Collectors.toList()));

        ImmutableMap.Builder<String, String> apOptions = new ImmutableMap.Builder<>();
        ImmutableSortedSet<String> javacAnnotationProcessorParams =
            javacOptions.getJavaAnnotationProcessorParams().getParameters();
        for (String param : javacAnnotationProcessorParams) {
          String[] splitParam = param.split("=");
          Preconditions.checkState(splitParam.length == 2);
          apOptions.put(splitParam[0], splitParam[1]);
        }

        SourcePathResolverAdapter sourcePathResolver = buildContext.getSourcePathResolver();
        Path annotationProcessorPath =
            sourcePathResolver.getAbsolutePath(annotationProcessingClassPath).getPath();
        Path standardLibraryPath =
            sourcePathResolver.getAbsolutePath(standardLibraryClasspath).getPath();

        ImmutableList<String> kaptPluginOptions =
            ImmutableList.<String>builder()
                .add(AP_CLASSPATH_ARG + annotationProcessorPath)
                .add(AP_CLASSPATH_ARG + standardLibraryPath)
                .addAll(apClassPaths)
                .addAll(annotationProcessors)
                .add(SOURCES_ARG + rootPath.resolve(sourcesOutput))
                .add(CLASSES_ARG + rootPath.resolve(classesOutput))
                .add(STUBS_ARG + rootPath.resolve(stubsOutput))
                .add(
                    AP_OPTIONS
                        + encodeKaptApOptions(
                            apOptions.build(), rootPath.resolve(kaptGeneratedOutput).toString()))
                .add(JAVAC_ARG + encodeOptions(Collections.emptyMap()))
                .add(LIGHT_ANALYSIS + "true") // TODO: Provide value as argument
                .add(CORRECT_ERROR_TYPES + "true")
                .build();

        annotationProcessingOptionsBuilder
            .add(X_PLUGIN_ARG + annotationProcessorPath)
            .add(PLUGIN)
            .add(KAPT3_PLUGIN + APT_MODE + "compile," + Joiner.on(",").join(kaptPluginOptions));

        postKotlinCompilationSteps.add(
            CopyIsolatedStep.forDirectory(
                sourcesOutput, annotationGenFolder, CopySourceMode.DIRECTORY_CONTENTS_ONLY));
        postKotlinCompilationSteps.add(
            CopyIsolatedStep.forDirectory(
                classesOutput, annotationGenFolder, CopySourceMode.DIRECTORY_CONTENTS_ONLY));
        postKotlinCompilationSteps.add(
            CopyIsolatedStep.forDirectory(
                kaptGeneratedOutput, annotationGenFolder, CopySourceMode.DIRECTORY_CONTENTS_ONLY));

        postKotlinCompilationSteps.add(
            ZipIsolatedStep.of(
                rootPath,
                genOutput.getPath(),
                ignoredPaths,
                ImmutableSet.of(),
                false,
                ZipCompressionLevel.DEFAULT,
                annotationGenFolder.getPath()));

        // Generated classes should be part of the output. This way generated files
        // such as META-INF dirs will also be added to the final jar.
        postKotlinCompilationSteps.add(
            CopyIsolatedStep.forDirectory(
                classesOutput.getPath(),
                outputDirectory.getPath(),
                CopySourceMode.DIRECTORY_CONTENTS_ONLY));

        sourceBuilder.add(genOutput.getPath());
      }

      ImmutableList.Builder<String> extraArguments =
          ImmutableList.<String>builder()
              .add(friendPathsArg)
              .addAll(
                  getKotlinCompilerPluginsArgs(
                      resolver, rootPath.resolve(kotlincPluginGeneratedOutput).toString()))
              .addAll(annotationProcessingOptionsBuilder.build())
              .add(MODULE_NAME)
              .add(moduleName)
              .add(NO_STDLIB)
              .add(NO_REFLECT);

      jvmTarget.ifPresent(
          target -> {
            extraArguments.add("-jvm-target");
            extraArguments.add(target);
          });

      extraArguments.addAll(extraKotlincArguments);

      steps.add(
          new KotlincStep(
              invokingRule,
              outputDirectory.getPath(),
              sourceFilePaths,
              pathToSrcsList,
              allClasspaths,
              kotlinc,
              extraArguments.build(),
              ImmutableList.of(VERBOSE),
              Optional.of(parameters.getOutputPaths().getWorkingDirectory()),
              withDownwardApi));

      steps.addAll(postKotlinCompilationSteps.build());
    }

    final JavacOptions finalJavacOptions;

    switch (annotationProcessingTool) {
      case KAPT:
        // If kapt was never invoked then do annotation processing with javac.
        finalJavacOptions =
            hasKotlinSources
                ? javacOptions.withJavaAnnotationProcessorParams(JavacPluginParams.EMPTY)
                : javacOptions;
        break;

      case JAVAC:
        finalJavacOptions = javacOptions;
        break;

      default:
        throw new IllegalStateException(
            "Unexpected annotationProcessingTool " + annotationProcessingTool);
    }

    // Note that this filters out only .kt files, so this keeps both .java and .src.zip files.
    ImmutableSortedSet<Path> javaSourceFiles =
        ImmutableSortedSet.copyOf(
            sourceBuilder.build().stream()
                .filter(input -> !KOTLIN_PATH_MATCHER.matches(input))
                .collect(Collectors.toSet()));

    CompilerParameters javacParameters =
        CompilerParameters.builder()
            .from(parameters)
            .setClasspathEntries(
                ImmutableSortedSet.<Path>naturalOrder()
                    .add(rootPath.resolve(outputDirectory).getPath())
                    .addAll(
                        RichStream.from(extraClasspathProvider.getExtraClasspath())
                            .map(AbsPath::getPath)
                            .iterator())
                    .addAll(declaredClasspathEntries)
                    .build())
            .setSourceFilePaths(javaSourceFiles)
            .build();

    JavacToJarStepFactory javacToJarStepFactory =
        new JavacToJarStepFactory(finalJavacOptions, extraClasspathProvider, withDownwardApi);

    javacToJarStepFactory.createCompileStep(
        filesystemParams,
        cellToPathMappings,
        invokingRule,
        javacParameters,
        steps,
        buildableContext,
        resolvedJavac,
        javacToJarStepFactory.createExtraParams(resolver, rootPath));
  }

  /**
   * Safely converts a URL to a File path. Use this instead of {@link URL#getFile} to ensure that
   * htmlencoded literals are not present in the file path.
   */
  private static String urlToFile(URL url) {
    try {
      return Paths.get(url.toURI()).toFile().getPath();
    } catch (URISyntaxException e) {
      // In case of error, fall back to the original implementation.
      return url.getFile();
    }
  }

  @Override
  protected Optional<String> getBootClasspath() {
    return javacOptions.withBootclasspathFromContext(extraClasspathProvider).getBootclasspath();
  }

  private String encodeKaptApOptions(Map<String, String> kaptApOptions, String kaptGeneratedPath) {
    Map<String, String> kaptApOptionsToEncode = new HashMap<>();
    kaptApOptionsToEncode.put(KAPT_GENERATED, kaptGeneratedPath);
    kaptApOptionsToEncode.putAll(kaptApOptions);

    return encodeOptions(kaptApOptionsToEncode);
  }

  private String encodeOptions(Map<String, String> options) {
    try (ByteArrayOutputStream os = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(os)) {

      oos.writeInt(options.size());
      for (Map.Entry<String, String> entry : options.entrySet()) {
        oos.writeUTF(entry.getKey());
        oos.writeUTF(entry.getValue());
      }

      oos.flush();
      return Base64.getEncoder().encodeToString(os.toByteArray());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private String getFriendsPath(
      SourcePathResolverAdapter sourcePathResolverAdapter,
      ImmutableList<SourcePath> friendPathsSourcePaths) {
    if (friendPathsSourcePaths.isEmpty()) {
      return "";
    }

    // https://youtrack.jetbrains.com/issue/KT-29933
    ImmutableSortedSet<String> absoluteFriendPaths =
        friendPathsSourcePaths.stream()
            .map(path -> sourcePathResolverAdapter.getAbsolutePath(path).toString())
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));

    return "-Xfriend-paths="
        + absoluteFriendPaths.stream().reduce("", (path1, path2) -> path1 + "," + path2);
  }

  private ImmutableList<String> getKotlinCompilerPluginsArgs(
      SourcePathResolverAdapter sourcePathResolverAdapter, String outputDir) {
    ImmutableList.Builder<String> pluginArgs = ImmutableList.builder();
    for (SourcePath pluginPath : kotlinCompilerPlugins.keySet()) {
      // Add plugin basic string, e.g. "-Xplugins=<pluginPath>"
      pluginArgs.add(getKotlincPluginBasicString(sourcePathResolverAdapter, pluginPath));

      // If plugin options exist, add plugin option string,
      // e.g. "-P" and "<optionKey>=<optionValue>,<optionKey2>=<optionValue2>,..."
      ImmutableMap<String, String> pluginOptions = kotlinCompilerPlugins.get(pluginPath);
      if (pluginOptions != null && !pluginOptions.isEmpty()) {
        ImmutableList.Builder<String> pluginOptionStrings = ImmutableList.builder();
        for (String pluginOptionKey : pluginOptions.keySet()) {
          String pluginOptionValue = pluginOptions.get(pluginOptionKey);

          // When value is "_codegen_dir_", it means it's asking buck to provide kotlin compiler
          // plugin output dir
          if (pluginOptionValue.equals("__codegen_dir__")) {
            pluginOptionValue = outputDir;
          }

          pluginOptionStrings.add(pluginOptionKey + "=" + pluginOptionValue);
        }
        String pluginOptionString = Joiner.on(",").join(pluginOptionStrings.build());
        pluginArgs.add(PLUGIN).add(pluginOptionString);
      }
    }
    return pluginArgs.build();
  }

  /**
   * Ideally, we would not use getAbsolutePath() here, but getRelativePath() does not appear to work
   * correctly if path is a BuildTargetSourcePath in a different cell than the kotlin_library() rule
   * being defined.
   */
  private String getKotlincPluginBasicString(
      SourcePathResolverAdapter sourcePathResolverAdapter, SourcePath path) {
    return X_PLUGIN_ARG + sourcePathResolverAdapter.getAbsolutePath(path).toString();
  }

  private String getModuleName(BuildTargetValue invokingRule) {
    return invokingRule.getCellRelativeBasePath().toString().replace('/', '.')
        + "."
        + invokingRule.getShortName();
  }

  public static RelPath getKaptAnnotationGenPath(BaseBuckPaths buckPaths, BuildTarget buildTarget) {
    return getKaptAnnotationGenPath(buckPaths, BuildTargetValue.of(buildTarget, buckPaths));
  }

  private static RelPath getKaptAnnotationGenPath(
      BaseBuckPaths buckPaths, BuildTargetValue buildTargetValue) {
    String format = buildTargetValue.isFlavored() ? "%s" : "%s__";
    RelPath genPath = CompilerOutputPaths.getGenPath(buckPaths, buildTargetValue, format);
    return genPath.resolveRel("__generated__");
  }
}
