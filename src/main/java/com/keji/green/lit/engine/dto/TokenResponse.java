package com.keji.green.lit.engine.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 令牌响应DTO
 * 用于向客户端返回JWT令牌和相关用户信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenResponse {
    
    /**
     * JWT令牌
     */
    private String token;
    
    /**
     * 令牌类型，固定值为"Bearer"
     */
    private String type;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 用户手机号
     */
    private String phone;
    
    /**
     * 创建TokenResponse实例的工厂方法
     * 
     * @param token JWT令牌
     * @param userId 用户ID
     * @param phone 用户手机号
     * @return TokenResponse实例
     */
    public static TokenResponse of(String token, Long userId, String phone) {
        return TokenResponse.builder()
                .token(token)
                .type("Bearer")
                .userId(userId)
                .phone(phone)
                .build();
    }
} 