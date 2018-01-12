/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.builtins;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Map;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.java.JavaInterop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalDecodeURINodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalEncodeURINodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalExitNodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalIndirectEvalNodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalIsFiniteNodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalIsNaNNodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalLoadNodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalLoadWithNewGlobalNodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalParseFloatNodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalParseIntNodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalPrintNodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalReadBufferNodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalReadFullyNodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalReadLineNodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalUnEscapeNodeGen;
import com.oracle.truffle.js.builtins.helper.FloatParser;
import com.oracle.truffle.js.builtins.helper.StringEscape;
import com.oracle.truffle.js.nodes.JSGuards;
import com.oracle.truffle.js.nodes.JavaScriptBaseNode;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.NodeEvaluator;
import com.oracle.truffle.js.nodes.ScriptNode;
import com.oracle.truffle.js.nodes.access.GlobalObjectNode;
import com.oracle.truffle.js.nodes.access.JSConstantNode;
import com.oracle.truffle.js.nodes.access.RealmNode;
import com.oracle.truffle.js.nodes.cast.JSToDoubleNode;
import com.oracle.truffle.js.nodes.cast.JSToInt32Node;
import com.oracle.truffle.js.nodes.cast.JSToNumberNode;
import com.oracle.truffle.js.nodes.cast.JSToStringNode;
import com.oracle.truffle.js.nodes.cast.JSTrimWhitespaceNode;
import com.oracle.truffle.js.nodes.function.EvalNode;
import com.oracle.truffle.js.nodes.function.JSBuiltin;
import com.oracle.truffle.js.nodes.function.JSBuiltinNode;
import com.oracle.truffle.js.nodes.function.JSLoadNode;
import com.oracle.truffle.js.runtime.AbstractJavaScriptLanguage;
import com.oracle.truffle.js.runtime.Boundaries;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.Evaluator;
import com.oracle.truffle.js.runtime.ExitException;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSErrorType;
import com.oracle.truffle.js.runtime.JSException;
import com.oracle.truffle.js.runtime.JSRealm;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.JSTruffleOptions;
import com.oracle.truffle.js.runtime.builtins.BuiltinEnum;
import com.oracle.truffle.js.runtime.builtins.JSArgumentsObject;
import com.oracle.truffle.js.runtime.builtins.JSArrayBuffer;
import com.oracle.truffle.js.runtime.builtins.JSFunction;
import com.oracle.truffle.js.runtime.builtins.JSURLDecoder;
import com.oracle.truffle.js.runtime.builtins.JSURLEncoder;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.Undefined;
import com.oracle.truffle.js.runtime.truffleinterop.JSInteropNodeUtil;
import com.oracle.truffle.js.runtime.truffleinterop.JSInteropUtil;

/**
 * Contains builtins for the global object.
 */
public class GlobalBuiltins extends JSBuiltinsContainer.SwitchEnum<GlobalBuiltins.Global> {

    protected GlobalBuiltins() {
        super(null, Global.class);
    }

    public enum Global implements BuiltinEnum<Global> {
        isNaN(1),
        isFinite(1),
        parseFloat(1),
        parseInt(2),
        encodeURI(1),
        encodeURIComponent(1),
        decodeURI(1),
        decodeURIComponent(1),
        eval(1),

        // Annex B
        escape(1),
        unescape(1),

        // non-standard extensions
        print(1),
        printErr(1),
        load(1),
        loadWithNewGlobal(-1),
        exit(1),
        quit(1),
        readline(1),
        readLine(1),
        read(1),
        readFully(1),
        readbuffer(1);

        private final int length;

        Global(int length) {
            this.length = length;
        }

        @Override
        public int getLength() {
            return length;
        }

        @Override
        public boolean isAnnexB() {
            return EnumSet.of(escape, unescape).contains(this);
        }
    }

