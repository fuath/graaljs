/*
 * Copyright (c) 2012, 2014, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.js.runtime.builtins.JSFunction;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.Null;

/**
 * Utility class to to create all kinds of ECMAScript-defined Error Objects.
 */
public final class Errors {

    private Errors() {
        // don't instantiate this
    }

    @TruffleBoundary
    public static JSException createError(String message) {
        return JSException.create(JSErrorType.Error, message);
    }

    @TruffleBoundary
    public static JSException createRangeError(String message) {
        return JSException.create(JSErrorType.RangeError, message);
    }

    @TruffleBoundary
    public static JSException createURIError(String message) {
        return JSException.create(JSErrorType.URIError, message);
    }

    @TruffleBoundary
    public static JSException createTypeError(String message) {
        return JSException.create(JSErrorType.TypeError, message);
    }

    @TruffleBoundary
    public static JSException createTypeError(String message, Object... args) {
        return JSException.create(JSErrorType.TypeError, String.format(message, args));
    }

    @TruffleBoundary
    public static JSException createTypeError(String message, Node originatingNode) {
        return JSException.create(JSErrorType.TypeError, message, originatingNode);
    }

    @TruffleBoundary
    public static JSException createTypeErrorDateTimeFormatExpected() {
        return createTypeError("DateTimeFormat object expected.");
    }

    @TruffleBoundary
    public static JSException createTypeErrorNotAFunction(Object functionObj) {
        return createTypeErrorNotAFunction(functionObj, null);
    }

    @TruffleBoundary
    public static JSException createTypeErrorNotAFunction(Object functionObj, Node originatingNode) {
        assert !JSFunction.isJSFunction(functionObj); // don't lie to me
        return JSException.create(JSErrorType.TypeError, String.format("%s is not a function", JSRuntime.objectToString(functionObj)), originatingNode);
    }

    @TruffleBoundary
    public static JSException createTypeErrorNumberFormatExpected() {
        return createTypeError("NumberFormat object expected.");
    }

    @TruffleBoundary
    public static JSException createSyntaxError(String message, Throwable cause, Node originatingNode) {
        return JSException.create(JSErrorType.SyntaxError, message, cause, originatingNode);
    }

    @TruffleBoundary
    public static JSException createSyntaxError(String message) {
        return JSException.create(JSErrorType.SyntaxError, message);
    }

    @TruffleBoundary
    public static JSException createReferenceError(String message, Node originatingNode) {
        return JSException.create(JSErrorType.ReferenceError, message, originatingNode);
    }

    @TruffleBoundary
    public static JSException createReferenceError(String message, Throwable cause, Node originatingNode) {
        return JSException.create(JSErrorType.ReferenceError, message, cause, originatingNode);
    }

    @TruffleBoundary
    public static JSException createReferenceError(String message) {
        return JSException.create(JSErrorType.ReferenceError, message);
    }

    @TruffleBoundary
    public static JSException createTypeErrorNotObjectCoercible() {
        return Errors.createTypeError("Cannot convert undefined or null to object");
    }

    @TruffleBoundary
    public static JSException createTypeErrorNotObjectCoercible(Object value) {
        if (JSTruffleOptions.NashornCompatibilityMode) {
            return Errors.createTypeErrorNotAnObject(value);
        }
        return Errors.createTypeError("Cannot convert undefined or null to object: " + JSRuntime.objectToString(value));
    }

    @TruffleBoundary
    public static JSException createTypeErrorNotAnObject(Object value) {
        return Errors.createTypeError(JSRuntime.objectToString(value) + " is not an Object");
    }

    @TruffleBoundary
    public static JSException createTypeErrorObjectExpected() {
        return Errors.createTypeError("object expected");
    }

    @TruffleBoundary
    public static JSException createTypeErrorInvalidInstanceofTarget(Object target, Node originatingNode) {
        if (!JSRuntime.isObject(target)) {
            return Errors.createTypeError("Right-hand-side of instanceof is not an object", originatingNode);
        } else {
            assert !JSRuntime.isCallable(target);
            return Errors.createTypeError("Right-hand-side of instanceof is not callable", originatingNode);
        }
    }

