#!/bin/bash
# Verify which translations exist on the CDN
# Run before ingesting to confirm codes are valid

BASE="https://cdn.jsdelivr.net/gh/wldeh/bible-api/bibles"
TEST_VERSE="/books/genesis/chapters/1/verses/1.json"

translations=(
    "en-kjv"
    "en-asv"
    "en-web"
    "en-dra"
    "en-bsb"
    "es-rv09"
    "es-bes"
    "fi-aeuut"
    "sv-skb"
    "de-luther1912"
    "hbo-wlc"
    "grc-byz1904"
    "arb-kehm"
)

echo "Checking translations..."
for t in "${translations[@]}"; do
    result=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/$t$TEST_VERSE")
    if [ "$result" = "200" ]; then
        text=$(curl -s "$BASE/$t$TEST_VERSE" | python3 -c "import json,sys; print(json.load(sys.stdin).get('text','')[:50])")
        echo "✅ $t — $text"
    else
        echo "❌ $t (HTTP $result)"
    fi
done
