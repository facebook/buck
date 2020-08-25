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

package com.facebook.buck.shell;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import java.util.ArrayList;
import java.util.Optional;
import java.util.function.BiConsumer;
import org.junit.Test;

public class WindowsCmdExeEscaperTest {
  @Test
  public void metaQuotedCircumflex() {
    WindowsCmdExeEscaper.CmdExeMetaParser metaParser = new WindowsCmdExeEscaper.CmdExeMetaParser();
    WindowsCmdExeEscaper.AnnotatedString result = metaParser.parse("\"^\"");
    int[] expected =
        new int[] {
          '"', '^', // escape character '^' is quoted and should be treated literally
          '"'
        };

    assertThat(result.getAnnotatedChars(), equalTo(expected));
  }

  @Test
  public void metaUnquotedTrailingCircumflex() {
    WindowsCmdExeEscaper.CmdExeMetaParser metaParser = new WindowsCmdExeEscaper.CmdExeMetaParser();
    WindowsCmdExeEscaper.AnnotatedString result = metaParser.parse("abc^");
    // trailing ^ only makes sense in interactive environments; nothing to do but drop it
    int[] expected =
        new int[] {
          'a', 'b', 'c' // no trailing '^'
        };

    assertThat(result.getAnnotatedChars(), equalTo(expected));
  }

  @Test
  public void metaUnquotedEscapedNonSpecial() {
    WindowsCmdExeEscaper.CmdExeMetaParser metaParser = new WindowsCmdExeEscaper.CmdExeMetaParser();
    WindowsCmdExeEscaper.AnnotatedString result = metaParser.parse("^abc");
    int[] expected =
        new int[] {
          'a', // non-special character 'a' is escaped and should be treated literally
          'b', 'c'
        };

    assertThat(result.getAnnotatedChars(), equalTo(expected));
  }

  @Test
  public void metaUnquotedEscapedSpecial() {
    WindowsCmdExeEscaper.CmdExeMetaParser metaParser = new WindowsCmdExeEscaper.CmdExeMetaParser();
    WindowsCmdExeEscaper.AnnotatedString result = metaParser.parse("^,bc");
    int[] expected =
        new int[] {
          ',', // special character ',' is escaped and should be treated literally
          'b', 'c'
        };

    assertThat(result.getAnnotatedChars(), equalTo(expected));
  }

  @Test
  public void metaUnquotedEscapedCircumflex() {
    WindowsCmdExeEscaper.CmdExeMetaParser metaParser = new WindowsCmdExeEscaper.CmdExeMetaParser();
    WindowsCmdExeEscaper.AnnotatedString result = metaParser.parse("^^abc");
    int[] expected =
        new int[] {
          '^', // special character '^' is escaped and should be treated literally
          'a', 'b', 'c'
        };

    assertThat(result.getAnnotatedChars(), equalTo(expected));
  }

  @Test
  public void metaUnquotedUnescapedSpecial() {
    WindowsCmdExeEscaper.CmdExeMetaParser metaParser = new WindowsCmdExeEscaper.CmdExeMetaParser();
    WindowsCmdExeEscaper.AnnotatedString result = metaParser.parse(",bc");
    int[] expected =
        new int[] {
          ',' | WindowsCmdExeEscaper.META, // special character ',' is not quoted or escaped
          // and should be treated as a metacharacter
          'b',
          'c'
        };

    assertThat(result.getAnnotatedChars(), equalTo(expected));
  }

  @Test
  public void metaQuotedParts() {
    WindowsCmdExeEscaper.CmdExeMetaParser metaParser = new WindowsCmdExeEscaper.CmdExeMetaParser();
    WindowsCmdExeEscaper.AnnotatedString result1 = metaParser.parse(",\",");
    WindowsCmdExeEscaper.AnnotatedString result2 = metaParser.parse(",\",");
    int[] expected1 =
        new int[] {
          ',' | WindowsCmdExeEscaper.META,
          '"',
          ',' // special character ',' is quoted; should be treated literally
        };
    int[] expected2 =
        new int[] {
          ',', // quoting from first part should be continued; ',' likewise treated literally
          '"',
          ',' | WindowsCmdExeEscaper.META
        };

    assertThat(result1.getAnnotatedChars(), equalTo(expected1));
    assertThat(result2.getAnnotatedChars(), equalTo(expected2));
  }