    @Override
    protected Object createNode(JSContext context, JSBuiltin builtin, boolean construct, boolean newTarget, Global builtinEnum) {
        switch (builtinEnum) {
            case isNaN:
                return JSGlobalIsNaNNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case isFinite:
                return JSGlobalIsFiniteNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case parseFloat:
                return JSGlobalParseFloatNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case parseInt:
                return JSGlobalParseIntNodeGen.create(context, builtin, args().fixedArgs(2).createArgumentNodes(context));
            case encodeURI:
                return JSGlobalEncodeURINodeGen.create(context, builtin, true, args().fixedArgs(1).createArgumentNodes(context));
            case encodeURIComponent:
                return JSGlobalEncodeURINodeGen.create(context, builtin, false, args().fixedArgs(1).createArgumentNodes(context));
            case decodeURI:
                return JSGlobalDecodeURINodeGen.create(context, builtin, true, args().fixedArgs(1).createArgumentNodes(context));
            case decodeURIComponent:
                return JSGlobalDecodeURINodeGen.create(context, builtin, false, args().fixedArgs(1).createArgumentNodes(context));
            case eval:
                return JSGlobalIndirectEvalNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case escape:
                return JSGlobalUnEscapeNodeGen.create(context, builtin, false, args().fixedArgs(1).createArgumentNodes(context));
            case unescape:
                return JSGlobalUnEscapeNodeGen.create(context, builtin, true, args().fixedArgs(1).createArgumentNodes(context));
            case print:
                return JSGlobalPrintNodeGen.create(context, builtin, false, args().varArgs().createArgumentNodes(context));
            case printErr:
                return JSGlobalPrintNodeGen.create(context, builtin, true, args().varArgs().createArgumentNodes(context));
            case load:
                return JSGlobalLoadNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case loadWithNewGlobal:
                return JSGlobalLoadWithNewGlobalNodeGen.create(context, builtin, args().fixedArgs(1).varArgs().createArgumentNodes(context));
            case exit:
            case quit:
                return JSGlobalExitNodeGen.create(context, builtin, args().varArgs().createArgumentNodes(context));
            case readline:
                return JSGlobalReadLineNodeGen.create(context, builtin, new JavaScriptNode[]{JSConstantNode.createUndefined()});
            case readLine:
                return JSGlobalReadLineNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case readFully:
            case read:
                return JSGlobalReadFullyNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case readbuffer:
                return JSGlobalReadBufferNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
        }
        return null;
    }

    private abstract static class JSGlobalOperation extends JSBuiltinNode {

        JSGlobalOperation(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Child private JSToStringNode toString1Node;

        protected final String toString1(Object target) {
            if (toString1Node == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                toString1Node = insert(JSToStringNode.create());
            }
            return toString1Node.executeString(target);
        }
    }

    public static final class ResolvePathNameNode extends JavaScriptBaseNode {
        private final JSContext context;

        public ResolvePathNameNode(JSContext context) {
            this.context = context;
        }

        public String resolvePathName(String name) {
            return resolvePathName(GlobalObjectNode.getGlobalObject(context), name);
        }

        public static String resolvePathName(JSContext context, String name) {
            return resolvePathName(context.getRealm().getGlobalObject(), name);
        }

        @TruffleBoundary
        private static String resolvePathName(DynamicObject globalObject, String name) {
            File f = new File(name);
            if (f.isAbsolute()) {
                return name;
            } else {
                Object dir = JSObject.get(globalObject, "__DIR__");
                return (dir == null || dir == Undefined.instance) ? name : Paths.get(dir.toString(), name).toString();
            }
        }
    }

    public abstract static class JSLoadOperation extends JSGlobalOperation {
        public JSLoadOperation(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Child private JSLoadNode loadNode;

        protected final Object runImpl(JSRealm realm, Source source) {
            if (loadNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                loadNode = insert(JSLoadNode.create(realm.getContext()));
            }
            return loadNode.executeLoad(source, realm);
        }

        @TruffleBoundary
        protected static ScriptNode loadStringImpl(JSContext ctxt, String name, String script) {
            long startTime = JSTruffleOptions.ProfileTime ? System.nanoTime() : 0L;
            try {
                return ((NodeEvaluator) ctxt.getEvaluator()).evalCompile(ctxt, script, name);
            } finally {
                if (JSTruffleOptions.ProfileTime) {
                    ctxt.getTimeProfiler().printElapsed(startTime, "parsing " + name);
                }
            }
        }

        @TruffleBoundary
        protected final Source sourceFromURL(URL url) {
            try {
                return Source.newBuilder(url).name(url.getFile()).mimeType(AbstractJavaScriptLanguage.APPLICATION_MIME_TYPE).build();
            } catch (IOException e) {
                throw JSException.create(JSErrorType.EvalError, e.getMessage(), e, this);
            }
        }

        @TruffleBoundary
        protected final Source sourceFromFileName(String fileName) {
            try {
                return AbstractJavaScriptLanguage.sourceFromFileName(fileName);
            } catch (IOException e) {
                throw JSException.create(JSErrorType.EvalError, e.getMessage(), e, this);
            }
        }
    }

    /**
     * Implementation of ECMAScript 5.1 15.1.2.4 isNaN() method.
     *
     */
    public abstract static class JSGlobalIsNaNNode extends JSBuiltinNode {

        public JSGlobalIsNaNNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected static boolean isNaN(@SuppressWarnings("unused") int value) {
            return false;
        }

        @Specialization
        protected static boolean isNaN(double value) {
            return Double.isNaN(value);
        }

        @Specialization(guards = "!isUndefined(value)")
        protected static boolean isNaN(Object value,
                        @Cached("create()") JSToDoubleNode toDoubleNode) {
            return isNaN(toDoubleNode.executeDouble(value));
        }

