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

package com.facebook.buck.support.fix;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.immutables.value.Value;
import org.stringtemplate.v4.ST;

/** Configuration that handles how `buck fix` and automatic invocations of fix scripts work */
@BuckStyleValue
public abstract class FixBuckConfig implements ConfigView<BuckConfig> {

  /** When to invoke `buck fix` automatically */
  enum AutofixEnabled {
    ALWAYS,
    INTERACTIVE,
    NEVER,
  }

  private static final String FIX_SECTION = "fix";

  private static final String FIX_SCRIPT_FIELD = "fix_script";
  private static final String FIX_SCRIPT_CONTACT_FIELD = "fix_script_contact";
  private static final String FIX_SCRIPT_MESSAGE_FIELD = "fix_script_message";
  // Use for testing because changing system properties can get messy.
  private static final String LEGACY_FIX_SCRIPT = "legacy_fix_script";
  private static final String AUTOFIX_ENABLED_FIELD = "autofix_enabled";
  private static final String AUTOFIX_COMMANDS_FIELD = "autofix_commands";

  private static final String DEFAULT_FIX_SCRIPT_MESSAGE =
      String.format(
          "Running buck fix command '{command}'.%s  Please report any problems to '{contact}'",
          System.lineSeparator());

  @Override
  public abstract BuckConfig getDelegate();

  public static FixBuckConfig of(BuckConfig delegate) {
    return ImmutableFixBuckConfig.of(delegate);
  }

  /** Get the script to run when buck fix is invoked */
  @Value.Lazy
  public Optional<ImmutableList<String>> getFixScript() {
    return getDelegate().getOptionalListWithoutComments(FIX_SECTION, FIX_SCRIPT_FIELD, ' ');
  }

  /** Get the previous script that JASABI fixes */
  @Value.Lazy
  public Optional<ImmutableList<String>> getLegacyFixScript() {
    Optional<ImmutableList<String>> fromConfig =
        getDelegate().getOptionalListWithoutComments(FIX_SECTION, LEGACY_FIX_SCRIPT, ' ');
    if (fromConfig.isPresent()) {
      return fromConfig;
    }
    return Optional.ofNullable(System.getProperty("buck.legacy_fix_script"))
        .map(path -> ImmutableList.copyOf(path.split("\\s+")));
  }

  /** Get the contact to use to tell users who to contact when `buck fix` fails */
  @Value.Lazy
  public Optional<String> getFixScriptContact() {
    return getDelegate().getValue(FIX_SECTION, FIX_SCRIPT_CONTACT_FIELD);
  }

  /** Get the message to show when invoking `buck fix` */
  @Value.Lazy
  public String getFixScriptMessage() {
    return getDelegate()
        .getRawValue(FIX_SECTION, FIX_SCRIPT_MESSAGE_FIELD)
        .orElse(DEFAULT_FIX_SCRIPT_MESSAGE);
  }

  /** When running `buck fix` automatically on command failure is enabled */
  public AutofixEnabled getAutofixEnabled() {
    return getDelegate()
        .getEnum(FIX_SECTION, AUTOFIX_ENABLED_FIELD, AutofixEnabled.class)
        .orElse(AutofixEnabled.NEVER);
  }

  /** List of commands that autofix is enabled for */
  public List<String> getAutofixCommands() {
    return getDelegate().getListWithoutComments(FIX_SECTION, AUTOFIX_COMMANDS_FIELD, ',');
  }

  /** Whether or not to run `buck fix` automatically */
  public boolean shouldRunAutofix(boolean isInteractive, String subcommand) {
    AutofixEnabled autofixEnabled = getAutofixEnabled();
    boolean basedOnInteractive =
        (autofixEnabled == AutofixEnabled.ALWAYS
            || (isInteractive && autofixEnabled == AutofixEnabled.INTERACTIVE));
    return basedOnInteractive && getAutofixCommands().contains(subcommand);
  }

  /** Determine whether to show a custom message when `buck fix` is invoked */
  @Value.Lazy
  public boolean shouldPrintFixScriptMessage() {
    return !getFixScriptMessage().isEmpty();
  }

  /** Determine whether to use the legacy script, or the new fix script system */
  @Value.Lazy
  public boolean shouldUseLegacyFixScript() {
    return !getFixScript().isPresent();
  }

  /**
   * Gets the full script to invoke in `buck fix`.
   *
   * <p>`{fix_spec_path}` is replaced with the path to the json file that contains details about the
   * last run. `{repository_root}` is replaced with the absolute path to the repository root.
   *
   * @param fixScript The script to run (generally from the config file) that has not been
   *     interpolated
   * @param repositoryRoot The path to use for `{repository_root}`
   * @param fixSpecPath The path to use for `{fix_spec_path}`
   * @return The interpolated command to run
   */
  public ImmutableList<String> getInterpolatedFixScript(
      ImmutableList<String> fixScript, Path repositoryRoot, Path fixSpecPath) {
    return ImmutableList.copyOf(
        Lists.transform(
            fixScript,
            arg ->
                new ST(arg, '{', '}')
                    .add("repository_root", repositoryRoot)
                    .add("fix_spec_path", fixSpecPath)
                    .render()));
  }

  /**
   * Gets the message to print to users when `buck fix` is invoked and performs some substitutions
   *
   * <p>`{command}` is replaced with {@code interpolatedFixScript} `{contact}` is replaced with
   * {@code fixScriptContact}
   *
   * @param interpolatedFixScript The fix script per {@link
   *     FixBuckConfig#getInterpolatedFixScript(ImmutableList, Path, Path)}
   * @param fixScriptContact The contact to use in the message
   * @return A string to be printed before the fix script is invoked, with template parameters
   *     substituted
   */
  public String getInterpolatedFixScriptMessage(
      ImmutableList<String> interpolatedFixScript, String fixScriptContact) {
    return new ST(getFixScriptMessage(), '{', '}')
        .add("command", Joiner.on(" ").join(interpolatedFixScript))
        .add("contact", fixScriptContact)
        .render();
  }

  /** Get a mapping of short names to Paths for buck provided fix scripts */
  public ImmutableMap<String, ImmutableList<String>> getBuckProvidedScripts() {
    return getLegacyFixScript()
        .map(fixScript -> ImmutableMap.of("jasabi_fix", fixScript))
        .orElse(ImmutableMap.of());
  }
}
