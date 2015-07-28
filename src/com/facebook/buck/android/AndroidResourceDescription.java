/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.android.aapt.MiniAapt;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.Files;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

public class AndroidResourceDescription implements Description<AndroidResourceDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("android_resource");

  private static final ImmutableSet<String> NON_ASSET_FILENAMES = ImmutableSet.of(
      ".gitkeep",
      ".svn",
      ".git",
      ".ds_store",
      ".scc",
      "cvs",
      "thumbs.db",
      "picasa.ini");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      final BuildRuleResolver resolver,
      A args) {

    // Only allow android resource and library rules as dependencies.
    Optional<BuildRule> invalidDep = FluentIterable.from(params.getDeclaredDeps())
        .filter(
            Predicates.not(
                Predicates.or(
                    Predicates.instanceOf(AndroidResource.class),
                    Predicates.instanceOf(AndroidLibrary.class))))
        .first();
    if (invalidDep.isPresent()) {
      throw new HumanReadableException(
          params.getBuildTarget() + " (android_resource): dependency " +
              invalidDep.get().getBuildTarget() + " (" + invalidDep.get().getType() +
              ") is not of type android_resource or android_library.");
    }

    final SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    // We don't handle the resources parameter well in `AndroidResource` rules, as instead of
    // hashing the contents of the entire resources directory, we try to filter out anything that
    // doesn't look like a resource.  This means when our resources are supplied from another rule,
    // we have to resort to some hackery to make sure things work correctly.
    ImmutableSortedSet<Path> inputResFiles = ImmutableSortedSet.of();
    Optional<Supplier<Sha1HashCode>> additionalAbiKey = Optional.absent();
    if (args.res.isPresent()) {

      // If our resources are coming from a `PathSourcePath`, we collect only the inputs we care
      // about and pass those in separately, so that that `AndroidResource` rule knows to only hash
      // these into it's rule key.
      if (args.res.get() instanceof PathSourcePath) {
        PathSourcePath path = (PathSourcePath) args.res.get();
        inputResFiles = collectInputFiles(
            path.getFilesystem(),
            Optional.of(path.getRelativePath()));

      // Otherwise, if our resources are coming form a `BuildTargetSourcePath`, we can't inspect the
      // contents of the directory, so we can't populate the `resSrcs` set.  Instead, we have to use
      // the `additionalAbiKey` suppler to use when calculating the ABI rule key.  We just use the
      // providing rule's rule key for now -- eventually this will all get cleaned up and use
      // input-based rule keys.
      } else if (args.res.get() instanceof BuildTargetSourcePath) {
        final BuildTargetSourcePath path = (BuildTargetSourcePath) args.res.get();
        additionalAbiKey =
            Optional.<Supplier<Sha1HashCode>>of(
                new Supplier<Sha1HashCode>() {
                  @Override
                  public Sha1HashCode get() {
                    return Sha1HashCode.of(
                        pathResolver.getRule(path).get().getRuleKey().toString());
                  }
                });
      }
    }

    ProjectFilesystem filesystem = params.getProjectFilesystem();
    return new AndroidResource(
        // We only propagate other AndroidResource rule dependencies, as these are
        // the only deps which should control whether we need to re-run the aapt_package
        // step.
        params.copyWithDeps(
            Suppliers.ofInstance(AndroidResourceHelper.androidResOnly(params.getDeclaredDeps())),
            Suppliers.ofInstance(params.getExtraDeps())),
        pathResolver,
        resolver.getAllRules(args.deps.get()),
        args.res.orNull(),
        inputResFiles,
        args.rDotJavaPackage.orNull(),
        args.assets.transform(SourcePaths.toSourcePath(params.getProjectFilesystem())).orNull(),
        collectInputFiles(filesystem, args.assets),
        args.manifest.orNull(),
        args.hasWhitelistedStrings.or(false),
        additionalAbiKey);
  }

  @VisibleForTesting
  ImmutableSortedSet<Path> collectInputFiles(
      ProjectFilesystem filesystem,
      Optional<Path> inputDir) {
    if (!inputDir.isPresent()) {
      return ImmutableSortedSet.of();
    }
    final ImmutableSortedSet.Builder<Path> paths = ImmutableSortedSet.naturalOrder();

    // We ignore the same files that mini-aapt and aapt ignore.
    FileVisitor<Path> fileVisitor = new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult preVisitDirectory(
          Path dir,
          BasicFileAttributes attr) throws IOException {
        String dirName = dir.getFileName().toString();
        // Special case: directory starting with '_' as per aapt.
        if (dirName.charAt(0) == '_' || !isResource(dirName)) {
          return FileVisitResult.SKIP_SUBTREE;
        }
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attr) throws IOException {
        String filename = file.getFileName().toString();
        if (isResource(filename)) {
          paths.add(file);
        }
        return FileVisitResult.CONTINUE;
      }

      private boolean isResource(String fileOrDirName) {
        if (NON_ASSET_FILENAMES.contains(fileOrDirName.toLowerCase())) {
          return false;
        }
        if (fileOrDirName.charAt(fileOrDirName.length() - 1) == '~') {
          return false;
        }
        if (MiniAapt.IGNORED_FILE_EXTENSIONS.contains(Files.getFileExtension(fileOrDirName))) {
          return false;
        }
        return true;
      }
    };

    try {
      filesystem.walkRelativeFileTree(inputDir.get(), fileVisitor);
    } catch (IOException e) {
      throw new HumanReadableException(e, "Error traversing directory: %s.", inputDir.get());
    }
    return paths.build();
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public Optional<SourcePath> res;
    public Optional<Path> assets;
    public Optional<Boolean> hasWhitelistedStrings;
    @Hint(name = "package")
    public Optional<String> rDotJavaPackage;
    public Optional<SourcePath> manifest;

    public Optional<ImmutableSortedSet<BuildTarget>> deps;
  }
}