        @Specialization(guards = "isUndefined(value)")
        protected static boolean isNaN(@SuppressWarnings("unused") Object value) {
            return true;
        }
    }

    /**
     * Implementation of ECMAScript 5.1 15.1.2.5 isFinite() method.
     *
     */
    public abstract static class JSGlobalIsFiniteNode extends JSBuiltinNode {

        public JSGlobalIsFiniteNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected static boolean isFinite(@SuppressWarnings("unused") int value) {
            return true;
        }

        @Specialization
        protected static boolean isFinite(double value) {
            return !Double.isInfinite(value) && !Double.isNaN(value);
        }

        @Specialization(guards = "!isUndefined(value)")
        protected static boolean isFinite(Object value,
                        @Cached("create()") JSToDoubleNode toDoubleNode) {
            return isFinite(toDoubleNode.executeDouble(value));
        }

        @Specialization(guards = "isUndefined(value)")
        protected static boolean isFinite(@SuppressWarnings("unused") Object value) {
            return false;
        }
    }

    /**
     * Implementation of ECMAScript 5.1 15.1.2.3 parseFloat() method.
     */
    public abstract static class JSGlobalParseFloatNode extends JSGlobalOperation {
        private final BranchProfile exponentBranch = BranchProfile.create();
        @Child protected JSTrimWhitespaceNode trimWhitespaceNode;

        public JSGlobalParseFloatNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected int parseFloat(int value) {
            return value;
        }

        @Specialization
        protected double parseFloat(double value, @Cached("createBinaryProfile()") ConditionProfile negativeZero) {
            if (negativeZero.profile(JSRuntime.isNegativeZero(value))) {
                return 0;
            }
            return value;
        }

        @Specialization
        protected double parseFloat(@SuppressWarnings("unused") boolean value) {
            return Double.NaN;
        }

        @Specialization(guards = "isUndefined(value)")
        protected double parseFloatUndefined(@SuppressWarnings("unused") Object value) {
            return Double.NaN;
        }

        @Specialization(guards = "isJSNull(value)")
        protected double parseFloatNull(@SuppressWarnings("unused") Object value) {
            return Double.NaN;
        }

        @Specialization
        protected double parseFloat(String value) {
            return parseFloatIntl(value);
        }

        @Specialization(guards = "isJSObject(value)")
        protected double parseFloat(DynamicObject value) {
            return parseFloatIntl(toString1(value));
        }

        @Specialization(guards = "!isDynamicObject(value)")
        protected double parseFloat(TruffleObject value) {
            return parseFloatIntl(toString1(value));
        }

        private double parseFloatIntl(String inputString) {
            String trimmedString = trimWhitespace(inputString);
            return parseFloatIntl2(trimmedString);
        }

        @TruffleBoundary
        private double parseFloatIntl2(String trimmedString) {
            if (trimmedString.startsWith(JSRuntime.INFINITY_STRING) || trimmedString.startsWith(JSRuntime.POSITIVE_INFINITY_STRING)) {
                return Double.POSITIVE_INFINITY;
            } else if (trimmedString.startsWith(JSRuntime.NEGATIVE_INFINITY_STRING)) {
                return Double.NEGATIVE_INFINITY;
            }
            try {
                FloatParser parser = new FloatParser(trimmedString, exponentBranch);
                return parser.getResult();
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }

        protected String trimWhitespace(String s) {
            if (trimWhitespaceNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                trimWhitespaceNode = insert(JSTrimWhitespaceNode.create());
            }
            return trimWhitespaceNode.executeString(s);
        }
    }

    /**
     * Implementation of ECMAScript 5.1 15.1.2.2 parseInt() method.
     *
     */
    public abstract static class JSGlobalParseIntNode extends JSBuiltinNode {

        public JSGlobalParseIntNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Child private JSToInt32Node toInt32Node;
        private final BranchProfile needsNaN = BranchProfile.create();

        protected int toInt32(Object target) {
            if (toInt32Node == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                toInt32Node = insert(JSToInt32Node.create());
            }
            return toInt32Node.executeInt(target);
        }

        @Specialization(guards = "isUndefined(radix0)")
        protected int parseIntNoRadix(int thing, @SuppressWarnings("unused") Object radix0) {
            return thing;
        }

        @Specialization(guards = "!isUndefined(radix0)")
        protected Object parseInt(int thing, Object radix0,
                        @Cached("create()") BranchProfile needsRadixConversion) {
            int radix = toInt32(radix0);
            if (radix == 10 || radix == 0) {
                return thing;
            }
            if (radix < 2 || radix > 36) {
                needsNaN.enter();
                return Double.NaN;
            }
            needsRadixConversion.enter();
            return convertToRadix(thing, radix);
        }

        @Specialization(guards = {"hasRegularToStringInInt32Range(thing)", "isUndefined(radix0)"})
        protected int parseIntDoubleToInt(double thing, @SuppressWarnings("unused") Object radix0) {
            return (int) thing;
        }

