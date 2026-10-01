"""Rewrite intermediary names in the posecurve files using current imports.

Idempotent: after one pass the imports are named and class tokens converted;
owner resolution then works from the CURRENT named import lines.
"""

import json
import re
import os

with open(r"E:\BBS FS AI\mc-mcp\int_map.json", encoding="utf-8") as f:
    m = json.load(f)

classes = m["classes"]                       # int fqn -> named fqn
methods_owner = {}
for key, named in m["methods"].items():
    owner, mint = key.split("\x01")
    methods_owner.setdefault(owner, {})[mint] = named

# reverse: named simple -> int fqn
simple_to_int = {v.rsplit(".", 1)[1]: k for k, v in classes.items()}

FILES = [
    r"E:\BBS FS AI\bbs-fs\src\client\java\bbsplus\example\bbsplus\client\pose\UIPoseTransformKeyframeGraph.java",
    r"E:\BBS FS AI\bbs-fs\src\client\java\bbsplus\example\bbsplus\mixin\client\MixinUIKeyframeGraph.java",
]

for full in FILES:
    s = open(full, encoding="utf-8").read()

    # Pass 1: imports (intermediary form -> named)
    simple = {}

    def import_repl(match):
        int_fqn = match.group(1)
        named_fqn = classes.get(int_fqn)
        if named_fqn is None:
            return match.group(0)
        simple[int_fqn] = named_fqn.rsplit(".", 1)[1]
        return "import " + named_fqn + ";"

    s = re.sub(r"import (net\.minecraft\.class_\d+);", import_repl, s)

    # Pass 2: remaining bare class tokens (imports may already be named from a
    # previous run - derive their int fqn from the CURRENT named import lines)
    for match in list(re.finditer(r"import ([\w.]+);", s)):
        named_fqn = match.group(1)

        if named_fqn in classes:
            continue

        int_fqn = simple_to_int.get(named_fqn.rsplit(".", 1)[1])

        if int_fqn is not None:
            simple[int_fqn] = named_fqn.rsplit(".", 1)[1]

    # Pass 3: bare class tokens by suffix
    def class_token(match):
        bare = match.group(0)
        int_fqn = "net.minecraft." + bare

        if int_fqn in classes:
            named_fqn = classes[int_fqn]
            simple[int_fqn] = named_fqn.rsplit(".", 1)[1]

            return named_fqn.rsplit(".", 1)[1]

        return bare

    s = re.sub(r"\bclass_\d+\b", class_token, s)

    # Pass 4: owner.method_int combos
    def method_repl(match):
        owner_simple, mint = match.group(1), match.group(2)
        int_fqn = next((k for k, v in simple.items() if v.endswith("." + owner_simple)), None)

        if int_fqn is None:
            return match.group(0)

        named = methods_owner.get(int_fqn, {}).get(mint)

        if named is None:
            return match.group(0)

        return owner_simple + "." + named

    s = re.sub(r"\b([A-Za-z_][A-Za-z0-9_]*)\.(method_\d+)\b", method_repl, s)

    open(full, "w", encoding="utf-8", newline="\n").write(s)

    leftovers = re.findall(r"\b(?:class_|method_)\d+\b", s)
    print(os.path.basename(full), "leftovers:", len(leftovers), leftovers[:8])
