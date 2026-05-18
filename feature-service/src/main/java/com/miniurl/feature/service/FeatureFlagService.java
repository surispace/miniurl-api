package com.miniurl.feature.service;

import com.miniurl.dto.FeatureFlagDTO;
import com.miniurl.entity.Feature;
import com.miniurl.entity.FeatureFlag;
import com.miniurl.entity.Role;
import com.miniurl.exception.ResourceNotFoundException;
import com.miniurl.feature.repository.FeatureFlagRepository;
import com.miniurl.feature.repository.FeatureRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FeatureFlagService {

    private final FeatureFlagRepository featureFlagRepository;
    private final FeatureRepository featureRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String FEATURE_FLAG_CACHE_PREFIX = "feature_flag:";

    public FeatureFlagService(FeatureFlagRepository featureFlagRepository,
                              FeatureRepository featureRepository,
                              RedisTemplate<String, Object> redisTemplate) {
        this.featureFlagRepository = featureFlagRepository;
        this.featureRepository = featureRepository;
        this.redisTemplate = redisTemplate;
    }

    @Transactional(readOnly = true)
    public boolean isFeatureEnabled(String featureKey, Long roleId) {
        String cacheKey = FEATURE_FLAG_CACHE_PREFIX + roleId + ":" + featureKey;
        
        // Try cache first
        Boolean cachedValue = (Boolean) redisTemplate.opsForValue().get(cacheKey);
        if (cachedValue != null) {
            return cachedValue;
        }

        // Fallback to DB
        boolean enabled = featureFlagRepository.findByFeatureKeyAndRoleId(featureKey, roleId)
                .map(FeatureFlag::isEnabled)
                .orElse(false);

        // Cache the result for 10 minutes
        redisTemplate.opsForValue().set(cacheKey, enabled, 10, TimeUnit.MINUTES);
        
        return enabled;
    }

    @Transactional(readOnly = true)
    public List<FeatureFlagDTO> getAllFeatures() {
        return featureFlagRepository.findAllWithFeatures().stream()
                .map(FeatureFlagDTO::new)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<FeatureFlagDTO> getFeaturesByRole(Long roleId) {
        return featureFlagRepository.findByRoleIdOrderByFeatureFeatureNameAsc(roleId).stream()
                .map(FeatureFlagDTO::new)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public FeatureFlagDTO getFeatureFlagById(Long id) {
        FeatureFlag featureFlag = featureFlagRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Feature flag not found with id: " + id));
        return new FeatureFlagDTO(featureFlag);
    }

    @Transactional
    public FeatureFlagDTO toggleFeature(Long id, boolean enabled) {
        FeatureFlag featureFlag = featureFlagRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Feature flag not found with id: " + id));

        boolean previousState = featureFlag.isEnabled();
        featureFlag.setEnabled(enabled);
        featureFlagRepository.save(featureFlag);

        // Invalidate cache for this role and feature
        redisTemplate.delete(FEATURE_FLAG_CACHE_PREFIX + featureFlag.getRole().getId() + ":" + featureFlag.getFeature().getFeatureKey());

        log.info("Feature '{}' (role ID: {}) toggled from {} to {}",
                featureFlag.getFeature().getFeatureKey(), 
                featureFlag.getRole().getId(), 
                previousState, enabled);

        return new FeatureFlagDTO(featureFlag);
    }

    @Transactional
    public FeatureFlagDTO createFeatureFlag(Long featureId, Long roleId, boolean enabled) {
        Feature feature = featureRepository.findById(featureId)
            .orElseThrow(() -> new ResourceNotFoundException("Feature not found with id: " + featureId));

        Role role = new Role();
        role.setId(roleId);

        FeatureFlag featureFlag = new FeatureFlag(feature, role, enabled);
        featureFlagRepository.save(featureFlag);

        log.info("Created feature flag for '{}' (role ID: {})", feature.getFeatureKey(), roleId);

        return new FeatureFlagDTO(featureFlag);
    }

    @Transactional
    public FeatureFlagDTO createFeatureFlag(String featureKey, String featureName, String description,
                                             boolean adminEnabled, boolean userEnabled) {
        // Find or create the feature
        Feature feature = featureRepository.findByFeatureKey(featureKey)
            .orElseGet(() -> featureRepository.save(new Feature(featureKey, featureName, description)));

        // We assume role IDs are fixed or managed by Identity Service. 
        // For this implementation, we'll use a simplified approach or assume 1=ADMIN, 2=USER 
        // In a real scenario, we'd fetch these from the Identity Service.
        long adminRoleId = 1L; 
        long userRoleId = 2L;

        // Create or update ADMIN flag
        FeatureFlag adminFlag = featureFlagRepository.findByFeatureKeyAndRoleId(featureKey, adminRoleId)
            .orElseGet(() -> {
                Role adminRole = new Role();
                adminRole.setId(adminRoleId);
                return new FeatureFlag(feature, adminRole, adminEnabled);
            });
        adminFlag.setEnabled(adminEnabled);
        featureFlagRepository.save(adminFlag);
        redisTemplate.delete(FEATURE_FLAG_CACHE_PREFIX + adminRoleId + ":" + featureKey);

        // Create or update USER flag
        FeatureFlag userFlag = featureFlagRepository.findByFeatureKeyAndRoleId(featureKey, userRoleId)
            .orElseGet(() -> {
                Role userRole = new Role();
                userRole.setId(userRoleId);
                return new FeatureFlag(feature, userRole, userEnabled);
            });
        userFlag.setEnabled(userEnabled);
        FeatureFlag savedUserFlag = featureFlagRepository.save(userFlag);
        redisTemplate.delete(FEATURE_FLAG_CACHE_PREFIX + userRoleId + ":" + featureKey);

        log.info("Created feature '{}' with ADMIN={}, USER={}", featureKey, adminEnabled, userEnabled);

        return new FeatureFlagDTO(savedUserFlag);
    }

    @Transactional
    public void deleteFeatureFlag(Long id) {
        FeatureFlag featureFlag = featureFlagRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Feature flag not found with id: " + id));
        
        String featureKey = featureFlag.getFeature().getFeatureKey();
        Long roleId = featureFlag.getRole().getId();
        
        featureFlagRepository.deleteById(id);
        
        // Invalidate cache
        redisTemplate.delete(FEATURE_FLAG_CACHE_PREFIX + roleId + ":" + featureKey);
        log.info("Deleted feature flag with id: {}", id);
    }
}