        @Specialization(guards = {"hasRegularToString(thing)", "isUndefined(radix0)"})
        protected double parseIntNoRadix(double thing, @SuppressWarnings("unused") Object radix0) {
            return JSRuntime.truncateDouble2(thing);
        }

        // double specializations should not be used for numbers
        // that use a scientific notation when stringified
        // (parseInt(1e21) === parseInt('1e21') === 1)
        protected static boolean hasRegularToString(double value) {
            return (-1e21 < value && value <= -1e-6) || (value == 0) || (1e-6 <= value && value < 1e21);
        }

        protected static boolean hasRegularToStringInInt32Range(double value) {
            return (Integer.MIN_VALUE - 1.0 < value && value <= -1e-6) || (value == 0) || (1e-6 <= value && value < Integer.MAX_VALUE + 1.0);
        }

        @Specialization(guards = "hasRegularToString(thing)")
        protected double parseInt(double thing, Object radix0,
                        @Cached("create()") BranchProfile needsRadixConversion) {
            int radix = toInt32(radix0);
            if (radix == 0) {
                radix = 10;
            } else if (radix < 2 || radix > 36) {
                needsNaN.enter();
                return Double.NaN;
            }
            double truncated = JSRuntime.truncateDouble2(thing);
            if (radix == 10) {
                return truncated;
            } else {
                needsRadixConversion.enter();
                return convertToRadix(truncated, radix);
            }
        }

        @Specialization
        protected Object parseIntGeneric(Object thing, Object radix0,
                        @Cached("create()") JSToStringNode toStringNode,
                        @Cached("create()") JSTrimWhitespaceNode trimWhitespaceNode,
                        @Cached("create()") BranchProfile needsRadix16,
                        @Cached("create()") BranchProfile needsDontFitLong,
                        @Cached("create()") BranchProfile needsTrimming) {
            String inputString = trimWhitespaceNode.executeString(toStringNode.executeString(thing));
            int radix = toInt32(radix0);
            if (inputString.length() <= 0) {
                needsNaN.enter();
                return Double.NaN;
            }

            if (radix == 16 || radix == 0) {
                needsRadix16.enter();
                if (hasHexStart(inputString)) {
                    needsTrimming.enter();
                    if (inputString.charAt(0) == '0') {
                        inputString = Boundaries.substring(inputString, 2);
                    } else {
                        String sign = Boundaries.substring(inputString, 0, 1);
                        String number = Boundaries.substring(inputString, 3);
                        inputString = JSRuntime.stringConcat(sign, number);
                    }
                    radix = 16; // could be 0
                } else if (radix == 0) {
                    radix = 10;
                }
            } else if (radix < 2 || radix > 36) {
                needsNaN.enter();
                return Double.NaN;
            }

            String valueString = trimInvalidChars(inputString, radix);
            int len = valueString.length();
            if (len <= 0) {
                needsNaN.enter();
                return Double.NaN;
            }
            if ((radix <= 10 && len >= 18) || (radix <= 16 && len >= 15) || (radix > 16 && len >= 12)) {
                needsDontFitLong.enter();
                if (radix == 10) {
                    // parseRawDontFitLong() can produce an incorrect result
                    // due to subtle rounding errors (for radix 10) but the spec.
                    // requires exact processing for this radix
                    return parseDouble(valueString);
                } else {
                    return JSRuntime.parseRawDontFitLong(valueString, radix);
                }
            }
            try {
                return JSRuntime.parseRawFitsLong(valueString, radix);
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }

        @TruffleBoundary
        private static double parseDouble(String s) {
            return Double.parseDouble(s);
        }

        private static Object convertToRadix(int thing, int radix) {
            assert radix >= 2 && radix <= 36;
            boolean negative = thing < 0;
            long value = thing;
            if (negative) {
                value = -value;
            }
            long result = 0;
            long radixVal = 1;
            while (value != 0) {
                long digit = value % 10;
                if (digit >= radix) {
                    return Double.NaN;
                }
                result += digit * radixVal;
                value /= 10;
                radixVal *= radix;
            }
            if (negative) {
                result = -result;
            }
            return JSRuntime.longToIntOrDouble(result);
        }

        private static double convertToRadix(double thing, int radix) {
            assert (radix >= 2 && radix <= 36);
            boolean negative = thing < 0;
            double value = negative ? -thing : thing;
            double result = 0;
            double radixVal = 1;
            while (value != 0) {
                double digit = (value % 10);
                if (digit >= radix) {
                    return Double.NaN;
                }
                result += digit * radixVal;
                value -= digit;
                value /= 10;
                radixVal *= radix;
            }
            return negative ? -result : result;
        }

        private static boolean hasHexStart(String inputString) {
            int length = inputString.length();
            if (length >= 2 && inputString.charAt(0) == '0') {
                char c1 = inputString.charAt(1);
                return (c1 == 'x' || c1 == 'X');
            } else if (length >= 3 && inputString.charAt(1) == '0') {
                char c0 = inputString.charAt(0);
                if (c0 == '-' || c0 == '+') {
                    char c2 = inputString.charAt(2);
                    return (c2 == 'x' || c2 == 'X');
                }
            }
            return false;
        }

        @TruffleBoundary
        private static String trimInvalidChars(String thing, int radix) {
            return thing.substring(0, validStringLength(thing, radix));
        }

        private static int validStringLength(String thing, int radix) {
            int pos = 0;
            while (pos < thing.length()) {
                char c = thing.charAt(pos);
                if (!(JSRuntime.valueInRadix(c, radix) >= 0 || c == '+' || c == '-')) {
                    break;
                }
                pos++;
            }
            return pos;
        }
    }

