package com.ruoyi;

import com.ruoyi.sokoban.SokobanFrame;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;

/**
 * 程序入口：启动推箱子游戏窗口。
 *
 * <p>直接运行本类的 {@code main} 方法即可开始游戏。</p>
 */
public class Main {

    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("当前环境不支持图形界面（headless），无法启动推箱子窗口。");
            System.err.println("请在带有桌面环境的机器上运行，或改用无界面的游戏逻辑 API：");
            System.err.println("    SokobanGame game = new SokobanGame(Levels.createDefault());");
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new SokobanFrame().setVisible(true);
            }
        });
    }
}
