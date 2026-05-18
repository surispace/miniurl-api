package com.miniurl.feature;

import com.miniurl.entity.FeatureFlag;
import com.miniurl.feature.repository.FeatureFlagRepository;
import com.miniurl.feature.repository.FeatureRepository;
import com.miniurl.feature.service.FeatureFlagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("FeatureFlagService Tests")
class FeatureFlagServiceTest {

    private FeatureFlagRepository featureFlagRepository;
    private FeatureRepository featureRepository;
    private RedisTemplate<String, Object> redisTemplate;
    private ValueOperations<String, Object> valueOps;
    private FeatureFlagService featureFlagService;

    private static final String FEATURE_KEY = "DARK_MODE";
    private static final Long ROLE_ID = 1L;
    private static final String EXPECTED_CACHE_KEY = "feature_flag:1:DARK_MODE";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        featureFlagRepository = mock(FeatureFlagRepository.class);
        featureRepository = mock(FeatureRepository.class);
        redisTemplate = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        featureFlagService = new FeatureFlagService(featureFlagRepository, featureRepository, redisTemplate);
    }

    @Test
    @DisplayName("isFeatureEnabled returns true when feature is enabled and not cached")
    void isFeatureEnabledTrue() {
        when(valueOps.get(EXPECTED_CACHE_KEY)).thenReturn(null);

        FeatureFlag featureFlag = new FeatureFlag();
        featureFlag.setEnabled(true);
        when(featureFlagRepository.findByFeatureKeyAndRoleId(FEATURE_KEY, ROLE_ID))
                .thenReturn(Optional.of(featureFlag));

        boolean result = featureFlagService.isFeatureEnabled(FEATURE_KEY, ROLE_ID);

        assertTrue(result, "Feature should be enabled");
        verify(valueOps).set(EXPECTED_CACHE_KEY, true, 10, TimeUnit.MINUTES);
    }

    @Test
    @DisplayName("isFeatureEnabled returns false when feature is not found")
    void isFeatureEnabledNotFound() {
        when(valueOps.get(EXPECTED_CACHE_KEY)).thenReturn(null);

        when(featureFlagRepository.findByFeatureKeyAndRoleId(FEATURE_KEY, ROLE_ID))
                .thenReturn(Optional.empty());

        boolean result = featureFlagService.isFeatureEnabled(FEATURE_KEY, ROLE_ID);

        assertFalse(result, "Feature should not be enabled when not found");
        verify(valueOps).set(EXPECTED_CACHE_KEY, false, 10, TimeUnit.MINUTES);
    }

    @Test
    @DisplayName("isFeatureEnabled returns false when feature is disabled")
    void isFeatureEnabledDisabled() {
        when(valueOps.get(EXPECTED_CACHE_KEY)).thenReturn(null);

        FeatureFlag featureFlag = new FeatureFlag();
        featureFlag.setEnabled(false);
        when(featureFlagRepository.findByFeatureKeyAndRoleId(FEATURE_KEY, ROLE_ID))
                .thenReturn(Optional.of(featureFlag));

        boolean result = featureFlagService.isFeatureEnabled(FEATURE_KEY, ROLE_ID);

        assertFalse(result, "Feature should be disabled");
        verify(valueOps).set(EXPECTED_CACHE_KEY, false, 10, TimeUnit.MINUTES);
    }

    @Test
    @DisplayName("isFeatureEnabled returns cached true value without hitting database")
    void isFeatureEnabledReturnsCachedTrue() {
        when(valueOps.get(EXPECTED_CACHE_KEY)).thenReturn(true);

        boolean result = featureFlagService.isFeatureEnabled(FEATURE_KEY, ROLE_ID);

        assertTrue(result, "Should return cached true value");
        verify(featureFlagRepository, never()).findByFeatureKeyAndRoleId(anyString(), anyLong());
    }

    @Test
    @DisplayName("isFeatureEnabled returns cached false value without hitting database")
    void isFeatureEnabledReturnsCachedFalse() {
        when(valueOps.get(EXPECTED_CACHE_KEY)).thenReturn(false);

        boolean result = featureFlagService.isFeatureEnabled(FEATURE_KEY, ROLE_ID);

        assertFalse(result, "Should return cached false value");
        verify(featureFlagRepository, never()).findByFeatureKeyAndRoleId(anyString(), anyLong());
    }

    @Test
    @DisplayName("isFeatureEnabled uses correct cache key format")
    void isFeatureEnabledUsesCorrectCacheKeyFormat() {
        when(valueOps.get(anyString())).thenReturn(null);

        when(featureFlagRepository.findByFeatureKeyAndRoleId(FEATURE_KEY, ROLE_ID))
                .thenReturn(Optional.empty());

        featureFlagService.isFeatureEnabled(FEATURE_KEY, ROLE_ID);

        verify(valueOps).get(EXPECTED_CACHE_KEY);
    }

    @Test
    @DisplayName("isFeatureEnabled caches result for 10 minutes after DB fetch")
    void isFeatureEnabledCachesResultWithTtl() {
        when(valueOps.get(EXPECTED_CACHE_KEY)).thenReturn(null);

        FeatureFlag featureFlag = new FeatureFlag();
        featureFlag.setEnabled(true);
        when(featureFlagRepository.findByFeatureKeyAndRoleId(FEATURE_KEY, ROLE_ID))
                .thenReturn(Optional.of(featureFlag));

        featureFlagService.isFeatureEnabled(FEATURE_KEY, ROLE_ID);

        verify(valueOps).set(EXPECTED_CACHE_KEY, true, 10, TimeUnit.MINUTES);
    }

    @Test
    @DisplayName("isFeatureEnabled caches false result when feature not found")
    void isFeatureEnabledCachesFalseOnNotFound() {
        when(valueOps.get(EXPECTED_CACHE_KEY)).thenReturn(null);

        when(featureFlagRepository.findByFeatureKeyAndRoleId(FEATURE_KEY, ROLE_ID))
                .thenReturn(Optional.empty());

        featureFlagService.isFeatureEnabled(FEATURE_KEY, ROLE_ID);

        verify(valueOps).set(EXPECTED_CACHE_KEY, false, 10, TimeUnit.MINUTES);
    }
}
