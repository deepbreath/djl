/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package org.apache.mxnet.engine;

import com.amazon.ai.Block;
import com.amazon.ai.Context;
import com.amazon.ai.ndarray.NDArray;
import com.amazon.ai.ndarray.NDFactory;
import com.amazon.ai.ndarray.types.DataDesc;
import com.amazon.ai.ndarray.types.GradReq;
import com.amazon.ai.ndarray.types.Layout;
import com.amazon.ai.ndarray.types.Shape;
import com.amazon.ai.ndarray.types.SparseFormat;
import com.amazon.ai.util.PairList;
import com.amazon.ai.util.Utils;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.mxnet.jna.JnaUtils;

public class Symbol extends NativeResource implements Block {

    private String[] argParams;
    private String[] auxParams;
    private String[] outputs;
    private List<Integer> outputLayouts;

    Symbol(NDFactory factory, Pointer pointer) {
        super(factory, pointer);
        argParams = JnaUtils.listSymbolArguments(getHandle());
        auxParams = JnaUtils.listSymbolAuxiliaryStates(getHandle());
    }

    public static Symbol load(NDFactory factory, String path) {
        Pointer pointer = JnaUtils.createSymbolFromFile(path);
        return new Symbol(factory, pointer);
    }

    public String[] getArgParams() {
        return argParams;
    }

    public String[] getAuxParams() {
        return auxParams;
    }

    public String[] getOutputs() {
        if (outputs == null) {
            outputs = JnaUtils.listSymbolOutputs(getHandle());
        }
        return outputs;
    }

    public List<Integer> getOutputLayouts() {
        if (outputLayouts == null) {
            outputLayouts = new ArrayList<>();
            for (String argName : getArgParams()) {
                try (Symbol symbol = get(argName)) {
                    Layout layout = Layout.fromValue(symbol.getAttribute("__layout__"));
                    outputLayouts.add(DataDesc.getBatchAxis(layout));
                }
            }
        }
        return outputLayouts;
    }

    public String getAttribute(String key) {
        return JnaUtils.getSymbolAttr(getHandle(), key);
    }

    public PairList<String, String> getAttributes() {
        return JnaUtils.listSymbolAttr(getHandle());
    }

    public void plus(Symbol other) {}

    public void plusScalar(Symbol other) {}

    public void minus(Symbol other) {}

    public void minusScalar(Symbol other) {}

    public Symbol copy() {
        return this;
    }

    public Symbol get(int index) {
        Pointer pointer = JnaUtils.getSymbolOutput(getHandle(), index);
        return new Symbol(getNDFactory(), pointer);
    }

    public Symbol get(String name) {
        String[] out = getOutputs();
        int index = Utils.indexOf(out, name);
        if (index < 0) {
            throw new IllegalArgumentException("Cannot find output that matches name: " + name);
        }
        return get(index);
    }

    public Symbol getInternals() {
        Pointer pointer = JnaUtils.getSymbolInternals(getHandle());
        return new Symbol(getNDFactory(), pointer);
    }

    public String debugStr() {
        return JnaUtils.getSymbolDebugString(getHandle());
    }

    public void setAttr(Map<String, String> attrs) {
        for (Map.Entry<String, String> entry : attrs.entrySet()) {
            JnaUtils.setSymbolAttr(getHandle(), entry.getKey(), entry.getValue());
        }
    }

    public PairList<String, String> listAttr() {
        return JnaUtils.listSymbolAttr(getHandle());
    }

    public PairList<String, String> attrMap() {
        return JnaUtils.listSymbolAttr(getHandle());
    }

    public void save(String path) {
        JnaUtils.saveSymbol(getHandle(), path);
    }

    public Symbol compose(String name, String[] keys) {
        return new Symbol(getNDFactory(), JnaUtils.compose(getHandle(), name, keys));
    }

    public void compose(String name, Map<String, String> symbols) {
        JnaUtils.compose(getHandle(), name, symbols.values().toArray(new String[0]));
    }

