package minecraft.heads;

/**
 * Non-potion powers granted by mob heads. Active abilities are triggered by
 * right-clicking; passive abilities run from the tick loop or from combat
 * events while the head is worn.
 */
public enum HeadAbility {

    FLIGHT("flight", "Creative flight", 0.0, false),
    KNOCKBACK("knockback", "Heavy knockback on hit", 0.0, false),
    THORNS("thorns", "Punishes attackers", 0.0, false),
    FIRE_HIT("fire-hit", "Sets melee targets on fire", 0.0, false),
    POISON_HIT("poison-hit", "Poisons melee targets", 0.0, false),
    WITHER_HIT("wither-hit", "Withers melee targets", 0.0, false),
    PIG_GUARDS("pig", "Passively calls nearby pigs", 0.0, false),

    EXPLOSION("creeper", "Entity-only blast", 0.0, true),
    TELEPORT("teleport", "Blink teleport", 0.0, true),
    FIRE_CHARGE("fire-charge", "Throws a fire charge", 0.0, true),
    DRAGON_BREATH("dragon-breath", "Breathes dragon fire", 0.0, true),
    WIND_BURST("wind-burst", "Releases a wind charge", 0.0, true),
    SHOOT_ARROW("arrow", "Shoots an arrow", 0.0, true),
    THROW_SNOWBALL("snowball", "Throws a freezing snowball", 0.0, true),
    SONIC_BOOM("sonic-boom", "Sonic boom", 0.0, true),
    WITHER_SKULL("wither-skull", "Launches a wither skull", 0.0, true),
    POISON_CLOUD("poison", "Poison cloud", 0.0, true),
    WEB("web", "Places a temporary web", 0.0, true),
    INVISIBILITY("invisibility", "Vanish from sight", 0.0, true),
    SHIELD("shield", "Protective barrier", 0.0, true),
    LIGHTNING("lightning", "Calls lightning", 0.0, true),
    FIRE_AURA("fire-aura", "Ignites nearby foes", 0.0, true),
    WATER_BURST("water-burst", "Tidal knockback", 0.0, true),
    PULL("pull", "Whirlpool pull", 0.0, true),
    HEAL("heal", "Restores health", 0.0, true),
    DASH("dash", "Forward dash", 0.0, true),
    LEAP("leap", "Powerful leap", 0.0, true),
    SUMMON_MINIONS("minions", "Summons loyal minions", 0.0, true);

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
