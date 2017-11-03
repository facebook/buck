// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.CompilationException;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.logging.Log;
import com.android.tools.r8.shaking.ProguardConfiguration.Builder;
import com.android.tools.r8.shaking.ProguardTypeMatcher.ClassOrType;
import com.android.tools.r8.shaking.ProguardTypeMatcher.MatchSpecificType;
import com.android.tools.r8.utils.DescriptorUtils;
import com.android.tools.r8.utils.InternalOptions.PackageObfuscationMode;
import com.android.tools.r8.utils.LongInterval;
import com.android.tools.r8.utils.StringDiagnostic;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

public class ProguardConfigurationParser {

  private final Builder configurationBuilder;

  private final DexItemFactory dexItemFactory;

  private final DiagnosticsHandler diagnosticsHandler;

  private static final List<String> IGNORED_SINGLE_ARG_OPTIONS = ImmutableList
      .of("protomapping",
          "target");
  private static final List<String> IGNORED_OPTIONAL_SINGLE_ARG_OPTIONS = ImmutableList
      .of("keepdirectories", "runtype", "laststageoutput");
  private static final List<String> IGNORED_FLAG_OPTIONS = ImmutableList
      .of("forceprocessing", "dontusemixedcaseclassnames",
          "dontpreverify", "experimentalshrinkunusedprotofields",
          "filterlibraryjarswithorginalprogramjars",
          "dontskipnonpubliclibraryclasses",
          "dontskipnonpubliclibraryclassmembers",
          "invokebasemethod");
  private static final List<String> IGNORED_CLASS_DESCRIPTOR_OPTIONS = ImmutableList
      .of("isclassnamestring",
          "whyarenotsimple");

  private static final List<String> WARNED_SINGLE_ARG_OPTIONS = ImmutableList
      .of("dontnote",
          "printconfiguration",
          // TODO -outjars (http://b/37137994) and -adaptresourcefilecontents (http://b/37139570)
          // should be reported as errors, not just as warnings!
          "outjars",
          "adaptresourcefilecontents");
  private static final List<String> WARNED_FLAG_OPTIONS = ImmutableList
      .of();

  // Those options are unsupported and are treated as compilation errors.
  // Just ignoring them would produce outputs incompatible with user expectations.
  private static final List<String> UNSUPPORTED_FLAG_OPTIONS = ImmutableList
      .of("skipnonpubliclibraryclasses");

  public ProguardConfigurationParser(
      DexItemFactory dexItemFactory, DiagnosticsHandler diagnosticsHandler) {
    this.dexItemFactory = dexItemFactory;
    configurationBuilder = ProguardConfiguration.builder(dexItemFactory);

    this.diagnosticsHandler = diagnosticsHandler;
  }

  public ProguardConfiguration.Builder getConfigurationBuilder() {
    return configurationBuilder;
  }

  public ProguardConfiguration getConfig()
      throws ProguardRuleParserException, CompilationException {
    if (configurationBuilder.isKeepParameterNames()
        && configurationBuilder.isObfuscating()) {
      // The flag -keepparameternames has only effect when minifying, so ignore it if we
      // are not.
      throw new ProguardRuleParserException("-keepparameternames is not supported");
    }

    return configurationBuilder.build();
  }

  public void parse(Path path) throws ProguardRuleParserException, IOException {
    parse(ImmutableList.of(new ProguardConfigurationSourceFile(path)));
  }

  public void parse(ProguardConfigurationSource source)
      throws ProguardRuleParserException, IOException {
    parse(ImmutableList.of(source));
  }

  public void parse(List<ProguardConfigurationSource> sources)
      throws ProguardRuleParserException, IOException {
    for (ProguardConfigurationSource source : sources) {
      new ProguardConfigurationSourceParser(source).parse();
    }
  }

  private void warnIgnoringOptions(String optionName) {
    diagnosticsHandler.warning(new StringDiagnostic("Ignoring option: -" + optionName));
  }

  private void warnOverridingOptions(String optionName, String victim) {
    diagnosticsHandler.warning(
        new StringDiagnostic("Option -" + optionName + " overrides -" + victim));
  }

  private class ProguardConfigurationSourceParser {
    private final String name;
    private final String contents;
    private int position = 0;
    private Path baseDirectory;

    ProguardConfigurationSourceParser(ProguardConfigurationSource source)
        throws IOException {
      contents = source.get();
      baseDirectory = source.getBaseDirectory();
      name = source.getName();
    }

    public void parse() throws ProguardRuleParserException {
      do {
        skipWhitespace();
      } while (parseOption());
    }

