/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.util;

import com.android.common.annotations.NonNull;
import com.android.common.annotations.Nullable;
import com.android.common.utils.ILogger;
import com.android.utils.LineUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;

/**
 * Parses the command-line and stores flags needed or requested.
 * <p/>
 * This is a base class. To be useful you want to:
 * <ul>
 * <li>override it.
 * <li>pass an action array to the constructor.
 * <li>define flags for your actions.
 * </ul>
 * <p/>
 * To use, call {@link #parseArgs(String[])} and then
 * call {@link #getValue(String, String, String)}.
 */
public class CommandLineParser {

    /*
     * Steps needed to add a new action:
     * - Each action is defined as a "verb object" followed by parameters.
     * - Either reuse a VERB_ constant or define a new one.
     * - Either reuse an OBJECT_ constant or define a new one.
     * - Add a new entry to mAction with a one-line help summary.
     * - In the constructor, add a define() call for each parameter (either mandatory
     *   or optional) for the given action.
     */

    /** Internal verb name for internally hidden flags. */
    public final static String GLOBAL_FLAG_VERB = "@@internal@@";   //$NON-NLS-1$

    /** String to use when the verb doesn't need any object. */
    public final static String NO_VERB_OBJECT = "";                 //$NON-NLS-1$

    /** The global help flag. */
    public static final String KEY_HELP = "help";
    /** The global verbose flag. */
    public static final String KEY_VERBOSE = "verbose";
    /** The global silent flag. */
    public static final String KEY_SILENT = "silent";

    /** Verb requested by the user. Null if none specified, which will be an error. */
    private String mVerbRequested;
    /** Direct object requested by the user. Can be null. */
    private String mDirectObjectRequested;

    /**
     * Action definitions.
     * <p/>
     * This list serves two purposes: first it is used to know which verb/object
     * actions are acceptable on the command-line; second it provides a summary
     * for each action that is printed in the help.
     * <p/>
     * Each entry is a string array with:
     * <ul>
     * <li> the verb.
     * <li> a direct object (use {@link #NO_VERB_OBJECT} if there's no object).
     * <li> a description.
     * <li> an alternate form for the object (e.g. plural).
     * </ul>
     */
    private final String[][] mActions;

    private static final int ACTION_VERB_INDEX = 0;
    private static final int ACTION_OBJECT_INDEX = 1;
    private static final int ACTION_DESC_INDEX = 2;
    private static final int ACTION_ALT_OBJECT_INDEX = 3;

    /**
     * The map of all defined arguments.
     * <p/>
     * The key is a string "verb/directObject/longName".
     */
    private final HashMap<String, Arg> mArguments = new HashMap<String, Arg>();
    /** Logger */
    private final ILogger mLog;

    /**
     * Constructs a new command-line processor.
     *
     * @param logger An SDK logger object. Must not be null.
     * @param actions The list of actions recognized on the command-line.
     *                See the javadoc of {@link #mActions} for more details.
     *
     * @see #mActions
     */
    public CommandLineParser(ILogger logger, String[][] actions) {
        mLog = logger;
        mActions = actions;

        /*
         * usage should fit in 80 columns, including the space to print the options:
         * "  -v --verbose  7890123456789012345678901234567890123456789012345678901234567890"
         */

        define(Mode.BOOLEAN, false, GLOBAL_FLAG_VERB, NO_VERB_OBJECT, "v", KEY_VERBOSE,
                           "Verbose mode, shows errors, warnings and all messages.",
                           false);
        define(Mode.BOOLEAN, false, GLOBAL_FLAG_VERB, NO_VERB_OBJECT, "s", KEY_SILENT,
                           "Silent mode, shows errors only.",
                           false);
        define(Mode.BOOLEAN, false, GLOBAL_FLAG_VERB, NO_VERB_OBJECT, "h", KEY_HELP,
                           "Help on a specific command.",
                           false);
    }

