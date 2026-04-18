package com.plm.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plm.auth.config.AuthPasswordRsaProperties;
import com.plm.common.api.dto.auth.AuthPasswordEncryptionKeyResponseDto;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

class PasswordTransportSecurityServiceTest {

    @Test
    void passwordEncryptionKey_shouldPersistGeneratedKeyPairInRedis() {
        RedisTestContext context = createRedisBackedService();

        AuthPasswordEncryptionKeyResponseDto first = context.service().getPasswordEncryptionKey();
        AuthPasswordEncryptionKeyResponseDto second = context.service().getPasswordEncryptionKey();

        Assertions.assertEquals(first.getKeyId(), second.getKeyId());
        Assertions.assertEquals(first.getPublicKeyBase64(), second.getPublicKeyBase64());

        String activeRedisKey = context.properties().getRedisKeyPrefix() + ":active";
        String pairRedisKey = context.properties().getRedisKeyPrefix() + ":pair:" + first.getKeyId();
        Duration expectedTtl = Duration.ofSeconds(context.properties().getRedisTtlSeconds());

        Assertions.assertEquals(first.getKeyId(), context.redisValues().get(activeRedisKey));
        Assertions.assertNotNull(context.redisValues().get(pairRedisKey));
        Assertions.assertEquals(expectedTtl, context.redisTtls().get(activeRedisKey));
        Assertions.assertEquals(expectedTtl, context.redisTtls().get(pairRedisKey));
    }

    @Test
    void resolvePassword_shouldDecryptCiphertextUsingRedisStoredPrivateKey() throws Exception {
        RedisTestContext context = createRedisBackedService();
        AuthPasswordEncryptionKeyResponseDto key = context.service().getPasswordEncryptionKey();

        String ciphertext = encryptWithPublicKey(key, "Password123!");
        String resolved = context.service().resolvePassword(null, ciphertext, key.getKeyId(), "password");

        Assertions.assertEquals("Password123!", resolved);
    }

    @Test
    void oaepSha256Transformation_shouldExplicitlyUseMgf1Sha256() throws Exception {
        RedisTestContext context = createRedisBackedService();
        AuthPasswordEncryptionKeyResponseDto key = context.service().getPasswordEncryptionKey();

        byte[] decoded = Base64.getDecoder().decode(key.getPublicKeyBase64());
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));

        Cipher cipher = Cipher.getInstance(key.getTransformation());
        OAEPParameterSpec parameterSpec = new OAEPParameterSpec(
                "SHA-256",
                "MGF1",
                MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT);
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, parameterSpec);

        AlgorithmParameters parameters = cipher.getParameters();
        OAEPParameterSpec actualSpec = parameters.getParameterSpec(OAEPParameterSpec.class);

        Assertions.assertEquals("SHA-256", actualSpec.getDigestAlgorithm());
        Assertions.assertEquals("MGF1", actualSpec.getMGFAlgorithm());
        Assertions.assertEquals("SHA-256", ((MGF1ParameterSpec) actualSpec.getMGFParameters()).getDigestAlgorithm());
    }

    private RedisTestContext createRedisBackedService() {
        AuthPasswordRsaProperties properties = new AuthPasswordRsaProperties();
        properties.setRedisTtlSeconds(86400);

        ObjectMapper objectMapper = new ObjectMapper();
        StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = Mockito.mock(ObjectProvider.class);

        Map<String, String> redisValues = new HashMap<>();
        Map<String, Duration> redisTtls = new HashMap<>();

        Mockito.when(provider.getIfAvailable()).thenReturn(stringRedisTemplate);
        Mockito.when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        Mockito.when(valueOperations.get(Mockito.anyString()))
                .thenAnswer(invocation -> redisValues.get(invocation.getArgument(0, String.class)));
        Mockito.doAnswer(invocation -> {
            String key = invocation.getArgument(0, String.class);
            String value = invocation.getArgument(1, String.class);
            Duration ttl = invocation.getArgument(2, Duration.class);
            redisValues.put(key, value);
            redisTtls.put(key, ttl);
            return null;
        }).when(valueOperations).set(Mockito.anyString(), Mockito.anyString(), Mockito.any(Duration.class));

        PasswordTransportSecurityService service = new PasswordTransportSecurityService(properties, objectMapper, provider);
        return new RedisTestContext(service, properties, redisValues, redisTtls);
    }

    private String encryptWithPublicKey(AuthPasswordEncryptionKeyResponseDto key, String plainText) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(key.getPublicKeyBase64());
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
        Cipher cipher = Cipher.getInstance(key.getTransformation());
        cipher.init(Cipher.ENCRYPT_MODE,
            publicKey,
            new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        return Base64.getEncoder().encodeToString(cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8)));
    }

    private record RedisTestContext(PasswordTransportSecurityService service,
                                    AuthPasswordRsaProperties properties,
                                    Map<String, String> redisValues,
                                    Map<String, Duration> redisTtls) {
    }
}