/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.ide.intellij;

import com.facebook.buck.android.AndroidBinaryDescription;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.android.RobolectricTestDescription;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.groovy.GroovyLibraryDescription;
import com.facebook.buck.jvm.groovy.GroovyTestDescription;
import com.facebook.buck.jvm.java.JavaBinaryDescription;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.kotlin.KotlinLibraryDescription;
import com.facebook.buck.jvm.kotlin.KotlinTestDescription;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class DefaultIjModuleFactory implements IjModuleFactory {

  private static final Logger LOG = Logger.get(DefaultIjModuleFactory.class);

  private final ProjectFilesystem projectFilesystem;
  private final Map<Class<? extends Description<?>>, IjModuleRule<?>> moduleRuleIndex =
      new HashMap<>();
  private final IjModuleFactoryResolver moduleFactoryResolver;
  private final IjProjectConfig projectConfig;

  /**
   * @param moduleFactoryResolver see {@link IjModuleFactoryResolver}.
   */
  public DefaultIjModuleFactory(
      ProjectFilesystem projectFilesystem,
      IjModuleFactoryResolver moduleFactoryResolver,
      IjProjectConfig projectConfig) {
    this.projectFilesystem = projectFilesystem;
    this.projectConfig = projectConfig;
    this.moduleFactoryResolver = moduleFactoryResolver;

    addToIndex(new AndroidBinaryModuleRule());
    addToIndex(new AndroidLibraryModuleRule());
    addToIndex(new AndroidResourceModuleRule());
    addToIndex(new CxxLibraryModuleRule());
    addToIndex(new JavaBinaryModuleRule());
    addToIndex(new JavaLibraryModuleRule());
    addToIndex(new JavaTestModuleRule());
    addToIndex(new RobolectricTestModuleRule());
    addToIndex(new GroovyLibraryModuleRule());
    addToIndex(new GroovyTestModuleRule());
    addToIndex(new KotlinLibraryModuleRule());
    addToIndex(new KotlinTestModuleRule());

    Preconditions.checkState(SupportedTargetTypeRegistry.areTargetTypesEqual(
        moduleRuleIndex.keySet()));
  }

  private void addToIndex(IjModuleRule<?> rule) {
    Preconditions.checkArgument(!moduleRuleIndex.containsKey(rule.getDescriptionClass()));
    Preconditions.checkArgument(SupportedTargetTypeRegistry.isTargetTypeSupported(
        rule.getDescriptionClass()));
    moduleRuleIndex.put(rule.getDescriptionClass(), rule);
  }

  @Override
  public IjModule createModule(
      Path moduleBasePath,
      ImmutableSet<TargetNode<?, ?>> targetNodes) {
    return createModuleUsingSortedTargetNodes(
        moduleBasePath,
        ImmutableSortedSet.copyOf(targetNodes));
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private IjModule createModuleUsingSortedTargetNodes(
      Path moduleBasePath,
      ImmutableSortedSet<TargetNode<?, ?>> targetNodes) {
    Preconditions.checkArgument(!targetNodes.isEmpty());

    ImmutableSet<BuildTarget> moduleBuildTargets = targetNodes.stream()
        .map(TargetNode::getBuildTarget)
        .collect(MoreCollectors.toImmutableSet());

    ModuleBuildContext context = new ModuleBuildContext(moduleBuildTargets);

    Set<Class<?>> seenTypes = new HashSet<>();
    for (TargetNode<?, ?> targetNode : targetNodes) {
      Class<?> nodeType = targetNode.getDescription().getClass();
      seenTypes.add(nodeType);
      IjModuleRule<?> rule = Preconditions.checkNotNull(moduleRuleIndex.get(nodeType));
      rule.apply((TargetNode) targetNode, context);
      context.setModuleType(rule.detectModuleType((TargetNode) targetNode));
    }

    if (seenTypes.size() > 1) {
      LOG.debug(
          "Multiple types at the same path. Path: %s, types: %s",
          moduleBasePath,
          seenTypes);
    }

    Optional<String> sourceLevel = getSourceLevel(targetNodes);

    if (context.isAndroidFacetBuilderPresent()) {
      context.getOrCreateAndroidFacetBuilder().setGeneratedSourcePath(
          createAndroidGenPath(moduleBasePath));
    }

    return IjModule.builder()
        .setModuleBasePath(moduleBasePath)
        .setTargets(targetNodes)
        .addAllFolders(context.getSourceFolders())
        .putAllDependencies(context.getDependencies())
        .setAndroidFacet(context.getAndroidFacet())
        .addAllExtraClassPathDependencies(context.getExtraClassPathDependencies())
        .addAllGeneratedSourceCodeFolders(context.getGeneratedSourceCodeFolders())
        .setLanguageLevel(sourceLevel)
        .setModuleType(context.getModuleType())
        .setMetaInfDirectory(context.getMetaInfDirectory())
        .build();
  }

  private Path createAndroidGenPath(Path moduleBasePath) {
    return Paths
        .get(IjAndroidHelper.getAndroidGenDir(projectFilesystem))
        .resolve(moduleBasePath)
        .resolve("gen");
  }

  private Optional<String> getSourceLevel(
      Iterable<TargetNode<?, ?>> targetNodes) {
    Optional<String> result = Optional.empty();
    for (TargetNode<?, ?> targetNode : targetNodes) {
      if (!(targetNode.getDescription() instanceof JavaLibraryDescription)) {
        continue;
      }

      JavacOptions defaultJavacOptions = projectConfig.getJavaBuckConfig().getDefaultJavacOptions();
      String defaultSourceLevel = defaultJavacOptions.getSourceLevel();
      String defaultTargetLevel = defaultJavacOptions.getTargetLevel();
      JavaLibraryDescription.Arg arg = (JavaLibraryDescription.Arg) targetNode.getConstructorArg();
      if (!defaultSourceLevel.equals(arg.source.orElse(defaultSourceLevel)) ||
          !defaultTargetLevel.equals(arg.target.orElse(defaultTargetLevel))) {
        result = arg.source;
      }
    }

    if (result.isPresent()) {
      result = Optional.of(JavaLanguageLevelHelper.normalizeSourceLevel(result.get()));
    }

    return result;
  }

  private class AndroidBinaryModuleRule
      extends AndroidModuleRule<AndroidBinaryDescription.Arg> {

    private AndroidBinaryModuleRule() {
      super(
          DefaultIjModuleFactory.this.projectFilesystem,
          DefaultIjModuleFactory.this.moduleFactoryResolver,
          DefaultIjModuleFactory.this.projectConfig,
          false);
    }

    @Override
    public Class<? extends Description<?>> getDescriptionClass() {
      return AndroidBinaryDescription.class;
    }

    @Override
    public void apply(
        TargetNode<AndroidBinaryDescription.Arg, ?> target,
        ModuleBuildContext context) {
      super.apply(target, context);
      context.addDeps(target.getBuildDeps(), DependencyType.PROD);

      IjModuleAndroidFacet.Builder androidFacetBuilder = context.getOrCreateAndroidFacetBuilder();
      androidFacetBuilder
          .setManifestPath(moduleFactoryResolver.getAndroidManifestPath(target))
          .setProguardConfigPath(moduleFactoryResolver.getProguardConfigPath(target));
    }

    @Override
    public IjModuleType detectModuleType(TargetNode<AndroidBinaryDescription.Arg, ?> targetNode) {
      return IjModuleType.ANDROID_MODULE;
    }
  }

  private class AndroidLibraryModuleRule
      extends AndroidModuleRule<AndroidLibraryDescription.Arg> {

    private AndroidLibraryModuleRule() {
      super(
          DefaultIjModuleFactory.this.projectFilesystem,
          DefaultIjModuleFactory.this.moduleFactoryResolver,
          DefaultIjModuleFactory.this.projectConfig,
          true);
    }

    @Override
    public Class<? extends Description<?>> getDescriptionClass() {
      return AndroidLibraryDescription.class;
    }

    @Override
    public void apply(TargetNode<AndroidLibraryDescription.Arg, ?> target,
        ModuleBuildContext context) {
      super.apply(target, context);
      addDepsAndSources(
          target,
          true /* wantsPackagePrefix */,
          context);
      JavaLibraryRuleHelper.addCompiledShadowIfNeeded(projectConfig, target, context);
      Optional<Path> dummyRDotJavaClassPath = moduleFactoryResolver.getDummyRDotJavaPath(target);
      if (dummyRDotJavaClassPath.isPresent()) {
        context.addExtraClassPathDependency(dummyRDotJavaClassPath.get());
      }

      IjModuleAndroidFacet.Builder builder = context.getOrCreateAndroidFacetBuilder();
      Optional<Path> manifestPath = moduleFactoryResolver.getLibraryAndroidManifestPath(target);
      if (manifestPath.isPresent()) {
        builder.setManifestPath(manifestPath.get());
      }
    }

    @Override
    public IjModuleType detectModuleType(TargetNode<AndroidLibraryDescription.Arg, ?> targetNode) {
      return IjModuleType.ANDROID_MODULE;
    }
  }

  private class AndroidResourceModuleRule
      extends AndroidModuleRule<AndroidResourceDescription.Arg> {

    private AndroidResourceModuleRule() {
      super(
          DefaultIjModuleFactory.this.projectFilesystem,
          DefaultIjModuleFactory.this.moduleFactoryResolver,
          DefaultIjModuleFactory.this.projectConfig,
          true);
    }

    @Override
    public Class<? extends Description<?>> getDescriptionClass() {
      return AndroidResourceDescription.class;
    }

    @Override
    public void apply(
        TargetNode<AndroidResourceDescription.Arg, ?> target,
        ModuleBuildContext context) {
      super.apply(target, context);

      IjModuleAndroidFacet.Builder androidFacetBuilder = context.getOrCreateAndroidFacetBuilder();

      Optional<Path> assets = moduleFactoryResolver.getAssetsPath(target);
      if (assets.isPresent()) {
        androidFacetBuilder.addAssetPaths(assets.get());
      }

      Optional<Path> resources = moduleFactoryResolver.getAndroidResourcePath(target);
      ImmutableSet<Path> resourceFolders;
      if (resources.isPresent()) {
        resourceFolders = ImmutableSet.of(resources.get());

        androidFacetBuilder.addAllResourcePaths(resourceFolders);

        List<String> excludedResourcePaths = projectConfig.getExcludedResourcePaths();

        for (Path resourceFolder : resourceFolders) {
          context.addSourceFolder(
              new AndroidResourceFolder(resourceFolder)
          );

          excludedResourcePaths
              .stream()
              .map((file) -> resourceFolder.resolve(file))
              .forEach((folder) -> context.addSourceFolder(new ExcludeFolder(folder)));
        }
      } else {
        resourceFolders = ImmutableSet.of();
      }

      androidFacetBuilder.setPackageName(target.getConstructorArg().rDotJavaPackage);

      Optional<Path> dummyRDotJavaClassPath = moduleFactoryResolver.getDummyRDotJavaPath(target);
      if (dummyRDotJavaClassPath.isPresent()) {
        context.addExtraClassPathDependency(dummyRDotJavaClassPath.get());
      }

      context.addDeps(resourceFolders, target.getBuildDeps(), DependencyType.PROD);
    }

    @Override
    public IjModuleType detectModuleType(TargetNode<AndroidResourceDescription.Arg, ?> targetNode) {
      return IjModuleType.ANDROID_RESOURCES_MODULE;
    }
  }

  private class CxxLibraryModuleRule extends BaseIjModuleRule<CxxLibraryDescription.Arg> {

    private CxxLibraryModuleRule() {
      super(
          DefaultIjModuleFactory.this.projectFilesystem,
          DefaultIjModuleFactory.this.moduleFactoryResolver,
          DefaultIjModuleFactory.this.projectConfig);
    }

    @Override
    public Class<? extends Description<?>> getDescriptionClass() {
      return CxxLibraryDescription.class;
    }

    @Override
    public void apply(TargetNode<CxxLibraryDescription.Arg, ?> target, ModuleBuildContext context) {
      addSourceFolders(
          SourceFolder.FACTORY,
          getSourceFoldersToInputsIndex(target.getInputs()),
          false /* wantsPackagePrefix */,
          context);
    }

    @Override
    public IjModuleType detectModuleType(TargetNode<CxxLibraryDescription.Arg, ?> targetNode) {
      return IjModuleType.UNKNOWN_MODULE;
    }
  }

  private class JavaBinaryModuleRule
      extends BaseIjModuleRule<JavaBinaryDescription.Args> {

    private JavaBinaryModuleRule() {
      super(
          DefaultIjModuleFactory.this.projectFilesystem,
          DefaultIjModuleFactory.this.moduleFactoryResolver,
          DefaultIjModuleFactory.this.projectConfig);
    }

    @Override
    public Class<? extends Description<?>> getDescriptionClass() {
      return JavaBinaryDescription.class;
    }

    @Override
    public void apply(
        TargetNode<JavaBinaryDescription.Args, ?> target,
        ModuleBuildContext context) {
      context.addDeps(target.getBuildDeps(), DependencyType.PROD);
      saveMetaInfDirectoryForIntellijPlugin(target, context);
    }

    private void saveMetaInfDirectoryForIntellijPlugin(
        TargetNode<JavaBinaryDescription.Args, ?> target,
        ModuleBuildContext context) {
      Set<String> intellijLibraries = projectConfig.getIntellijSdkTargets();
      for (BuildTarget dep : target.getBuildDeps()) {
        Optional<Path> metaInfDirectory = target.getConstructorArg().metaInfDirectory;
        if (metaInfDirectory.isPresent() &&
            intellijLibraries.contains(dep.getFullyQualifiedName())) {
          context.setMetaInfDirectory(metaInfDirectory.get());
          break;
        }
      }
    }

    @Override
    public IjModuleType detectModuleType(TargetNode<JavaBinaryDescription.Args, ?> targetNode) {
      Set<String> intellijLibraries = projectConfig.getIntellijSdkTargets();
      for (BuildTarget dep : targetNode.getBuildDeps()) {
        Optional<Path> metaInfDirectory = targetNode.getConstructorArg().metaInfDirectory;
        if (metaInfDirectory.isPresent() &&
            intellijLibraries.contains(dep.getFullyQualifiedName())) {
          return IjModuleType.INTELLIJ_PLUGIN_MODULE;
        }
      }
      return IjModuleType.JAVA_MODULE;
    }
  }

  private class JavaLibraryModuleRule extends BaseIjModuleRule<JavaLibraryDescription.Arg> {

    private JavaLibraryModuleRule() {
      super(
          DefaultIjModuleFactory.this.projectFilesystem,
          DefaultIjModuleFactory.this.moduleFactoryResolver,
          DefaultIjModuleFactory.this.projectConfig);
    }

    @Override
    public Class<? extends Description<?>> getDescriptionClass() {
      return JavaLibraryDescription.class;
    }

    @Override
    public void apply(
        TargetNode<JavaLibraryDescription.Arg, ?> target,
        ModuleBuildContext context) {
      addDepsAndSources(
          target,
          true /* wantsPackagePrefix */,
          context);
      JavaLibraryRuleHelper.addCompiledShadowIfNeeded(projectConfig, target, context);
    }

    @Override
    public IjModuleType detectModuleType(TargetNode<JavaLibraryDescription.Arg, ?> targetNode) {
      return IjModuleType.JAVA_MODULE;
    }
  }

  private class GroovyLibraryModuleRule extends BaseIjModuleRule<GroovyLibraryDescription.Arg> {

    private GroovyLibraryModuleRule() {
      super(
          DefaultIjModuleFactory.this.projectFilesystem,
          DefaultIjModuleFactory.this.moduleFactoryResolver,
          DefaultIjModuleFactory.this.projectConfig);
    }

    @Override
    public Class<? extends Description<?>> getDescriptionClass() {
      return GroovyLibraryDescription.class;
    }

    @Override
    public void apply(
        TargetNode<GroovyLibraryDescription.Arg, ?> target,
        ModuleBuildContext context) {
      addDepsAndSources(
          target,
          false /* wantsPackagePrefix */,
          context);
    }

    @Override
    public IjModuleType detectModuleType(TargetNode<GroovyLibraryDescription.Arg, ?> targetNode) {
      return IjModuleType.UNKNOWN_MODULE;
    }
  }

  private class GroovyTestModuleRule extends BaseIjModuleRule<GroovyTestDescription.Arg> {

    private GroovyTestModuleRule() {
      super(
          DefaultIjModuleFactory.this.projectFilesystem,
          DefaultIjModuleFactory.this.moduleFactoryResolver,
          DefaultIjModuleFactory.this.projectConfig);
    }

    @Override
    public Class<? extends Description<?>> getDescriptionClass() {
      return GroovyTestDescription.class;
    }

    @Override
    public void apply(
        TargetNode<GroovyTestDescription.Arg, ?> target,
        ModuleBuildContext context) {
      addDepsAndTestSources(
          target,
          false /* wantsPackagePrefix */,
          context);
    }

    @Override
    public IjModuleType detectModuleType(TargetNode<GroovyTestDescription.Arg, ?> targetNode) {
      return IjModuleType.UNKNOWN_MODULE;
    }
  }

  private class JavaTestModuleRule extends BaseIjModuleRule<JavaTestDescription.Arg> {

    private JavaTestModuleRule() {
      super(
          DefaultIjModuleFactory.this.projectFilesystem,
          DefaultIjModuleFactory.this.moduleFactoryResolver,
          DefaultIjModuleFactory.this.projectConfig);
    }

    @Override
    public Class<? extends Description<?>> getDescriptionClass() {
      return JavaTestDescription.class;
    }

    @Override
    public void apply(TargetNode<JavaTestDescription.Arg, ?> target, ModuleBuildContext context) {
      addDepsAndTestSources(
          target,
          true /* wantsPackagePrefix */,
          context);
      JavaLibraryRuleHelper.addCompiledShadowIfNeeded(projectConfig, target, context);
    }

    @Override
    public IjModuleType detectModuleType(TargetNode<JavaTestDescription.Arg, ?> targetNode) {
      return IjModuleType.JAVA_MODULE;
    }
  }

  private class KotlinLibraryModuleRule extends BaseIjModuleRule<KotlinLibraryDescription.Arg> {

    private KotlinLibraryModuleRule() {
      super(
          DefaultIjModuleFactory.this.projectFilesystem,
          DefaultIjModuleFactory.this.moduleFactoryResolver,
          DefaultIjModuleFactory.this.projectConfig);
    }

    @Override
    public Class<? extends Description<?>> getDescriptionClass() {
      return KotlinLibraryDescription.class;
    }

    @Override
    public void apply(
        TargetNode<KotlinLibraryDescription.Arg, ?> target,
        ModuleBuildContext context) {
      addDepsAndSources(
          target,
          false /* wantsPackagePrefix */,
          context);
    }

    @Override
    public IjModuleType detectModuleType(TargetNode<KotlinLibraryDescription.Arg, ?> targetNode) {
      return IjModuleType.UNKNOWN_MODULE;
    }
  }

  private class KotlinTestModuleRule extends BaseIjModuleRule<KotlinTestDescription.Arg> {

    private KotlinTestModuleRule() {
      super(
          DefaultIjModuleFactory.this.projectFilesystem,
          DefaultIjModuleFactory.this.moduleFactoryResolver,
          DefaultIjModuleFactory.this.projectConfig);
    }

    @Override
    public Class<? extends Description<?>> getDescriptionClass() {
      return KotlinTestDescription.class;
    }

    @Override
    public void apply(
        TargetNode<KotlinTestDescription.Arg, ?> target,
        ModuleBuildContext context) {
      addDepsAndTestSources(
          target,
          false /* wantsPackagePrefix */,
          context);
    }

    @Override
    public IjModuleType detectModuleType(TargetNode<KotlinTestDescription.Arg, ?> targetNode) {
      return IjModuleType.UNKNOWN_MODULE;
    }
  }

  private class RobolectricTestModuleRule
      extends AndroidModuleRule<RobolectricTestDescription.Arg> {

    protected RobolectricTestModuleRule() {
      super(
          DefaultIjModuleFactory.this.projectFilesystem,
          DefaultIjModuleFactory.this.moduleFactoryResolver,
          DefaultIjModuleFactory.this.projectConfig,
          true);
    }

    @Override
    public Class<? extends Description<?>> getDescriptionClass() {
      return RobolectricTestDescription.class;
    }

    @Override
    public void apply(
        TargetNode<RobolectricTestDescription.Arg, ?> target, ModuleBuildContext context) {
      super.apply(target, context);
      addDepsAndTestSources(
          target,
          true /* wantsPackagePrefix */,
          context);
      JavaLibraryRuleHelper.addCompiledShadowIfNeeded(projectConfig, target, context);
    }

    @Override
    public IjModuleType detectModuleType(TargetNode<RobolectricTestDescription.Arg, ?> targetNode) {
      return IjModuleType.ANDROID_MODULE;
    }
  }
}
