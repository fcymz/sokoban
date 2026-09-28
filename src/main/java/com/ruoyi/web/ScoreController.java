package com.ruoyi.web;

import com.ruoyi.sokoban.Campaign;
import com.ruoyi.sokoban.SaveData;
import com.ruoyi.web.dto.ScoreBoardDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 成绩榜接口：每关的最佳步数。
 */
@RestController
@RequestMapping("/api")
public class ScoreController {

    private final SaveData saveData;

    /**
     * @param saveData 成绩与进度存档
     */
    public ScoreController(SaveData saveData) {
        this.saveData = saveData;
    }

    /**
     * 列出有成绩记录的关卡。
     *
     * @return 成绩榜
     */
    @GetMapping("/scores")
    public ScoreBoardDto scores() {
        Campaign campaign = Campaign.createDefault();
        List<ScoreBoardDto.ScoreEntryDto> entries = new ArrayList<>();
        for (int levelIndex : saveData.scoreLevels()) {
            entries.add(new ScoreBoardDto.ScoreEntryDto(
                    levelIndex,
                    campaign.getTitle(levelIndex),
                    campaign.getShortProgress(levelIndex),
                    saveData.getBest(levelIndex)));
        }
        return new ScoreBoardDto(entries, campaign.getBuiltInCount());
    }
}