    private boolean parseOption()
        throws ProguardRuleParserException {
      if (eof()) {
        return false;
      }
      if (acceptArobaseInclude()) {
        return true;
      }
      expectChar('-');
      String option;
      if (Iterables.any(IGNORED_SINGLE_ARG_OPTIONS, this::skipOptionWithSingleArg)
          || Iterables.any(
              IGNORED_OPTIONAL_SINGLE_ARG_OPTIONS, this::skipOptionWithOptionalSingleArg)
          || Iterables.any(IGNORED_FLAG_OPTIONS, this::skipFlag)
          || Iterables.any(IGNORED_CLASS_DESCRIPTOR_OPTIONS, this::skipOptionWithClassSpec)
          || parseOptimizationOption()) {
        // Intentionally left empty.
      } else if (
          (option = Iterables.find(WARNED_SINGLE_ARG_OPTIONS,
              this::skipOptionWithSingleArg, null)) != null
              || (option = Iterables.find(WARNED_FLAG_OPTIONS, this::skipFlag, null)) != null) {
        warnIgnoringOptions(option);
      } else if (
          (option = Iterables.find(UNSUPPORTED_FLAG_OPTIONS, this::skipFlag, null)) != null) {
        throw parseError("Unsupported option: -" + option);
      } else if (acceptString("renamesourcefileattribute")) {
        skipWhitespace();
        if (isOptionalArgumentGiven()) {
          configurationBuilder.setRenameSourceFileAttribute(acceptString());
        } else {
          configurationBuilder.setRenameSourceFileAttribute("");
        }
      } else if (acceptString("keepattributes")) {
        parseKeepAttributes();
      } else if (acceptString("keeppackagenames")) {
        ProguardKeepPackageNamesRule rule = parseKeepPackageNamesRule();
        configurationBuilder.addRule(rule);
      } else if (acceptString("keepparameternames")) {
        configurationBuilder.setKeepParameterNames(true);
      } else if (acceptString("checkdiscard")) {
        ProguardCheckDiscardRule rule = parseCheckDiscardRule();
        configurationBuilder.addRule(rule);
      } else if (acceptString("keep")) {
        ProguardKeepRule rule = parseKeepRule();
        configurationBuilder.addRule(rule);
      } else if (acceptString("whyareyoukeeping")) {
        ProguardWhyAreYouKeepingRule rule = parseWhyAreYouKeepingRule();
        configurationBuilder.addRule(rule);
      } else if (acceptString("dontoptimize")) {
        configurationBuilder.setOptimizing(false);
      } else if (acceptString("optimizationpasses")) {
        skipWhitespace();
        Integer expectedOptimizationPasses = acceptInteger();
        if (expectedOptimizationPasses == null) {
          throw parseError("Missing n of \"-optimizationpasses n\"");
        }
        warnIgnoringOptions("optimizationpasses");
      } else if (acceptString("dontobfuscate")) {
        configurationBuilder.setObfuscating(false);
      } else if (acceptString("dontshrink")) {
        configurationBuilder.setShrinking(false);
      } else if (acceptString("printusage")) {
        configurationBuilder.setPrintUsage(true);
        skipWhitespace();
        if (isOptionalArgumentGiven()) {
          configurationBuilder.setPrintUsageFile(parseFileName());
        }
      } else if (acceptString("verbose")) {
        configurationBuilder.setVerbose(true);
      } else if (acceptString("ignorewarnings")) {
        configurationBuilder.setIgnoreWarnings(true);
      } else if (acceptString("dontwarn")) {
        if (isOptionalArgumentGiven()) {
          configurationBuilder.addDontWarnPattern(parseClassNames());
        } else {
          configurationBuilder.addDontWarnPattern(
              ProguardClassNameList.singletonList(ProguardTypeMatcher.defaultAllMatcher()));
        }
      } else if (acceptString("repackageclasses")) {
        if (configurationBuilder.getPackageObfuscationMode() == PackageObfuscationMode.FLATTEN) {
          warnOverridingOptions("repackageclasses", "flattenpackagehierarchy");
        }
        skipWhitespace();
        if (acceptChar('\'')) {
          configurationBuilder.setPackagePrefix(parsePackageNameOrEmptyString());
          expectChar('\'');
        } else {
          configurationBuilder.setPackagePrefix("");
        }
      } else if (acceptString("flattenpackagehierarchy")) {
        if (configurationBuilder.getPackageObfuscationMode() == PackageObfuscationMode.REPACKAGE) {
          warnOverridingOptions("repackageclasses", "flattenpackagehierarchy");
          skipWhitespace();
          if (isOptionalArgumentGiven()) {
            skipSingleArgument();
          }
        } else {
          skipWhitespace();
          if (acceptChar('\'')) {
            configurationBuilder.setFlattenPackagePrefix(parsePackageNameOrEmptyString());
            expectChar('\'');
          } else {
            configurationBuilder.setFlattenPackagePrefix("");
          }
        }
      } else if (acceptString("overloadaggressively")) {
        configurationBuilder.setOverloadAggressively(true);
      } else if (acceptString("allowaccessmodification")) {
        configurationBuilder.setAllowAccessModification(true);
      } else if (acceptString("printmapping")) {
        configurationBuilder.setPrintMapping(true);
        skipWhitespace();
        if (isOptionalArgumentGiven()) {
          configurationBuilder.setPrintMappingFile(parseFileName());
        }
      } else if (acceptString("applymapping")) {
        configurationBuilder.setApplyMappingFile(parseFileName());
      } else if (acceptString("assumenosideeffects")) {
        ProguardAssumeNoSideEffectRule rule = parseAssumeNoSideEffectsRule();
        configurationBuilder.addRule(rule);
      } else if (acceptString("assumevalues")) {
        ProguardAssumeValuesRule rule = parseAssumeValuesRule();
        configurationBuilder.addRule(rule);
      } else if (acceptString("include")) {
        skipWhitespace();
        parseInclude();
      } else if (acceptString("basedirectory")) {
        skipWhitespace();
        baseDirectory = parseFileName();
      } else if (acceptString("injars")) {
        configurationBuilder.addInjars(parseClassPath());
      } else if (acceptString("libraryjars")) {
        configurationBuilder.addLibraryJars(parseClassPath());
      } else if (acceptString("printseeds")) {
        configurationBuilder.setPrintSeeds(true);
        skipWhitespace();
        if (isOptionalArgumentGiven()) {
          configurationBuilder.setSeedFile(parseFileName());
        }
      } else if (acceptString("obfuscationdictionary")) {
        configurationBuilder.setObfuscationDictionary(parseFileName());
      } else if (acceptString("classobfuscationdictionary")) {
        configurationBuilder.setClassObfuscationDictionary(parseFileName());
      } else if (acceptString("packageobfuscationdictionary")) {
        configurationBuilder.setPackageObfuscationDictionary(parseFileName());
      } else if (acceptString("alwaysinline")) {
        ProguardAlwaysInlineRule rule = parseAlwaysInlineRule();
        configurationBuilder.addRule(rule);
      } else if (acceptString("useuniqueclassmembernames")) {
        configurationBuilder.setUseUniqueClassMemberNames(true);
      } else if (acceptString("adaptclassstrings")) {
        skipWhitespace();
        if (isOptionalArgumentGiven()) {
          configurationBuilder.addAdaptClassStringsPattern(parseClassNames());
        } else {
          configurationBuilder.addAdaptClassStringsPattern(
              ProguardClassNameList.singletonList(ProguardTypeMatcher.defaultAllMatcher()));
        }
      } else if (acceptString("identifiernamestring")) {
        configurationBuilder.addRule(parseIdentifierNameStringRule());
        // TODO(b/36799092): warn until it is fully implemented.
        warnIgnoringOptions("identifiernamestring");
      } else {
        throw parseError("Unknown option");
      }
      return true;
    }


