#!/usr/bin/env python3
"""
Test helper script for PythonScriptExecutorTest and PythonDirectProcessTest.

Usage:
  python test_script.py        → returns 2 stock entries
  python test_script.py 3      → returns 3 stock entries
  python test_script.py 0      → returns empty array
  python test_script.py abc    → exits with code 1 (invalid arg)
"""
import json
import sys

SAMPLE_STOCKS = [
    {"symbol": "AAPL", "name": "Apple Inc.", "price": 150.25},
    {"symbol": "MSFT", "name": "Microsoft Corp.", "price": 380.10},
    {"symbol": "GOOG", "name": "Alphabet Inc.", "price": 2800.50},
    {"symbol": "AMZN", "name": "Amazon.com Inc.", "price": 178.50},
    {"symbol": "TSLA", "name": "Tesla Inc.", "price": 250.00},
]


def main():
    if len(sys.argv) > 1:
        arg = sys.argv[1]
        if arg == "abc":
            # Invalid argument — exit with non-zero code
            print("Invalid argument: abc", file=sys.stderr)
            sys.exit(1)
        try:
            count = int(arg)
        except ValueError:
            print(f"Invalid argument: {arg}", file=sys.stderr)
            sys.exit(1)
        result = SAMPLE_STOCKS[:count] if count > 0 else []
    else:
        result = SAMPLE_STOCKS[:2]  # default: 2 entries

    print(json.dumps(result))


if __name__ == "__main__":
    main()
