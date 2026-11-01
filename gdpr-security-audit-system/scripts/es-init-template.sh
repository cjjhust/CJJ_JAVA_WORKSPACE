#!/usr/bin/env bash
# ES Index Template 初始化 — 为 audit-logs-* 定义正确的字段类型映射，
# 避免动态映射把所有字段建成 text 导致无法聚合。可重复执行（幂等）。
set -euo pipefail

ES_URL="${ES_URL:-http://localhost:9200}"

curl -s -X PUT "${ES_URL}/_index_template/audit-logs-template" \
  -H "Content-Type: application/json" \
  -d '{
  "index_patterns": ["audit-logs-*"],
  "template": {
    "settings": {
      "number_of_shards": 1,
      "number_of_replicas": 0
    },
    "mappings": {
      "properties": {
        "@timestamp":     { "type": "date" },
        "timestamp":      { "type": "date" },
        "userId":         { "type": "keyword" },
        "username":       { "type": "keyword" },
        "userRoles":      { "type": "keyword" },
        "action":         { "type": "keyword" },
        "resourceType":   { "type": "keyword" },
        "resourceId":     { "type": "keyword" },
        "requestMethod":  { "type": "keyword" },
        "requestUri":     { "type": "text", "fields": { "keyword": { "type": "keyword" } } },
        "requestBody":    { "type": "text" },
        "responseStatus": { "type": "integer" },
        "clientIp":       { "type": "ip" },
        "userAgent":      { "type": "text" },
        "duration":       { "type": "long" },
        "success":        { "type": "boolean" },
        "severity":       { "type": "keyword" },
        "errorMessage":   { "type": "text" },
        "chainSequence":  { "type": "long" },
        "previousHash":   { "type": "keyword" },
        "recordHash":     { "type": "keyword" },
        "signingKeyId":   { "type": "keyword" },
        "application":    { "type": "keyword" },
        "environment":    { "type": "keyword" }
      }
    }
  }
}'

echo ""
echo "Template installed. Verify:"
curl -s "${ES_URL}/_index_template/audit-logs-template" | head -c 200
echo ""