    private void parseInclude() throws ProguardRuleParserException {
      Path included = parseFileName();
      try {
        new ProguardConfigurationSourceParser(new ProguardConfigurationSourceFile(included))
            .parse();
      } catch (FileNotFoundException | NoSuchFileException e) {
        throw parseError("Included file '" + included.toString() + "' not found", e);
      } catch (IOException e) {
        throw parseError("Failed to read included file '" + included.toString() + "'", e);
      }
    }

    private boolean acceptArobaseInclude() throws ProguardRuleParserException {
      if (remainingChars() < 2) {
        return false;
      }
      if (!acceptChar('@')) {
        return false;
      }
      parseInclude();
      return true;
    }

    private void parseKeepAttributes() throws ProguardRuleParserException {
      List<String> attributesPatterns = acceptPatternList();
      if (attributesPatterns.isEmpty()) {
        throw parseError("Expected attribute pattern list");
      }
      configurationBuilder.addKeepAttributePatterns(attributesPatterns);
    }

    private boolean skipFlag(String name) {
      if (acceptString(name)) {
        if (Log.ENABLED) {
          Log.debug(ProguardConfigurationParser.class, "Skipping '-%s` flag", name);
        }
        return true;
      }
      return false;
    }

    private boolean skipOptionWithSingleArg(String name) {
      if (acceptString(name)) {
        if (Log.ENABLED) {
          Log.debug(ProguardConfigurationParser.class, "Skipping '-%s` option", name);
        }
        skipSingleArgument();
        return true;
      }
      return false;
    }

    private boolean skipOptionWithOptionalSingleArg(String name) {
      if (acceptString(name)) {
        if (Log.ENABLED) {
          Log.debug(ProguardConfigurationParser.class, "Skipping '-%s` option", name);
        }
        skipWhitespace();
        if (isOptionalArgumentGiven()) {
          skipSingleArgument();
        }
        return true;
      }
      return false;
    }