  @Test
  public void metaUnquotedParts() {
    WindowsCmdExeEscaper.CmdExeMetaParser metaParser = new WindowsCmdExeEscaper.CmdExeMetaParser();
    WindowsCmdExeEscaper.AnnotatedString result1 = metaParser.parse("\",\",");
    WindowsCmdExeEscaper.AnnotatedString result2 = metaParser.parse(",\",\"");
    int[] expected1 =
        new int[] {
          '"',
          ',', // quoted; treat literally
          '"',
          ',' | WindowsCmdExeEscaper.META, // not quoted and not escaped; treat as meta
        };
    int[] expected2 =
        new int[] {
          ',' | WindowsCmdExeEscaper.META, // non-quoting from first part should be
          // continued; ',' likewise treated as meta
          '"',
          ',', // quoted; treat literally
          '"'
        };

    assertThat(result1.getAnnotatedChars(), equalTo(expected1));
    assertThat(result2.getAnnotatedChars(), equalTo(expected2));
  }

  static void testCommandLineToArgvW(String commandLine, String[] argv) {
    WindowsCmdExeEscaper.CommandLineToArgvWParser argvParser =
        new WindowsCmdExeEscaper.CommandLineToArgvWParser();
    ArrayList<String> argvBuilder = new ArrayList<String>();
    BiConsumer<String, Optional<WindowsCmdExeEscaper.AnnotatedString>> argConsumer =
        (separator, optionalArg) -> optionalArg.map(arg -> argvBuilder.add(arg.toString()));
    argvParser.parse(new WindowsCmdExeEscaper.AnnotatedString(commandLine), argConsumer);
    argvParser.consumeArg(argConsumer);
    assertThat(argvBuilder.toArray(), equalTo(argv));
  }

  @Test
  public void commandLineToArgvWConformity1() {
    // see
    // https://docs.microsoft.com/en-us/cpp/c-language/parsing-c-command-line-arguments?view=vs-2019
    String commandLine = "\"a b c\" d e";
    String[] argv = new String[] {"a b c", "d", "e"};
    testCommandLineToArgvW(commandLine, argv);
  }

  @Test
  public void commandLineToArgvWConformity2() {
    // see
    // https://docs.microsoft.com/en-us/cpp/c-language/parsing-c-command-line-arguments?view=vs-2019
    String commandLine = "\"ab\\\"c\" \"\\\\\" d";
    String[] argv = new String[] {"ab\"c", "\\", "d"};
    testCommandLineToArgvW(commandLine, argv);
  }

  @Test
  public void commandLineToArgvWConformity3() {
    // see
    // https://docs.microsoft.com/en-us/cpp/c-language/parsing-c-command-line-arguments?view=vs-2019
    String commandLine = "a\\\\\\b d\"e f\"g h";
    String[] argv = new String[] {"a\\\\\\b", "de fg", "h"};
    testCommandLineToArgvW(commandLine, argv);
  }

  @Test
  public void commandLineToArgvWConformity4() {
    // see
    // https://docs.microsoft.com/en-us/cpp/c-language/parsing-c-command-line-arguments?view=vs-2019
    String commandLine = "a\\\\\\\"b c d";
    String[] argv = new String[] {"a\\\"b", "c", "d"};
    testCommandLineToArgvW(commandLine, argv);
  }

  @Test
  public void commandLineToArgvWConformity5() {
    // see
    // https://docs.microsoft.com/en-us/cpp/c-language/parsing-c-command-line-arguments?view=vs-2019
    String commandLine = "a\\\\\\\\\"b c\" d e";
    String[] argv = new String[] {"a\\\\b c", "d", "e"};
    testCommandLineToArgvW(commandLine, argv);
  }

  @Test
  public void commandLineToArgvWConformityEmptyArg() {
    String commandLine = "a \"\" b";
    String[] argv = new String[] {"a", "", "b"};
    testCommandLineToArgvW(commandLine, argv);
  }

  @Test
  public void commandLineToArgvWConformityTrailingEmptyArg() {
    String commandLine = "a \"\" b \"\"";
    String[] argv = new String[] {"a", "", "b", ""};
    testCommandLineToArgvW(commandLine, argv);
  }

