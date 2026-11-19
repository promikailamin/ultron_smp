package minecraft.heads;

/**
 * Non-potion powers granted by mob heads. Active abilities are triggered by
 * right-clicking with an empty hand; passive abilities run from the tick loop
 * or from combat events.
 */
public enum HeadAbility {

    FLIGHT("flight", "Creative flight", 0.0, false),
    KNOCKBACK("knockback", "Heavy knockback on hit", 0.0, false),
    THORNS("thorns", "Punishes attackers", 1.0, false),

    EXPLOSION("creeper", "Entity-only blast", 10.0, true),
    SUMMON_PIGS("pig", "Summons pig guards", 5.0, true),
    TELEPORT("teleport", "Blink teleport", 5.0, true),
    FIRE_CHARGE("fire-charge", "Throws a fire charge", 4.0, true),
    SHOOT_ARROW("arrow", "Shoots an arrow", 2.0, true),
    THROW_SNOWBALL("snowball", "Throws a freezing snowball", 2.0, true),
    SONIC_BOOM("sonic-boom", "Sonic boom", 15.0, true),
    WITHER_SKULL("wither-skull", "Launches a wither skull", 6.0, true),
    POISON_CLOUD("poison", "Poison cloud", 8.0, true),
    WEB("web", "Places a temporary web", 8.0, true),
    INVISIBILITY("invisibility", "Vanish from sight", 15.0, true),
    SHIELD("shield", "Protective barrier", 12.0, true),
    LIGHTNING("lightning", "Calls lightning", 15.0, true),
    FIRE_AURA("fire-aura", "Ignites nearby foes", 10.0, true),
    WATER_BURST("water-burst", "Tidal knockback", 8.0, true),
    PULL("pull", "Whirlpool pull", 8.0, true),
    HEAL("heal", "Restores health", 12.0, true),
    DASH("dash", "Forward dash", 5.0, true),
    LEAP("leap", "Powerful leap", 5.0, true),
    SUMMON_MINIONS("minions", "Summons loyal minions", 20.0, true);

    private final String id;
    private final String displayName;
    private final double defaultCooldown;
    private final boolean active;

    HeadAbility(String id, String displayName, double defaultCooldown, boolean active) {
        this.id = id;
        this.displayName = displayName;
        this.defaultCooldown = defaultCooldown;
        this.active = active;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public double defaultCooldown() {
        return defaultCooldown;
    }

    public boolean active() {
        return active;
    }

    public String cooldownPath() {
        return "powers." + id + "-cooldown";
    }
}
