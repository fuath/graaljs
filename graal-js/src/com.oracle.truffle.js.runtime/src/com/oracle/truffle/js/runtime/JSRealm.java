/*
 * Copyright (c) 2015, 2017, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.DynamicObjectFactory;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.js.runtime.array.TypedArray;
import com.oracle.truffle.js.runtime.array.TypedArray.TypedArrayFactory;
import com.oracle.truffle.js.runtime.builtins.Builtin;
import com.oracle.truffle.js.runtime.builtins.JSAdapter;
import com.oracle.truffle.js.runtime.builtins.JSArgumentsObject;
import com.oracle.truffle.js.runtime.builtins.JSArray;
import com.oracle.truffle.js.runtime.builtins.JSArrayBuffer;
import com.oracle.truffle.js.runtime.builtins.JSArrayBufferView;
import com.oracle.truffle.js.runtime.builtins.JSBoolean;
import com.oracle.truffle.js.runtime.builtins.JSConstructor;
import com.oracle.truffle.js.runtime.builtins.JSDataView;
import com.oracle.truffle.js.runtime.builtins.JSDate;
import com.oracle.truffle.js.runtime.builtins.JSDebug;
import com.oracle.truffle.js.runtime.builtins.JSDictionaryObject;
import com.oracle.truffle.js.runtime.builtins.JSError;
import com.oracle.truffle.js.runtime.builtins.JSFunction;
import com.oracle.truffle.js.runtime.builtins.JSFunctionData;
import com.oracle.truffle.js.runtime.builtins.JSGlobalObject;
import com.oracle.truffle.js.runtime.builtins.JSIntl;
import com.oracle.truffle.js.runtime.builtins.JSCollator;
import com.oracle.truffle.js.runtime.builtins.JSDateTimeFormat;
import com.oracle.truffle.js.runtime.builtins.JSJava;
import com.oracle.truffle.js.runtime.builtins.JSJavaWorkerBuiltin;
import com.oracle.truffle.js.runtime.builtins.JSMap;
import com.oracle.truffle.js.runtime.builtins.JSMath;
import com.oracle.truffle.js.runtime.builtins.JSNumber;
import com.oracle.truffle.js.runtime.builtins.JSNumberFormat;
import com.oracle.truffle.js.runtime.builtins.JSON;
import com.oracle.truffle.js.runtime.builtins.JSObjectPrototype;
import com.oracle.truffle.js.runtime.builtins.JSPerformance;
import com.oracle.truffle.js.runtime.builtins.JSPromise;
import com.oracle.truffle.js.runtime.builtins.JSProxy;
import com.oracle.truffle.js.runtime.builtins.JSRegExp;
import com.oracle.truffle.js.runtime.builtins.JSSIMD;
import com.oracle.truffle.js.runtime.builtins.JSSet;
import com.oracle.truffle.js.runtime.builtins.JSSharedArrayBuffer;
import com.oracle.truffle.js.runtime.builtins.JSString;
import com.oracle.truffle.js.runtime.builtins.JSSymbol;
import com.oracle.truffle.js.runtime.builtins.JSTest262;
import com.oracle.truffle.js.runtime.builtins.JSTestNashorn;
import com.oracle.truffle.js.runtime.builtins.JSTestV8;
import com.oracle.truffle.js.runtime.builtins.JSUserObject;
import com.oracle.truffle.js.runtime.builtins.JSWeakMap;
import com.oracle.truffle.js.runtime.builtins.JSWeakSet;
import com.oracle.truffle.js.runtime.builtins.SIMD;
import com.oracle.truffle.js.runtime.builtins.SIMDType;
import com.oracle.truffle.js.runtime.builtins.SIMDType.SIMDTypeFactory;
import com.oracle.truffle.js.runtime.interop.JavaImporter;
import com.oracle.truffle.js.runtime.interop.JavaPackage;
import com.oracle.truffle.js.runtime.objects.JSAttributes;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.JSObjectUtil;
import com.oracle.truffle.js.runtime.objects.Undefined;

/**
 * Container for JavaScript globals (i.e. an ECMAScript 6 Realm object).
 */
public class JSRealm implements ShapeContext {

    public static final String INTERNAL_JS_FILE_NAME_PREFIX = "internal:";
    public static final String INTEROP_CLASS_NAME = "Interop";
    public static final String REFLECT_CLASS_NAME = "Reflect";
    public static final String SHARED_ARRAY_BUFFER_CLASS_NAME = "SharedArrayBuffer";
    public static final String ATOMICS_CLASS_NAME = "Atomics";
    public static final String REALM_BUILTIN_CLASS_NAME = "Realm";
    public static final String ARGUMENTS_NAME = "arguments";

    private static final String ALT_GRAALVM_VERSION_PROPERTY = "graalvm.version";
    private static final String GRAALVM_VERSION_PROPERTY = "org.graalvm.version";
    private static final String GRAALVM_VERSION;

    static {
        // Copied from `Launcher`. See GR-6243.
        String version = System.getProperty(GRAALVM_VERSION_PROPERTY);
        String altVersion = System.getProperty(ALT_GRAALVM_VERSION_PROPERTY);
        if (version != null && altVersion == null) {
            GRAALVM_VERSION = version;
        } else if (altVersion != null && version == null) {
            GRAALVM_VERSION = altVersion;
        } else if (version != null && version.equals(altVersion)) {
            GRAALVM_VERSION = version;
        } else {
            GRAALVM_VERSION = null;
        }
    }

    private final JSContext context;

    @CompilationFinal private DynamicObject globalObject;

    private final Shape initialUserObjectShape;
    private final DynamicObjectFactory initialUserObjectFactory;

    private final DynamicObject objectConstructor;
    private final DynamicObject objectPrototype;
    private final DynamicObject functionConstructor;
    private final DynamicObject functionPrototype;
    private final DynamicObjectFactory initialFunctionFactory;
    private final DynamicObjectFactory initialConstructorFactory;
    private final DynamicObjectFactory initialStrictFunctionFactory;
    private final DynamicObjectFactory initialStrictConstructorFactory;

    private final JSConstructor arrayConstructor;
    private final DynamicObjectFactory arrayFactory;
    private final DynamicObjectFactory lazyRegexArrayFactory;
    private final JSConstructor booleanConstructor;
    private final DynamicObjectFactory booleanFactory;
    private final JSConstructor numberConstructor;
    private final DynamicObjectFactory numberFactory;
    private final JSConstructor stringConstructor;
    private final DynamicObjectFactory stringFactory;
    private final JSConstructor regExpConstructor;
    private final DynamicObjectFactory regExpFactory;
    private final JSConstructor collatorConstructor;
    private final DynamicObjectFactory collatorFactory;
    private final JSConstructor numberFormatConstructor;
    private final DynamicObjectFactory numberFormatFactory;
    private final JSConstructor dateTimeFormatConstructor;
    private final DynamicObjectFactory dateTimeFormatFactory;
    private final JSConstructor dateConstructor;
    private final DynamicObjectFactory dateFactory;

    @CompilationFinal(dimensions = 1) private final JSConstructor[] errorConstructors;
    @CompilationFinal(dimensions = 1) private final DynamicObjectFactory[] errorObjectFactories;
    @CompilationFinal(dimensions = 1) private final DynamicObjectFactory[] errorWithMessageObjectFactories;
    private final JSConstructor callSiteConstructor;
    private final DynamicObjectFactory callSiteFactory;