  @Test
  public void commandLineToArgvWConformityTrailingWhitespace() {
    String commandLine = "a \"\" b ";
    String[] argv = new String[] {"a", "", "b"};
    testCommandLineToArgvW(commandLine, argv);
  }

  @Test
  public void commandLineToArgvWConformityTrailingEmptyArgAndWhitespace() {
    String commandLine = "a \"\" b \"\" ";
    String[] argv = new String[] {"a", "", "b", ""};
    testCommandLineToArgvW(commandLine, argv);
  }

  @Test
  public void escapedSubstringSimple() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    // generic string with no special characters or metacharacters; no transformation needed
    commandBuilder.appendEscapedSubstring("echo");

    assertThat(WindowsCmdExeEscaper.escape(commandBuilder.build()), equalTo("echo"));
  }

  @Test
  public void escapedSubstringEmptyArg() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    // generic string with no special characters or metacharacters; no transformation needed
    commandBuilder.appendEscapedSubstring("a \"\" b");

    assertThat(WindowsCmdExeEscaper.escape(commandBuilder.build()), equalTo("a ^\"^\" b"));
  }

  @Test
  public void escapedSubstringTrailingEmptyArg() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    // generic string with no special characters or metacharacters; no transformation needed
    commandBuilder.appendEscapedSubstring("a \"\" b \"\"");

    assertThat(WindowsCmdExeEscaper.escape(commandBuilder.build()), equalTo("a ^\"^\" b ^\"^\""));
  }

  @Test
  public void escapedSubstringTrailingEmptyArgWithWhitespace() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    // generic string with no special characters or metacharacters; no transformation needed
    commandBuilder.appendEscapedSubstring("a \"\" b \"\" ");

    assertThat(WindowsCmdExeEscaper.escape(commandBuilder.build()), equalTo("a ^\"^\" b ^\"^\" "));
  }

  @Test
  public void escapedSubstringAllMetacharacters() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    // no cmd.exe-quoting, so all special characters are metacharacters
    commandBuilder.appendEscapedSubstring("!%&'()+,;<=>[]`{|}~");

    assertThat(WindowsCmdExeEscaper.escape(commandBuilder.build()), equalTo("!%&'()+,;<=>[]`{|}~"));
  }

  @Test
  public void escapedSubstringAllEscapedMetacharacters() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    // no cmd.exe-quoting, so all special characters are metacharacters
    commandBuilder.appendEscapedSubstring("^!^%^&^'^(^)^+^,^;^<^=^>^[^]^`^{^|^}^~");

    assertThat(
        WindowsCmdExeEscaper.escape(commandBuilder.build()),
        equalTo("^!^%^&^'^(^)^+^,^;^<^=^>^[^]^`^{^|^}^~"));
  }

  @Test
  public void escapedSubstringQuotedPercent() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    // '%' is a metacharacter even when quoted
    commandBuilder.appendEscapedSubstring("echo \"%PATH%\"");

    assertThat(WindowsCmdExeEscaper.escape(commandBuilder.build()), equalTo("echo ^\"%PATH%^\""));
  }

  @Test
  public void escapedSubstringUnquotedEscapedPercent() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    // '%' is a metacharacter even when quoted
    commandBuilder.appendEscapedSubstring("echo ^%PATH^%");

    assertThat(WindowsCmdExeEscaper.escape(commandBuilder.build()), equalTo("echo ^%PATH^%"));
  }

  @Test
  public void escapedSubstringUnquotedWhitespace() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    // whitespace should be maintained
    commandBuilder.appendEscapedSubstring("echo  one\ttwo ");

    assertThat(WindowsCmdExeEscaper.escape(commandBuilder.build()), equalTo("echo  one\ttwo "));
  }

  @Test
  public void escapedSubstringQuotedWhitespace() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    // simple quoting with whitespace; no special characters or metacharacters
    commandBuilder.appendEscapedSubstring("echo \"hello world\"");

    assertThat(
        WindowsCmdExeEscaper.escape(commandBuilder.build()), equalTo("echo ^\"hello world^\""));
  }

  @Test
  public void escapedSubstringQuotedSpecial() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    // special characters ',' and '!' are quoted and therefore should be escaped
    commandBuilder.appendEscapedSubstring("echo \"hello, world!\"");

    assertThat(
        WindowsCmdExeEscaper.escape(commandBuilder.build()), equalTo("echo ^\"hello^, world^!^\""));
  }

  @Test
  public void escapedSubstringUnquotedSpecial() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    // escaped quotes; special characters become metacharacters
    commandBuilder.appendEscapedSubstring("echo ^\"hello, world!^\"");

    assertThat(
        WindowsCmdExeEscaper.escape(commandBuilder.build()), equalTo("echo ^\"hello, world!^\""));
  }

  @Test
  public void escapedSubstringNestedQuotesWithSpecial() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    // nested quotes which disable cmd.exe "in-quotes" mode, making ',' a special character
    // (which should be escaped) and '!' a metacharacter (which should not be escaped)
    commandBuilder.appendEscapedSubstring("echo \"hello, \\\"world!\\\"\"");

    assertThat(
        WindowsCmdExeEscaper.escape(commandBuilder.build()),
        equalTo("echo ^\"hello^, \\^\"world!\\^\"^\""));
  }

  @Test
  public void unescapedSubstringAllMetacharacters() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    // all special characters are quoted to prevent metacharacters
    commandBuilder.appendUnescapedSubstring("!%&'()+,;<=>[]`{|}~");

    assertThat(
        WindowsCmdExeEscaper.escape(commandBuilder.build()),
        equalTo("^\"^!^%^&^'^(^)^+^,^;^<^=^>^[^]^`^{^|^}^~^\""));
  }

  @Test
  public void unescapedSubstringSimple() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    // generic string with no special characters, so no quoting or escaping needed
    commandBuilder.appendUnescapedSubstring("echo");

    assertThat(WindowsCmdExeEscaper.escape(commandBuilder.build()), equalTo("echo"));
  }

  @Test
  public void unescapedSubstringEmptyArg() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    // generic string with no special characters or metacharacters; no transformation needed
    commandBuilder.appendUnescapedSubstring("a \"\" b");

    assertThat(WindowsCmdExeEscaper.escape(commandBuilder.build()), equalTo("a ^\"^\" b"));
  }

  @Test
  public void unescapedSubstringTrailingEmptyArg() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    // generic string with no special characters or metacharacters; no transformation needed
    commandBuilder.appendUnescapedSubstring("a \"\" b \"\"");

    assertThat(WindowsCmdExeEscaper.escape(commandBuilder.build()), equalTo("a ^\"^\" b ^\"^\""));
  }

  @Test
  public void unescapedSubstringTrailingEmptyArgWithWhitespace() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    // generic string with no special characters or metacharacters; no transformation needed
    commandBuilder.appendUnescapedSubstring("a \"\" b \"\" ");

    assertThat(WindowsCmdExeEscaper.escape(commandBuilder.build()), equalTo("a ^\"^\" b ^\"^\" "));
  }

  @Test
  public void unescapedSubstringNestedQuotes() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    // simple generic string with no special characters, so no quoting or escaping needed
    commandBuilder.appendUnescapedSubstring("echo a\\\"b c");

    assertThat(WindowsCmdExeEscaper.escape(commandBuilder.build()), equalTo("echo a\\^\"b c"));
  }

  @Test
  public void unescapedSubstringQuotedWhitespace() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    // generic string with quoted whitespace; whitespace should remain quoted
    commandBuilder.appendUnescapedSubstring("echo \"a b\" c");

    assertThat(WindowsCmdExeEscaper.escape(commandBuilder.build()), equalTo("echo ^\"a b^\" c"));
  }

  @Test
  public void unescapedSubstringUnquotedSpecial() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    // generic string with special characters; special characters should be quoted and escaped
    commandBuilder.appendUnescapedSubstring("echo a,b !c");

    assertThat(
        WindowsCmdExeEscaper.escape(commandBuilder.build()), equalTo("echo a^\"^,^\"b ^\"^!^\"c"));
  }

  @Test
  public void unescapedSubstringQuotedAdjacentSpecial() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    // special characters should be "absorbed" into surrounding quotes and escaped
    commandBuilder.appendUnescapedSubstring("echo \"a\",b !\"c\"");

    assertThat(
        WindowsCmdExeEscaper.escape(commandBuilder.build()), equalTo("echo ^\"a^,^\"b ^\"^!c^\""));
  }

  @Test
  public void unescapedSubstringUnquotedWhitespace() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    // precise whitespace should be maintained
    commandBuilder.appendUnescapedSubstring("echo  one\ttwo ");

    assertThat(WindowsCmdExeEscaper.escape(commandBuilder.build()), equalTo("echo  one\ttwo "));
  }

  @Test
  public void unescapedSubstringsAdjacentBackslashes() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    // should be escaped as \a\\b\
    commandBuilder.appendUnescapedSubstring("\\a\\").appendUnescapedSubstring("\\b\\");

    assertThat(WindowsCmdExeEscaper.escape(commandBuilder.build()), equalTo("\\a\\\\b\\"));
  }

  @Test
  public void unescapedSubstringsAdjacentBackslashesAndQuote() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    // should be escaped as a\"b rather than a\\"b
    commandBuilder.appendUnescapedSubstring("a\\").appendUnescapedSubstring("\\\"b");

    assertThat(WindowsCmdExeEscaper.escape(commandBuilder.build()), equalTo("a\\\\\\^\"b"));
  }

  @Test
  public void unescapedSubstringBackslashesAdjacentSpecial() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    // should be escaped as a\","b rather than a\,b
    commandBuilder.appendUnescapedSubstring("a\\,b");

    assertThat(WindowsCmdExeEscaper.escape(commandBuilder.build()), equalTo("a\\\\^\"^,^\"b"));
  }

  @Test
  public void mixedSubstrings() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    commandBuilder
        .appendUnescapedSubstring("subdir,flavor\\foo.exe arg")
        .appendEscapedSubstring(" | find \"bar\"");

    assertThat(
        WindowsCmdExeEscaper.escape(commandBuilder.build()),
        equalTo("subdir^\"^,^\"flavor\\foo.exe arg | find ^\"bar^\""));
  }

  @Test
  public void mixedSubstringsWithTrailingEmptyArg1() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    commandBuilder
        .appendEscapedSubstring("copy \"")
        .appendUnescapedSubstring("subdir,flavor\\foo.exe")
        .appendEscapedSubstring("\" \"\"");

    assertThat(
        WindowsCmdExeEscaper.escape(commandBuilder.build()),
        equalTo("copy ^\"subdir^,flavor\\foo.exe^\" ^\"^\""));
  }

  @Test
  public void mixedSubstringsWithTrailingEmptyArgAndWhitespace1() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    commandBuilder
        .appendEscapedSubstring("copy \"")
        .appendUnescapedSubstring("subdir,flavor\\foo.exe")
        .appendEscapedSubstring("\" \"\" ");

    assertThat(
        WindowsCmdExeEscaper.escape(commandBuilder.build()),
        equalTo("copy ^\"subdir^,flavor\\foo.exe^\" ^\"^\" "));
  }

  @Test
  public void mixedSubstringsWithTrailingEmptyArg2() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    commandBuilder
        .appendEscapedSubstring("type ")
        .appendUnescapedSubstring("subdir,flavor\\foo.exe \"\"");

    assertThat(
        WindowsCmdExeEscaper.escape(commandBuilder.build()),
        equalTo("type subdir^\"^,^\"flavor\\foo.exe ^\"^\""));
  }

  @Test
  public void mixedSubstringsWithTrailingEmptyArgAndWhitespace2() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    commandBuilder
        .appendEscapedSubstring("type ")
        .appendUnescapedSubstring("subdir,flavor\\foo.exe \"\"")
        .appendEscapedSubstring(" ");

    assertThat(
        WindowsCmdExeEscaper.escape(commandBuilder.build()),
        equalTo("type subdir^\"^,^\"flavor\\foo.exe ^\"^\" "));
  }

  @Test
  public void escapedSubstringWithQuotedUnescapedSubstring() {
    WindowsCmdExeEscaper.Command.Builder commandBuilder = WindowsCmdExeEscaper.Command.builder();

    // generic substring should be entirely quoted and escaped
    commandBuilder
        .appendEscapedSubstring("echo \"")
        .appendUnescapedSubstring("\"hello, world!\"")
        .appendEscapedSubstring("\"");

    assertThat(
        WindowsCmdExeEscaper.escape(commandBuilder.build()),
        equalTo("echo ^\"\\^\"hello^, world^!\\^\"^\""));
  }
}
