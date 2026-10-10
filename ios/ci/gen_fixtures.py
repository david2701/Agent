#!/usr/bin/env python3
"""Regenerates the JSON fixtures of the KairoKit tests from demo_data.py with a fixed moment
(Saturday, October 10, 2026, 9:00 AM New York time), so they never change between runs.
Usage: python3 gen_fixtures.py"""
import json
import os
from datetime import datetime
from zoneinfo import ZoneInfo

import demo_data

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "KairoKit", "Tests", "KairoCoreTests", "Fixtures")

if __name__ == "__main__":
    demo = demo_data.Demo(datetime(2026, 10, 10, 9, 0, tzinfo=ZoneInfo(demo_data.TZ_NAME)))
    for name, data in demo.resources().items():
        with open(os.path.join(OUT, name + ".json"), "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False)
        print("wrote", name + ".json")
