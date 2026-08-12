package jwt

import (
	"crypto/rsa"
	"crypto/x509"
	"encoding/pem"
	"fmt"
	"strconv"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// Validator validates RS256 JWT tokens and extracts user_id.
type Validator struct {
	pub *rsa.PublicKey
}

// NewValidatorFromPEM creates a Validator from a PEM-encoded RSA public key.
func NewValidatorFromPEM(pemStr string) (*Validator, error) {
	block, _ := pem.Decode([]byte(pemStr))
	if block == nil {
		return nil, fmt.Errorf("bad pem: no block found")
	}
	pub, err := x509.ParsePKIXPublicKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("parse pkix: %w", err)
	}
	rsaPub, ok := pub.(*rsa.PublicKey)
	if !ok {
		return nil, fmt.Errorf("not an RSA public key")
	}
	return &Validator{pub: rsaPub}, nil
}

// ParseUserID validates the token and returns the user_id from the subject claim.
// Returns 0 if the token is invalid or malformed.
func (v *Validator) ParseUserID(tokenStr string) int64 {
	if tokenStr == "" {
		return 0
	}
	tok, err := jwt.Parse(tokenStr, func(t *jwt.Token) (interface{}, error) {
		if _, ok := t.Method.(*jwt.SigningMethodRSA); !ok {
			return nil, fmt.Errorf("unexpected signing method: %v", t.Header["alg"])
		}
		return v.pub, nil
	}, jwt.WithLeeway(30*time.Second))
	if err != nil || !tok.Valid {
		return 0
	}
	sub, err := tok.Claims.GetSubject()
	if err != nil {
		return 0
	}
	uid, _ := strconv.ParseInt(sub, 10, 64)
	return uid
}

// ParseRole extracts the role claim from a valid token.
func (v *Validator) ParseRole(tokenStr string) string {
	tok, err := jwt.Parse(tokenStr, func(t *jwt.Token) (interface{}, error) {
		if _, ok := t.Method.(*jwt.SigningMethodRSA); !ok {
			return nil, fmt.Errorf("not rs256")
		}
		return v.pub, nil
	})
	if err != nil || !tok.Valid {
		return ""
	}
	if claims, ok := tok.Claims.(jwt.MapClaims); ok {
		if role, ok := claims["role"].(string); ok {
			return role
		}
	}
	return ""
}
