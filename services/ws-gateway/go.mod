module github.com/education-platform/ws-gateway

go 1.25

require (
	github.com/alicebob/miniredis/v2 v2.33.0
	github.com/golang-jwt/jwt/v5 v5.3.1
	github.com/redis/go-redis/v9 v9.21.0
)

// D-109: shared utilities extracted from ws-gateway.
require github.com/education-platform/ep-common-go v0.0.0

replace github.com/education-platform/ep-common-go => ../ep-common-go

require (
	github.com/alicebob/gopher-json v0.0.0-20200520072559-a9ecdc9d1d3a // indirect
	github.com/cespare/xxhash/v2 v2.3.0 // indirect
	github.com/yuin/gopher-lua v1.1.1 // indirect
	go.uber.org/atomic v1.11.0 // indirect
)
