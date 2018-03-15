/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.nodes.control;

import java.util.ArrayDeque;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.HiddenKey;
import com.oracle.truffle.js.nodes.JavaScriptBaseNode;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.access.PropertyGetNode;
import com.oracle.truffle.js.nodes.access.PropertySetNode;
import com.oracle.truffle.js.nodes.arguments.AccessIndexedArgumentNode;
import com.oracle.truffle.js.nodes.function.InternalCallNode;
import com.oracle.truffle.js.nodes.function.JSFunctionCallNode;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSFrameUtil;
import com.oracle.truffle.js.runtime.JavaScriptRootNode;
import com.oracle.truffle.js.runtime.builtins.JSFunction;
import com.oracle.truffle.js.runtime.builtins.JSFunction.AsyncGeneratorState;
import com.oracle.truffle.js.runtime.builtins.JSFunctionData;
import com.oracle.truffle.js.runtime.builtins.JSPromise;
import com.oracle.truffle.js.runtime.objects.AsyncGeneratorRequest;
import com.oracle.truffle.js.runtime.objects.Undefined;

public class AsyncGeneratorResumeNextNode extends JavaScriptBaseNode {
    @Child private PropertyGetNode getGeneratorState;
    @Child private PropertySetNode setGeneratorState;
    @Child private PropertyGetNode getGeneratorTarget;
    @Child private PropertyGetNode getGeneratorContext;
    @Child private PropertyGetNode getAsyncGeneratorQueueNode;
    @Child private PropertyGetNode getPromiseResolve;
    @Child private JSFunctionCallNode callPromiseResolveNode;
    @Child private JSFunctionCallNode callPerformPromiseThen;
    @Child private JSFunctionCallNode createPromiseCapability;
    @Child private AsyncGeneratorResolveNode asyncGeneratorResolveNode;
    @Child private AsyncGeneratorRejectNode asyncGeneratorRejectNode;
    @Child private PropertyGetNode getPromise;
    @Child private PropertySetNode setGenerator;
    @Child private PropertySetNode setPromiseIsHandled;
    @Child private InternalCallNode callNode;
    private final JSContext context;

    static final HiddenKey RETURN_PROCESSOR_GENERATOR = new HiddenKey("Generator");

    protected AsyncGeneratorResumeNextNode(JSContext context) {
        this.context = context;
        this.getGeneratorState = PropertyGetNode.create(JSFunction.GENERATOR_STATE_ID, false, context);
        this.setGeneratorState = PropertySetNode.create(JSFunction.GENERATOR_STATE_ID, false, context, false);
        this.getGeneratorTarget = PropertyGetNode.create(JSFunction.GENERATOR_TARGET_ID, false, context);
        this.getGeneratorContext = PropertyGetNode.create(JSFunction.GENERATOR_CONTEXT_ID, false, context);
        this.getAsyncGeneratorQueueNode = PropertyGetNode.create(JSFunction.ASYNC_GENERATOR_QUEUE_ID, false, context);
        this.getPromiseResolve = PropertyGetNode.create("resolve", false, context);
        this.callPromiseResolveNode = JSFunctionCallNode.createCall();
        this.asyncGeneratorResolveNode = AsyncGeneratorResolveNode.create(context);
        this.getPromise = PropertyGetNode.create("promise", false, context);
        this.setGenerator = PropertySetNode.create(RETURN_PROCESSOR_GENERATOR, false, context, false);
        this.setPromiseIsHandled = PropertySetNode.create(JSPromise.PROMISE_IS_HANDLED, false, context, false);
        this.callNode = InternalCallNode.create();
        this.createPromiseCapability = JSFunctionCallNode.createCall();
    }

    public static AsyncGeneratorResumeNextNode create(JSContext context) {
        return new AsyncGeneratorResumeNextNode(context);
    }

    @SuppressWarnings("unchecked")
    public Object execute(VirtualFrame frame, DynamicObject generator) {
        for (;;) {
            AsyncGeneratorState state = (AsyncGeneratorState) getGeneratorState.getValue(generator);
            assert state != AsyncGeneratorState.Executing;
            if (state == AsyncGeneratorState.AwaitingReturn) {
                return Undefined.instance;
            }
            ArrayDeque<AsyncGeneratorRequest> queue = (ArrayDeque<AsyncGeneratorRequest>) getAsyncGeneratorQueueNode.getValue(generator);
            if (queue.isEmpty()) {
                return Undefined.instance;
            }
            AsyncGeneratorRequest next = queue.peekFirst();
            if (next.isAbruptCompletion()) {
                if (state == AsyncGeneratorState.SuspendedStart) {
                    setGeneratorState.setValue(generator, state = AsyncGeneratorState.Completed);
                }
                if (state == AsyncGeneratorState.Completed) {
                    if (next.isReturn()) {
                        setGeneratorState.setValue(generator, AsyncGeneratorState.AwaitingReturn);
                        DynamicObject promiseCapability = newPromiseCapability();
                        Object resolve = getPromiseResolve.getValue(promiseCapability);
                        callPromiseResolveNode.executeCall(JSArguments.createOneArg(Undefined.instance, resolve, next.getCompletionValue()));
                        DynamicObject onFulfilled = createAsyncGeneratorReturnProcessorFulfilledFunction(generator);
                        DynamicObject onRejected = createAsyncGeneratorReturnProcessorRejectedFunction(generator);
                        DynamicObject throwawayCapability = newPromiseCapability();
                        setPromiseIsHandled.setValueBoolean(getPromise.getValue(throwawayCapability), true);
                        performPromiseThen(getPromise.getValue(promiseCapability), onFulfilled, onRejected, throwawayCapability);
                        return Undefined.instance;
                    } else {
                        assert next.isThrow();
                        // return ! AsyncGeneratorReject(generator, completion.[[Value]]).
                        if (asyncGeneratorRejectNode == null) {
                            CompilerDirectives.transferToInterpreterAndInvalidate();
                            this.asyncGeneratorRejectNode = insert(AsyncGeneratorRejectNode.create(context));
                        }
                        asyncGeneratorRejectNode.performReject(frame, generator, next.getCompletionValue());
                        continue; // Perform ! AsyncGeneratorResumeNext(generator).
                    }
                }
            } else if (state == AsyncGeneratorState.Completed) {
                // return ! AsyncGeneratorResolve(generator, undefined, true).
                asyncGeneratorResolveNode.performResolve(frame, generator, Undefined.instance, true);
                continue; // Perform ! AsyncGeneratorResumeNext(generator).
            }
            assert state == AsyncGeneratorState.SuspendedStart || state == AsyncGeneratorState.SuspendedYield;

            setGeneratorState.setValue(generator, state = AsyncGeneratorState.Executing);
            CallTarget generatorTarget = (CallTarget) getGeneratorTarget.getValue(generator);
            Object generatorContext = getGeneratorContext.getValue(generator);
            callNode.execute(generatorTarget, new Object[]{generatorContext, generator, next.getCompletion()});
            return Undefined.instance;
        }
    }