    // ES6:
    private final JSConstructor symbolConstructor;
    private final DynamicObjectFactory symbolFactory;
    private final JSConstructor mapConstructor;
    private final DynamicObjectFactory mapFactory;
    private final JSConstructor setConstructor;
    private final DynamicObjectFactory setFactory;
    private final JSConstructor weakMapConstructor;
    private final DynamicObjectFactory weakMapFactory;
    private final JSConstructor weakSetConstructor;
    private final DynamicObjectFactory weakSetFactory;

    private final DynamicObjectFactory initialArgumentsFactory;
    private final DynamicObjectFactory initialStrictArgumentsFactory;

    private final DynamicObject mathObject;
    private DynamicObject realmBuiltinObject;
    private Object evalFunctionObject;
    private Object applyFunctionObject;
    private Object callFunctionObject;
    private Object loadFunctionObject;

    private final JSConstructor arrayBufferConstructor;
    private final JSConstructor sharedArrayBufferConstructor;
    private final DynamicObjectFactory arrayBufferFactory;
    private final DynamicObjectFactory directArrayBufferFactory;
    private final DynamicObjectFactory sharedArrayBufferFactory;

    @CompilationFinal(dimensions = 1) private final JSConstructor[] typedArrayConstructors;
    @CompilationFinal(dimensions = 1) private final DynamicObjectFactory[] typedArrayFactories;
    @CompilationFinal(dimensions = 1) private final DynamicObjectFactory[] directTypedArrayFactories;
    private final JSConstructor dataViewConstructor;
    private final DynamicObjectFactory dataViewFactory;
    private final JSConstructor jsAdapterConstructor;
    private final DynamicObjectFactory jsAdapterFactory;
    private final JSConstructor javaImporterConstructor;
    private final DynamicObjectFactory javaImportFactory;
    private final JSConstructor proxyConstructor;
    private final DynamicObjectFactory proxyFactory;
    private final DynamicObject iteratorPrototype;
    private final DynamicObject asyncIteratorPrototype;
    private final DynamicObject asyncFromSyncIteratorPrototype;

    @CompilationFinal(dimensions = 1) private final JSConstructor[] simdTypeConstructors;
    @CompilationFinal(dimensions = 1) private final DynamicObjectFactory[] simdTypeFactories;

    private final JSConstructor generatorFunctionConstructor;
    private final DynamicObjectFactory initialGeneratorFactory;
    private final Shape initialGeneratorObjectShape;
    private final DynamicObjectFactory initialEnumerateIteratorFactory;
    private final DynamicObjectFactory initialBoundFunctionFactory;
    private final DynamicObjectFactory promiseFactory;

    private final JSConstructor asyncFunctionConstructor;
    private final DynamicObjectFactory initialAsyncFunctionFactory;

    private final DynamicObject throwerFunction;

    private final DynamicObjectFactory initialJavaPackageFactory;
    private DynamicObject javaPackageToPrimitiveFunction;

    private final JSConstructor javaInteropWorkerConstructor;
    private final DynamicObjectFactory javaInteropWorkerFactory;

    private boolean allowLoadInternal;

    @CompilationFinal private DynamicObject arrayProtoValuesIterator;
    @CompilationFinal private DynamicObject typedArrayConstructor;
    @CompilationFinal private DynamicObject typedArrayPrototype;
    @CompilationFinal private DynamicObject promiseConstructor;
    @CompilationFinal private DynamicObject promisePrototype;

    @CompilationFinal private DynamicObject simdTypeConstructor;
    @CompilationFinal private DynamicObject simdTypePrototype;

    private volatile Map<List<String>, DynamicObject> templateRegistry;
    private final Shape dictionaryShapeObjectPrototype;