    private boolean skipOptionWithClassSpec(String name) {
      if (acceptString(name)) {
        if (Log.ENABLED) {
          Log.debug(ProguardConfigurationParser.class, "Skipping '-%s` option", name);
        }
        try {
          ProguardKeepRule.Builder keepRuleBuilder = ProguardKeepRule.builder();
          parseClassSpec(keepRuleBuilder, true);
          return true;
        } catch (ProguardRuleParserException e) {
          System.out.println(e);
          return false;
        }
      }
      return false;

    }

    private boolean parseOptimizationOption() {
      if (!acceptString("optimizations")) {
        return false;
      }
      skipWhitespace();
      do {
        skipOptimizationName();
        skipWhitespace();
      } while (acceptChar(','));
      return true;
    }

    private void skipOptimizationName() {
      if (acceptChar('!')) {
        skipWhitespace();
      }
      for (char next = peekChar();
          Character.isAlphabetic(next) || next == '/' || next == '*';
          next = peekChar()) {
        readChar();
      }
    }

    private void skipSingleArgument() {
      skipWhitespace();
      while (!eof() && !Character.isWhitespace(peekChar())) {
        readChar();
      }
    }

    private ProguardKeepRule parseKeepRule()
        throws ProguardRuleParserException {
      ProguardKeepRule.Builder keepRuleBuilder = ProguardKeepRule.builder();
      parseRuleTypeAndModifiers(keepRuleBuilder);
      parseClassSpec(keepRuleBuilder, false);
      if (keepRuleBuilder.getMemberRules().isEmpty()) {
        // If there are no member rules, a default rule for the parameterless constructor
        // applies. So we add that here.
        ProguardMemberRule.Builder defaultRuleBuilder = ProguardMemberRule.builder();
        defaultRuleBuilder.setName(Constants.INSTANCE_INITIALIZER_NAME);
        defaultRuleBuilder.setRuleType(ProguardMemberType.INIT);
        defaultRuleBuilder.setArguments(Collections.emptyList());
        keepRuleBuilder.getMemberRules().add(defaultRuleBuilder.build());
      }
      return keepRuleBuilder.build();
    }

    private ProguardWhyAreYouKeepingRule parseWhyAreYouKeepingRule()
        throws ProguardRuleParserException {
      ProguardWhyAreYouKeepingRule.Builder keepRuleBuilder = ProguardWhyAreYouKeepingRule.builder();
      parseClassSpec(keepRuleBuilder, false);
      return keepRuleBuilder.build();
    }

    private ProguardKeepPackageNamesRule parseKeepPackageNamesRule()
        throws ProguardRuleParserException {
      ProguardKeepPackageNamesRule.Builder keepRuleBuilder = ProguardKeepPackageNamesRule.builder();
      keepRuleBuilder.setClassNames(parseClassNames());
      return keepRuleBuilder.build();
    }

    private ProguardCheckDiscardRule parseCheckDiscardRule()
        throws ProguardRuleParserException {
      ProguardCheckDiscardRule.Builder keepRuleBuilder = ProguardCheckDiscardRule.builder();
      parseClassSpec(keepRuleBuilder, false);
      return keepRuleBuilder.build();
    }

    private ProguardAlwaysInlineRule parseAlwaysInlineRule()
        throws ProguardRuleParserException {
      ProguardAlwaysInlineRule.Builder keepRuleBuilder = ProguardAlwaysInlineRule.builder();
      parseClassSpec(keepRuleBuilder, false);
      return keepRuleBuilder.build();
    }

    private ProguardIdentifierNameStringRule parseIdentifierNameStringRule()
        throws ProguardRuleParserException {
      ProguardIdentifierNameStringRule.Builder keepRuleBuilder =
          ProguardIdentifierNameStringRule.builder();
      parseClassSpec(keepRuleBuilder, false);
      return keepRuleBuilder.build();
    }

    private void parseClassSpec(
        ProguardConfigurationRule.Builder builder, boolean allowValueSpecification)
        throws ProguardRuleParserException {
      parseClassFlagsAndAnnotations(builder);
      builder.setClassType(parseClassType());
      builder.setClassNames(parseClassNames());
      parseInheritance(builder);
      parseMemberRules(builder, allowValueSpecification);
    }

    private void parseRuleTypeAndModifiers(ProguardKeepRule.Builder builder)
        throws ProguardRuleParserException {
      if (acceptString("names")) {
        builder.setType(ProguardKeepRuleType.KEEP);
        builder.getModifiersBuilder().allowsShrinking = true;
      } else if (acceptString("class")) {
        if (acceptString("members")) {
          builder.setType(ProguardKeepRuleType.KEEP_CLASS_MEMBERS);
        } else if (acceptString("eswithmembers")) {
          builder.setType(ProguardKeepRuleType.KEEP_CLASSES_WITH_MEMBERS);
        } else if (acceptString("membernames")) {
          builder.setType(ProguardKeepRuleType.KEEP_CLASS_MEMBERS);
          builder.getModifiersBuilder().allowsShrinking = true;
        } else if (acceptString("eswithmembernames")) {
          builder.setType(ProguardKeepRuleType.KEEP_CLASSES_WITH_MEMBERS);
          builder.getModifiersBuilder().allowsShrinking = true;
        } else {
          // The only path to here is through "-keep" followed by "class".
          unacceptString("-keepclass");
          throw parseError("Unknown option");
        }
      } else {
        builder.setType(ProguardKeepRuleType.KEEP);
      }
      parseRuleModifiers(builder);
    }

