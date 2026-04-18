package com.plm.auth.service;

import com.plm.auth.config.AuthPasswordRsaProperties;
import com.plm.common.api.dto.auth.AuthPasswordEncryptionKeyResponseDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

@Service
public class PasswordTransportSecurityService {
    private static final String PUBLIC_KEY_HEADER = "-----BEGIN PUBLIC KEY-----";
    private static final String PUBLIC_KEY_FOOTER = "-----END PUBLIC KEY-----";
    private static final String PRIVATE_KEY_HEADER = "-----BEGIN PRIVATE KEY-----";
    private static final String PRIVATE_KEY_FOOTER = "-----END PRIVATE KEY-----";

    private final AuthPasswordRsaProperties properties;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final KeyMaterial configuredKeyMaterial;
    private final Object redisKeyMonitor = new Object();

    public PasswordTransportSecurityService(AuthPasswordRsaProperties properties,
                                            ObjectMapper objectMapper,
                                            ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.stringRedisTemplate = stringRedisTemplateProvider.getIfAvailable();
        this.configuredKeyMaterial = loadConfiguredKeyMaterial();
    }

    public AuthPasswordEncryptionKeyResponseDto getPasswordEncryptionKey() {
        KeyMaterial keyMaterial = resolveCurrentKeyMaterial();
        AuthPasswordEncryptionKeyResponseDto response = new AuthPasswordEncryptionKeyResponseDto();
        response.setKeyId(keyMaterial.keyId());
        response.setAlgorithm("RSA");
        response.setTransformation(keyMaterial.transformation());
        response.setPublicKeyBase64(stripPem(keyMaterial.publicKeyPem(), PUBLIC_KEY_HEADER, PUBLIC_KEY_FOOTER));
        response.setPlaintextFallbackAllowed(properties.isAllowPlaintextFallback());
        return response;
    }

    public String resolvePassword(String plaintext,
                                  String ciphertext,
                                  String encryptionKeyId,
                                  String fieldName) {
        if (!properties.isEnabled()) {
            return plaintext;
        }
        if (ciphertext != null && !ciphertext.isBlank()) {
            KeyMaterial keyMaterial = resolveKeyMaterialForDecryption(encryptionKeyId);
            return decrypt(ciphertext, fieldName, keyMaterial);
        }
        if (properties.isAllowPlaintextFallback()) {
            return plaintext;
        }
        throw new IllegalArgumentException(fieldName + "Ciphertext is required");
    }

    private KeyMaterial resolveCurrentKeyMaterial() {
        if (configuredKeyMaterial != null) {
            return configuredKeyMaterial;
        }
        return getOrCreateRedisKeyMaterial();
    }

    private KeyMaterial resolveKeyMaterialForDecryption(String encryptionKeyId) {
        validateRequiredKeyId(encryptionKeyId);
        if (configuredKeyMaterial != null) {
            validateStaticKeyId(encryptionKeyId);
            return configuredKeyMaterial;
        }
        KeyMaterial keyMaterial = loadRedisKeyMaterial(encryptionKeyId);
        if (keyMaterial == null) {
            throw new IllegalArgumentException("encryptionKeyId is invalid or expired");
        }
        return keyMaterial;
    }

    private void validateRequiredKeyId(String encryptionKeyId) {
        if (encryptionKeyId == null || encryptionKeyId.isBlank()) {
            throw new IllegalArgumentException("encryptionKeyId is required");
        }
    }

    private void validateStaticKeyId(String encryptionKeyId) {
        if (!properties.getKeyId().equals(encryptionKeyId)) {
            throw new IllegalArgumentException("encryptionKeyId is invalid or expired");
        }
    }

    private KeyMaterial getOrCreateRedisKeyMaterial() {
        String activeKeyId = getRedisTemplate().opsForValue().get(activeKeyRedisKey());
        if (hasText(activeKeyId)) {
            KeyMaterial existing = loadRedisKeyMaterial(activeKeyId);
            if (existing != null) {
                return existing;
            }
        }

        synchronized (redisKeyMonitor) {
            String currentActiveKeyId = getRedisTemplate().opsForValue().get(activeKeyRedisKey());
            if (hasText(currentActiveKeyId)) {
                KeyMaterial existing = loadRedisKeyMaterial(currentActiveKeyId);
                if (existing != null) {
                    return existing;
                }
            }

            KeyMaterial generated = generateKeyMaterial(generateDynamicKeyId(), properties.getTransformation());
            storeRedisKeyMaterial(generated);
            return generated;
        }
    }

    private KeyMaterial loadConfiguredKeyMaterial() {
        if (!hasText(properties.getPublicKeyPem()) || !hasText(properties.getPrivateKeyPem())) {
            return null;
        }
        try {
            PublicKey loadedPublicKey = loadPublicKey(properties.getPublicKeyPem());
            PrivateKey loadedPrivateKey = loadPrivateKey(properties.getPrivateKeyPem());
            return new KeyMaterial(
                    properties.getKeyId(),
                    loadedPublicKey,
                    loadedPrivateKey,
                    normalizePem(properties.getPublicKeyPem(), PUBLIC_KEY_HEADER, PUBLIC_KEY_FOOTER),
                    normalizePem(properties.getPrivateKeyPem(), PRIVATE_KEY_HEADER, PRIVATE_KEY_FOOTER),
                    properties.getTransformation());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("failed to load configured password RSA key pair", ex);
        }
    }