    public JSRealm(JSContext context) {
        this.context = context;
        context.setRealm(this); // (GR-1992)

        // need to build Function and Function.proto in a weird order to avoid circular dependencies
        this.objectPrototype = JSObjectPrototype.create(context);

        this.functionPrototype = JSFunction.createFunctionPrototype(this, objectPrototype);
        this.initialFunctionFactory = JSFunction.makeInitialFunctionShape(this, functionPrototype, false).createFactory();
        this.initialConstructorFactory = JSFunction.makeConstructorShape(JSFunction.makeInitialFunctionShape(this, functionPrototype, false)).createFactory();
        this.throwerFunction = createThrowerFunction();
        this.initialStrictFunctionFactory = JSFunction.makeInitialFunctionShape(this, functionPrototype, true).createFactory();
        this.initialStrictConstructorFactory = JSFunction.makeConstructorShape(JSFunction.makeInitialFunctionShape(this, functionPrototype, true)).createFactory();

        if (context.isOptionAnnexB()) {
            putProtoAccessorProperty(this);
        }

        this.globalObject = JSGlobalObject.create(this, objectPrototype);

        this.objectConstructor = createObjectConstructor(this, objectPrototype);
        JSObjectUtil.putDataProperty(context, this.objectPrototype, JSObject.CONSTRUCTOR, objectConstructor, JSAttributes.getDefaultNotEnumerable());
        JSObjectUtil.putFunctionsFromContainer(this, this.objectPrototype, JSUserObject.PROTOTYPE_NAME);
        this.functionConstructor = JSFunction.createFunctionConstructor(this);
        JSFunction.fillFunctionPrototype(this);

        this.initialUserObjectShape = JSObjectUtil.getProtoChildShape(this.objectPrototype, JSUserObject.INSTANCE, context);
        this.initialUserObjectFactory = initialUserObjectShape.createFactory();

        this.arrayConstructor = JSArray.createConstructor(this);
        this.arrayFactory = JSArray.makeInitialArrayShape(context, arrayConstructor.getPrototype()).createFactory();
        this.lazyRegexArrayFactory = JSRegExp.makeInitialShapeLazyArray(context, arrayConstructor.getPrototype()).createFactory();
        this.booleanConstructor = JSBoolean.createConstructor(this);
        this.booleanFactory = JSBoolean.makeInitialShape(context, booleanConstructor.getPrototype()).createFactory();
        this.numberConstructor = JSNumber.createConstructor(this);
        this.numberFactory = JSNumber.makeInitialShape(context, numberConstructor.getPrototype()).createFactory();
        this.stringConstructor = JSString.createConstructor(this);
        this.stringFactory = JSString.makeInitialShape(context, stringConstructor.getPrototype()).createFactory();
        this.regExpConstructor = JSRegExp.createConstructor(this);
        this.regExpFactory = JSRegExp.makeInitialShape(context, regExpConstructor.getPrototype()).createFactory();
        this.dateConstructor = JSDate.createConstructor(this);
        this.dateFactory = JSDate.makeInitialShape(context, dateConstructor.getPrototype()).createFactory();
        boolean es6 = JSTruffleOptions.MaxECMAScriptVersion >= 6;
        if (es6) {
            this.symbolConstructor = JSSymbol.createConstructor(this);
            this.symbolFactory = JSSymbol.makeInitialShape(context, symbolConstructor.getPrototype()).createFactory();
            this.mapConstructor = JSMap.createConstructor(this);
            this.mapFactory = JSMap.makeInitialShape(context, mapConstructor.getPrototype()).createFactory();
            this.setConstructor = JSSet.createConstructor(this);
            this.setFactory = JSSet.makeInitialShape(context, setConstructor.getPrototype()).createFactory();
            this.weakMapConstructor = JSWeakMap.createConstructor(this);
            this.weakMapFactory = JSWeakMap.makeInitialShape(context, weakMapConstructor.getPrototype()).createFactory();
            this.weakSetConstructor = JSWeakSet.createConstructor(this);
            this.weakSetFactory = JSWeakSet.makeInitialShape(context, weakSetConstructor.getPrototype()).createFactory();
            this.proxyConstructor = JSProxy.createConstructor(this);
            this.proxyFactory = JSProxy.makeInitialShape(context, proxyConstructor.getPrototype()).createFactory();
            this.promiseFactory = JSPromise.makeInitialShape(this).createFactory();
        } else {
            this.symbolConstructor = null;
            this.symbolFactory = null;
            this.mapConstructor = null;
            this.mapFactory = null;
            this.setConstructor = null;
            this.setFactory = null;
            this.weakMapConstructor = null;
            this.weakMapFactory = null;
            this.weakSetConstructor = null;
            this.weakSetFactory = null;
            this.proxyConstructor = null;
            this.proxyFactory = null;
            this.promiseFactory = null;
        }

        this.errorConstructors = new JSConstructor[JSErrorType.values().length];
        this.errorObjectFactories = new DynamicObjectFactory[JSErrorType.values().length];
        this.errorWithMessageObjectFactories = new DynamicObjectFactory[JSErrorType.values().length];
        initializeErrorConstructors();
        this.callSiteConstructor = JSError.createCallSiteConstructor(this);
        this.callSiteFactory = JSError.makeInitialCallSiteShape(context, callSiteConstructor.getPrototype()).createFactory();

        Shape initialArgumentsShape = JSArgumentsObject.makeInitialNonStrictArgumentsShape(this);
        this.initialArgumentsFactory = initialArgumentsShape.createFactory();
        Shape initialStrictArgumentsShape = JSArgumentsObject.makeInitialStrictArgumentsShape(this);
        this.initialStrictArgumentsFactory = initialStrictArgumentsShape.createFactory();

        this.arrayBufferConstructor = JSArrayBuffer.createConstructor(this);
        this.arrayBufferFactory = JSArrayBuffer.makeInitialArrayBufferShape(context, arrayBufferConstructor.getPrototype(), false).createFactory();
        this.directArrayBufferFactory = JSArrayBuffer.makeInitialArrayBufferShape(context, arrayBufferConstructor.getPrototype(), true).createFactory();

        this.typedArrayConstructors = new JSConstructor[TypedArray.FACTORIES.length];
        this.typedArrayFactories = new DynamicObjectFactory[TypedArray.FACTORIES.length];
        this.directTypedArrayFactories = new DynamicObjectFactory[TypedArray.FACTORIES.length];
        initializeTypedArrayConstructors();
        this.dataViewConstructor = JSDataView.createConstructor(this);
        this.dataViewFactory = JSDataView.makeInitialArrayBufferViewShape(context, dataViewConstructor.getPrototype()).createFactory();

        if (JSTruffleOptions.SIMDJS) {
            this.simdTypeFactories = new DynamicObjectFactory[SIMDType.FACTORIES.length];
            this.simdTypeConstructors = new JSConstructor[SIMDType.FACTORIES.length];
            initializeSIMDTypeConstructors();
        } else {
            this.simdTypeFactories = null;
            this.simdTypeConstructors = null;
        }

        if (context.isOptionIntl402()) {
            this.collatorConstructor = JSCollator.createConstructor(this);
            this.collatorFactory = JSCollator.makeInitialShape(context, collatorConstructor.getPrototype()).createFactory();
            this.numberFormatConstructor = JSNumberFormat.createConstructor(this);
            this.numberFormatFactory = JSNumberFormat.makeInitialShape(context, numberFormatConstructor.getPrototype()).createFactory();
            this.dateTimeFormatConstructor = JSDateTimeFormat.createConstructor(this);
            this.dateTimeFormatFactory = JSDateTimeFormat.makeInitialShape(context, dateTimeFormatConstructor.getPrototype()).createFactory();
        } else {
            this.collatorConstructor = null;
            this.collatorFactory = null;
            this.numberFormatConstructor = null;
            this.numberFormatFactory = null;
            this.dateTimeFormatConstructor = null;
            this.dateTimeFormatFactory = null;
        }

        this.jsAdapterConstructor = JSTruffleOptions.NashornExtensions ? JSAdapter.createConstructor(this) : null;
        this.jsAdapterFactory = JSTruffleOptions.NashornExtensions ? JSAdapter.makeInitialShape(context, jsAdapterConstructor.getPrototype()).createFactory() : null;
        this.javaImporterConstructor = JSTruffleOptions.NashornJavaInterop ? JavaImporter.createConstructor(this) : null;
        this.javaImportFactory = JSTruffleOptions.NashornJavaInterop ? JavaImporter.makeInitialShape(context, javaImporterConstructor.getPrototype()).createFactory() : null;
        this.initialJavaPackageFactory = JSTruffleOptions.NashornJavaInterop ? JavaPackage.createInitialShape(this).createFactory() : null;

        this.iteratorPrototype = es6 ? createIteratorPrototype() : null;
        this.generatorFunctionConstructor = es6 ? JSFunction.createGeneratorFunctionConstructor(this) : null;
        this.initialGeneratorFactory = es6 ? JSFunction.makeInitialGeneratorFunctionConstructorShape(this, generatorFunctionConstructor.getPrototype()).createFactory() : null;
        this.initialGeneratorObjectShape = es6 ? JSFunction.makeInitialGeneratorObjectShape(this) : null;
        this.initialEnumerateIteratorFactory = JSFunction.makeInitialEnumerateIteratorShape(this).createFactory();
        this.initialBoundFunctionFactory = JSFunction.makeInitialBoundFunctionShape(this, functionPrototype).createFactory();

        if (context.isOptionSharedArrayBuffer()) {
            this.sharedArrayBufferConstructor = JSSharedArrayBuffer.createConstructor(this);
            this.sharedArrayBufferFactory = JSSharedArrayBuffer.makeInitialArrayBufferShape(context, sharedArrayBufferConstructor.getPrototype()).createFactory();
        } else {
            this.sharedArrayBufferConstructor = null;
            this.sharedArrayBufferFactory = null;
        }

        this.mathObject = JSMath.create(this);

        if (JSTruffleOptions.Stage3 && !context.isOptionV8CompatibilityMode()) {
            JSObjectUtil.putDataProperty(context, this.globalObject, "global", this.globalObject, JSAttributes.getDefaultNotEnumerable());
        }

        this.dictionaryShapeObjectPrototype = JSTruffleOptions.DictionaryObject ? JSDictionaryObject.makeDictionaryShape(context, objectPrototype) : null;

        boolean es8 = JSTruffleOptions.MaxECMAScriptVersion >= 8;
        this.asyncIteratorPrototype = es8 ? createAsyncIteratorPrototype() : null;
        this.asyncFromSyncIteratorPrototype = es8 ? createAsyncFromSyncIteratorPrototype() : null;

        this.asyncFunctionConstructor = es8 ? JSFunction.createAsyncFunctionConstructor(this) : null;
        this.initialAsyncFunctionFactory = es8 ? JSFunction.makeInitialAsyncFunctionConstructorShape(this, asyncFunctionConstructor.getPrototype()).createFactory() : null;

        this.javaInteropWorkerConstructor = isJavaInteropAvailable() ? JSJavaWorkerBuiltin.createWorkerConstructor(this) : null;
        this.javaInteropWorkerFactory = isJavaInteropAvailable() ? JSJavaWorkerBuiltin.makeInitialShape(context, javaInteropWorkerConstructor.getPrototype()).createFactory() : null;
    }

