package jwt

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/pem"
	"testing"

	"github.com/golang-jwt/jwt/v5"
)

func genKey(t *testing.T) *rsa.PrivateKey {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	return key
}

func encodePEMPublic(pub *rsa.PublicKey) string {
	der, _ := x509.MarshalPKIXPublicKey(pub)
	block := &pem.Block{Type: "PUBLIC KEY", Bytes: der}
	return string(pem.EncodeToMemory(block))
}

func TestParseUserID(t *testing.T) {
	key := genKey(t)
	tok, _ := jwt.NewWithClaims(jwt.SigningMethodRS256, jwt.MapClaims{
		"sub":  "777",
		"role": "student",
	}).SignedString(key)
	pemStr := encodePEMPublic(&key.PublicKey)
	v, err := NewValidatorFromPEM(pemStr)
	if err != nil {
		t.Fatal(err)
	}
	if uid := v.ParseUserID(tok); uid != 777 {
		t.Fatalf("want 777 got %d", uid)
	}
}

func TestParseUserIDReturnsZeroOnBad(t *testing.T) {
	v, _ := NewValidatorFromPEM(encodePEMPublic(&genKey(t).PublicKey))
	if uid := v.ParseUserID("garbage"); uid != 0 {
		t.Fatalf("want 0 got %d", uid)
	}
}

func TestParseUserIDReturnsZeroOnEmpty(t *testing.T) {
	v, _ := NewValidatorFromPEM(encodePEMPublic(&genKey(t).PublicKey))
	if uid := v.ParseUserID(""); uid != 0 {
		t.Fatalf("want 0 got %d", uid)
	}
}

func TestParseRole(t *testing.T) {
	key := genKey(t)
	tok, _ := jwt.NewWithClaims(jwt.SigningMethodRS256, jwt.MapClaims{
		"sub":  "42",
		"role": "teacher",
	}).SignedString(key)
	v, _ := NewValidatorFromPEM(encodePEMPublic(&key.PublicKey))
	if role := v.ParseRole(tok); role != "teacher" {
		t.Fatalf("want teacher got %s", role)
	}
}

func TestParseRoleEmptyOnInvalid(t *testing.T) {
	v, _ := NewValidatorFromPEM(encodePEMPublic(&genKey(t).PublicKey))
	if role := v.ParseRole("bad"); role != "" {
		t.Fatalf("want empty got %s", role)
	}
}

func TestNewValidatorFromBadPEM(t *testing.T) {
	_, err := NewValidatorFromPEM("garbage")
	if err == nil {
		t.Fatal("expected error for bad pem")
	}
}
