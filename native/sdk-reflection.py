#!/usr/bin/env python3
"""Reflection metadata for the provider SDKs' model classes, written as GraalVM
reachability metadata. The SDKs (de)serialize requests and responses with
Jackson over reflection, and the tracing agent only records the classes of the
responses it happened to see; this registers the same shape for every model
class: its constructors (concrete classes only: registering those of abstract
classes or interfaces crashes native-image 25.3) and every Jackson-annotated
method and field (`@JsonCreator`, `@JsonProperty` getters, `@JsonAnySetter`,
`@JsonValue`, ...). Registering every method instead makes hundreds of
thousands of methods reachable and the build runs out of memory.

    sdk-reflection.py <jar> <out.json>
"""
import json, struct, sys, zipfile

PREFIXES = ("com/openai/models/", "com/openai/core/", "com/anthropic/models/", "com/anthropic/core/")
JACKSON = ("Lcom/fasterxml/jackson/annotation/", "Lcom/fasterxml/jackson/databind/annotation/")
ACC_INTERFACE, ACC_ABSTRACT = 0x0200, 0x0400
PRIMITIVES = {"B": "byte", "C": "char", "D": "double", "F": "float", "I": "int", "J": "long", "S": "short", "Z": "boolean", "V": "void"}

class ClassFile:
    def __init__(self, data: bytes):
        self.data = data
        pos = 8
        count = self.u2(pos); pos += 2
        self.pool = [None] * count
        i = 1
        while i < count:
            tag = data[pos]; pos += 1
            if tag == 1:
                n = self.u2(pos); self.pool[i] = data[pos + 2:pos + 2 + n].decode("utf-8", "replace"); pos += 2 + n
            elif tag in (3, 4): pos += 4
            elif tag in (5, 6): pos += 8; i += 1
            elif tag in (7, 8, 16, 19, 20): pos += 2
            elif tag in (9, 10, 11, 12, 17, 18): pos += 4
            elif tag == 15: pos += 3
            else: raise ValueError(f"unknown constant pool tag {tag}")
            i += 1
        self.flags = self.u2(pos); pos += 2
        pos += 4  # this, super
        pos += 2 + 2 * self.u2(pos)  # interfaces
        self.fields = self.members(pos)
        self.methods = self.members(self.end)

    def u2(self, pos): return struct.unpack_from(">H", self.data, pos)[0]
    def u4(self, pos): return struct.unpack_from(">I", self.data, pos)[0]

    def members(self, pos):
        """(name, descriptor, annotated) per member; leaves `self.end` after the table."""
        out = []
        count = self.u2(pos); pos += 2
        for _ in range(count):
            name, desc = self.pool[self.u2(pos + 2)], self.pool[self.u2(pos + 4)]
            attrs = self.u2(pos + 6); pos += 8
            annotated = False
            for _ in range(attrs):
                aname, alen = self.pool[self.u2(pos)], self.u4(pos + 2)
                if aname in ("RuntimeVisibleAnnotations", "RuntimeVisibleParameterAnnotations"):
                    annotated = annotated or self.mentions_jackson(pos + 6, alen)
                pos += 6 + alen
            out.append((name, desc, annotated))
        self.end = pos
        return out

    def mentions_jackson(self, pos, length):
        """Whether any constant-pool index in the attribute names a Jackson annotation type."""
        for p in range(pos, pos + length - 1):
            s = self.pool[self.u2(p)] if self.u2(p) < len(self.pool) else None
            if isinstance(s, str) and s.startswith(JACKSON):
                return True
        return False

def java_type(desc, pos=0):
    """One parameter type of a descriptor as a Java class name, and the next position."""
    dims = 0
    while desc[pos] == "[": dims += 1; pos += 1
    if desc[pos] == "L":
        end = desc.index(";", pos); name = desc[pos + 1:end].replace("/", "."); pos = end + 1
    else:
        name = PRIMITIVES[desc[pos]]; pos += 1
    return name + "[]" * dims, pos

def parameter_types(desc):
    types, pos = [], 1
    while desc[pos] != ")":
        t, pos = java_type(desc, pos); types.append(t)
    return types

jar, out = sys.argv[1], sys.argv[2]
entries, methods_total = [], 0
with zipfile.ZipFile(jar) as z:
    for name in sorted(z.namelist()):
        if not name.endswith(".class") or not name.startswith(PREFIXES):
            continue
        cf = ClassFile(z.read(name))
        concrete = not cf.flags & (ACC_INTERFACE | ACC_ABSTRACT)
        methods = [{"name": n, "parameterTypes": parameter_types(d)} for n, d, annotated in cf.methods
                   if (annotated or (n == "<init>" and concrete)) and n != "<clinit>"]
        fields = [{"name": n} for n, d, annotated in cf.fields if annotated]
        if methods or fields:
            entry = {"type": name[:-6].replace("/", ".")}
            if methods: entry["methods"] = methods
            if fields: entry["fields"] = fields
            entries.append(entry); methods_total += len(methods)
with open(out, "w") as f:
    json.dump({"reflection": entries}, f)
print(f"{len(entries)} classes, {methods_total} methods registered for reflection", file=sys.stderr)
