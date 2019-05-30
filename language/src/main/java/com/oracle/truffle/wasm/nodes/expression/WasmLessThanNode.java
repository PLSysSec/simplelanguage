package com.oracle.truffle.wasm.nodes.expression;

import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.wasm.nodes.WasmBinaryNode;

@NodeInfo(shortName = "lt")
public abstract class WasmLessThanNode extends WasmBinaryNode {
}