    /**
     * Implementation of ECMAScript 5.1 15.1.3.3 encodeURI() and of ECMAScript 5.1 15.1.3.4
     * encodeURIComponent().
     *
     */
    public abstract static class JSGlobalEncodeURINode extends JSGlobalOperation {

        private final JSURLEncoder encoder;

        public JSGlobalEncodeURINode(JSContext context, JSBuiltin builtin, boolean isSpecial) {
            super(context, builtin);
            this.encoder = new JSURLEncoder(isSpecial);
        }

        @Specialization
        protected String encodeURI(Object value) {
            return encoder.encode(toString1(value));
        }
    }

    /**
     * Implementation of ECMAScript 5.1 15.1.3.1 decodeURI() and of ECMAScript 5.1 15.1.3.2
     * decodeURIComponent().
     *
     */
    public abstract static class JSGlobalDecodeURINode extends JSGlobalOperation {

        private final JSURLDecoder decoder;

        public JSGlobalDecodeURINode(JSContext context, JSBuiltin builtin, boolean isSpecial) {
            super(context, builtin);
            this.decoder = new JSURLDecoder(isSpecial);
        }

        @Specialization
        protected String decodeURI(Object value) {
            return decoder.decode(toString1(value));
        }
    }

    /**
     * This node is used only for indirect calls to eval. Direct calls are handled by
     * {@link EvalNode}.
     */
    public abstract static class JSGlobalIndirectEvalNode extends JSBuiltinNode {
        @Child private RealmNode realmNode;

        public JSGlobalIndirectEvalNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
            this.realmNode = RealmNode.create(context);
        }

        @Specialization(guards = "isString(source)")
        protected Object indirectEvalString(VirtualFrame frame, Object source) {
            JSRealm realm = realmNode.execute(frame);
            return indirectEvalImpl(realm, source);
        }

        @TruffleBoundary(transferToInterpreterOnException = false)
        private Object indirectEvalImpl(JSRealm realm, Object source) {
            String sourceText = source.toString();
            String sourceName = null;
            if (getContext().isOptionV8CompatibilityMode()) {
                sourceName = EvalNode.findAndFormatEvalOrigin(null);
            }
            if (sourceName == null) {
                sourceName = Evaluator.EVAL_SOURCE_NAME;
            }
            return getContext().getEvaluator().evaluate(realm, this, Source.newBuilder(sourceText).name(sourceName).mimeType(AbstractJavaScriptLanguage.APPLICATION_MIME_TYPE).build());
        }

        @Specialization(guards = "!isString(source)")
        protected Object indirectEval(Object source) {
            return source;
        }
    }

    /**
     * Implementation of ECMAScript 5.1 B.2.1 escape() method and of ECMAScript 5.1 B.2.2 unescape()
     * method.
     *
     */
    public abstract static class JSGlobalUnEscapeNode extends JSGlobalOperation {
        private final boolean unescape;

        public JSGlobalUnEscapeNode(JSContext context, JSBuiltin builtin, boolean unescape) {
            super(context, builtin);
            this.unescape = unescape;
        }

        @Specialization
        protected String escape(Object value) {
            String s = toString1(value);
            return unescape ? StringEscape.unescape(s) : StringEscape.escape(s);
        }
    }

    /**
     * Non-standard print()/printErr() method to write to the console.
     */
    public abstract static class JSGlobalPrintNode extends JSGlobalOperation {

        private final ConditionProfile argumentsCount = ConditionProfile.createBinaryProfile();
        private final boolean useErr;

        public JSGlobalPrintNode(JSContext context, JSBuiltin builtin, boolean useErr) {
            super(context, builtin);
            this.useErr = useErr;
        }