    /**
     * Indicates if this command-line can work when no verb is specified.
     * The default is false, which generates an error when no verb/object is specified.
     * Derived implementations can set this to true if they can deal with a lack
     * of verb/action.
     */
    public boolean acceptLackOfVerb() {
        return false;
    }


    //------------------
    // Helpers to get flags values

    /** Helper that returns true if --verbose was requested. */
    public boolean isVerbose() {
        return ((Boolean) getValue(GLOBAL_FLAG_VERB, NO_VERB_OBJECT, KEY_VERBOSE)).booleanValue();
    }

    /** Helper that returns true if --silent was requested. */
    public boolean isSilent() {
        return ((Boolean) getValue(GLOBAL_FLAG_VERB, NO_VERB_OBJECT, KEY_SILENT)).booleanValue();
    }

    /** Helper that returns true if --help was requested. */
    public boolean isHelpRequested() {
        return ((Boolean) getValue(GLOBAL_FLAG_VERB, NO_VERB_OBJECT, KEY_HELP)).booleanValue();
    }

    /** Returns the verb name from the command-line. Can be null. */
    public String getVerb() {
        return mVerbRequested;
    }

    /** Returns the direct object name from the command-line. Can be null. */
    public String getDirectObject() {
        return mDirectObjectRequested;
    }

    //------------------

    /**
     * Raw access to parsed parameter values.
     * <p/>
     * The default is to scan all parameters. Parameters that have been explicitly set on the
     * command line are returned first. Otherwise one with a non-null value is returned.
     * <p/>
     * Both a verb and a direct object filter can be specified. When they are non-null they limit
     * the scope of the search.
     * <p/>
     * If nothing has been found, return the last default value seen matching the filter.
     *
     * @param verb The verb name, including {@link #GLOBAL_FLAG_VERB}. If null, all possible
     *             verbs that match the direct object condition will be examined and the first
     *             value set will be used.
     * @param directObject The direct object name, including {@link #NO_VERB_OBJECT}. If null,
     *             all possible direct objects that match the verb condition will be examined and
     *             the first value set will be used.
     * @param longFlagName The long flag name for the given action. Mandatory. Cannot be null.
     * @return The current value object stored in the parameter, which depends on the argument mode.
     */
    public Object getValue(String verb, String directObject, String longFlagName) {

        if (verb != null && directObject != null) {
            String key = verb + '/' + directObject + '/' + longFlagName;
            Arg arg = mArguments.get(key);
            return arg.getCurrentValue();
        }

        Object lastDefault = null;
        for (Arg arg : mArguments.values()) {
            if (arg.getLongArg().equals(longFlagName)) {
                if (verb == null || arg.getVerb().equals(verb)) {
                    if (directObject == null || arg.getDirectObject().equals(directObject)) {
                        if (arg.isInCommandLine()) {
                            return arg.getCurrentValue();
                        }
                        if (arg.getCurrentValue() != null) {
                            lastDefault = arg.getCurrentValue();
                        }
                    }
                }
            }
        }

        return lastDefault;
    }

    /**
     * Internal setter for raw parameter value.
     * @param verb The verb name, including {@link #GLOBAL_FLAG_VERB}.
     * @param directObject The direct object name, including {@link #NO_VERB_OBJECT}.
     * @param longFlagName The long flag name for the given action.
     * @param value The new current value object stored in the parameter, which depends on the
     *              argument mode.
     */
    protected void setValue(String verb, String directObject, String longFlagName, Object value) {
        String key = verb + '/' + directObject + '/' + longFlagName;
        Arg arg = mArguments.get(key);
        arg.setCurrentValue(value);
    }