    private void parseRuleModifiers(ProguardKeepRule.Builder builder) {
      while (acceptChar(',')) {
        if (acceptString("allow")) {
          if (acceptString("shrinking")) {
            builder.getModifiersBuilder().allowsShrinking = true;
          } else if (acceptString("optimization")) {
            builder.getModifiersBuilder().allowsOptimization = true;
          } else if (acceptString("obfuscation")) {
            builder.getModifiersBuilder().allowsObfuscation = true;
          }
        } else if (acceptString("includedescriptorclasses")) {
          builder.getModifiersBuilder().includeDescriptorClasses = true;
        }
      }
    }

    private ProguardTypeMatcher parseAnnotation() throws ProguardRuleParserException {
      skipWhitespace();
      int startPosition = position;
      if (acceptChar('@')) {
        String className = parseClassName();
        if (className.equals("interface")) {
          // Not an annotation after all but a class type. Move position back to start
          // so this can be dealt with as a class type instead.
          position = startPosition;
          return null;
        }
        return ProguardTypeMatcher.create(className, ClassOrType.CLASS, dexItemFactory);
      }
      return null;
    }

    private boolean parseNegation() {
      skipWhitespace();
      return acceptChar('!');
    }

    private void parseClassFlagsAndAnnotations(ProguardClassSpecification.Builder builder)
        throws ProguardRuleParserException {
      while (true) {
        skipWhitespace();
        ProguardTypeMatcher annotation = parseAnnotation();
        if (annotation != null) {
          // TODO(ager): Should we only allow one annotation? It looks that way from the
          // proguard keep rule description, but that seems like a strange restriction?
          assert builder.getClassAnnotation() == null;
          builder.setClassAnnotation(annotation);
        } else {
          ProguardAccessFlags flags =
              parseNegation()
                  ? builder.getNegatedClassAccessFlags()
                  : builder.getClassAccessFlags();
          skipWhitespace();
          if (acceptString("public")) {
            flags.setPublic();
          } else if (acceptString("final")) {
            flags.setFinal();
          } else if (acceptString("abstract")) {
            flags.setAbstract();
          } else {
            break;
          }
        }
      }
    }

    private ProguardClassType parseClassType() throws ProguardRuleParserException {
      skipWhitespace();
      if (acceptString("interface")) {
        return ProguardClassType.INTERFACE;
      } else if (acceptString("@interface")) {
        return ProguardClassType.ANNOTATION_INTERFACE;
      } else if (acceptString("class")) {
        return ProguardClassType.CLASS;
      } else if (acceptString("enum")) {
        return ProguardClassType.ENUM;
      } else {
        throw parseError("Expected interface|class|enum");
      }
    }

    private void parseInheritance(ProguardClassSpecification.Builder classSpecificationBuilder)
        throws ProguardRuleParserException {
      skipWhitespace();
      if (acceptString("implements")) {
        classSpecificationBuilder.setInheritanceIsExtends(false);
      } else if (acceptString("extends")) {
        classSpecificationBuilder.setInheritanceIsExtends(true);
      } else {
        return;
      }
      classSpecificationBuilder.setInheritanceAnnotation(parseAnnotation());
      classSpecificationBuilder.setInheritanceClassName(ProguardTypeMatcher.create(parseClassName(),
          ClassOrType.CLASS, dexItemFactory));
    }

    private void parseMemberRules(ProguardClassSpecification.Builder classSpecificationBuilder,
        boolean allowValueSpecification)
        throws ProguardRuleParserException {
      skipWhitespace();
      if (!eof() && acceptChar('{')) {
        ProguardMemberRule rule = null;
        while ((rule = parseMemberRule(allowValueSpecification)) != null) {
          classSpecificationBuilder.getMemberRules().add(rule);
        }
        skipWhitespace();
        expectChar('}');
      }
    }

    private ProguardMemberRule parseMemberRule(boolean allowValueSpecification)
        throws ProguardRuleParserException {
      ProguardMemberRule.Builder ruleBuilder = ProguardMemberRule.builder();
      skipWhitespace();
      ruleBuilder.setAnnotation(parseAnnotation());
      parseMemberAccessFlags(ruleBuilder);
      parseMemberPattern(ruleBuilder, allowValueSpecification);
      return ruleBuilder.isValid() ? ruleBuilder.build() : null;
    }