    private void initializeTypedArrayConstructors() {
        JSConstructor taConst = JSArrayBufferView.createTypedArrayConstructor(this);
        typedArrayConstructor = taConst.getFunctionObject();
        typedArrayPrototype = taConst.getPrototype();

        for (TypedArrayFactory factory : TypedArray.FACTORIES) {
            JSConstructor constructor = JSArrayBufferView.createConstructor(this, factory, taConst);
            typedArrayConstructors[factory.getFactoryIndex()] = constructor;
            directTypedArrayFactories[factory.getFactoryIndex()] = JSArrayBufferView.makeInitialArrayBufferViewShape(context, constructor.getPrototype(), true).createFactory();
            typedArrayFactories[factory.getFactoryIndex()] = JSArrayBufferView.makeInitialArrayBufferViewShape(context, constructor.getPrototype(), false).createFactory();
        }
    }

    private void initializeSIMDTypeConstructors() {
        assert JSTruffleOptions.SIMDJS;
        JSConstructor taConst = JSSIMD.createSIMDTypeConstructor(this);
        simdTypeConstructor = taConst.getFunctionObject();
        simdTypePrototype = taConst.getPrototype();

        for (SIMDTypeFactory<? extends SIMDType> factory : SIMDType.FACTORIES) {
            JSConstructor constructor = JSSIMD.createConstructor(this, factory, taConst);
            simdTypeConstructors[factory.getFactoryIndex()] = constructor;
            simdTypeFactories[factory.getFactoryIndex()] = JSSIMD.makeInitialSIMDShape(context, constructor.getPrototype()).createFactory();
        }
    }

    private void initializeErrorConstructors() {
        for (JSErrorType type : JSErrorType.values()) {
            JSConstructor errorConstructor = JSError.createErrorConstructor(this, type);
            errorConstructors[type.ordinal()] = errorConstructor;
            Shape initialShape = JSError.makeInitialShape(context, errorConstructor.getPrototype());
            errorObjectFactories[type.ordinal()] = initialShape.createFactory();
            errorWithMessageObjectFactories[type.ordinal()] = JSError.addMessagePropertyToShape(initialShape).createFactory();
        }
    }

    public final JSContext getContext() {
        return context;
    }

    public final DynamicObject lookupFunction(String containerName, String methodName) {
        Builtin builtin = Objects.requireNonNull(context.getFunctionLookup().lookupBuiltinFunction(containerName, methodName));
        JSFunctionData functionData = builtin.createFunctionData(context);
        return JSFunction.create(this, functionData);
    }

    public static DynamicObject createObjectConstructor(JSRealm realm, DynamicObject objectPrototype) {
        JSContext context = realm.getContext();
        DynamicObject objectConstructor = realm.lookupFunction(JSConstructor.BUILTINS, JSUserObject.CLASS_NAME);
        JSObjectUtil.putConstructorPrototypeProperty(context, objectConstructor, objectPrototype);
        JSObjectUtil.putFunctionsFromContainer(realm, objectConstructor, JSUserObject.CLASS_NAME);
        return objectConstructor;
    }

    public final JSConstructor getErrorConstructor(JSErrorType type) {
        return errorConstructors[type.ordinal()];
    }

    public final DynamicObjectFactory getErrorFactory(JSErrorType type) {
        return errorObjectFactories[type.ordinal()];
    }

    public final DynamicObjectFactory getErrorWithMessageFactory(JSErrorType type) {
        return errorWithMessageObjectFactories[type.ordinal()];
    }

    public final DynamicObject getGlobalObject() {
        return globalObject;
    }

    public final void setGlobalObject(DynamicObject global) {
        this.globalObject = global;
    }

    public final DynamicObject getObjectConstructor() {
        return objectConstructor;
    }

    public final DynamicObject getObjectPrototype() {
        return objectPrototype;
    }

    public final DynamicObject getFunctionConstructor() {
        return functionConstructor;
    }

    public final DynamicObject getFunctionPrototype() {
        return functionPrototype;
    }

    public final DynamicObjectFactory getFunctionFactory() {
        return initialFunctionFactory;
    }

    public final DynamicObjectFactory getStrictFunctionFactory() {
        return initialStrictFunctionFactory;
    }

    public final DynamicObjectFactory getConstructorFactory() {
        return initialConstructorFactory;
    }

    public final DynamicObjectFactory getStrictConstructorFactory() {
        return initialStrictConstructorFactory;
    }

    public final DynamicObjectFactory getFunctionFactory(boolean strictFunctionProperties, boolean isConstructor, boolean isGenerator, boolean isAsync) {
        if (isAsync) {
            return initialAsyncFunctionFactory;
        } else if (isConstructor) {
            if (!isGenerator) {
                if (strictFunctionProperties) {
                    return getStrictConstructorFactory();
                } else {
                    return getConstructorFactory();
                }
            } else {
                return initialGeneratorFactory;
            }
        } else {
            assert !isGenerator;
            if (strictFunctionProperties) {
                return getStrictFunctionFactory();
            } else {
                return getFunctionFactory();
            }
        }
    }

    public final JSConstructor getArrayConstructor() {
        return arrayConstructor;
    }

    public final JSConstructor getBooleanConstructor() {
        return booleanConstructor;
    }

    public final JSConstructor getNumberConstructor() {
        return numberConstructor;
    }

    public final JSConstructor getStringConstructor() {
        return stringConstructor;
    }

    public final JSConstructor getRegExpConstructor() {
        return regExpConstructor;
    }

    public final JSConstructor getCollatorConstructor() {
        return collatorConstructor;
    }

    public final JSConstructor getNumberFormatConstructor() {
        return numberFormatConstructor;
    }

    public final JSConstructor getDateTimeFormatConstructor() {
        return dateTimeFormatConstructor;
    }

    public final JSConstructor getDateConstructor() {
        return dateConstructor;
    }

    public final JSConstructor getSymbolConstructor() {
        return symbolConstructor;
    }

    public final JSConstructor getMapConstructor() {
        return mapConstructor;
    }

    public final JSConstructor getSetConstructor() {
        return setConstructor;
    }

    public final JSConstructor getWeakMapConstructor() {
        return weakMapConstructor;
    }

    public final JSConstructor getWeakSetConstructor() {
        return weakSetConstructor;
    }

    @Override
    public final Shape getInitialUserObjectShape() {
        return initialUserObjectShape;
    }

    public final Shape getInitialGeneratorObjectShape() {
        return initialGeneratorObjectShape;
    }

    public DynamicObjectFactory getInitialBoundFunctionFactory() {
        return initialBoundFunctionFactory;
    }

    public final DynamicObjectFactory getNonStrictArgumentsFactory() {
        return initialArgumentsFactory;
    }

    public final DynamicObjectFactory getStrictArgumentsFactory() {
        return initialStrictArgumentsFactory;
    }

    public final DynamicObjectFactory getUserObjectFactory() {
        return initialUserObjectFactory;
    }

    public final JSConstructor getArrayBufferConstructor() {
        return arrayBufferConstructor;
    }

    @Override
    public final DynamicObjectFactory getArrayBufferFactory() {
        return arrayBufferFactory;
    }