    /**
     * Parses the command-line arguments.
     * <p/>
     * This method will exit and not return if a parsing error arise.
     *
     * @param args The arguments typically received by a main method.
     */
    public void parseArgs(String[] args) {
        String errorMsg = null;
        String verb = null;
        String directObject = null;

        try {
            int n = args.length;
            for (int i = 0; i < n; i++) {
                Arg arg = null;
                String a = args[i];
                if (a.startsWith("--")) {                                       //$NON-NLS-1$
                    arg = findLongArg(verb, directObject, a.substring(2));
                } else if (a.startsWith("-")) {                                 //$NON-NLS-1$
                    arg = findShortArg(verb, directObject, a.substring(1));
                }

                // No matching argument name found
                if (arg == null) {
                    // Does it looks like a dashed parameter?
                    if (a.startsWith("-")) {                                    //$NON-NLS-1$
                        if (verb == null || directObject == null) {
                            // It looks like a dashed parameter and we don't have a a verb/object
                            // set yet, the parameter was just given too early.

                            errorMsg = String.format(
                                "Flag '%1$s' is not a valid global flag. Did you mean to specify it after the verb/object name?",
                                a);
                            return;
                        } else {
                            // It looks like a dashed parameter but it is unknown by this
                            // verb-object combination

                            errorMsg = String.format(
                                    "Flag '%1$s' is not valid for '%2$s %3$s'.",
                                    a, verb, directObject);
                            return;
                        }
                    }

                    if (verb == null) {
                        // Fill verb first. Find it.
                        for (String[] actionDesc : mActions) {
                            if (actionDesc[ACTION_VERB_INDEX].equals(a)) {
                                verb = a;
                                break;
                            }
                        }

                        // Error if it was not a valid verb
                        if (verb == null) {
                            errorMsg = String.format(
                                "Expected verb after global parameters but found '%1$s' instead.",
                                a);
                            return;
                        }

                    } else if (directObject == null) {
                        // Then fill the direct object. Find it.
                        for (String[] actionDesc : mActions) {
                            if (actionDesc[ACTION_VERB_INDEX].equals(verb)) {
                                if (actionDesc[ACTION_OBJECT_INDEX].equals(a)) {
                                    directObject = a;
                                    break;
                                } else if (actionDesc.length > ACTION_ALT_OBJECT_INDEX &&
                                        actionDesc[ACTION_ALT_OBJECT_INDEX].equals(a)) {
                                    // if the alternate form exist and is used, we internally
                                    // only memorize the default direct object form.
                                    directObject = actionDesc[ACTION_OBJECT_INDEX];
                                    break;
                                }
                            }
                        }

                        // Error if it was not a valid object for that verb
                        if (directObject == null) {
                            errorMsg = String.format(
                                "Expected verb after global parameters but found '%1$s' instead.",
                                a);
                            return;

                        }
                    } else {
                        // The argument is not a dashed parameter and we already
                        // have a verb/object. Must be some extra unknown argument.
                        errorMsg = String.format(
                                "Argument '%1$s' is not recognized.",
                                a);
                    }
                } else {
                    // This argument was present on the command line
                    arg.setInCommandLine(true);

                    // Process keyword
                    Object error = null;
                    if (arg.getMode().needsExtra()) {
                        if (i+1 >= n) {
                            errorMsg = String.format("Missing argument for flag %1$s.", a);
                            return;
                        }

                        while (i+1 < n) {
                            String b = args[i+1];

                            if (arg.getMode() != Mode.STRING_ARRAY) {
                                // We never accept something that looks like a valid argument
                                // unless we see -- first
                                Arg dummyArg = null;
                                if (b.startsWith("--")) {                              //$NON-NLS-1$
                                    dummyArg = findLongArg(verb, directObject, b.substring(2));
                                } else if (b.startsWith("-")) {                        //$NON-NLS-1$
                                    dummyArg = findShortArg(verb, directObject, b.substring(1));
                                }
                                if (dummyArg != null) {
                                    errorMsg = String.format(
                                            "Oops, it looks like you didn't provide an argument for '%1$s'.\n'%2$s' was found instead.",
                                            a, b);
                                    return;
                                }
                            }

                            error = arg.getMode().process(arg, b);
                            if (error == Accept.CONTINUE) {
                                i++;
                            } else if (error == Accept.ACCEPT_AND_STOP) {
                                i++;
                                break;
                            } else if (error == Accept.REJECT_AND_STOP) {
                                break;
                            } else if (error instanceof String) {
                                // We stop because of an error
                                break;
                            }
                        }
                    } else {
                        error = arg.getMode().process(arg, null);

                        if (isHelpRequested()) {
                            // The --help flag was requested. We'll continue the usual processing
                            // so that we can find the optional verb/object words. Those will be
                            // used to print specific help.
                            // Setting a non-null error message triggers printing the help, however
                            // there is no specific error to print.
                            errorMsg = "";                                          //$NON-NLS-1$
                        }
                    }

                    if (error instanceof String) {
                        errorMsg = String.format("Invalid usage for flag %1$s: %2$s.", a, error);
                        return;
                    }
                }
            }

            if (errorMsg == null) {
                if (verb == null && !acceptLackOfVerb()) {
                    errorMsg = "Missing verb name.";
                } else if (verb != null) {
                    if (directObject == null) {
                        // Make sure this verb has an optional direct object
                        for (String[] actionDesc : mActions) {
                            if (actionDesc[ACTION_VERB_INDEX].equals(verb) &&
                                    actionDesc[ACTION_OBJECT_INDEX].equals(NO_VERB_OBJECT)) {
                                directObject = NO_VERB_OBJECT;
                                break;
                            }
                        }

                        if (directObject == null) {
                            errorMsg = String.format("Missing object name for verb '%1$s'.", verb);
                            return;
                        }
                    }

                    // Validate that all mandatory arguments are non-null for this action
                    String missing = null;
                    boolean plural = false;
                    for (Entry<String, Arg> entry : mArguments.entrySet()) {
                        Arg arg = entry.getValue();
                        if (arg.getVerb().equals(verb) &&
                                arg.getDirectObject().equals(directObject)) {
                            if (arg.isMandatory() && arg.getCurrentValue() == null) {
                                if (missing == null) {
                                    missing = "--" + arg.getLongArg();              //$NON-NLS-1$
                                } else {
                                    missing += ", --" + arg.getLongArg();           //$NON-NLS-1$
                                    plural = true;
                                }
                            }
                        }
                    }

                    if (missing != null) {
                        errorMsg  = String.format(
                                "The %1$s %2$s must be defined for action '%3$s %4$s'",
                                plural ? "parameters" : "parameter",
                                missing,
                                verb,
                                directObject);
                    }

                    mVerbRequested = verb;
                    mDirectObjectRequested = directObject;
                }
            }
        } finally {
            if (errorMsg != null) {
                printHelpAndExitForAction(verb, directObject, errorMsg);
            }
        }
    }

