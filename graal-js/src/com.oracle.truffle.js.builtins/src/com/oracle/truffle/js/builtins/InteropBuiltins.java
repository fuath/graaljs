/*
 * Copyright (c) 2012, 2015, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.builtins;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.Message;
import com.oracle.truffle.api.interop.MessageResolution;
import com.oracle.truffle.api.interop.Resolve;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.interop.java.JavaInterop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.js.builtins.InteropBuiltinsFactory.InteropConstructNodeGen;
import com.oracle.truffle.js.builtins.InteropBuiltinsFactory.InteropCreateForeignDynamicObjectNodeGen;
import com.oracle.truffle.js.builtins.InteropBuiltinsFactory.InteropCreateForeignObjectNodeGen;
import com.oracle.truffle.js.builtins.InteropBuiltinsFactory.InteropEvalNodeGen;
import com.oracle.truffle.js.builtins.InteropBuiltinsFactory.InteropExecuteNodeGen;
import com.oracle.truffle.js.builtins.InteropBuiltinsFactory.InteropExportNodeGen;
import com.oracle.truffle.js.builtins.InteropBuiltinsFactory.InteropExportUnboundNodeGen;
import com.oracle.truffle.js.builtins.InteropBuiltinsFactory.InteropGetSizeNodeGen;
import com.oracle.truffle.js.builtins.InteropBuiltinsFactory.InteropHasKeysNodeGen;
import com.oracle.truffle.js.builtins.InteropBuiltinsFactory.InteropHasSizePropertyNodeGen;
import com.oracle.truffle.js.builtins.InteropBuiltinsFactory.InteropImportNodeGen;
import com.oracle.truffle.js.builtins.InteropBuiltinsFactory.InteropIsBoxedPrimitiveNodeGen;
import com.oracle.truffle.js.builtins.InteropBuiltinsFactory.InteropIsExecutableNodeGen;
import com.oracle.truffle.js.builtins.InteropBuiltinsFactory.InteropIsInstantiableNodeGen;
import com.oracle.truffle.js.builtins.InteropBuiltinsFactory.InteropIsNullNodeGen;
import com.oracle.truffle.js.builtins.InteropBuiltinsFactory.InteropKeysNodeGen;
import com.oracle.truffle.js.builtins.InteropBuiltinsFactory.InteropParseNodeGen;
import com.oracle.truffle.js.builtins.InteropBuiltinsFactory.InteropReadPropertyNodeGen;
import com.oracle.truffle.js.builtins.InteropBuiltinsFactory.InteropUnboxValueNodeGen;
import com.oracle.truffle.js.builtins.InteropBuiltinsFactory.InteropWritePropertyNodeGen;
import com.oracle.truffle.js.nodes.cast.JSToStringNode;
import com.oracle.truffle.js.nodes.function.JSBuiltin;
import com.oracle.truffle.js.nodes.function.JSBuiltinNode;
import com.oracle.truffle.js.nodes.interop.ExportValueNode;
import com.oracle.truffle.js.nodes.interop.JSForeignToJSTypeNode;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.Evaluator;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSRealm;
import com.oracle.truffle.js.runtime.JSTruffleOptions;
import com.oracle.truffle.js.runtime.TruffleGlobalScopeImpl;
import com.oracle.truffle.js.runtime.builtins.BuiltinEnum;
import com.oracle.truffle.js.runtime.objects.Null;
import com.oracle.truffle.js.runtime.objects.Undefined;

public final class InteropBuiltins extends JSBuiltinsContainer.SwitchEnum<InteropBuiltins.Interop> {
    protected InteropBuiltins() {
        super(JSRealm.INTEROP_CLASS_NAME, Interop.class);
    }

    public enum Interop implements BuiltinEnum<Interop> {
        isExecutable(1),
        isBoxed(1),
        isBoxedPrimitive(1), // @deprecated
        isNull(1),
        hasSize(1),
        hasSizeProperty(1), // @deprecated
        read(2),
        readProperty(2), // @deprecated
        write(3),
        writeProperty(3), // @deprecated
        unbox(1),
        unboxValue(1), // @deprecated
        construct(1),
        execute(1),
        getSize(1),
        // import and export
        export(2),
        exportUnbound(2),
        import_(1),
        eval(2),
        parse(2),
        keys(1),
        hasKeys(1),
        isInstantiable(1),

        createForeignObject(0) {
            @Override
            public boolean isAOTSupported() {
                return false;
            }
        },
        createForeignDynamicObject(0) {
            @Override
            public boolean isAOTSupported() {
                return false;
            }
        };

        private final int length;

        Interop(int length) {
            this.length = length;
        }

        @Override
        public int getLength() {
            return length;
        }
    }

    @Override
    protected Object createNode(JSContext context, JSBuiltin builtin, boolean construct, boolean newTarget, Interop builtinEnum) {
        switch (builtinEnum) {
            case isExecutable:
                return InteropIsExecutableNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case isBoxed:
            case isBoxedPrimitive:
                return InteropIsBoxedPrimitiveNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case isNull:
                return InteropIsNullNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case isInstantiable:
                return InteropIsInstantiableNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case hasSize:
            case hasSizeProperty:
                return InteropHasSizePropertyNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case read:
            case readProperty:
                return InteropReadPropertyNodeGen.create(context, builtin, args().fixedArgs(2).createArgumentNodes(context));
            case write:
            case writeProperty:
                return InteropWritePropertyNodeGen.create(context, builtin, args().fixedArgs(3).createArgumentNodes(context));
            case unbox:
            case unboxValue:
                return InteropUnboxValueNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case construct:
                return InteropConstructNodeGen.create(context, builtin, args().fixedArgs(1).varArgs().createArgumentNodes(context));
            case execute:
                return InteropExecuteNodeGen.create(context, builtin, args().fixedArgs(1).varArgs().createArgumentNodes(context));
            case getSize:
                return InteropGetSizeNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case export:
                return InteropExportNodeGen.create(context, builtin, args().fixedArgs(2).createArgumentNodes(context));
            case exportUnbound:
                return InteropExportUnboundNodeGen.create(context, builtin, args().fixedArgs(2).createArgumentNodes(context));
            case import_:
                return InteropImportNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case eval:
                return InteropEvalNodeGen.create(context, builtin, args().fixedArgs(2).createArgumentNodes(context));
            case parse:
                return InteropParseNodeGen.create(context, builtin, args().fixedArgs(2).createArgumentNodes(context));
            case keys:
                return InteropKeysNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case hasKeys:
                return InteropHasKeysNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case createForeignObject:
                if (!JSTruffleOptions.SubstrateVM) {
                    return InteropCreateForeignObjectNodeGen.create(context, builtin, args().fixedArgs(0).createArgumentNodes(context));
                }
                break;
            case createForeignDynamicObject:
                if (!JSTruffleOptions.SubstrateVM) {
                    return InteropCreateForeignDynamicObjectNodeGen.create(context, builtin, args().fixedArgs(0).createArgumentNodes(context));
                }
                break;
        }
        return null;
    }

    abstract static class InteropExportNode extends JSBuiltinNode {
        @Child ExportValueNode export = ExportValueNode.create();

        InteropExportNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected TruffleObject execute(String identifier, TruffleObject value) {
            TruffleObject result = (TruffleObject) export.executeWithTarget(value, Undefined.instance);
            getContext().getInteropRuntime().getMultilanguageGlobal().exportTruffleObject(identifier, result);
            return result;
        }

        @Specialization(guards = "!isTruffleObject(value)")
        @TruffleBoundary
        protected TruffleObject execute(@SuppressWarnings("unused") String identifier, Object value) {
            throw Errors.createTypeError("Cannot export " + value);
        }

        @Specialization(guards = "!isJavaLangString(identifier)")
        @TruffleBoundary
        protected TruffleObject execute(Object identifier, @SuppressWarnings("unused") TruffleObject value) {
            throw Errors.createTypeError("Invalid identifier " + identifier);
        }

        @Specialization(guards = {"!isTruffleObject(value)", "!isJavaLangString(identifier)"})
        @TruffleBoundary
        protected TruffleObject execute(Object identifier, Object value) {
            throw Errors.createTypeError("Invalid identifier " + identifier + " and value " + value);
        }

    }

    abstract static class InteropExportUnboundNode extends JSBuiltinNode {
        InteropExportUnboundNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected TruffleObject execute(String identifier, TruffleObject value) {
            getContext().getInteropRuntime().getMultilanguageGlobal().exportTruffleObject(identifier, value);
            return value;
        }

        @Specialization(guards = "!isTruffleObject(value)")
        @TruffleBoundary
        protected TruffleObject execute(@SuppressWarnings("unused") String identifier, Object value) {
            throw Errors.createTypeError("Cannot export " + value);
        }

        @Specialization(guards = "!isJavaLangString(identifier)")
        @TruffleBoundary
        protected TruffleObject execute(Object identifier, @SuppressWarnings("unused") TruffleObject value) {
            throw Errors.createTypeError("Invalid identifier " + identifier);
        }

        @Specialization(guards = {"!isTruffleObject(value)", "!isJavaLangString(identifier)"})
        @TruffleBoundary
        protected TruffleObject execute(Object identifier, Object value) {
            throw Errors.createTypeError("Invalid identifier " + identifier + " and value " + value);
        }

    }

    abstract static class InteropImportNode extends JSBuiltinNode {
        InteropImportNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected Object execute(Object identifier,
                        @Cached("create()") JSToStringNode toStringNode) {
            TruffleGlobalScopeImpl multilanguageGlobal = getContext().getInteropRuntime().getMultilanguageGlobal();
            Object truffleObj = multilanguageGlobal.getTruffleObject(toStringNode.executeString(identifier));
            return truffleObj == null ? Null.instance : truffleObj;
        }
    }

    abstract static class InteropIsExecutableNode extends JSBuiltinNode {
        @Child private Node isExecutable;

        InteropIsExecutableNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected boolean execute(TruffleObject obj) {
            if (isExecutable == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                isExecutable = insert(Message.IS_EXECUTABLE.createNode());
            }
            return ForeignAccess.sendIsExecutable(isExecutable, obj);
        }

        @Specialization(guards = "isJavaPrimitive(obj)")
        protected boolean executeJavaPrimitive(@SuppressWarnings("unused") Object obj) {
            return false;
        }

        @SuppressWarnings("unused")
        @Specialization(guards = {"!isTruffleObject(obj)", "!isJavaPrimitive(obj)"})
        protected boolean executeError(Object obj) {
            return false;
        }
    }

    abstract static class InteropIsBoxedPrimitiveNode extends JSBuiltinNode {
        @Child private Node isBoxedPrimitive;

        InteropIsBoxedPrimitiveNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected boolean execute(TruffleObject obj) {
            if (isBoxedPrimitive == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                isBoxedPrimitive = insert(Message.IS_BOXED.createNode());
            }
            return ForeignAccess.sendIsBoxed(isBoxedPrimitive, obj);
        }

        @Specialization(guards = "isJavaPrimitive(obj)")
        protected boolean executeJavaPrimitive(@SuppressWarnings("unused") Object obj) {
            return false;
        }

        @SuppressWarnings("unused")
        @Specialization(guards = {"!isTruffleObject(obj)", "!isJavaPrimitive(obj)"})
        protected boolean executeError(Object obj) {
            return false;
        }
    }

    abstract static class InteropIsNullNode extends JSBuiltinNode {
        @Child private Node isNull;

        InteropIsNullNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected boolean execute(TruffleObject obj) {
            if (isNull == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                isNull = insert(Message.IS_NULL.createNode());
            }
            return ForeignAccess.sendIsNull(isNull, obj);
        }

        @Specialization(guards = "isJavaPrimitive(obj)")
        protected boolean executeJavaPrimitive(@SuppressWarnings("unused") Object obj) {
            return false;
        }

        @SuppressWarnings("unused")
        @Specialization(guards = {"!isTruffleObject(obj)", "!isJavaPrimitive(obj)"})
        protected boolean executeError(Object obj) {
            return false;
        }
    }

    abstract static class InteropHasSizePropertyNode extends JSBuiltinNode {
        @Child private Node hasSizeProperty;

        InteropHasSizePropertyNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected boolean execute(TruffleObject obj) {
            if (hasSizeProperty == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                hasSizeProperty = insert(Message.HAS_SIZE.createNode());
            }
            return ForeignAccess.sendHasSize(hasSizeProperty, obj);
        }

        @Specialization(guards = "isJavaPrimitive(obj)")
        protected boolean executeJavaPrimitive(@SuppressWarnings("unused") Object obj) {
            return false;
        }

        @SuppressWarnings("unused")
        @Specialization(guards = {"!isTruffleObject(obj)", "!isJavaPrimitive(obj)"})
        protected boolean executeError(Object obj) {
            return false;
        }
    }

    abstract static class InteropReadPropertyNode extends JSBuiltinNode {
        @Child private Node read;
        @Child private JSForeignToJSTypeNode foreignConvert;

        InteropReadPropertyNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected Object execute(TruffleObject obj, Object name) {
            if (read == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                read = insert(Message.READ.createNode());
            }
            if (foreignConvert == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                foreignConvert = insert(JSForeignToJSTypeNode.create());
            }
            try {
                return foreignConvert.executeWithTarget(ForeignAccess.sendRead(read, obj, name));
            } catch (UnknownIdentifierException | UnsupportedMessageException e) {
                return Null.instance;
            }
        }

        @SuppressWarnings("unused")
        @Specialization(guards = "!isTruffleObject(obj)")
        protected boolean executeError(Object obj, Object name) {
            throw Errors.createTypeError("cannot call READ on a non-interop object");
        }
    }

    abstract static class InteropWritePropertyNode extends JSBuiltinNode {
        @Child private Node write;
        @Child ExportValueNode exportValue = ExportValueNode.create();

        InteropWritePropertyNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected Object execute(TruffleObject obj, Object name, Object value) {
            if (write == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                write = insert(Message.WRITE.createNode());
            }
            try {
                Object identifier = exportValue.executeWithTarget(name, Undefined.instance);
                Object convertedValue = exportValue.executeWithTarget(value, Undefined.instance);
                return ForeignAccess.sendWrite(write, obj, identifier, convertedValue);
            } catch (UnknownIdentifierException | UnsupportedTypeException | UnsupportedMessageException e) {
                return Null.instance;
            }
        }

        @SuppressWarnings("unused")
        @Specialization(guards = "!isTruffleObject(obj)")
        protected boolean executeError(Object obj, Object name, Object value) {
            throw Errors.createTypeError("cannot call WRITE on a non-interop object");
        }
    }

    abstract static class InteropUnboxValueNode extends JSBuiltinNode {
        @Child private Node unbox;
        @Child JSForeignToJSTypeNode foreignConvertNode;

        InteropUnboxValueNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected Object execute(TruffleObject obj) {
            if (unbox == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                unbox = insert(Message.UNBOX.createNode());
            }
            try {
                return toJSType(ForeignAccess.sendUnbox(unbox, obj));
            } catch (UnsupportedMessageException e) {
                return Null.instance;
            }
        }

        @Specialization(guards = "isJavaPrimitive(obj)")
        protected Object executeJavaPrimitive(Object obj) {
            // identity function
            return obj;
        }

        @SuppressWarnings("unused")
        @Specialization(guards = {"!isTruffleObject(obj)", "!isJavaPrimitive(obj)"})
        protected boolean executeError(Object obj) {
            throw Errors.createTypeError("cannot call UNBOX on a non-interop object");
        }

        private Object toJSType(Object value) {
            if (foreignConvertNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                foreignConvertNode = insert(JSForeignToJSTypeNode.create());
            }
            return foreignConvertNode.executeWithTarget(value);
        }

    }

    abstract static class InteropExecuteNode extends JSBuiltinNode {
        @Child private Node execute;
        @Child ExportValueNode exportValue = ExportValueNode.create();

        InteropExecuteNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected Object execute(TruffleObject obj, Object[] arguments) {
            if (execute == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                execute = insert(Message.createExecute(arguments.length).createNode());
            }
            try {
                TruffleObject target = (TruffleObject) exportValue.executeWithTarget(obj, Undefined.instance);
                Object[] convertedArgs = new Object[arguments.length];
                for (int i = 0; i < arguments.length; i++) {
                    convertedArgs[i] = exportValue.executeWithTarget(arguments[i], Undefined.instance);
                }
                return ForeignAccess.sendExecute(execute, target, convertedArgs);
            } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
                return Null.instance;
            }
        }

        @SuppressWarnings("unused")
        @Specialization(guards = "!isTruffleObject(obj)")
        protected boolean executeError(Object obj, Object[] arguments) {
            throw Errors.createTypeError("Cannot execute a non-interop object");
        }
    }

    abstract static class InteropConstructNode extends JSBuiltinNode {
        @Child private Node construct;
        @Child ExportValueNode exportValue = ExportValueNode.create();

        InteropConstructNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected Object construct(TruffleObject obj, Object[] arguments) {
            if (construct == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                construct = insert(Message.createNew(arguments.length).createNode());
            }
            try {
                TruffleObject target = (TruffleObject) exportValue.executeWithTarget(obj, Undefined.instance);
                Object[] convertedArgs = new Object[arguments.length];
                for (int i = 0; i < arguments.length; i++) {
                    convertedArgs[i] = exportValue.executeWithTarget(arguments[i], Undefined.instance);
                }
                return ForeignAccess.sendNew(construct, target, convertedArgs);
            } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
                return Null.instance;
            }
        }

        @SuppressWarnings("unused")
        @Specialization(guards = "!isTruffleObject(obj)")
        protected boolean executeError(Object obj, Object[] arguments) {
            throw Errors.createTypeError("Cannot construct a non-interop object");
        }
    }

    abstract static class InteropGetSizeNode extends JSBuiltinNode {
        @Child private Node getSize;

        InteropGetSizeNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected Object execute(TruffleObject obj) {
            if (getSize == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                getSize = insert(Message.GET_SIZE.createNode());
            }
            try {
                return ForeignAccess.sendGetSize(getSize, obj);
            } catch (UnsupportedMessageException e) {
                return Null.instance;
            }
        }

        @SuppressWarnings("unused")
        @Specialization(guards = "!isTruffleObject(obj)")
        protected boolean executeError(Object obj) {
            throw Errors.createTypeError("cannot call GET_SIZE on a non-interop object");
        }
    }

    abstract static class InteropEvalNode extends JSBuiltinNode {

        InteropEvalNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization(guards = {"isString(mimeType)", "isString(source)"})
        @TruffleBoundary
        protected Object evalString(Object mimeType, Object source) {
            String sourceText = source.toString();
            Source sourceObject = Source.newBuilder(sourceText).name(Evaluator.EVAL_SOURCE_NAME).mimeType(mimeType.toString()).build();

            CallTarget callTarget;

            try {
                callTarget = getContext().getEnv().parse(sourceObject);
            } catch (Exception e) {
                throw Errors.createError(e.getMessage());
            }

            return callTarget.call();
        }

        @SuppressWarnings("unused")
        @Specialization(guards = "!isString(mimeType) || !isString(source)")
        protected Object eval(Object mimeType, Object source) {
            throw Errors.createTypeError("Expected arguments: (String mimeType, String sourceCode)");
        }
    }

    abstract static class InteropParseNode extends JSBuiltinNode {

        InteropParseNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization(guards = {"isString(mimeType)", "isString(fileName)"})
        @TruffleBoundary
        protected Object evalString(Object mimeType, Object fileName) {
            String fileNameStr = fileName.toString();
            Source source;
            try {
                source = Source.newBuilder(new File(fileNameStr)).mimeType(mimeType.toString()).build();
            } catch (IOException e) {
                throw Errors.createError(e.getMessage());
            }

            CallTarget callTarget;
            try {
                callTarget = getContext().getEnv().parse(source);
            } catch (Exception e) {
                throw Errors.createError(e.getMessage());
            }

            return new CallTargetObject(callTarget);
        }

        @SuppressWarnings("unused")
        @Specialization(guards = "!isString(mimeType) || !isString(fileName)")
        protected Object eval(Object mimeType, Object fileName) {
            throw Errors.createTypeError("Expected arguments: (String mimeType, String fileName)");
        }
    }

    @MessageResolution(receiverType = CallTargetObject.class)
    static class CallTargetObject implements TruffleObject {
        final CallTarget callTarget;

        CallTargetObject(CallTarget callTarget) {
            this.callTarget = callTarget;
        }

        @Override
        public ForeignAccess getForeignAccess() {
            return CallTargetObjectForeign.ACCESS;
        }

        static boolean isInstance(TruffleObject obj) {
            return obj instanceof CallTargetObject;
        }

        @Resolve(message = "EXECUTE")
        abstract static class ExecuteNode extends Node {
            @TruffleBoundary
            protected Object access(CallTargetObject obj, Object[] args) {
                return obj.callTarget.call(args);
            }
        }

        @Resolve(message = "IS_EXECUTABLE")
        abstract static class IsExecutableNode extends Node {
            protected boolean access(@SuppressWarnings("unused") CallTargetObject obj) {
                return true;
            }
        }
    }

    abstract static class InteropHasKeysNode extends JSBuiltinNode {
        @Child private Node hasKeysNode;

        InteropHasKeysNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected boolean execute(TruffleObject obj) {
            if (hasKeysNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                hasKeysNode = insert(Message.HAS_KEYS.createNode());
            }
            return ForeignAccess.sendHasKeys(hasKeysNode, obj);
        }

        @Specialization(guards = "!isTruffleObject(obj)")
        protected boolean executeError(@SuppressWarnings("unused") Object obj) {
            return false;
        }
    }

    abstract static class InteropKeysNode extends JSBuiltinNode {
        @Child private Node keysNode;

        InteropKeysNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected TruffleObject execute(TruffleObject obj) {
            if (keysNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                keysNode = insert(Message.KEYS.createNode());
            }
            try {
                return ForeignAccess.sendKeys(keysNode, obj);
            } catch (UnsupportedMessageException e) {
                throw Errors.createTypeError("cannot retrieve keys of foreign object due to: %s", e.getMessage());
            }
        }

        @SuppressWarnings("unused")
        @Specialization(guards = "!isTruffleObject(obj)")
        protected boolean executeError(Object obj) {
            throw Errors.createTypeError("cannot call KEYS on a non-interop object");
        }
    }

    abstract static class InteropIsInstantiableNode extends JSBuiltinNode {
        @Child private Node isInstantiable;

        InteropIsInstantiableNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected boolean execute(TruffleObject obj) {
            if (isInstantiable == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                isInstantiable = insert(Message.IS_INSTANTIABLE.createNode());
            }
            return ForeignAccess.sendIsInstantiable(isInstantiable, obj);
        }

        @Specialization(guards = "!isTruffleObject(obj)")
        protected boolean executeNonObject(@SuppressWarnings("unused") Object obj) {
            return false;
        }
    }

    /**
     * This node exists for debugging purposes. You can call Interop.createForeignObject() from
     * JavaScript code to create a {@link TruffleObject}. It is used to simplify testing interop
     * features in JavaScript code.
     *
     */
    abstract static class InteropCreateForeignObjectNode extends JSBuiltinNode {

        private static Class<?> testMapClass;

        InteropCreateForeignObjectNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        @TruffleBoundary
        protected Object createForeignObject() {
            if (!JSTruffleOptions.SubstrateVM) {
                try {
                    if (testMapClass == null) {
                        testMapClass = Class.forName("com.oracle.truffle.js.test.interop.object.ForeignTestMap");
                    }
                    return JavaInterop.asTruffleObject(testMapClass.newInstance());
                } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
                    throw Errors.createTypeError("cannot test with ForeignTestMap: " + e.getMessage());
                }
            } else {
                return Undefined.instance;
            }
        }
    }

    /**
     * This node exists for debugging purposes. You can call Interop.createForeignDynamicObject()
     * from JavaScript code to create a {@link DynamicObject}. It is used to simplify testing
     * interop features in JavaScript code.
     *
     */
    abstract static class InteropCreateForeignDynamicObjectNode extends JSBuiltinNode {

        private static Class<?> testMapClass;

        InteropCreateForeignDynamicObjectNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        @TruffleBoundary
        protected Object createForeignDynamicObject() {
            if (!JSTruffleOptions.SubstrateVM) {
                try {
                    if (testMapClass == null) {
                        testMapClass = Class.forName("com.oracle.truffle.js.test.interop.object.ForeignDynamicObject");
                    }
                    Method createNew = testMapClass.getMethod("createNew");
                    Object result = createNew.invoke(null);
                    return result;
                } catch (ClassNotFoundException | NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
                    throw Errors.createTypeError("cannot test with ForeignDynamicObject: " + e.getMessage());
                }
            } else {
                return Undefined.instance;
            }
        }
    }
}