    private void parseMemberAccessFlags(ProguardMemberRule.Builder ruleBuilder) {
      boolean found = true;
      while (found && !eof()) {
        found = false;
        ProguardAccessFlags flags =
            parseNegation() ? ruleBuilder.getNegatedAccessFlags() : ruleBuilder.getAccessFlags();
        skipWhitespace();
        switch (peekChar()) {
          case 'a':
            if (found = acceptString("abstract")) {
              flags.setAbstract();
            }
            break;
          case 'f':
            if (found = acceptString("final")) {
              flags.setFinal();
            }
            break;
          case 'n':
            if (found = acceptString("native")) {
              flags.setNative();
            }
            break;
          case 'p':
            if (found = acceptString("public")) {
              flags.setPublic();
            } else if (found = acceptString("private")) {
              flags.setPrivate();
            } else if (found = acceptString("protected")) {
              flags.setProtected();
            }
            break;
          case 's':
            if (found = acceptString("synchronized")) {
              flags.setSynchronized();
            } else if (found = acceptString("static")) {
              flags.setStatic();
            } else if (found = acceptString("strictfp")) {
              flags.setStrict();
            }
            break;
          case 't':
            if (found = acceptString("transient")) {
              flags.setTransient();
            }
            break;
          case 'v':
            if (found = acceptString("volatile")) {
              flags.setVolatile();
            }
            break;
          default:
            // Intentionally left empty.
        }
      }
    }

    private void parseMemberPattern(
        ProguardMemberRule.Builder ruleBuilder, boolean allowValueSpecification)
        throws ProguardRuleParserException {
      skipWhitespace();
      if (acceptString("<methods>")) {
        ruleBuilder.setRuleType(ProguardMemberType.ALL_METHODS);
      } else if (acceptString("<fields>")) {
        ruleBuilder.setRuleType(ProguardMemberType.ALL_FIELDS);
      } else if (acceptString("<init>")) {
        ruleBuilder.setRuleType(ProguardMemberType.INIT);
        ruleBuilder.setName("<init>");
        ruleBuilder.setArguments(parseArgumentList());
      } else {
        String first = acceptClassName();
        if (first != null) {
          skipWhitespace();
          if (first.equals("*") && hasNextChar(';')) {
            ruleBuilder.setRuleType(ProguardMemberType.ALL);
          } else {
            if (hasNextChar('(')) {
              ruleBuilder.setRuleType(ProguardMemberType.CONSTRUCTOR);
              ruleBuilder.setName(first);
              ruleBuilder.setArguments(parseArgumentList());
            } else {
              String second = acceptClassName();
              if (second != null) {
                skipWhitespace();
                if (hasNextChar('(')) {
                  ruleBuilder.setRuleType(ProguardMemberType.METHOD);
                  ruleBuilder.setName(second);
                  ruleBuilder
                      .setTypeMatcher(
                          ProguardTypeMatcher.create(first, ClassOrType.TYPE, dexItemFactory));
                  ruleBuilder.setArguments(parseArgumentList());
                } else {
                  ruleBuilder.setRuleType(ProguardMemberType.FIELD);
                  ruleBuilder.setName(second);
                  ruleBuilder
                      .setTypeMatcher(
                          ProguardTypeMatcher.create(first, ClassOrType.TYPE, dexItemFactory));
                }
                skipWhitespace();
                // Parse "return ..." if present.
                if (acceptString("return")) {
                  skipWhitespace();
                  if (acceptString("true")) {
                    ruleBuilder.setReturnValue(new ProguardMemberRuleReturnValue(true));
                  } else if (acceptString("false")) {
                    ruleBuilder.setReturnValue(new ProguardMemberRuleReturnValue(false));
                  } else {
                    String qualifiedFieldName = acceptFieldName();
                    if (qualifiedFieldName != null) {
                      if (ruleBuilder.getTypeMatcher() instanceof MatchSpecificType) {
                        int lastDotIndex = qualifiedFieldName.lastIndexOf(".");
                        DexType fieldType = ((MatchSpecificType) ruleBuilder.getTypeMatcher()).type;
                        DexType fieldClass =
                            dexItemFactory.createType(
                                DescriptorUtils.javaTypeToDescriptor(
                                    qualifiedFieldName.substring(0, lastDotIndex)));
                        DexString fieldName =
                            dexItemFactory.createString(
                                qualifiedFieldName.substring(lastDotIndex + 1));
                        DexField field = dexItemFactory
                            .createField(fieldClass, fieldType, fieldName);
                        ruleBuilder.setReturnValue(new ProguardMemberRuleReturnValue(field));
                      } else {
                        throw parseError("Expected specific type");
                      }
                    } else {
                      Integer min = acceptInteger();
                      Integer max = min;
                      if (min == null) {
                        throw parseError("Expected integer value");
                      }
                      skipWhitespace();
                      if (acceptString("..")) {
                        max = acceptInteger();
                        if (max == null) {
                          throw parseError("Expected integer value");
                        }
                      }
                      if (!allowValueSpecification) {
                        throw parseError("Unexpected value specification");
                      }
                      ruleBuilder.setReturnValue(
                          new ProguardMemberRuleReturnValue(new LongInterval(min, max)));
                    }
                  }
                }
              } else {
                throw parseError("Expected field or method name");
              }
            }
          }
        }
      }
      // If we found a member pattern eat the terminating ';'.
      if (ruleBuilder.isValid()) {
        skipWhitespace();
        expectChar(';');
      }
    }

