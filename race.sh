#!/bin/sh
CALL='{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"vredis_upsert","arguments":{"key":"multi:1","value":"cross-instance"}}}'
S1=$(curl -s -D - -o /dev/null -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'X-API-Key: admin-key' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"r1","version":"1"}}}' \
  | grep -i 'mcp-session-id' | tr -d '\r' | awk '{print $2}')
S2=$(curl -s -D - -o /dev/null -X POST http://mcp-gateway-2:8080/mcp \
  -H 'Content-Type: application/json' -H 'X-API-Key: admin-key' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"r2","version":"1"}}}' \
  | grep -i 'mcp-session-id' | tr -d '\r' | awk '{print $2}')
echo "S1=$S1 S2=$S2"
curl -s -o /tmp/r1.txt -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'X-API-Key: admin-key' \
  -H 'Accept: application/json, text/event-stream' -H "Mcp-Session-Id: $S1" \
  -d "$CALL" &
P1=$!
curl -s -o /tmp/r2.txt -X POST http://mcp-gateway-2:8080/mcp \
  -H 'Content-Type: application/json' -H 'X-API-Key: admin-key' \
  -H 'Accept: application/json, text/event-stream' -H "Mcp-Session-Id: $S2" \
  -d "$CALL" &
P2=$!
wait $P1 $P2
echo "--- gw1:"; grep -aoE 'executed|pending' /tmp/r1.txt | head -1
echo "--- gw2:"; grep -aoE 'executed|pending' /tmp/r2.txt | head -1