        @Specialization
        protected Object print(Object[] arguments) {
            // without a StringBuilder, synchronization fails testnashorn JDK-8041998.js
            StringBuilder builder = new StringBuilder();
            if (argumentsCount.profile(arguments.length == 1)) {
                Boundaries.builderAppend(builder, toString1(arguments[0]));
            } else {
                for (int i = 0; i < arguments.length; i++) {
                    if (i != 0) {
                        Boundaries.builderAppend(builder, ' ');
                    }
                    Boundaries.builderAppend(builder, toString1(arguments[i]));
                }
            }
            return printIntl(builder);
        }

        @TruffleBoundary
        private Object printIntl(StringBuilder builder) {
            builder.append(JSRuntime.LINE_SEPARATOR);
            PrintWriter writer = useErr ? getContext().getErrorWriter() : getContext().getWriter();
            writer.print(builder.toString());
            writer.flush();
            return Undefined.instance;
        }
    }

    /**
     * Inspired by jdk.nashorn.internal.runtime.Context.load(...).
     */
    @ImportStatic(value = JSInteropUtil.class)
    public abstract static class JSGlobalLoadNode extends JSLoadOperation {
        // nashorn load pseudo URL prefixes
        private static final String LOAD_CLASSPATH = "classpath:";
        private static final String LOAD_FX = "fx:";
        private static final String LOAD_NASHORN = "nashorn:";
        // nashorn default paths
        private static final String RESOURCES_PATH = "resources/";
        private static final String FX_RESOURCES_PATH = "resources/fx/";
        private static final String NASHORN_BASE_PATH = "jdk/nashorn/internal/runtime/";
        private static final String NASHORN_PARSER_JS = "nashorn:parser.js";
        private static final String NASHORN_MOZILLA_COMPAT_JS = "nashorn:mozilla_compat.js";

        private static final String EVAL_OBJ_FILE_NAME = "name";
        private static final String EVAL_OBJ_SOURCE = "script";

        @Child private RealmNode realmNode;

        public JSGlobalLoadNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
            this.realmNode = RealmNode.create(context);
        }

        @Specialization
        protected Object loadString(VirtualFrame frame, String path) {
            Source source = sourceFromPath(path);
            return runImpl(realmNode.execute(frame), source);
        }

        @TruffleBoundary
        private Source sourceFromPath(String path) {
            Source source = null;
            if (path.indexOf(':') != -1) {
                source = sourceFromURI(path);
                if (source != null) {
                    return source;
                }
            }

            File file = new File(path);
            if (file.isFile()) {
                source = sourceFromFileName(file.getPath());
            } else if (!file.isAbsolute()) {
                // TODO why do we need this? nashorn tests fail despite prepending __DIR__
                file = resolveScriptRelativePath(file);
                if (file.isFile()) {
                    source = sourceFromFileName(file.getPath());
                }
            }

            if (source == null) {
                throw cannotLoadScript(path);
            }
            return source;
        }

        @Specialization
        protected Object loadURL(VirtualFrame frame, URL url) {
            return runImpl(realmNode.execute(frame), sourceFromURL(url));
        }

        @Specialization
        protected Object loadFile(VirtualFrame frame, File file) {
            return runImpl(realmNode.execute(frame), sourceFromFileName(file.getPath()));
        }

        @Specialization(guards = "isForeignObject(scriptObj)")
        protected Object loadTruffleObject(VirtualFrame frame, TruffleObject scriptObj,
                        @Cached("createUnbox()") Node unboxNode) {
            if (JavaInterop.isJavaObject(File.class, scriptObj)) {
                return loadFile(frame, JavaInterop.asJavaObject(File.class, scriptObj));
            } else if (JavaInterop.isJavaObject(URL.class, scriptObj)) {
                return loadURL(frame, JavaInterop.asJavaObject(URL.class, scriptObj));
            } else {
                Object unboxed = JSInteropNodeUtil.unbox(scriptObj, unboxNode);
                String stringPath = toString1(unboxed);
                return loadString(frame, stringPath);
            }
        }

        @Specialization(guards = "isJSObject(scriptObj)")
        protected Object loadScriptObj(VirtualFrame frame, DynamicObject scriptObj,
                        @Cached("create()") JSToStringNode fileNameToStringNode,
                        @Cached("create()") JSToStringNode sourceToStringNode) {
            if (JSObject.hasProperty(scriptObj, EVAL_OBJ_FILE_NAME) && JSObject.hasProperty(scriptObj, EVAL_OBJ_SOURCE)) {
                Object fileNameObj = JSObject.get(scriptObj, EVAL_OBJ_FILE_NAME);
                Object sourceObj = JSObject.get(scriptObj, EVAL_OBJ_SOURCE);
                return evalImpl(frame, fileNameToStringNode.executeString(fileNameObj), sourceToStringNode.executeString(sourceObj));
            } else {
                throw cannotLoadScript(scriptObj);
            }
        }