    @TruffleBoundary
    public static JSException createTypeErrorCannotConvertToPrimitiveValue() {
        return Errors.createTypeError("Cannot convert object to primitive value");
    }

    @TruffleBoundary
    public static JSException createTypeErrorCannotConvertToPrimitiveValue(String what) {
        return Errors.createTypeError("Cannot convert " + what + " object to primitive value");
    }

    @TruffleBoundary
    public static JSException createTypeErrorCannotConvertToString(String what) {
        return Errors.createTypeError("Cannot convert " + what + " to a string");
    }

    @TruffleBoundary
    public static JSException createTypeErrorCannotConvertToNumber(String what) {
        return Errors.createTypeError("Cannot convert " + what + " to a number");
    }

    @TruffleBoundary
    public static JSException createTypeErrorIncompatibleReceiver(Object what) {
        return Errors.createTypeError("incompatible receiver: " + JSRuntime.objectToString(what));
    }

    @TruffleBoundary
    public static JSException createTypeErrorProtoCycle(DynamicObject thisObj) {
        return Errors.createTypeError("Cannot create__proto__ cycle for " + JSObject.defaultToString(thisObj));
    }

    @TruffleBoundary
    public static JSException createTypeErrorNotWritableProperty(Object key, Object thisObj) {
        return Errors.createTypeError(keyToString(key) + " is not a writable property of " + JSRuntime.objectToString(thisObj));
    }

    @TruffleBoundary
    public static JSException createTypeErrorLengthNotWritable() {
        return Errors.createTypeError("Cannot assign to read only property 'length'");
    }

    @TruffleBoundary
    public static JSException createTypeErrorNotConfigurableProperty(Object key) {
        return JSException.create(JSErrorType.TypeError, keyToString(key) + " is not a configurable property");
    }

    @TruffleBoundary
    public static JSException createTypeErrorNotExtensible(DynamicObject thisObj, Object key) {
        return Errors.createTypeError("Cannot add new property " + keyToString(key) + " to non-extensible " + JSObject.defaultToString(thisObj));
    }

    private static String keyToString(Object key) {
        assert JSRuntime.isPropertyKey(key);
        return key instanceof String ? "\"" + key + "\"" : key.toString();
    }

    @TruffleBoundary
    public static JSException createReferenceErrorNotDefined(Object key, Node originatingNode) {
        return Errors.createReferenceError(quoteKey(key) + " is not defined", originatingNode);
    }

    private static String quoteKey(Object key) {
        return JSTruffleOptions.NashornCompatibilityMode ? "\"" + key + "\"" : key.toString();
    }

    @TruffleBoundary
    public static JSException createTypeErrorNotConstructible(DynamicObject functionObj) {
        assert JSFunction.isJSFunction(functionObj);
        String message = String.format("%s is not a constructor function", JSRuntime.toString(functionObj));
        return JSException.create(JSErrorType.TypeError, message);
    }

    @TruffleBoundary
    public static JSException createTypeErrorCannotRedefineProperty(Object key) {
        assert JSRuntime.isPropertyKey(key);
        return Errors.createTypeError("Cannot redefine property %s", key);
    }

    @TruffleBoundary
    public static JSException createTypeErrorCannotSetPropertyOf(Object key, Object object) {
        assert JSRuntime.isPropertyKey(key);
        if (JSTruffleOptions.NashornCompatibilityMode) {
            return Errors.createTypeError("Cannot set property \"%s\" of %s", key, JSRuntime.objectToString(object));
        } else {
            return Errors.createTypeErrorCannotRedefineProperty(key);
        }
    }

    @TruffleBoundary
    public static JSException createTypeErrorCannotSetAccessorProperty(Object key, DynamicObject store) {
        assert JSRuntime.isPropertyKey(key);
        if (JSTruffleOptions.NashornCompatibilityMode) {
            return Errors.createTypeError("Cannot set property \"%s\" of %s that has only a getter", key, JSObject.defaultToString(store));
        } else {
            return Errors.createTypeError("Cannot redefine property %s which has only a getter", key);
        }
    }