    /**
     * Finds an {@link com.android.sdklib.util.CommandLineParser.Arg} given an action name and a long flag name.
     * @return The {@link com.android.sdklib.util.CommandLineParser.Arg} found or null.
     */
    protected Arg findLongArg(String verb, String directObject, String longName) {
        if (verb == null) {
            verb = GLOBAL_FLAG_VERB;
        }
        if (directObject == null) {
            directObject = NO_VERB_OBJECT;
        }
        String key = verb + '/' + directObject + '/' + longName;                    //$NON-NLS-1$
        return mArguments.get(key);
    }

    /**
     * Finds an {@link com.android.sdklib.util.CommandLineParser.Arg} given an action name and a short flag name.
     * @return The {@link com.android.sdklib.util.CommandLineParser.Arg} found or null.
     */
    protected Arg findShortArg(String verb, String directObject, String shortName) {
        if (verb == null) {
            verb = GLOBAL_FLAG_VERB;
        }
        if (directObject == null) {
            directObject = NO_VERB_OBJECT;
        }

        for (Entry<String, Arg> entry : mArguments.entrySet()) {
            Arg arg = entry.getValue();
            if (arg.getVerb().equals(verb) && arg.getDirectObject().equals(directObject)) {
                if (shortName.equals(arg.getShortArg())) {
                    return arg;
                }
            }
        }

        return null;
    }

