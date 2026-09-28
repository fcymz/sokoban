package com.ruoyi.web;

import com.ruoyi.web.dto.GameStateDto;
import com.ruoyi.web.dto.HintDto;
import com.ruoyi.web.dto.LevelChangeRequest;
import com.ruoyi.web.dto.MoveRequest;
import com.ruoyi.web.dto.NewGameRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 游戏会话接口。
 *
 * <p>所有规则判定都在服务端完成，前端只负责发指令和画棋盘。</p>
 */
@RestController
@RequestMapping("/api/game")
public class GameController {

    private final GameSessionService service;

    /**
     * @param service 会话服务
     */
    public GameController(GameSessionService service) {
        this.service = service;
    }

    /**
     * 开一局新游戏。
     *
     * @param request 开局参数，可为空
     * @return 新会话快照
     */
    @PostMapping("/sessions")
    public GameStateDto create(@RequestBody(required = false) NewGameRequest request) {
        String seed = request == null ? null : request.seedCode();
        boolean endless = request != null && Boolean.TRUE.equals(request.endless());
        return service.createSession(seed, endless);
    }

    /**
     * 读取当前局面。
     *
     * @param sessionId 会话编号
     * @return 快照
     */
    @GetMapping("/sessions/{sessionId}")
    public GameStateDto state(@PathVariable String sessionId) {
        return service.state(sessionId);
    }

    /**
     * 结束会话。
     *
     * @param sessionId 会话编号
     * @return 空响应
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> close(@PathVariable String sessionId) {
        service.closeSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 走一步。
     *
     * @param sessionId 会话编号
     * @param request   方向
     * @return 走完之后的快照
     */
    @PostMapping("/sessions/{sessionId}/moves")
    public GameStateDto move(@PathVariable String sessionId,
                             @RequestBody MoveRequest request) {
        return service.move(sessionId, request == null ? null : request.dir());
    }

    /**
     * 撤销一步。
     *
     * @param sessionId 会话编号
     * @return 撤销之后的快照
     */
    @PostMapping("/sessions/{sessionId}/undo")
    public GameStateDto undo(@PathVariable String sessionId) {
        return service.undo(sessionId);
    }

    /**
     * 重玩本关。
     *
     * @param sessionId 会话编号
     * @return 重开之后的快照
     */
    @PostMapping("/sessions/{sessionId}/reset")
    public GameStateDto reset(@PathVariable String sessionId) {
        return service.reset(sessionId);
    }

    /**
     * 跳关：给下标就按绝对下标载入，给 delta 就相对切换。
     *
     * @param sessionId 会话编号
     * @param request   关卡切换参数
     * @return 切换之后的快照
     */
    @PostMapping("/sessions/{sessionId}/level")
    public GameStateDto changeLevel(@PathVariable String sessionId,
                                    @RequestBody LevelChangeRequest request) {
        if (request != null && request.index() != null) {
            return service.loadLevel(sessionId, request.index().intValue());
        }
        int delta = request == null || request.delta() == null ? 1 : request.delta().intValue();
        return service.changeLevel(sessionId, delta);
    }

    /**
     * 开关“无尽模式跳关”作弊。
     *
     * @param sessionId 会话编号
     * @param unlocked  是否打开
     * @return 切换之后的快照
     */
    @PostMapping("/sessions/{sessionId}/endless-skip")
    public GameStateDto setEndlessSkip(@PathVariable String sessionId,
                                       @RequestBody Map<String, Boolean> body) {
        boolean unlocked = body != null && Boolean.TRUE.equals(body.get("unlocked"));
        return service.setEndlessSkip(sessionId, unlocked);
    }

    /**
     * 求一条提示（自动演示用的解法）。
     *
     * @param sessionId 会话编号
     * @return 解法步骤与说明
     */
    @PostMapping("/sessions/{sessionId}/hint")
    public HintDto hint(@PathVariable String sessionId) {
        GameSessionService.HintResult hint = service.hint(sessionId);
        return new HintDto(hint.plan(), hint.total(), hint.message(), service.state(sessionId));
    }
}
