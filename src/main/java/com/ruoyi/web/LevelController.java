package com.ruoyi.web;

import com.ruoyi.web.dto.LevelInfoDto;
import com.ruoyi.web.dto.SaveSlotDto;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.web.dto.GameStateDto;

import java.util.List;

/**
 * 关卡与存档接口。
 */
@RestController
@RequestMapping("/api")
public class LevelController {

    private final GameSessionService service;

    /**
     * @param service 会话服务
     */
    public LevelController(GameSessionService service) {
        this.service = service;
    }

    /**
     * 列出内置关卡。
     *
     * @return 内置关卡列表
     */
    @GetMapping("/levels")
    public List<LevelInfoDto> levels() {
        return service.listBuiltInLevels();
    }

    /**
     * 列出全部存档槽。
     *
     * @return 槽位列表
     */
    @GetMapping("/saves")
    public List<SaveSlotDto> saves() {
        return service.listSaves();
    }

    /**
     * 把当前局面存进某个槽。
     *
     * @param sessionId 会话编号
     * @param slot      槽位编号
     * @return 存完之后的槽位列表
     */
    @PostMapping("/saves/{slot}/from/{sessionId}")
    public List<SaveSlotDto> save(@PathVariable int slot, @PathVariable String sessionId) {
        return service.save(sessionId, slot);
    }

    /**
     * 读档：用某个槽新建一个会话。
     *
     * @param slot 槽位编号
     * @return 读档之后的会话快照
     */
    @PostMapping("/saves/{slot}/load")
    public GameStateDto load(@PathVariable int slot) {
        return service.loadSave(slot);
    }

    /**
     * 删除某个槽。
     *
     * @param slot 槽位编号
     * @return 删除之后的槽位列表
     */
    @DeleteMapping("/saves/{slot}")
    public List<SaveSlotDto> delete(@PathVariable int slot) {
        return service.deleteSave(slot);
    }
}
