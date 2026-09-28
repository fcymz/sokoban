package com.ruoyi.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置。
 *
 * <p>开发时前端跑在 Vite 的 {@code http://localhost:5173}，后端跑在 8080，属于不同源，
 * 所以要么让 Vite 代理（见 {@code frontend/vite.config.ts}），要么后端放行跨域。
 * 这里两者都支持：即使不用代理、直接用浏览器打开独立部署的前端也能调通。</p>
 *
 * <p>生产环境请把 {@code allowedOriginPatterns} 收窄到真实域名。</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * 放行 {@code /api/**} 的跨域请求。
     *
     * @param registry 跨域注册表
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
