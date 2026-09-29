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
     * 把某个会话的当前局面写进自动存档槽位（关标签页时的兜底存档走这里）。
     *
     * @param sessionId 会话编号
     * @return 写完之后（并清掉未存档标记）的槽位列表
     */
    @PostMapping("/saves/auto/from/{sessionId}")
    public List<SaveSlotDto> autoSave(@PathVariable String sessionId) {
        return service.autoSave(sessionId);
    }

    /**
     * 把一个槽位的存档转存到另一个槽位，转存成功后来源槽位被清空。
     *
     * @param from 来源槽位
     * @param to   目标槽位
     * @return 转存之后的槽位列表
     */
    @PostMapping("/saves/{from}/move/{to}")
    public List<SaveSlotDto> move(@PathVariable int from, @PathVariable int to) {
        return service.moveSave(from, to);
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