    private DynamicObject newPromiseCapability() {
        return (DynamicObject) createPromiseCapability.executeCall(JSArguments.createZeroArg(Undefined.instance, context.getAsyncFunctionPromiseCapabilityConstructor()));
    }

    private void performPromiseThen(Object promise, DynamicObject onFulfilled, DynamicObject onRejected, DynamicObject resultCapability) {
        callPerformPromiseThen.executeCall(JSArguments.create(Undefined.instance, context.getPerformPromiseThen(), promise, onFulfilled, onRejected, resultCapability));
    }

    private DynamicObject createAsyncGeneratorReturnProcessorFulfilledFunction(DynamicObject generator) {
        JSFunctionData functionData = context.getOrCreateBuiltinFunctionData(JSContext.BuiltinFunctionKey.AsyncGeneratorReturnFulfilled, (c) -> createAsyncGeneratorReturnProcessorFulfilledImpl(c));
        DynamicObject function = JSFunction.create(context.getRealm(), functionData);
        setGenerator.setValue(function, generator);
        return function;
    }

    private static JSFunctionData createAsyncGeneratorReturnProcessorFulfilledImpl(JSContext context) {
        CallTarget callTarget = Truffle.getRuntime().createCallTarget(new JavaScriptRootNode() {
            @Child private JavaScriptNode valueNode = AccessIndexedArgumentNode.create(0);
            @Child private AsyncGeneratorResolveNode asyncGeneratorResolveNode = AsyncGeneratorResolveNode.create(context);
            @Child private PropertyGetNode getGenerator = PropertyGetNode.create(RETURN_PROCESSOR_GENERATOR, false, context);
            @Child private PropertySetNode setGeneratorState = PropertySetNode.create(JSFunction.GENERATOR_STATE_ID, false, context, false);

            @Override
            public Object execute(VirtualFrame frame) {
                DynamicObject functionObject = JSFrameUtil.getFunctionObject(frame);
                DynamicObject generatorObject = (DynamicObject) getGenerator.getValue(functionObject);
                setGeneratorState.setValue(generatorObject, AsyncGeneratorState.Completed);
                Object value = valueNode.execute(frame);
                return asyncGeneratorResolveNode.execute(frame, generatorObject, value, true);
            }
        });
        return JSFunctionData.createCallOnly(context, callTarget, 1, "AsyncGeneratorResumeNext Return Processor Fulfilled");
    }

    private DynamicObject createAsyncGeneratorReturnProcessorRejectedFunction(DynamicObject generator) {
        JSFunctionData functionData = context.getOrCreateBuiltinFunctionData(JSContext.BuiltinFunctionKey.AsyncGeneratorReturnRejected, (c) -> createAsyncGeneratorReturnProcessorRejectedImpl(c));
        DynamicObject function = JSFunction.create(context.getRealm(), functionData);
        setGenerator.setValue(function, generator);
        return function;
    }

    private static JSFunctionData createAsyncGeneratorReturnProcessorRejectedImpl(JSContext context) {
        CallTarget callTarget = Truffle.getRuntime().createCallTarget(new JavaScriptRootNode() {
            @Child private JavaScriptNode reasonNode = AccessIndexedArgumentNode.create(0);
            @Child private AsyncGeneratorRejectNode asyncGeneratorRejectNode = AsyncGeneratorRejectNode.create(context);
            @Child private PropertyGetNode getGenerator = PropertyGetNode.create(RETURN_PROCESSOR_GENERATOR, false, context);
            @Child private PropertySetNode setGeneratorState = PropertySetNode.create(JSFunction.GENERATOR_STATE_ID, false, context, false);

            @Override
            public Object execute(VirtualFrame frame) {
                DynamicObject functionObject = JSFrameUtil.getFunctionObject(frame);
                DynamicObject generatorObject = (DynamicObject) getGenerator.getValue(functionObject);
                setGeneratorState.setValue(generatorObject, AsyncGeneratorState.Completed);
                Object reason = reasonNode.execute(frame);
                return asyncGeneratorRejectNode.execute(frame, generatorObject, reason);
            }
        });
        return JSFunctionData.createCallOnly(context, callTarget, 1, "AsyncGeneratorResumeNext Return Processor Rejected");
    }
}