    /**
     * Prints the help/usage and exits.
     *
     * @param errorFormat Optional error message to print prior to usage using String.format
     * @param args Arguments for String.format
     */
    public void printHelpAndExit(String errorFormat, Object... args) {
        printHelpAndExitForAction(null /*verb*/, null /*directObject*/, errorFormat, args);
    }

    /**
     * Prints the help/usage and exits.
     *
     * @param verb If null, displays help for all verbs. If not null, display help only
     *          for that specific verb. In all cases also displays general usage and action list.
     * @param directObject If null, displays help for all verb objects.
     *          If not null, displays help only for that specific action
     *          In all cases also display general usage and action list.
     * @param errorFormat Optional error message to print prior to usage using String.format
     * @param args Arguments for String.format
     */
    public void printHelpAndExitForAction(String verb, String directObject,
            String errorFormat, Object... args) {
        if (errorFormat != null && errorFormat.length() > 0) {
            stderr(errorFormat, args);
        }

        /*
         * usage should fit in 80 columns
         *   12345678901234567890123456789012345678901234567890123456789012345678901234567890
         */
        stdout("\n" +
            "Usage:\n" +
            "  android [global options] %s [action options]\n" +
            "\n" +
            "Global options:",
            verb == null ? "action" :
                verb + (directObject == null ? "" : " " + directObject));           //$NON-NLS-1$
        listOptions(GLOBAL_FLAG_VERB, NO_VERB_OBJECT);

        if (verb == null || directObject == null) {
            stdout("\nValid actions are composed of a verb and an optional direct object:");
            for (String[] action : mActions) {
                if (verb == null || verb.equals(action[ACTION_VERB_INDEX])) {
                    stdout("- %1$6s %2$-13s: %3$s",
                            action[ACTION_VERB_INDEX],
                            action[ACTION_OBJECT_INDEX],
                            action[ACTION_DESC_INDEX]);
                }
            }
        }

        // Only print details if a verb/object is requested
        if (verb != null) {
            for (String[] action : mActions) {
                if (verb.equals(action[ACTION_VERB_INDEX])) {
                    if (directObject == null || directObject.equals(action[ACTION_OBJECT_INDEX])) {
                        stdout("\nAction \"%1$s %2$s\":",
                                action[ACTION_VERB_INDEX],
                                action[ACTION_OBJECT_INDEX]);
                        stdout("  %1$s", action[ACTION_DESC_INDEX]);
                        stdout("Options:");
                        listOptions(action[ACTION_VERB_INDEX], action[ACTION_OBJECT_INDEX]);
                    }
                }
            }
        }

        exit();
    }

    /**
     * Internal helper to print all the option flags for a given action name.
     */
    protected void listOptions(String verb, String directObject) {
        int numOptions = 0;
        int longArgLen = 8;

        for (Entry<String, Arg> entry : mArguments.entrySet()) {
            Arg arg = entry.getValue();
            if (arg.getVerb().equals(verb) && arg.getDirectObject().equals(directObject)) {
                int n = arg.getLongArg().length();
                if (n > longArgLen) {
                    longArgLen = n;
                }
            }
        }

        for (Entry<String, Arg> entry : mArguments.entrySet()) {
            Arg arg = entry.getValue();
            if (arg.getVerb().equals(verb) && arg.getDirectObject().equals(directObject)) {

                String value = "";                                              //$NON-NLS-1$
                String required = "";                                           //$NON-NLS-1$
                if (arg.isMandatory()) {
                    required = " [required]";

                } else {
                    if (arg.getDefaultValue() instanceof String[]) {
                        for (String v : (String[]) arg.getDefaultValue()) {
                            if (value.length() > 0) {
                                value += ", ";
                            }
                            value += v;
                        }
                    } else if (arg.getDefaultValue() != null) {
                        Object v = arg.getDefaultValue();
                        if (arg.getMode() != Mode.BOOLEAN || v.equals(Boolean.TRUE)) {
                            value = v.toString();
                        }
                    }
                    if (value.length() > 0) {
                        value = " [Default: " + value + "]";
                    }
                }

                // Java doesn't support * for printf variable width, so we'll insert the long arg
                // width "manually" in the printf format string.
                String longArgWidth = Integer.toString(longArgLen + 2);

                // Print a line in the form " -1_letter_arg --long_arg description"
                // where either the 1-letter arg or the long arg are optional.
                String output = String.format(
                        "  %1$-2s %2$-" + longArgWidth + "s: %3$s%4$s%5$s", //$NON-NLS-1$ //$NON-NLS-2$
                        arg.getShortArg().length() > 0 ?
                                "-" + arg.getShortArg() :                              //$NON-NLS-1$
                                "",                                                    //$NON-NLS-1$
                        arg.getLongArg().length() > 0 ?
                                "--" + arg.getLongArg() :                              //$NON-NLS-1$
                                "",                                                    //$NON-NLS-1$
                        arg.getDescription(),
                        value,
                        required);
                stdout(output);
                numOptions++;
            }
        }

        if (numOptions == 0) {
            stdout("  No options");
        }
    }