        @Specialization
        protected Object loadMap(VirtualFrame frame, Map<?, ?> map,
                        @Cached("create()") JSToStringNode fileNameToStringNode,
                        @Cached("create()") JSToStringNode sourceToStringNode) {
            if (Boundaries.mapContainsKey(map, EVAL_OBJ_FILE_NAME) && Boundaries.mapContainsKey(map, EVAL_OBJ_SOURCE)) {
                Object fileNameObj = Boundaries.mapGet(map, EVAL_OBJ_FILE_NAME);
                Object sourceObj = Boundaries.mapGet(map, EVAL_OBJ_SOURCE);
                return evalImpl(frame, fileNameToStringNode.executeString(fileNameObj), sourceToStringNode.executeString(sourceObj));
            } else {
                throw cannotLoadScript(map);
            }
        }

        @Specialization(guards = "isFallback(fileName)")
        protected Object loadConvertToString(VirtualFrame frame, Object fileName,
                        @Cached("create()") JSToStringNode fileNameToStringNode) {
            return loadString(frame, fileNameToStringNode.executeString(fileName));
        }

        protected static boolean isFallback(Object value) {
            return !(JSGuards.isString(value) || value instanceof URL || value instanceof File || value instanceof Map || value instanceof TruffleObject || JSGuards.isJSObject(value));
        }

        private Object evalImpl(VirtualFrame frame, String fileName, String source) {
            return loadStringImpl(getContext(), fileName, source).run(realmNode.execute(frame));
        }

        private Source sourceFromURI(String resource) {
            if (resource.startsWith(LOAD_CLASSPATH) || resource.startsWith(LOAD_NASHORN) || resource.startsWith(LOAD_FX)) {
                if (JSTruffleOptions.SubstrateVM) {
                    return null;
                } else {
                    return sourceFromResourceURL(resource);
                }
            }

            try {
                URL url = new URL(resource);
                return sourceFromURL(url);
            } catch (MalformedURLException e) {
            }
            return null;
        }

        private static Source sourceFromResourceURL(String resource) {
            assert !JSTruffleOptions.SubstrateVM;
            InputStream stream = null;
            if (resource.startsWith(LOAD_CLASSPATH)) {
                stream = ClassLoader.getSystemResourceAsStream(resource.substring(LOAD_CLASSPATH.length()));
            } else if (resource.startsWith(LOAD_NASHORN) && (resource.equals(NASHORN_PARSER_JS) || resource.equals(NASHORN_MOZILLA_COMPAT_JS))) {
                stream = JSContext.class.getResourceAsStream(RESOURCES_PATH + resource.substring(LOAD_NASHORN.length()));
            } else if (resource.startsWith(LOAD_FX)) {
                stream = ClassLoader.getSystemResourceAsStream(NASHORN_BASE_PATH + FX_RESOURCES_PATH + resource.substring(LOAD_FX.length()));
            }
            if (stream != null) {
                try {
                    return Source.newBuilder(new InputStreamReader(stream, StandardCharsets.UTF_8)).name(resource).mimeType(AbstractJavaScriptLanguage.APPLICATION_MIME_TYPE).build();
                } catch (IOException e) {
                }
            }
            return null;
        }

        private File resolveScriptRelativePath(File file) {
            String fileName = file.getPath();
            fileName = ResolvePathNameNode.resolvePathName(getContext(), fileName);
            return new File(fileName);
        }

        @TruffleBoundary
        private static JSException cannotLoadScript(Object script) {
            return Errors.createTypeError("Cannot load script: " + JSRuntime.objectToString(script));
        }
    }

    /**
     * Implementation of non-standard method loadWithNewGlobal() as defined by Nashorn.
     *
     */
    public abstract static class JSGlobalLoadWithNewGlobalNode extends JSLoadOperation {

        public JSGlobalLoadWithNewGlobalNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected Object loadURL(URL thing, @SuppressWarnings("unused") Object[] args) {
            return runImpl(createRealm(), sourceFromURL(thing));
        }

        @Specialization(guards = "!isJSObject(thing)")
        protected Object load(Object thing, @SuppressWarnings("unused") Object[] args) {
            String name = toString1(thing);
            return runImpl(createRealm(), sourceFromURL(toURL(name)));
        }

        @TruffleBoundary
        private static URL toURL(String urlStr) {
            try {
                return new URL(urlStr);
            } catch (MalformedURLException e) {
                return null;
            }
        }

        @Specialization
        protected Object load(DynamicObject thing, Object[] args, //
                        @Cached("create()") JSToStringNode toString2Node) {
            String name = toString1(JSObject.get(thing, "name"));
            String script = toString2Node.executeString(JSObject.get(thing, "script"));
            return loadIntl(name, script, args);
        }