    public MxExecutor[] simpleBind(
            MxModel model,
            List<Context> contexts,
            String[] labelNames,
            String[] stateNames,
            GradReq gradReq,
            Map<String, Context> g2cMap,
            Map<String, SparseFormat> stypeMap) {
        MxExecutor[] executors = new MxExecutor[contexts.size()];

        // each argParams have a gradReq value
        String[] argParamGradReqs = new String[argParams.length];
        Arrays.fill(argParamGradReqs, gradReq.getType());

        // g2c
        String[] g2cKeys = null;
        int[] g2cDeviceTypes = null;
        int[] g2cDeviceIds = null;
        if (g2cMap != null && !g2cMap.isEmpty()) {
            g2cKeys = new String[g2cMap.size()];
            g2cDeviceTypes = new int[g2cKeys.length];
            g2cDeviceIds = new int[g2cKeys.length];

            int k = 0;
            for (Map.Entry<String, Context> entry : g2cMap.entrySet()) {
                g2cKeys[k] = entry.getKey();
                Context ctx = entry.getValue();
                g2cDeviceTypes[k] = DeviceType.toDeviceType(ctx);
                g2cDeviceIds[k] = ctx.getDeviceId();
                ++k;
            }
        }

        // Prepare input data related parameters
        DataDesc[] dataDescriptors = model.describeInput();
        int size = 0;
        for (DataDesc desc : dataDescriptors) {
            size += desc.getShape().getShape().length;
        }
        String[] inputArgNames = new String[dataDescriptors.length];
        String[] inputDataTypeNames = new String[inputArgNames.length];
        int[] inputDataTypes = new int[inputArgNames.length];

        IntBuffer inputShapeData = IntBuffer.allocate(size);
        IntBuffer inputShapeIdx = IntBuffer.allocate(inputArgNames.length + 1);
        inputShapeIdx.put(0);
        int k = 0;
        int offset = 0;
        for (DataDesc desc : dataDescriptors) {
            inputArgNames[k] = desc.getName();
            inputDataTypeNames[k] = desc.getName();
            inputDataTypes[k] = desc.getDataType().ordinal();
            int[] shape = desc.getShape().getShape();
            inputShapeData.put(shape);
            offset += shape.length;
            inputShapeIdx.put(offset);
            ++k;
        }
        inputShapeData.rewind();
        inputShapeIdx.rewind();

        String[] inputStorageTypeNames = null;
        int[] inputStorageTypes = null;
        if (stypeMap != null && !stypeMap.isEmpty()) {
            inputStorageTypeNames = new String[stypeMap.size()];
            inputStorageTypes = new int[inputStorageTypeNames.length];

            k = 0;
            for (Map.Entry<String, SparseFormat> entry : stypeMap.entrySet()) {
                inputStorageTypeNames[k] = entry.getKey();
                inputStorageTypes[k] = entry.getValue().getValue();
                ++k;
            }
        }

        // filter argParams from inputNames, labelNames, and stateNames
        List<String> sharedArgNames = new ArrayList<>();
        for (String arg : argParams) {
            if (!Utils.contains(inputArgNames, arg)
                    && !Utils.contains(labelNames, arg)
                    && !Utils.contains(stateNames, arg)) {
                sharedArgNames.add(arg);
            }
        }
        String[] sharedArgParams = sharedArgNames.toArray(new String[0]); // NOPMD

        IntBuffer sharedBufferLen = IntBuffer.allocate(1);
        sharedBufferLen.put(0, 0);
        String[] sharedBufferNames = new String[0];
        PointerByReference sharedBufferHandles = new PointerByReference();

        for (int i = 0; i < contexts.size(); ++i) {
            Context context = contexts.get(i);

            PointerByReference updatedSharedBufferNames = new PointerByReference();
            PointerByReference updatedSharedBufferHandles = new PointerByReference();

            IntBuffer numInArgs = IntBuffer.allocate(1);
            PointerByReference inArgs = new PointerByReference();
            PointerByReference argGrads = new PointerByReference();
            IntBuffer numAuxStates = IntBuffer.allocate(1);
            PointerByReference auxStates = new PointerByReference();

            Pointer pointer =
                    JnaUtils.bindExecutorSimple(
                            this,
                            context,
                            g2cKeys,
                            g2cDeviceTypes,
                            g2cDeviceIds,
                            argParams,
                            argParamGradReqs,
                            inputArgNames,
                            inputShapeData,
                            inputShapeIdx,
                            inputDataTypeNames,
                            inputDataTypes,
                            inputStorageTypeNames,
                            inputStorageTypes,
                            sharedArgParams,
                            sharedBufferLen,
                            sharedBufferNames,
                            sharedBufferHandles,
                            updatedSharedBufferNames,
                            updatedSharedBufferHandles,
                            numInArgs,
                            inArgs,
                            argGrads,
                            numAuxStates,
                            auxStates);

            // update shared buffer
            int updatedSize = sharedBufferLen.get(0);
            if (updatedSize > 0) {
                Pointer[] updatedPointer =
                        updatedSharedBufferHandles.getValue().getPointerArray(0, updatedSize);
                String[] updatedNames =
                        updatedSharedBufferNames.getValue().getStringArray(0, updatedSize);
            }

            Map<String, MxNDArray> argParamMap = model.getArgParams().toMap();

            // get output for current executor's in_args, arg_grads, and aux_states
            int inArgSize = numInArgs.get(0);
            Pointer[] inArgsPointers = inArgs.getValue().getPointerArray(0, inArgSize);
            Pointer[] gradPointers = argGrads.getValue().getPointerArray(0, inArgSize);
            MxNDArray[] argArray = new MxNDArray[inArgSize];
            MxNDArray[] gradArray = new MxNDArray[inArgSize];
            MxNDArray[] dataArray = new MxNDArray[inputArgNames.length];
            for (int j = 0; j < inArgSize; ++j) {
                argArray[j] = new MxNDArray(alloc, inArgsPointers[j]);

                String paramName = argParams[j];

                MxNDArray param = argParamMap.get(paramName);
                if (param == null) {
                    int dataIdx = Utils.indexOf(inputArgNames, paramName);
                    if (dataIdx >= 0) {
                        dataArray[dataIdx] = argArray[j];
                    }
                } else {
                    param.copyTo(argArray[j]);
                }

                if (gradPointers[j] != null) {
                    gradArray[j] = new MxNDArray(alloc, gradPointers[j]);
                }
            }

            int auxStatesSize = numAuxStates.get();
            MxNDArray[] auxArray = new MxNDArray[auxStatesSize];
            if (auxStatesSize > 0) {
                Map<String, MxNDArray> auxParamMap = model.getAuxParams().toMap();
                Pointer[] pointers = auxStates.getValue().getPointerArray(0, auxStatesSize);
                for (int j = 0; j < auxStatesSize; ++j) {
                    auxArray[j] = new MxNDArray(alloc, pointers[j]);

                    MxNDArray param = auxParamMap.get(auxParams[j]);
                    if (param == null) {
                        throw new IllegalStateException("aux parameter not found: " + auxParams[j]);
                    }
                    param.copyTo(auxArray[j]);
                }
            }

            MxNDArray[] out = JnaUtils.getExecutorOutputs((MxNDFactory) alloc, pointer);

            executors[i] =
                    new MxExecutor(alloc, pointer, argArray, auxArray, dataArray, out, gradArray);
        }
        return executors;
    }

    public String toJson() {
        return JnaUtils.symbolToJson(getHandle());
    }

    @Override
    public void close() {
        Pointer pointer = handle.getAndSet(null);
        if (pointer != null) {
            JnaUtils.freeSymbol(pointer);
        }
        if (alloc != null) {
            alloc.detach(this);
        }
    }

    @Override
    public void forward() {}

    @Override
    public void backward() {}

    @Override
    public Shape getInputShape() {
        return null;
    }

    @Override
    public Shape getOutputShape() {
        return null;
    }

    @Override
    public void setInput(NDArray array) {}

    @Override
    public NDArray getOutput() {
        return null;
    }

    @Override
    public byte[] getEncoded() {
        return new byte[0];
    }

    public MxNDFactory getNDFactory() {
        return (MxNDFactory) alloc;
    }
}