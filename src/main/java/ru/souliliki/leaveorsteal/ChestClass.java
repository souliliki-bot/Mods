package ru.souliliki.leaveorsteal;

public enum ChestClass {
    COMMON("Обычные предметы"),
    IRON_GEAR("Железное снаряжение"),
    DIAMOND_GEAR("Алмазное снаряжение"),
    NETHERITE_GEAR("Незеритовое снаряжение"),
    POTIONS_FOOD("Зелья и еда"),
    RARE("Редкие предметы"),
    LEGENDARY("Легендарные предметы");

    private final String displayName;

    ChestClass(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