    @Override
    public final DynamicObjectFactory getDirectArrayBufferFactory() {
        return directArrayBufferFactory;
    }

    public JSConstructor getSharedArrayBufferConstructor() {
        assert context.isOptionSharedArrayBuffer();
        return sharedArrayBufferConstructor;
    }

    public final JSConstructor getArrayBufferViewConstructor(TypedArrayFactory factory) {
        return typedArrayConstructors[factory.getFactoryIndex()];
    }

    @Override
    public final DynamicObjectFactory getArrayBufferViewFactory(TypedArrayFactory factory) {
        return typedArrayFactories[factory.getFactoryIndex()];
    }

    @Override
    public final DynamicObjectFactory getDirectArrayBufferViewFactory(TypedArrayFactory factory) {
        return directTypedArrayFactories[factory.getFactoryIndex()];
    }

    public final JSConstructor getDataViewConstructor() {
        return dataViewConstructor;
    }

    public final DynamicObject getTypedArrayConstructor() {
        return typedArrayConstructor;
    }

    public final DynamicObject getTypedArrayPrototype() {
        return typedArrayPrototype;
    }

    public final DynamicObject getMathObject() {
        return mathObject;
    }

    public final DynamicObject getRealmBuiltinObject() {
        return realmBuiltinObject;
    }

    public final JSConstructor getJSAdapterConstructor() {
        return jsAdapterConstructor;
    }

    public final JSConstructor getProxyConstructor() {
        return proxyConstructor;
    }

    public final JSConstructor getGeneratorFunctionConstructor() {
        return generatorFunctionConstructor;
    }

    public JSConstructor getAsyncFunctionConstructor() {
        return asyncFunctionConstructor;
    }

    @Override
    public DynamicObjectFactory getEnumerateIteratorFactory() {
        return initialEnumerateIteratorFactory;
    }

    public final JSConstructor getJavaImporterConstructor() {
        return javaImporterConstructor;
    }

    public final DynamicObjectFactory getJavaPackageFactory() {
        return initialJavaPackageFactory;
    }

    public final DynamicObject getJavaPackageToPrimitiveFunction() {
        if (javaPackageToPrimitiveFunction == null) {
            javaPackageToPrimitiveFunction = JavaPackage.createToPrimitiveFunction(this);
        }
        return javaPackageToPrimitiveFunction;
    }

    public final Map<List<String>, DynamicObject> getTemplateRegistry() {
        if (templateRegistry == null) {
            createTemplateRegistry();
        }
        return templateRegistry;
    }

    @TruffleBoundary
    private synchronized void createTemplateRegistry() {
        if (templateRegistry == null) {
            templateRegistry = new HashMap<>();
        }
    }

    public final Object getEvalFunctionObject() {
        return evalFunctionObject;
    }

    public final Object getApplyFunctionObject() {
        return applyFunctionObject;
    }

    public final Object getCallFunctionObject() {
        return callFunctionObject;
    }

    public final Object getLoadFunctionObject() {
        return loadFunctionObject;
    }

    private static void putProtoAccessorProperty(final JSRealm realm) {
        JSContext context = realm.getContext();
        DynamicObject getProto = JSFunction.create(realm, JSFunctionData.createCallOnly(context, Truffle.getRuntime().createCallTarget(new JavaScriptRootNode(context.getLanguage(), null, null) {
            @Override
            public Object execute(VirtualFrame frame) {
                Object obj = JSArguments.getThisObject(frame.getArguments());
                return JSObject.getPrototype(JSRuntime.toObject(context, obj));
            }
        }), 0, "get " + JSObject.PROTO));
        DynamicObject setProto = JSFunction.create(realm, JSFunctionData.createCallOnly(context, Truffle.getRuntime().createCallTarget(new JavaScriptRootNode(context.getLanguage(), null, null) {
            @Override
            public Object execute(VirtualFrame frame) {
                Object[] arguments = frame.getArguments();
                DynamicObject thisObj = JSRuntime.toObject(context, JSArguments.getThisObject(arguments));
                if (JSArguments.getUserArgumentCount(arguments) < 1) {
                    return Undefined.instance;
                }
                Object value = JSArguments.getUserArgument(arguments, 0);

                DynamicObject current = JSObject.getPrototype(thisObj);
                if (current == value) {
                    return Undefined.instance; // true in OrdinarySetPrototype
                }
                if (!JSObject.isExtensible(thisObj)) {
                    throwCannotSetNonExtensibleProtoError(thisObj);
                }

                if (!(JSObject.isDynamicObject(value)) || value == Undefined.instance) {
                    return Undefined.instance;
                }
                if (!JSObject.setPrototype(thisObj, (DynamicObject) value)) {
                    throwCannotSetProtoError(thisObj);
                }
                return Undefined.instance;
            }

            @TruffleBoundary
            private void throwCannotSetNonExtensibleProtoError(DynamicObject thisObj) {
                throw Errors.createTypeError("Cannot set __proto__ of non-extensible " + JSObject.defaultToString(thisObj));
            }

            @TruffleBoundary
            private void throwCannotSetProtoError(DynamicObject thisObj) {
                throw Errors.createTypeError("Cannot set __proto__ of " + JSObject.defaultToString(thisObj));
            }
        }), 0, "set " + JSObject.PROTO));

        // ES6 draft annex, B.2.2 Additional Properties of the Object.prototype Object
        JSObjectUtil.putConstantAccessorProperty(context, realm.getObjectPrototype(), JSObject.PROTO, getProto, setProto, JSAttributes.configurableNotEnumerable());
    }

    public final DynamicObject getThrowerFunction() {
        return throwerFunction;
    }

    public DynamicObject getIteratorPrototype() {
        return iteratorPrototype;
    }

    public DynamicObject getAsyncIteratorPrototype() {
        return asyncIteratorPrototype;
    }

    public DynamicObject getAsyncFromSyncIteratorPrototype() {
        return asyncFromSyncIteratorPrototype;
    }

    /**
     * This function is used whenever a function is required that throws a TypeError. It is used by
     * some of the builtins that provide accessor functions that should not be called (e.g., as a
     * method of deprecation). In the specification, this is often referred to as
     * "[[ThrowTypeError]] function Object (13.2.3)".
     *
     */
    private DynamicObject createThrowerFunction() {
        CallTarget throwTypeErrorCallTarget = Truffle.getRuntime().createCallTarget(new JavaScriptRootNode(context.getLanguage(), null, null) {

            @Override
            @TruffleBoundary
            public Object execute(VirtualFrame frame) {
                throw Errors.createTypeError("[[ThrowTypeError]] defined by ECMAScript");
            }
        });
        DynamicObject thrower = JSFunction.create(this, JSFunctionData.create(context, throwTypeErrorCallTarget, throwTypeErrorCallTarget, 0, "", false, false, false, false));
        JSObject.preventExtensions(thrower);
        JSObject.setIntegrityLevel(thrower, true);
        return thrower;
    }

    public DynamicObject getPromiseConstructor() {
        assert promiseConstructor != null;
        return promiseConstructor;
    }

    public DynamicObject getPromisePrototype() {
        assert promisePrototype != null;
        return promisePrototype;
    }

