package com.ruoyi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 推箱子 Web 后端的启动类。
 *
 * <p>本工程是典型的前后端分离结构：</p>
 * <ul>
 *   <li>后端（本模块）：只提供 REST 接口，内含完整的推箱子游戏内核
 *       （{@code com.ruoyi.sokoban} 包），不渲染任何界面；</li>
 *   <li>前端：独立的 Vue 3 项目，位于仓库的 {@code frontend/} 目录，
 *       通过 HTTP 调用本后端的 {@code /api/**} 接口。</li>
 * </ul>
 *
 * <p>启动后默认监听 {@code http://localhost:8080}。</p>
 */
@SpringBootApplication
public class SokobanApplication {

    /**
     * 启动入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(SokobanApplication.class, args);
    }
}
