"""Consumer side of the event contract for the lake pipeline.

Every payload field a dbt silver model reads must be declared in that event type's JSON Schema
(services/common/src/main/resources/events/schemas). If a producer renames or drops a field, the
schema changes and this test points at the model that would silently start reading nulls.
"""
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / "services/common/src/main/resources/events/schemas"
SILVER = ROOT / "data/dbt/models/silver"

FIELD = re.compile(r"payload->>?'([A-Za-z_]+)'")
SOURCE = re.compile(r"(?:dedup|bronze)\(\s*'([a-z_.*]+)'")


def schema_for(event_type):
    name = "client" if event_type.startswith("client") else event_type
    return json.loads((SCHEMAS / f"{name}.schema.json").read_text())


def models():
    for sql in sorted(SILVER.glob("*.sql")):
        text = sql.read_text()
        types = SOURCE.findall(text)
        if types:
            yield sql.name, text, types


def test_silver_models_read_only_declared_fields():
    problems = []
    checked = 0
    for name, text, types in models():
        fields = set(FIELD.findall(text))
        for t in types:
            props = set(schema_for(t)["properties"])
            missing = sorted(fields - props)
            # a model reading several event types (e.g. markets) may read fields of any of them
            if len(types) > 1:
                union = set().union(*(set(schema_for(x)["properties"]) for x in types))
                missing = sorted(fields - union)
            if missing:
                problems.append(f"{name} reads {missing} not declared for {t}")
            checked += 1
    assert checked > 0, "no silver models found"
    assert not problems, "\n".join(problems)


def test_every_event_type_used_by_the_lake_has_a_schema():
    for name, _, types in models():
        for t in types:
            assert schema_for(t)["required"], f"{t} (used by {name}) has no required fields"


def test_schema_examples_carry_every_required_field():
    for path in SCHEMAS.glob("*.schema.json"):
        s = json.loads(path.read_text())
        example = s["examples"][0]
        missing = [f for f in s["required"] if f not in example]
        assert not missing, f"{path.name} example lacks {missing}"