    private void storeRedisKeyMaterial(KeyMaterial keyMaterial) {
        try {
            String payload = objectMapper.writeValueAsString(new RedisStoredKeyMaterial(
                    keyMaterial.keyId(),
                    keyMaterial.publicKeyPem(),
                    keyMaterial.privateKeyPem(),
                    keyMaterial.transformation()));
            Duration ttl = keyTtl();
            getRedisTemplate().opsForValue().set(keyPairRedisKey(keyMaterial.keyId()), payload, ttl);
            getRedisTemplate().opsForValue().set(activeKeyRedisKey(), keyMaterial.keyId(), ttl);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize password RSA key pair", ex);
        }
    }

    private KeyMaterial loadRedisKeyMaterial(String keyId) {
        String payload = getRedisTemplate().opsForValue().get(keyPairRedisKey(keyId));
        if (!hasText(payload)) {
            return null;
        }
        try {
            RedisStoredKeyMaterial stored = objectMapper.readValue(payload, RedisStoredKeyMaterial.class);
            String transformation = hasText(stored.transformation()) ? stored.transformation() : properties.getTransformation();
            return new KeyMaterial(
                    hasText(stored.keyId()) ? stored.keyId() : keyId,
                    loadPublicKey(stored.publicKeyPem()),
                    loadPrivateKey(stored.privateKeyPem()),
                    normalizePem(stored.publicKeyPem(), PUBLIC_KEY_HEADER, PUBLIC_KEY_FOOTER),
                    normalizePem(stored.privateKeyPem(), PRIVATE_KEY_HEADER, PRIVATE_KEY_FOOTER),
                    transformation);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to read password RSA key pair from redis", ex);
        }
    }

    private String decrypt(String ciphertext, String fieldName, KeyMaterial keyMaterial) {
        try {
            Cipher cipher = createCipher(Cipher.DECRYPT_MODE, keyMaterial.privateKey(), keyMaterial.transformation());
            byte[] encryptedBytes = Base64.getDecoder().decode(ciphertext);
            byte[] plainBytes = cipher.doFinal(encryptedBytes);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(fieldName + "Ciphertext is invalid base64", ex);
        } catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException(fieldName + "Ciphertext decryption failed", ex);
        }
    }

    private Cipher createCipher(int mode, Key key, String transformation) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(transformation);
        AlgorithmParameterSpec parameterSpec = resolveAlgorithmParameterSpec(transformation);
        if (parameterSpec == null) {
            cipher.init(mode, key);
            return cipher;
        }
        cipher.init(mode, key, parameterSpec);
        return cipher;
    }

    private AlgorithmParameterSpec resolveAlgorithmParameterSpec(String transformation) {
        if (transformation == null) {
            return null;
        }
        if ("RSA/ECB/OAEPWithSHA-256AndMGF1Padding".equalsIgnoreCase(transformation)) {
            return new OAEPParameterSpec(
                    "SHA-256",
                    "MGF1",
                    MGF1ParameterSpec.SHA256,
                    PSource.PSpecified.DEFAULT);
        }
        return null;
    }

    private KeyMaterial generateKeyMaterial(String keyId, String transformation) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(properties.getKeySize());
            KeyPair keyPair = generator.generateKeyPair();
            String publicKeyPem = toPem(keyPair.getPublic().getEncoded(), PUBLIC_KEY_HEADER, PUBLIC_KEY_FOOTER);
            String privateKeyPem = toPem(keyPair.getPrivate().getEncoded(), PRIVATE_KEY_HEADER, PRIVATE_KEY_FOOTER);
            return new KeyMaterial(keyId, keyPair.getPublic(), keyPair.getPrivate(), publicKeyPem, privateKeyPem, transformation);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("failed to initialize password RSA key pair", ex);
        }
    }

    private PublicKey loadPublicKey(String pem) throws GeneralSecurityException {
        byte[] decoded = Base64.getDecoder().decode(stripPem(pem, PUBLIC_KEY_HEADER, PUBLIC_KEY_FOOTER));
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
    }

    private PrivateKey loadPrivateKey(String pem) throws GeneralSecurityException {
        byte[] decoded = Base64.getDecoder().decode(stripPem(pem, PRIVATE_KEY_HEADER, PRIVATE_KEY_FOOTER));
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }

    private String stripPem(String pem, String header, String footer) {
        return pem.replace(header, "")
                .replace(footer, "")
                .replaceAll("\\s+", "");
    }

    private String normalizePem(String pem, String header, String footer) {
        return toPem(Base64.getDecoder().decode(stripPem(pem, header, footer)), header, footer);
    }

    private String toPem(byte[] encoded, String header, String footer) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(encoded);
        return header + "\n" + base64 + "\n" + footer;
    }

    private String generateDynamicKeyId() {
        return properties.getKeyId() + "-" + UUID.randomUUID();
    }

    private String activeKeyRedisKey() {
        return properties.getRedisKeyPrefix() + ":active";
    }

    private String keyPairRedisKey(String keyId) {
        return properties.getRedisKeyPrefix() + ":pair:" + keyId;
    }

    private Duration keyTtl() {
        return Duration.ofSeconds(Math.max(properties.getRedisTtlSeconds(), 1));
    }

    private StringRedisTemplate getRedisTemplate() {
        if (stringRedisTemplate == null) {
            throw new IllegalStateException("password RSA redis key store is enabled but Redis is not configured");
        }
        return stringRedisTemplate;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record KeyMaterial(String keyId,
                               PublicKey publicKey,
                               PrivateKey privateKey,
                               String publicKeyPem,
                               String privateKeyPem,
                               String transformation) {
    }

    private record RedisStoredKeyMaterial(String keyId,
                                          String publicKeyPem,
                                          String privateKeyPem,
                                          String transformation) {
    }
}