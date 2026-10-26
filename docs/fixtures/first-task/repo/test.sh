#!/bin/sh
# Run with: sh test.sh
. ./lib.sh

result=$(add 2 3)
if [ "$result" != "5" ]; then
    echo "FAIL: add 2 3 printed '$result', expected '5'"
    exit 1
fi
echo "PASS"
