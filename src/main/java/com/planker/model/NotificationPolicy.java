package com.planker.model;

import lombok.Data;

@Data
public class NotificationPolicy
{
    private int version = 0;
    private Drops drops = new Drops();
    private Levels levels = new Levels();
    private Screenshots screenshots = new Screenshots();
    private int refreshSeconds = 900;

    @Data
    public static class Drops
    {
        private long minimumValueGp = 1_000_000L;
    }

    @Data
    public static class Levels
    {
        private int everyFiveThrough = 85;
        private int everyLevelFrom = 90;
        private int maximumVirtualLevel = 126;
        private int[] heroLevels = new int[] {99, 126};
    }

    @Data
    public static class Screenshots
    {
        private boolean enabled = true;
        private long dropMinimumValueGp = 10_000_000L;
        private boolean levelUps = true;
        private boolean quests = true;
        private boolean collectionLogs = true;
        private boolean combatAchievements = true;
    }
}
