/*
 * Copyright (c) 2017, 2017, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.shell;

import static com.oracle.truffle.js.shell.JSLauncher.PreprocessResult.Consumed;
import static com.oracle.truffle.js.shell.JSLauncher.PreprocessResult.ConsumedPolyglotOption;
import static com.oracle.truffle.js.shell.JSLauncher.PreprocessResult.MissingValue;
import static com.oracle.truffle.js.shell.JSLauncher.PreprocessResult.Unhandled;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import org.graalvm.launcher.AbstractLanguageLauncher;
import org.graalvm.options.OptionCategory;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

public class JSLauncher extends AbstractLanguageLauncher {
    public static void main(String[] args) {
        new JSLauncher().launch(args);
    }

    boolean printResult = false;
    String[] programArgs;
    final List<UnparsedSource> unparsedSources = new LinkedList<>();

    @Override
    protected void launch(Context.Builder contextBuilder) {
        System.exit(executeScripts(contextBuilder));
    }

    @Override
    protected String getLanguageId() {
        return "js";
    }

    @Override
    protected List<String> preprocessArguments(List<String> arguments, Map<String, String> polyglotOptions) {
        final List<String> unrecognizedOptions = new ArrayList<>();

        ListIterator<String> iterator = arguments.listIterator();
        while (iterator.hasNext()) {
            String arg = iterator.next();
            if (arg.length() >= 2 && arg.startsWith("-")) {
                if (arg.equals("--")) {
                    break;
                }
                String flag;
                if (arg.startsWith("--")) {
                    flag = arg.substring(2);
                } else {
                    flag = arg.substring(1);
                    if (flag.length() == 1) {
                        String longFlag = expandShortFlag(flag.charAt(0));
                        if (longFlag != null) {
                            flag = longFlag;
                        }
                    }
                }

                switch (preprocessArgument(flag, polyglotOptions)) {
                    case ConsumedPolyglotOption:
                        iterator.remove();
                        // fall through
                    case Consumed:
                        continue;
                    case MissingValue:
                        throw new RuntimeException("Should not reach here");
                }

                String value;
                int equalsIndex = flag.indexOf('=');
                if (equalsIndex > 0) {
                    value = flag.substring(equalsIndex + 1);
                    flag = flag.substring(0, equalsIndex);
                } else if (iterator.hasNext()) {
                    value = iterator.next();
                } else {
                    value = null;
                }

                switch ((preprocessArgument(flag, value, polyglotOptions))) {
                    case ConsumedPolyglotOption:
                        iterator.remove();
                        if (equalsIndex < 0 && value != null) {
                            iterator.previous();
                        }
                        iterator.remove();
                        // fall through
                    case Consumed:
                        continue;
                    case MissingValue:
                        throw abort("Missing argument for " + arg);
                }

                unrecognizedOptions.add(arg);
                if (equalsIndex < 0 && value != null) {
                    iterator.previous();
                }
            } else {
                addFile(arg);
            }
        }
        List<String> programArgsList = arguments.subList(iterator.nextIndex(), arguments.size());
        programArgs = programArgsList.toArray(new String[programArgsList.size()]);
        return unrecognizedOptions;
    }

    public enum PreprocessResult {
        Consumed,
        ConsumedPolyglotOption,
        Unhandled,
        MissingValue
    }

    protected PreprocessResult preprocessArgument(String argument, Map<String, String> polyglotOptions) {
        switch (argument) {
            case "printResult":
            case "print-result":
                printResult = true;
                return Consumed;
            case "syntax-extensions":
                polyglotOptions.put("js.syntax-extensions", "true");
                return ConsumedPolyglotOption;
            case "scripting":
                polyglotOptions.put("js.scripting", "true");
                return ConsumedPolyglotOption;
            case "no-shebang":
                polyglotOptions.put("js.shebang", "false");
                return ConsumedPolyglotOption;
            case "strict":
                polyglotOptions.put("js.strict", "true");
                return ConsumedPolyglotOption;
            case "no-constasvar":
                polyglotOptions.put("js.const-as-var", "false");
                return ConsumedPolyglotOption;
        }
        return Unhandled;
    }

    protected PreprocessResult preprocessArgument(String argument, String value, @SuppressWarnings("unused") Map<String, String> polyglotOptions) {
        switch (argument) {
            case "eval":
                if (value == null) {
                    return MissingValue;
                }
                addEval(value);
                return Consumed;
            case "file":
                if (value == null) {
                    return MissingValue;
                }
                addFile(value);
                return Consumed;
            case "module":
                if (value == null) {
                    return MissingValue;
                }
                addModule(value);
                return Consumed;
        }
        return Unhandled;
    }

    protected String expandShortFlag(char f) {
        switch (f) {
            case 'e':
                return "eval";
            case 'f':
                // some other engines use a "-f filename" syntax.
                return "file";
        }
        return null;
    }

    boolean hasSources() {
        return unparsedSources.size() > 0;
    }

    Source[] parseSources() {
        Source[] sources = new Source[unparsedSources.size()];
        int i = 0;
        for (UnparsedSource unparsedSource : unparsedSources) {
            try {
                sources[i++] = unparsedSource.parse();
            } catch (IOException e) {
                System.err.println(String.format("Error: Error loading file %s. %s", unparsedSource.src, e.getMessage()));
                return new Source[0];
            }
        }
        return sources;
    }

    void addFile(String file) {
        unparsedSources.add(new UnparsedSource(file, SourceType.FILE));
    }

    void addEval(String str) {
        unparsedSources.add(new UnparsedSource(str, SourceType.EVAL));
    }

    void addModule(String file) {
        unparsedSources.add(new UnparsedSource(file, SourceType.MODULE));
    }

    @Override
    protected void validateArguments(Map<String, String> polyglotOptions) {
        if (!hasSources() && printResult) {
            throw abort("Error: cannot print the return value when no FILE is passed.", 6);
        }
    }

    @Override
    protected void printHelp(OptionCategory maxCategory) {
        // @formatter:off
        System.out.println();
        System.out.println("Usage: js [OPTION]... [FILE]...");
        System.out.println("Run JavaScript FILEs on the Graal.js engine. Run an interactive JavaScript shell if no FILE nor --eval is specified.\n");
        System.out.println("Arguments that are mandatory for long options are also mandatory for short options.\n");
        System.out.println("Basic Options:");
        printOption("-e, --eval CODE",      "evaluate the code");
        printOption("-f, --file FILE",      "load script file");
        printOption("--module FILE",        "load module file");
        printOption("--no-constasvar",      "disallows parsing of 'const' declarations as 'var'");
        printOption("--no-shebang",         "disallows support for files starting with '#!'");
        printOption("--syntax-extensions",  "enable non-spec syntax extensions");
        printOption("--print-result",       "print the return value of each FILE");
        printOption("--scripting",          "enable scripting features (Nashorn compatibility option)");
        printOption("--strict",             "run in strict mode");
    }


    @Override
    protected void collectArguments(Set<String> args) {
        args.addAll(Arrays.asList(
                        "-e", "--eval",
                        "-f", "--file",
                        "--no-constasvar",
                        "--no-shebang",
                        "--syntax-extensions",
                        "--print-result",
                        "--scripting",
                        "--strict"));
    }

    protected static void printOption(String option, String description) {
        String opt;
        if (option.length() >= 22) {
            System.out.println(String.format("%s%s", "  ", option));
            opt = "";
        } else {
            opt = option;
        }
        System.out.println(String.format("  %-22s%s", opt, description));
    }

    protected int executeScripts(Context.Builder contextBuilder) {
        int status;
        Context context;
        if (hasSources()) {
            contextBuilder.arguments("js", programArgs);
            context = contextBuilder.build();
            // Every engine runs different Source objects.
            Source[] sources = parseSources();
            status = -1;
            for (Source source : sources) {
                try {
                    Value result = context.eval(source);
                    if (printResult) {
                        System.out.println("Result: " + result.toString());
                    }
                    status = 0;
                } catch (PolyglotException t) {
                    if (t.isExit()) {
                        status = t.getExitStatus();
                    } else if (t.isGuestException()) {
                        t.printStackTrace();
                        status = 7;
                    } else {
                        t.printStackTrace();
                        status = 8;
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                    status = 8;
                }
            }
        } else {
            context = contextBuilder.build();
            status = runREPL(context);
        }
        context.close();
        return status;
    }

    private static int runREPL(Context context) {
        final BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        PrintStream output = System.out;

        for (;;) {
            try {
                output.print("> ");
                String line = input.readLine();
                if (line == null) {
                    return 0;
                }
                if (line.equals("")) {
                    continue;
                }

                context.eval(Source.newBuilder("js", line, "ShellSource").interactive(true).build());
            } catch (PolyglotException t) {
                if (t.isExit()) {
                    return t.getExitStatus();
                } else if (t.isGuestException()) {
                    // TODO format stack trace as expected for JS
                    t.printStackTrace();
                } else {
                    t.printStackTrace();
                    return 8;
                }
            } catch (Throwable t) {
                t.printStackTrace();
                return 8;
            }
        }
    }

    private enum SourceType {
        FILE,
        EVAL,
        MODULE,
    }

    private static final class UnparsedSource {
        private final String src;
        private final SourceType type;

        private UnparsedSource(String src, SourceType type) {
            this.src = src;
            this.type = type;
        }

        private Source parse() throws IOException {
            switch (type) {
                case FILE:
                    return Source.newBuilder("js", new File(src)).build();
                case EVAL:
                    return Source.newBuilder("js", src, "<eval_script>").buildLiteral();
                case MODULE:
                    return Source.newBuilder("js", new File(src)).name("module:" + src).build();
                default:
                    throw new IllegalStateException();
            }
        }
    }
}