        @TruffleBoundary
        private Object loadIntl(String name, String script, Object[] args) {
            JSRealm childRealm = createRealm();
            ScriptNode scriptNode = loadStringImpl(childRealm.getContext(), name, script);
            DynamicObject globalObject = childRealm.getGlobalObject();
            if (args.length > 0) {
                DynamicObject argObj = JSArgumentsObject.createStrict(childRealm, args);
                JSRuntime.createDataProperty(globalObject, JSFunction.ARGUMENTS, argObj);
            }
            return scriptNode.run(JSArguments.create(globalObject, JSFunction.create(childRealm, scriptNode.getFunctionData()), args));
        }

        @TruffleBoundary
        private JSRealm createRealm() {
            return getContext().createChildContext().getRealm();
        }
    }

    /**
     * Non-standard global exit() and quit() functions to provide compatibility with Nashorn (both)
     * and V8 (only quit).
     */
    public abstract static class JSGlobalExitNode extends JSBuiltinNode {

        public JSGlobalExitNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected Object exit(Object[] arg,
                        @Cached("create()") JSToNumberNode toNumberNode) {
            int exitCode = arg.length == 0 ? 0 : (int) JSRuntime.toInteger(toNumberNode.executeNumber(arg[0]));
            throw new ExitException(exitCode, this);
        }
    }

    /**
     * Non-standard readline() and readLine() to provide compatibility with V8 and Nashorn,
     * respectively.
     */
    public abstract static class JSGlobalReadLineNode extends JSGlobalOperation {

        public JSGlobalReadLineNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected String readLine(Object prompt) {
            String promptString = null;
            if (prompt != Undefined.instance) {
                promptString = toString1(prompt);
            }
            return doReadLine(promptString);
        }

        @TruffleBoundary
        private static String doReadLine(String promptString) {
            if (promptString != null) {
                System.out.println(promptString);
            }
            try {
                final BufferedReader inReader = new BufferedReader(new InputStreamReader(System.in));
                return inReader.readLine();
            } catch (Exception ex) {
                throw Errors.createError(ex.getMessage());
            }
        }
    }

    /**
     * Non-standard read() and readFully() to provide compatibility with V8 and Nashorn,
     * respectively.
     */
    public abstract static class JSGlobalReadFullyNode extends JSBuiltinNode {
        private static final int BUFFER_SIZE = 2048;

        public JSGlobalReadFullyNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        @TruffleBoundary
        protected String read(Object fileParam) {
            File file = null;

            if (fileParam instanceof File) {
                file = (File) fileParam;
            } else if (JSRuntime.isString(fileParam)) {
                file = new File(ResolvePathNameNode.resolvePathName(getContext(), fileParam.toString()));
            } else if (fileParam instanceof TruffleObject && JavaInterop.isJavaObject(File.class, (TruffleObject) fileParam)) {
                file = JavaInterop.asJavaObject(File.class, (TruffleObject) fileParam);
            }

            if (file == null || !file.isFile()) {
                throw Errors.createTypeError("Not a file: " + (file == null ? fileParam : file));
            }

            try {
                return readImpl(file);
            } catch (Exception ex) {
                throw JSException.create(JSErrorType.Error, ex.getMessage(), ex, this);
            }
        }

        private static String readImpl(File file) throws IOException {
            final StringBuilder sb = new StringBuilder();
            final char[] arr = new char[BUFFER_SIZE];
            try (final BufferedReader reader = Files.newBufferedReader(file.toPath())) {
                int numChars;
                while ((numChars = reader.read(arr, 0, arr.length)) > 0) {
                    sb.append(arr, 0, numChars);
                }
            }
            return sb.toString();
        }
    }

    /**
     * Non-standard readbuffer() to provide compatibility with V8.
     */
    public abstract static class JSGlobalReadBufferNode extends JSBuiltinNode {

        public JSGlobalReadBufferNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        @TruffleBoundary
        protected final DynamicObject readbuffer(Object fileParam) {
            File file = null;

            if (fileParam instanceof File) {
                file = (File) fileParam;
            } else if (JSRuntime.isString(fileParam)) {
                file = new File(ResolvePathNameNode.resolvePathName(getContext(), fileParam.toString()));
            }

            if (file == null || !file.isFile()) {
                throw Errors.createTypeError("Not a file: " + (file == null ? fileParam : file));
            }

            try {
                final byte[] bytes = Files.readAllBytes(file.toPath());
                final DynamicObject arrayBuffer;
                if (getContext().isOptionDirectByteBuffer()) {
                    ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
                    buffer.put(bytes).rewind();
                    arrayBuffer = JSArrayBuffer.createDirectArrayBuffer(getContext(), buffer);
                } else {
                    arrayBuffer = JSArrayBuffer.createArrayBuffer(getContext(), bytes);
                }
                return arrayBuffer;
            } catch (Exception ex) {
                throw JSException.create(JSErrorType.Error, ex.getMessage(), ex, this);
            }
        }
    }
}