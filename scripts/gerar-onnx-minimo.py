#!/usr/bin/env python3
"""Gera o `.onnx` mínimo que o instrumentado `OnnxSessionTest` roda: z = x + y (float32, forma [n]) e w = a + b (int64, forma [n]).

Protobuf escrito à mão (sem dependência de `onnx`): ir_version 7, opset 13, dois nós `Add`, forma dinâmica "n". Uso:
    python3 -I scripts/gerar-onnx-minimo.py app/src/androidTest/assets/onnx/add.onnx
A saída é determinística; o SHA-256 esperado está no teste (`OnnxSessionTest.ADD_ONNX_SHA256`).
"""
import hashlib, sys

def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)

def field(num, wire, payload):
    tag = varint((num << 3) | wire)
    if wire == 2:
        return tag + varint(len(payload)) + payload
    return tag + payload

def s(num, text): return field(num, 2, text.encode())
def i(num, value): return field(num, 0, varint(value))
def m(num, payload): return field(num, 2, payload)

def value_info(name, elem_type):
    dim = s(2, "n")                      # Dimension.dim_param = "n"
    shape = m(1, dim)                    # TensorShapeProto.dim
    tensor = i(1, elem_type) + m(2, shape)   # Tensor.elem_type, Tensor.shape
    typ = m(1, tensor)                   # TypeProto.tensor_type
    return s(1, name) + m(2, typ)

def node(a, b, out, op="Add"):
    return s(1, a) + s(1, b) + s(2, out) + s(4, op)

FLOAT, INT64 = 1, 7
graph = (m(1, node("x", "y", "z")) + m(1, node("a", "b", "w")) + s(2, "add")
         + m(11, value_info("x", FLOAT)) + m(11, value_info("y", FLOAT))
         + m(11, value_info("a", INT64)) + m(11, value_info("b", INT64))
         + m(12, value_info("z", FLOAT)) + m(12, value_info("w", INT64)))
opset = s(1, "") + i(2, 13)
model = i(1, 7) + s(2, "keyflow") + m(7, graph) + m(8, opset)   # ir_version 7, producer, graph, opset 13
out = sys.argv[1]
open(out, "wb").write(model)
print(out, len(model), "bytes", "sha256", hashlib.sha256(model).hexdigest())