    public void setupGlobals() {
        CompilerAsserts.neverPartOfCompilation("do not setup globals from compiled code");
        long time = JSTruffleOptions.ProfileTime ? System.nanoTime() : 0L;

        DynamicObject global = getGlobalObject();
        putGlobalProperty(global, JSUserObject.CLASS_NAME, getObjectConstructor());
        putGlobalProperty(global, JSFunction.CLASS_NAME, getFunctionConstructor());
        putGlobalProperty(global, JSArray.CLASS_NAME, getArrayConstructor().getFunctionObject());
        putGlobalProperty(global, JSString.CLASS_NAME, getStringConstructor().getFunctionObject());
        putGlobalProperty(global, JSDate.CLASS_NAME, getDateConstructor().getFunctionObject());
        putGlobalProperty(global, JSNumber.CLASS_NAME, getNumberConstructor().getFunctionObject());
        putGlobalProperty(global, JSBoolean.CLASS_NAME, getBooleanConstructor().getFunctionObject());
        putGlobalProperty(global, JSRegExp.CLASS_NAME, getRegExpConstructor().getFunctionObject());
        putGlobalProperty(global, JSMath.CLASS_NAME, mathObject);
        putGlobalProperty(global, JSON.CLASS_NAME, JSON.create(this));

        if (JSTruffleOptions.SIMDJS) {
            putGlobalProperty(global, SIMD.CLASS_NAME, SIMD.create(this));
        }

        if (context.isOptionIntl402()) {
            DynamicObject intlObject = JSIntl.create(this);
            DynamicObject collatorFn = getCollatorConstructor().getFunctionObject();
            DynamicObject numberFormatFn = getNumberFormatConstructor().getFunctionObject();
            DynamicObject dateTimeFormatFn = getDateTimeFormatConstructor().getFunctionObject();
            JSObjectUtil.putDataProperty(context, intlObject, JSFunction.getName(collatorFn), collatorFn, JSAttributes.getDefaultNotEnumerable());
            JSObjectUtil.putDataProperty(context, intlObject, JSFunction.getName(numberFormatFn), numberFormatFn, JSAttributes.getDefaultNotEnumerable());
            JSObjectUtil.putDataProperty(context, intlObject, JSFunction.getName(dateTimeFormatFn), dateTimeFormatFn, JSAttributes.getDefaultNotEnumerable());
            putGlobalProperty(global, JSIntl.CLASS_NAME, intlObject);
        }

        JSObjectUtil.putDataProperty(context, global, JSRuntime.NAN_STRING, Double.NaN);
        JSObjectUtil.putDataProperty(context, global, JSRuntime.INFINITY_STRING, Double.POSITIVE_INFINITY);
        JSObjectUtil.putDataProperty(context, global, Undefined.NAME, Undefined.instance);

        JSObjectUtil.putFunctionsFromContainer(this, global, JSGlobalObject.CLASS_NAME);
        this.evalFunctionObject = JSObject.get(global, JSGlobalObject.EVAL_NAME);
        this.applyFunctionObject = JSObject.get(getFunctionPrototype(), "apply");
        this.callFunctionObject = JSObject.get(getFunctionPrototype(), "call");
        this.loadFunctionObject = JSObject.get(global, "load");

        for (JSErrorType type : JSErrorType.values()) {
            putGlobalProperty(global, type.name(), getErrorConstructor(type).getFunctionObject());
        }

        putGlobalProperty(global, JSArrayBuffer.CLASS_NAME, getArrayBufferConstructor().getFunctionObject());
        for (TypedArrayFactory factory : TypedArray.FACTORIES) {
            putGlobalProperty(global, factory.getName(), getArrayBufferViewConstructor(factory).getFunctionObject());
        }
        putGlobalProperty(global, JSDataView.CLASS_NAME, getDataViewConstructor().getFunctionObject());

        if (JSTruffleOptions.SIMDJS) {
            for (SIMDTypeFactory<? extends SIMDType> factory : SIMDType.FACTORIES) {
                JSObjectUtil.putDataProperty(context, (DynamicObject) global.get(SIMD.CLASS_NAME), factory.getName(), getSIMDTypeConstructor(factory).getFunctionObject(),
                                JSAttributes.getDefaultNotEnumerable());
            }
        }

        if (JSTruffleOptions.NashornExtensions) {
            putGlobalProperty(global, JSAdapter.CLASS_NAME, getJSAdapterConstructor().getFunctionObject());
        }
        if (JSTruffleOptions.TruffleInterop) {
            setupTruffleInterop(global);
        }
        if (isJavaInteropAvailable()) {
            setupJavaInterop(global);
        }
        if (context.isOptionDebugBuiltin()) {
            putGlobalProperty(global, JSTruffleOptions.DebugPropertyName, JSDebug.create(this));
        }
        if (JSTruffleOptions.Test262Mode) {
            putGlobalProperty(global, JSTest262.CLASS_NAME, JSTest262.create(this));
        }
        if (JSTruffleOptions.TestV8Mode) {
            putGlobalProperty(global, JSTestV8.CLASS_NAME, JSTestV8.create(this));
        }
        if (JSTruffleOptions.TestNashornMode) {
            putGlobalProperty(global, JSTestNashorn.CLASS_NAME, JSTestNashorn.create(this));
        }
        if (context.getEcmaScriptVersion() >= 6) {
            Object parseInt = JSObject.get(global, "parseInt");
            Object parseFloat = JSObject.get(global, "parseFloat");
            putGlobalProperty(getNumberConstructor().getFunctionObject(), "parseInt", parseInt);
            putGlobalProperty(getNumberConstructor().getFunctionObject(), "parseFloat", parseFloat);

            putGlobalProperty(global, JSMap.CLASS_NAME, getMapConstructor().getFunctionObject());
            putGlobalProperty(global, JSSet.CLASS_NAME, getSetConstructor().getFunctionObject());
            putGlobalProperty(global, JSWeakMap.CLASS_NAME, getWeakMapConstructor().getFunctionObject());
            putGlobalProperty(global, JSWeakSet.CLASS_NAME, getWeakSetConstructor().getFunctionObject());
            putGlobalProperty(global, JSSymbol.CLASS_NAME, getSymbolConstructor().getFunctionObject());
            setupPredefinedSymbols(getSymbolConstructor().getFunctionObject());
            putGlobalProperty(global, REFLECT_CLASS_NAME, createReflect());
            putGlobalProperty(global, JSProxy.CLASS_NAME, getProxyConstructor().getFunctionObject());
        }

        if (context.isOptionSharedArrayBuffer()) {
            putGlobalProperty(global, SHARED_ARRAY_BUFFER_CLASS_NAME, getSharedArrayBufferConstructor().getFunctionObject());
        }
        if (context.isOptionAtomics() && !JSTruffleOptions.SubstrateVM) {
            putGlobalProperty(global, ATOMICS_CLASS_NAME, createAtomics());
        }
        if (JSTruffleOptions.GraalBuiltin) {
            putGraalObject(global);
        }
        if (JSTruffleOptions.Extensions) {
            putConsoleObject(global);
            putGlobalProperty(global, JSPerformance.CLASS_NAME, JSPerformance.create(this));
        }
        if (JSTruffleOptions.ProfileTime) {
            System.out.println("SetupGlobals: " + (System.nanoTime() - time) / 1000000);
        }
        if (JSTruffleOptions.LoadInternalScripts) {
            loadInternalScripts();
        }

        arrayProtoValuesIterator = (DynamicObject) getArrayConstructor().getPrototype().get(Symbol.SYMBOL_ITERATOR, Undefined.instance);
    }