    //----

    private static enum Accept {
        CONTINUE,
        ACCEPT_AND_STOP,
        REJECT_AND_STOP,
    }

    /**
     * The mode of an argument specifies the type of variable it represents,
     * whether an extra parameter is required after the flag and how to parse it.
     */
    public static enum Mode {
        /** Argument value is a Boolean. Default value is a Boolean. */
        BOOLEAN {
            @Override
            public boolean needsExtra() {
                return false;
            }
            @Override
            public Object process(Arg arg, String extra) {
                // Toggle the current value
                arg.setCurrentValue(! ((Boolean) arg.getCurrentValue()).booleanValue());
                return Accept.ACCEPT_AND_STOP;
            }
        },

        /** Argument value is an Integer. Default value is an Integer. */
        INTEGER {
            @Override
            public boolean needsExtra() {
                return true;
            }
            @Override
            public Object process(Arg arg, String extra) {
                try {
                    arg.setCurrentValue(Integer.parseInt(extra));
                    return null;
                } catch (NumberFormatException e) {
                    return String.format("Failed to parse '%1$s' as an integer: %2$s", extra,
                            e.getMessage());
                }
            }
        },

        /** Argument value is a String. Default value is a String[]. */
        ENUM {
            @Override
            public boolean needsExtra() {
                return true;
            }
            @Override
            public Object process(Arg arg, String extra) {
                StringBuilder desc = new StringBuilder();
                String[] values = (String[]) arg.getDefaultValue();
                for (String value : values) {
                    if (value.equals(extra)) {
                        arg.setCurrentValue(extra);
                        return Accept.ACCEPT_AND_STOP;
                    }

                    if (desc.length() != 0) {
                        desc.append(", ");
                    }
                    desc.append(value);
                }

                return String.format("'%1$s' is not one of %2$s", extra, desc.toString());
            }
        },

        /** Argument value is a String. Default value is a null. */
        STRING {
            @Override
            public boolean needsExtra() {
                return true;
            }
            @Override
            public Object process(Arg arg, String extra) {
                arg.setCurrentValue(extra);
                return Accept.ACCEPT_AND_STOP;
            }
        },

        /** Argument value is a {@link java.util.List}&lt;String&gt;. Default value is an empty list. */
        STRING_ARRAY {
            @Override
            public boolean needsExtra() {
                return true;
            }
            @Override
            public Object process(Arg arg, String extra) {
                // For simplification, a string array doesn't accept something that
                // starts with a dash unless a pure -- was seen before.
                if (extra != null) {
                    Object v = arg.getCurrentValue();
                    if (v == null) {
                        ArrayList<String> a = new ArrayList<String>();
                        arg.setCurrentValue(a);
                        v = a;
                    }
                    if (v instanceof List<?>) {
                        @SuppressWarnings("unchecked") List<String> a = (List<String>) v;

                        if (extra.equals("--") ||
                                !extra.startsWith("-") ||
                                (extra.startsWith("-") && a.contains("--"))) {
                            a.add(extra);
                            return Accept.CONTINUE;
                        } else if (a.isEmpty()) {
                            return "No values provided";
                        }
                    }
                }
                return Accept.REJECT_AND_STOP;
            }
        };