    private List<ProguardTypeMatcher> parseArgumentList() throws ProguardRuleParserException {
      List<ProguardTypeMatcher> arguments = new ArrayList<>();
      skipWhitespace();
      expectChar('(');
      skipWhitespace();
      if (acceptChar(')')) {
        return arguments;
      }
      if (acceptString("...")) {
        arguments
            .add(ProguardTypeMatcher.create("...", ClassOrType.TYPE, dexItemFactory));
      } else {
        for (String name = parseClassName(); name != null; name =
            acceptChar(',') ? parseClassName() : null) {
          arguments
              .add(ProguardTypeMatcher.create(name, ClassOrType.TYPE, dexItemFactory));
          skipWhitespace();
        }
      }
      skipWhitespace();
      expectChar(')');
      return arguments;
    }

    private Path parseFileName() throws ProguardRuleParserException {
      skipWhitespace();
      String fileName = acceptString(character ->
          character != File.pathSeparatorChar
              && !Character.isWhitespace(character)
              && character != '(');
      if (fileName == null) {
        throw parseError("File name expected");
      }
      return baseDirectory.resolve(fileName);
    }

    private List<FilteredClassPath> parseClassPath() throws ProguardRuleParserException {
      List<FilteredClassPath> classPath = new ArrayList<>();
      skipWhitespace();
      Path file = parseFileName();
      ImmutableList<String> filters = parseClassPathFilters();
      classPath.add(new FilteredClassPath(file, filters));
      while (acceptChar(File.pathSeparatorChar)) {
        file = parseFileName();
        filters = parseClassPathFilters();
        classPath.add(new FilteredClassPath(file, filters));
      }
      return classPath;
    }

    private ImmutableList<String> parseClassPathFilters() throws ProguardRuleParserException {
      skipWhitespace();
      if (acceptChar('(')) {
        ImmutableList.Builder<String> filters = new ImmutableList.Builder<>();
        filters.add(parseFileFilter());
        skipWhitespace();
        while (acceptChar(',')) {
          filters.add(parseFileFilter());
          skipWhitespace();
        }
        if (acceptChar(';')) {
          throw parseError("Only class file filters are supported in classpath");
        }
        expectChar(')');
        return filters.build();
      } else {
        return ImmutableList.of();
      }
    }

    private String parseFileFilter() throws ProguardRuleParserException {
      skipWhitespace();
      String fileFilter = acceptString(character ->
          character != ',' && character != ';' && character != ')'
              && !Character.isWhitespace(character));
      if (fileFilter == null) {
        throw parseError("file filter expected");
      }
      return fileFilter;
    }

    private ProguardAssumeNoSideEffectRule parseAssumeNoSideEffectsRule()
        throws ProguardRuleParserException {
      ProguardAssumeNoSideEffectRule.Builder builder = ProguardAssumeNoSideEffectRule.builder();
      parseClassSpec(builder, true);
      return builder.build();
    }

    private ProguardAssumeValuesRule parseAssumeValuesRule() throws ProguardRuleParserException {
      ProguardAssumeValuesRule.Builder builder = ProguardAssumeValuesRule.builder();
      parseClassSpec(builder, true);
      return builder.build();
    }

    private void skipWhitespace() {
      while (!eof() && Character.isWhitespace(contents.charAt(position))) {
        position++;
      }
      skipComment();
    }

    private void skipComment() {
      if (eof()) {
        return;
      }
      if (peekChar() == '#') {
        while (!eof() && readChar() != '\n') {
          ;
        }
        skipWhitespace();
      }
    }

    private boolean eof() {
      return position == contents.length();
    }

    private boolean eof(int position) {
      return position == contents.length();
    }

    private boolean hasNextChar(char c) {
      if (eof()) {
        return false;
      }
      return peekChar() == c;
    }

    private boolean isOptionalArgumentGiven() {
      return !eof() && !hasNextChar('-');
    }

    private boolean acceptChar(char c) {
      if (hasNextChar(c)) {
        position++;
        return true;
      }
      return false;
    }

    private char peekChar() {
      return contents.charAt(position);
    }

    private char readChar() {
      return contents.charAt(position++);
    }

    private int remainingChars() {
      return contents.length() - position;
    }