    @TruffleBoundary
    public static JSException createTypeErrorCannotGetProperty(Object key, Object object, boolean isGetMethod, Node originatingNode) {
        assert JSRuntime.isPropertyKey(key);
        String errorMessage;
        if (JSTruffleOptions.NashornCompatibilityMode) {
            if (isGetMethod) {
                errorMessage = JSRuntime.objectToString(object) + " has no such function \"" + key + "\"";
            } else {
                if (object == Null.instance) {
                    errorMessage = "Cannot get property \"" + key + "\" of " + Null.NAME;
                } else {
                    errorMessage = "Cannot read property \"" + key + "\" from " + JSRuntime.objectToString(object);
                }
            }
        } else {
            errorMessage = "Cannot read property \'" + key + "\' of " + JSRuntime.objectToString(object);
        }
        return createTypeError(errorMessage, originatingNode);
    }

    @TruffleBoundary
    public static JSException createTypeErrorDetachedBuffer() {
        return Errors.createTypeError("Detached buffer");
    }

    @TruffleBoundary
    public static JSException createTypeErrorArrayBufferExpected() {
        return Errors.createTypeError("ArrayBuffer expected");
    }

    @TruffleBoundary
    public static JSException createTypeErrorArrayBufferViewExpected() {
        return Errors.createTypeError("ArrayBufferView expected");
    }

    @TruffleBoundary
    public static JSException createTypeErrorNotADataView() {
        return Errors.createTypeError("Not a DataView");
    }

    @TruffleBoundary
    public static JSException createCallStackSizeExceededError() {
        return Errors.createRangeError("Maximum call stack size exceeded");
    }

    @TruffleBoundary
    public static JSException createRangeErrorInvalidStringLength() {
        return Errors.createRangeError("Invalid string length");
    }

    @TruffleBoundary
    public static JSException createRangeErrorInvalidArrayLength() {
        return Errors.createRangeError("Invalid array length");
    }

    /**
     * @see #notYetImplemented(String)
     */
    public static RuntimeException notYetImplemented() {
        CompilerDirectives.transferToInterpreter();
        throw new UnsupportedOperationException("not yet implemented");
    }

    /**
     * Similar to UnsupportedOperationException, but with a flavor of a missing feature that will be
     * resolved in the future. In contrast, UnsupportedOperationException should be used for
     * operations that are expected to be unsupported forever.
     */
    public static RuntimeException notYetImplemented(String message) {
        CompilerDirectives.transferToInterpreter();
        throw new UnsupportedOperationException("not yet implemented: " + message);
    }

    public static RuntimeException shouldNotReachHere() {
        CompilerDirectives.transferToInterpreter();
        throw new IllegalStateException("should not reach here");
    }

    @TruffleBoundary
    public static JSException createTypeErrorConfigurableExpected() {
        return createTypeError("configurable expected");
    }

    @TruffleBoundary
    public static JSException createTypeErrorSameResultExpected() {
        return createTypeError("same result expected");
    }

    @TruffleBoundary
    public static JSException createTypeErrorCannotDeletePropertyOf(Object propertyKey, Object object) {
        assert JSRuntime.isPropertyKey(propertyKey);
        return createTypeError("Cannot delete property " + JSRuntime.quote(JSRuntime.javaToString(propertyKey)) + " of " + JSRuntime.objectToString(object));
    }

    public static JSException createTypeErrorJSObjectExpected() {
        return createTypeError("only JavaScript objects are supported by this operation");
    }

    public static JSException createNotAnObjectError(Node origin) {
        return createTypeError("not an object", origin);
    }

    @TruffleBoundary
    public static JSException createTypeErrorTrapReturnedFalsish(String trap, Object propertyKey) {
        return createTypeError("'" + trap + "' on proxy: trap returned falsish for property '" + propertyKey + "'");
    }

}