        /**
         * Returns true if this mode requires an extra parameter.
         */
        public abstract boolean needsExtra();

        /**
         * Processes the flag for this argument.
         *
         * @param arg The argument being processed.
         * @param extra The extra parameter. Null if {@link #needsExtra()} returned false.
         * @return {@link com.android.util.CommandLineParser.Accept#CONTINUE} if this argument can use multiple values and
         *   wishes to receive more.
         *   Or {@link com.android.util.CommandLineParser.Accept#ACCEPT_AND_STOP} if this was the last value accepted by the argument.
         *   Or {@link com.android.util.CommandLineParser.Accept#REJECT_AND_STOP} if this was value was reject and the argument
         *   stops accepting new values with no error.
         *   Or a string in case of error.
         *   Never returns null.
         */
        public abstract Object process(Arg arg, String extra);
    }

    /**
     * An argument accepted by the command-line, also called "a flag".
     * Arguments must have a short version (one letter), a long version name and a description.
     * They can have a default value, or it can be null.
     * Depending on the {@link com.android.sdklib.util.CommandLineParser.Mode}, the default value can be a Boolean, an Integer, a String
     * or a String array (in which case the first item is the current by default.)
     */
    static class Arg {
        /** Verb for that argument. Never null. */
        private final String mVerb;
        /** Direct Object for that argument. Never null, but can be empty string. */
        private final String mDirectObject;
        /** The 1-letter short name of the argument, e.g. -v. */
        private final String mShortName;
        /** The long name of the argument, e.g. --verbose. */
        private final String mLongName;
        /** A description. Never null. */
        private final String mDescription;
        /** A default value. Can be null. */
        private final Object mDefaultValue;
        /** The argument mode (type + process method). Never null. */
        private final Mode mMode;
        /** True if this argument is mandatory for this verb/directobject. */
        private final boolean mMandatory;
        /** Current value. Initially set to the default value. */
        private Object mCurrentValue;
        /** True if the argument has been used on the command line. */
        private boolean mInCommandLine;

        /**
         * Creates a new argument flag description.
         *
         * @param mode The {@link com.android.util.CommandLineParser.Mode} for the argument.
         * @param mandatory True if this argument is mandatory for this action.
         * @param verb The verb name. Never null. Can be {@link com.android.util.CommandLineParser#GLOBAL_FLAG_VERB}.
         * @param directObject The action name. Can be {@link com.android.util.CommandLineParser#NO_VERB_OBJECT}.
         * @param shortName The one-letter short argument name. Can be empty but not null.
         * @param longName The long argument name. Can be empty but not null.
         * @param description The description. Cannot be null.
         * @param defaultValue The default value (or values), which depends on the selected
         *          {@link com.android.util.CommandLineParser.Mode}. Can be null.
         */
        public Arg(Mode mode,
                   boolean mandatory,
                   @NonNull String verb,
                   @NonNull String directObject,
                   @NonNull String shortName,
                   @NonNull String longName,
                   @NonNull String description,
                   @Nullable Object defaultValue) {
            mMode = mode;
            mMandatory = mandatory;
            mVerb = verb;
            mDirectObject = directObject;
            mShortName = shortName;
            mLongName = longName;
            mDescription = description;
            mDefaultValue = defaultValue;
            mInCommandLine = false;
            if (defaultValue instanceof String[]) {
                mCurrentValue = ((String[])defaultValue)[0];
            } else {
                mCurrentValue = mDefaultValue;
            }
        }