    private void expectChar(char c) throws ProguardRuleParserException {
      if (eof() || readChar() != c) {
        throw parseError("Expected char '" + c + "'");
      }
    }

    private void expectString(String expected) throws ProguardRuleParserException {
      if (remainingChars() < expected.length()) {
        throw parseError("Expected string '" + expected + "'");
      }
      for (int i = 0; i < expected.length(); i++) {
        if (expected.charAt(i) != readChar()) {
          throw parseError("Expected string '" + expected + "'");
        }
      }
    }

    private boolean acceptString(String expected) {
      if (remainingChars() < expected.length()) {
        return false;
      }
      for (int i = 0; i < expected.length(); i++) {
        if (expected.charAt(i) != contents.charAt(position + i)) {
          return false;
        }
      }
      position += expected.length();
      return true;
    }

    private String acceptString() {
      return acceptString(character -> character != '-' && character != '\n');
    }

    private Integer acceptInteger() {
      String s = acceptString(Character::isDigit);
      if (s == null) {
        return null;
      }
      return Integer.parseInt(s);
    }

    private String acceptClassName() {
      return acceptString(character ->
          Character.isJavaIdentifierPart(character)
              || character == '.'
              || character == '*'
              || character == '?'
              || character == '%'
              || character == '['
              || character == ']');
    }

    private String acceptFieldName() {
      skipWhitespace();
      int start = position;
      int end = position;
      while (!eof(end)) {
        char current = contents.charAt(end);
        if ((start == end && Character.isJavaIdentifierStart(current)) ||
            ((start < end) && (Character.isJavaIdentifierPart(current) || current == '.'))) {
          end++;
        } else {
          break;
        }
      }
      if (start == end) {
        return null;
      }
      position = end;
      return contents.substring(start, end);
    }

    private List<String> acceptPatternList() throws ProguardRuleParserException {
      List<String> patterns = new ArrayList<>();
      String pattern = acceptPattern();
      while (pattern != null) {
        patterns.add(pattern);
        skipWhitespace();
        if (acceptChar(',')) {
          pattern = acceptPattern();
          if (pattern == null) {
            throw parseError("Expected list element");
          }
        } else {
          pattern = null;
        }
      }
      return patterns;
    }

    private String acceptPattern() {
      return acceptString(character ->
          Character.isJavaIdentifierPart(character) || character == '!' || character == '*');
    }

    private String acceptString(Predicate<Character> characterAcceptor) {
      skipWhitespace();
      int start = position;
      int end = position;
      while (!eof(end)) {
        char current = contents.charAt(end);
        if (characterAcceptor.test(current)) {
          end++;
        } else {
          break;
        }
      }
      if (start == end) {
        return null;
      }
      position = end;
      return contents.substring(start, end);
    }

    private void unacceptString(String expected) {
      assert position >= expected.length();
      position -= expected.length();
      for (int i = 0; i < expected.length(); i++) {
        assert expected.charAt(i) == contents.charAt(position + i);
      }
    }

    private ProguardClassNameList parseClassNames() throws ProguardRuleParserException {
      ProguardClassNameList.Builder builder = ProguardClassNameList.builder();
      skipWhitespace();
      boolean negated = acceptChar('!');
      builder.addClassName(negated,
          ProguardTypeMatcher.create(parseClassName(), ClassOrType.CLASS, dexItemFactory));
      skipWhitespace();
      while (acceptChar(',')) {
        negated = acceptChar('!');
        builder.addClassName(negated,
            ProguardTypeMatcher.create(parseClassName(), ClassOrType.CLASS, dexItemFactory));
        skipWhitespace();
      }
      return builder.build();
    }

    private String parsePackageNameOrEmptyString() {
      String name = acceptClassName();
      return name == null ? "" : name;
    }

    private String parseClassName() throws ProguardRuleParserException {
      String name = acceptClassName();
      if (name == null) {
        throw parseError("Class name expected");
      }
      return name;
    }

    private String snippetForPosition() {
      // TODO(ager): really should deal with \r as well to get column right.
      String[] lines = contents.split("\n", -1);  // -1 to get trailing empty lines represented.
      int remaining = position;
      for (int lineNumber = 0; lineNumber < lines.length; lineNumber++) {
        String line = lines[lineNumber];
        if (remaining <= line.length() || lineNumber == lines.length - 1) {
          String arrow = CharBuffer.allocate(remaining).toString().replace('\0', ' ') + '^';
          return name + ":" + (lineNumber + 1) + ":" + remaining + "\n" + line
              + '\n' + arrow;
        }
        remaining -= (line.length() + 1); // Include newline.
      }
      return name;
    }

    private ProguardRuleParserException parseError(String message) {
      return new ProguardRuleParserException(message, snippetForPosition());
    }

    private ProguardRuleParserException parseError(String message, Throwable cause) {
      return new ProguardRuleParserException(message, snippetForPosition(), cause);
    }
  }
}
