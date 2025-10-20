package com.plm.auth.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import cn.dev33.satoken.stp.StpUtil;

@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(new AuthLoginInterceptor())
                .addPathPatterns("/auth/**")
                .excludePathPatterns("/auth/public/**", "/auth/ping");
    }

    @Bean
    public SaStartupLoggerSuppressor saStartupLoggerSuppressor() {
        return new SaStartupLoggerSuppressor();
    }

    // 用于减少启动时 Sa-Token 的 banner/日志，可选
    static class SaStartupLoggerSuppressor {
        public SaStartupLoggerSuppressor() {
            System.setProperty("sa-token.log.level", "info");
        }
    }

    static class AuthLoginInterceptor implements HandlerInterceptor {
        @Override
        public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) {
            if (!StpUtil.isLogin()) {
                StpUtil.checkLogin();
            }
            return true;
        }
    }
}
