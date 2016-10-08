// Copyright 2004-present Facebook. All Rights Reserved.

package com.facebook.buck.android;

import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.kotlin.KotlinBuckConfig;
import com.facebook.buck.jvm.kotlin.KotlincToJarStepFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.collect.ImmutableList;

public class KotlinAndroidLibraryCompiler extends AndroidLibraryCompiler {

  private final KotlinBuckConfig kotlinBuckConfig;

  public KotlinAndroidLibraryCompiler(KotlinBuckConfig kotlinBuckConfig) {
    super();
    this.kotlinBuckConfig = kotlinBuckConfig;
  }

  @Override
  public boolean trackClassUsage(JavacOptions javacOptions) {
    return false;
  }

  @Override
  public CompileToJarStepFactory compileToJar(
      AndroidLibraryDescription.Arg args, JavacOptions javacOptions, BuildRuleResolver resolver) {
    return new KotlincToJarStepFactory(
        kotlinBuckConfig.getKotlinCompiler().get(),
        ImmutableList.of(),
        ANDROID_CLASSPATH_FROM_CONTEXT);
  }

  @Override
  public Iterable<BuildRule> getExtraDeps(
      AndroidLibraryDescription.Arg args, BuildRuleResolver resolver) {
    return kotlinBuckConfig.getKotlinCompiler().get()
        .getDeps(new SourcePathResolver(resolver));
  }
}