        /** Return true if this argument is mandatory for this verb/directobject. */
        public boolean isMandatory() {
            return mMandatory;
        }

        /** Returns the 1-letter short name of the argument, e.g. -v. */
        public String getShortArg() {
            return mShortName;
        }

        /** Returns the long name of the argument, e.g. --verbose. */
        public String getLongArg() {
            return mLongName;
        }

        /** Returns the description. Never null. */
        public String getDescription() {
            return mDescription;
        }

        /** Returns the verb for that argument. Never null. */
        public String getVerb() {
            return mVerb;
        }

        /** Returns the direct Object for that argument. Never null, but can be empty string. */
        public String getDirectObject() {
            return mDirectObject;
        }

        /** Returns the default value. Can be null. */
        public Object getDefaultValue() {
            return mDefaultValue;
        }

        /** Returns the current value. Initially set to the default value. Can be null. */
        public Object getCurrentValue() {
            return mCurrentValue;
        }

        /** Sets the current value. Can be null. */
        public void setCurrentValue(Object currentValue) {
            mCurrentValue = currentValue;
        }

        /** Returns the argument mode (type + process method). Never null. */
        public Mode getMode() {
            return mMode;
        }

        /** Returns true if the argument has been used on the command line. */
        public boolean isInCommandLine() {
            return mInCommandLine;
        }

        /** Sets if the argument has been used on the command line. */
        public void setInCommandLine(boolean inCommandLine) {
            mInCommandLine = inCommandLine;
        }
    }

    /**
     * Internal helper to define a new argument for a give action.
     *
     * @param mode The {@link com.android.sdklib.util.CommandLineParser.Mode} for the argument.
     * @param mandatory The argument is required (never if {@link com.android.sdklib.util.CommandLineParser.Mode#BOOLEAN})
     * @param verb The verb name. Never null. Can be {@link com.android.sdklib.util.CommandLineParser#GLOBAL_FLAG_VERB}.
     * @param directObject The action name. Can be {@link com.android.sdklib.util.CommandLineParser#NO_VERB_OBJECT}.
     * @param shortName The one-letter short argument name. Can be empty but not null.
     * @param longName The long argument name. Can be empty but not null.
     * @param description The description. Cannot be null.
     * @param defaultValue The default value (or values), which depends on the selected
     *          {@link com.android.sdklib.util.CommandLineParser.Mode}.
     */
    protected void define(Mode mode,
            boolean mandatory,
            @NonNull String verb,
            @NonNull String directObject,
            @NonNull String shortName,
            @NonNull String longName,
            @NonNull String description,
            @Nullable Object defaultValue) {
        assert verb != null;
        assert(!(mandatory && mode == Mode.BOOLEAN)); // a boolean mode cannot be mandatory

        // We should always have at least a short or long name, ideally both but never none.
        assert shortName != null;
        assert longName != null;
        assert shortName.length() > 0 || longName.length()  > 0;

        if (directObject == null) {
            directObject = NO_VERB_OBJECT;
        }

        String key = verb + '/' + directObject + '/' + longName;
        mArguments.put(key, new Arg(mode, mandatory,
                verb, directObject, shortName, longName, description, defaultValue));
    }

    /**
     * Exits in case of error.
     * This is protected so that it can be overridden in unit tests.
     */
    protected void exit() {
        System.exit(1);
    }

    /**
     * Prints a line to stdout.
     * This is protected so that it can be overridden in unit tests.
     *
     * @param format The string to be formatted. Cannot be null.
     * @param args Format arguments.
     */
    protected void stdout(String format, Object...args) {
        String output = String.format(format, args);
        output = LineUtil.reflowLine(output);
        mLog.info("%s\n", output);    //$NON-NLS-1$
    }

    /**
     * Prints a line to stderr.
     * This is protected so that it can be overridden in unit tests.
     *
     * @param format The string to be formatted. Cannot be null.
     * @param args Format arguments.
     */
    protected void stderr(String format, Object...args) {
        mLog.error(null, format, args);
    }
}