    private void putGraalObject(DynamicObject global) {
        DynamicObject graalObject = JSUserObject.create(context);
        JSObjectUtil.putDataProperty(context, graalObject, "language", AbstractJavaScriptLanguage.NAME);
        JSObjectUtil.putDataProperty(context, graalObject, "versionJS", AbstractJavaScriptLanguage.VERSION_NUMBER);
        String graalVMVersion = System.getProperty(ALT_GRAALVM_VERSION_PROPERTY);
        if (graalVMVersion == null) {
            graalVMVersion = GRAALVM_VERSION;
        }

        if (graalVMVersion != null) {
            JSObjectUtil.putDataProperty(context, graalObject, "versionGraalVM", graalVMVersion);
        }
        putGlobalProperty(global, "Graal", graalObject);
    }

    private JSConstructor getSIMDTypeConstructor(SIMDTypeFactory<? extends SIMDType> factory) {
        return simdTypeConstructors[factory.getFactoryIndex()];
    }

    @Override
    public DynamicObjectFactory getSIMDTypeFactory(SIMDTypeFactory<? extends SIMDType> factory) {
        return simdTypeFactories[factory.getFactoryIndex()];
    }

    private void loadInternalScripts() {
        long time = JSTruffleOptions.ProfileTime ? System.nanoTime() : 0L;
        allowLoadInternal = true;
        try {
            loadInternal("array.js");
            loadInternal("typedarray.js");
            loadInternal("string.js");

            if (context.isOptionAnnexB()) {
                loadInternal("annexb.js");
            }
            if (context.getEcmaScriptVersion() >= 6) {
                loadInternal("iterator.js");
                loadInternal("promise.js");
                initPromiseFields();
                loadInternal("proxy.js");
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        } finally {
            allowLoadInternal = false;
            if (JSTruffleOptions.ProfileTime) {
                System.out.println("LoadInternalScripts: " + (System.nanoTime() - time) / 1000000);
            }
        }
    }

    private void initPromiseFields() {
        assert promiseConstructor == null && promisePrototype == null;
        promiseConstructor = (DynamicObject) Objects.requireNonNull(JSObject.get(getGlobalObject(), JSPromise.CLASS_NAME));
        promisePrototype = (DynamicObject) JSObject.get(promiseConstructor, JSObject.PROTOTYPE);
    }

    private void loadInternal(String fileName) {
        if (!allowLoadInternal) {
            throw new AssertionError("realm already initialized");
        }
        context.getEvaluator().loadInternal(this, fileName);
    }

    /**
     * Convenience method for defining global data properties with default attributes.
     */
    private void putGlobalProperty(DynamicObject global, String name, Object value) {
        JSObjectUtil.putDataProperty(context, global, name, value, JSAttributes.getDefaultNotEnumerable());
    }

    private static void setupPredefinedSymbols(DynamicObject symbolFunction) {
        putSymbolProperty(symbolFunction, "hasInstance", Symbol.SYMBOL_HAS_INSTANCE);
        putSymbolProperty(symbolFunction, "isConcatSpreadable", Symbol.SYMBOL_IS_CONCAT_SPREADABLE);
        putSymbolProperty(symbolFunction, "iterator", Symbol.SYMBOL_ITERATOR);
        putSymbolProperty(symbolFunction, "asyncIterator", Symbol.SYMBOL_ASYNC_ITERATOR);
        putSymbolProperty(symbolFunction, "match", Symbol.SYMBOL_MATCH);
        putSymbolProperty(symbolFunction, "replace", Symbol.SYMBOL_REPLACE);
        putSymbolProperty(symbolFunction, "search", Symbol.SYMBOL_SEARCH);
        putSymbolProperty(symbolFunction, "species", Symbol.SYMBOL_SPECIES);
        putSymbolProperty(symbolFunction, "split", Symbol.SYMBOL_SPLIT);
        putSymbolProperty(symbolFunction, "toStringTag", Symbol.SYMBOL_TO_STRING_TAG);
        putSymbolProperty(symbolFunction, "toPrimitive", Symbol.SYMBOL_TO_PRIMITIVE);
        putSymbolProperty(symbolFunction, "unscopables", Symbol.SYMBOL_UNSCOPABLES);
    }

    private static void putSymbolProperty(DynamicObject symbolFunction, String name, Symbol symbol) {
        symbolFunction.define(name, symbol, JSAttributes.notConfigurableNotEnumerableNotWritable(), (s, v) -> s.allocator().constantLocation(v));
    }

    private static boolean isJavaInteropAvailable() {
        return !JSTruffleOptions.SubstrateVM;
    }

    private void setupJavaInterop(DynamicObject global) {
        if (JSTruffleOptions.SubstrateVM) {
            return;
        }
        assert isJavaInteropAvailable();
        DynamicObject java = JSJava.create(this);
        JSObjectUtil.putFunctionsFromContainer(this, java, JSJava.CLASS_NAME);
        putGlobalProperty(global, JSJava.CLASS_NAME, java);

        if (JSTruffleOptions.NashornJavaInterop && context.getEnv() != null && context.getEnv().isHostLookupAllowed()) {
            putGlobalProperty(global, "Packages", JavaPackage.create(context, ""));
            putGlobalProperty(global, "java", JavaPackage.create(context, "java"));
            putGlobalProperty(global, "javafx", JavaPackage.create(context, "javafx"));
            putGlobalProperty(global, "javax", JavaPackage.create(context, "javax"));
            putGlobalProperty(global, "com", JavaPackage.create(context, "com"));
            putGlobalProperty(global, "org", JavaPackage.create(context, "org"));
            putGlobalProperty(global, "edu", JavaPackage.create(context, "edu"));
            putGlobalProperty(global, JavaImporter.CLASS_NAME, getJavaImporterConstructor().getFunctionObject());
        }
    }

    private void setupTruffleInterop(DynamicObject global) {
        DynamicObject obj = JSObject.create(this, this.getObjectPrototype(), JSUserObject.INSTANCE);
        JSObjectUtil.putFunctionsFromContainer(this, obj, INTEROP_CLASS_NAME);
        putGlobalProperty(global, INTEROP_CLASS_NAME, obj);
    }

    private void putConsoleObject(DynamicObject global) {
        DynamicObject console = JSUserObject.create(this);
        putGlobalProperty(console, "log", JSObject.get(global, "print"));
        putGlobalProperty(global, "console", console);
    }

    /**
     * Creates the %IteratorPrototype% object as specified in ES6 25.1.2.
     */
    private DynamicObject createIteratorPrototype() {
        return JSObject.create(this, this.getObjectPrototype(), JSUserObject.INSTANCE);
    }

    /**
     * Creates the %AsyncIteratorPrototype% object as specified in ES8 11.1.2.
     */
    private DynamicObject createAsyncIteratorPrototype() {
        return JSObject.create(this, this.getObjectPrototype(), JSUserObject.INSTANCE);
    }

    /**
     * Creates the %AsyncFromSyncIteratorPrototype% object as specified in ES8 11.1.3.2.
     */
    private DynamicObject createAsyncFromSyncIteratorPrototype() {
        DynamicObject prototype = JSObject.create(this, getObjectPrototype(), JSUserObject.INSTANCE);
        JSObjectUtil.putFunctionsFromContainer(this, prototype, JSFunction.ASYNC_FROM_SYNC_ITERATOR_PROTOTYPE_NAME);
        return prototype;
    }

    public DynamicObject getArrayProtoValuesIterator() {
        assert arrayProtoValuesIterator != null;
        return arrayProtoValuesIterator;
    }

    private DynamicObject createReflect() {
        DynamicObject obj = JSObject.create(this, this.getObjectPrototype(), JSUserObject.INSTANCE);
        JSObjectUtil.putDataProperty(context, obj, Symbol.SYMBOL_TO_STRING_TAG, REFLECT_CLASS_NAME, JSAttributes.configurableNotEnumerableNotWritable());
        JSObjectUtil.putFunctionsFromContainer(this, obj, REFLECT_CLASS_NAME);
        return obj;
    }

    private DynamicObject createAtomics() {
        DynamicObject obj = JSObject.create(this, this.getObjectPrototype(), JSUserObject.INSTANCE);
        JSObjectUtil.putDataProperty(context, obj, Symbol.SYMBOL_TO_STRING_TAG, ATOMICS_CLASS_NAME, JSAttributes.configurableNotEnumerableNotWritable());
        JSObjectUtil.putFunctionsFromContainer(this, obj, ATOMICS_CLASS_NAME);
        return obj;
    }

    @Override
    public final Shape getEmptyShape() {
        return context.getEmptyShape();
    }

    @Override
    public final Shape getEmptyShapePrototypeInObject() {
        return context.getEmptyShapePrototypeInObject();
    }

    @Override
    public final DynamicObjectFactory getArrayFactory() {
        return arrayFactory;
    }

    public DynamicObjectFactory getLazyRegexArrayFactory() {
        return lazyRegexArrayFactory;
    }

    @Override
    public final DynamicObjectFactory getStringFactory() {
        return stringFactory;
    }

    @Override
    public final DynamicObjectFactory getBooleanFactory() {
        return booleanFactory;
    }

    @Override
    public final DynamicObjectFactory getNumberFactory() {
        return numberFactory;
    }

    @Override
    public final DynamicObjectFactory getSymbolFactory() {
        return symbolFactory;
    }

    @Override
    public final DynamicObjectFactory getRegExpFactory() {
        return regExpFactory;
    }

    @Override
    public final DynamicObjectFactory getCollatorFactory() {
        return collatorFactory;
    }

    @Override
    public final DynamicObjectFactory getNumberFormatFactory() {
        return numberFormatFactory;
    }

    @Override
    public final DynamicObjectFactory getDateTimeFormatFactory() {
        return dateTimeFormatFactory;
    }

    @Override
    public final DynamicObjectFactory getDateFactory() {
        return dateFactory;
    }

    @Override
    public final DynamicObjectFactory getMapFactory() {
        return mapFactory;
    }

    @Override
    public final DynamicObjectFactory getWeakMapFactory() {
        return weakMapFactory;
    }

    @Override
    public final DynamicObjectFactory getSetFactory() {
        return setFactory;
    }

    @Override
    public final DynamicObjectFactory getWeakSetFactory() {
        return weakSetFactory;
    }

    @Override
    public final DynamicObjectFactory getDataViewFactory() {
        return dataViewFactory;
    }

    @Override
    public final DynamicObjectFactory getProxyFactory() {
        return proxyFactory;
    }

    @Override
    public final DynamicObjectFactory getSharedArrayBufferFactory() {
        assert context.isOptionSharedArrayBuffer();
        return sharedArrayBufferFactory;
    }

    @Override
    public final DynamicObjectFactory getJavaImporterFactory() {
        return javaImportFactory;
    }

    @Override
    public DynamicObjectFactory getJSAdapterFactory() {
        return jsAdapterFactory;
    }

    @Override
    public final DynamicObjectFactory getPromiseFactory() {
        return promiseFactory;
    }

    @Override
    public final DynamicObjectFactory getErrorFactory(JSErrorType type, boolean withMessage) {
        if (withMessage) {
            return getErrorWithMessageFactory(type);
        } else {
            return getErrorFactory(type);
        }
    }

    @Override
    public final DynamicObjectFactory getModuleNamespaceFactory() {
        return context.getModuleNamespaceFactory();
    }

    public JSConstructor getCallSiteConstructor() {
        return callSiteConstructor;
    }

    public DynamicObjectFactory getCallSiteFactory() {
        return callSiteFactory;
    }

    /**
     * Adds an {@code $OPTIONS} property to the global object that exposes several options to the
     * script, in case scripting mode is enabled. (for Nashorn compatibility).
     */
    public void addScriptingOptionsObject() {
        CompilerAsserts.neverPartOfCompilation();
        if (!JSTruffleOptions.NashornExtensions) {
            return;
        }
        String optionsObjName = "$OPTIONS";
        boolean scripting = true; // $OPTIONS only created in scripting mode
        String timezone = context.getLocalTimeZoneId().getId();
        DynamicObject globalObj = getGlobalObject();

        if (!JSObject.hasOwnProperty(globalObj, optionsObjName)) {
            DynamicObject optionsObj = JSUserObject.create(context);
            DynamicObject timezoneObj = JSUserObject.create(context);

            JSObjectUtil.putDataProperty(context, timezoneObj, "ID", timezone, JSAttributes.notConfigurableNotEnumerableNotWritable());

            JSObjectUtil.putDataProperty(context, optionsObj, "_timezone", timezoneObj, JSAttributes.notConfigurableNotEnumerableNotWritable());
            JSObjectUtil.putDataProperty(context, optionsObj, "_scripting", scripting, JSAttributes.notConfigurableNotEnumerableNotWritable());
            JSObjectUtil.putDataProperty(context, optionsObj, "_compile_only", false, JSAttributes.notConfigurableNotEnumerableNotWritable());

            JSObjectUtil.putDataProperty(context, globalObj, optionsObjName, optionsObj, JSAttributes.notConfigurableNotEnumerableNotWritable());
        }
    }

    public void setRealmBuiltinObject(DynamicObject realmBuiltinObject) {
        if (this.realmBuiltinObject == null && realmBuiltinObject != null) {
            this.realmBuiltinObject = realmBuiltinObject;
            putGlobalProperty(globalObject, "Realm", realmBuiltinObject);
        }
    }

    public void initRealmBuiltinObject() {
        if (context.isOptionV8CompatibilityMode()) {
            setRealmBuiltinObject(createRealmBuiltinObject());
        } else {
            this.realmBuiltinObject = null;
        }
    }

    private DynamicObject createRealmBuiltinObject() {
        DynamicObject obj = JSObject.create(this, this.getObjectPrototype(), JSUserObject.INSTANCE);
        JSObjectUtil.putDataProperty(getContext(), obj, Symbol.SYMBOL_TO_STRING_TAG, REALM_BUILTIN_CLASS_NAME, JSAttributes.configurableNotEnumerableNotWritable());
        JSObjectUtil.putFunctionsFromContainer(this, obj, REALM_BUILTIN_CLASS_NAME);
        return obj;
    }

    public void setArguments(Object[] arguments) {
        JSObjectUtil.putDataProperty(context, getGlobalObject(), ARGUMENTS_NAME, JSArray.createConstant(context, arguments),
                        context.isOptionV8CompatibilityMode() ? JSAttributes.getDefault() : JSAttributes.getDefaultNotEnumerable());
    }

    public Shape getDictionaryShapeObjectPrototype() {
        return dictionaryShapeObjectPrototype;
    }

    public DynamicObjectFactory getJavaInteropWorkerFactory() {
        return javaInteropWorkerFactory;
    }

    public JSConstructor getJavaInteropWorkerConstructor() {
        return javaInteropWorkerConstructor;
    }
}